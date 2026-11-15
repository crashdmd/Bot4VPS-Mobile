package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import com.bot4vps.mobile.data.local.SensitiveKeyStorage
import java.util.Base64
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class MobileConnectionTransportTest {
    private class Keys : MobileDeviceKeys {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val records = mutableMapOf<String, PairedMobileDevice>()
        override fun origins() = records.keys.toList()
        override fun device(origin: String) = records[origin]
        override fun publicKey(origin: String) = "original-key" to key.public.encoded
        override fun operationPublicKey(origin: String) = "original-operation" to key.public.encoded
        override fun remember(origin: String, device: PairedMobileDevice) { records[origin] = device }
        override fun forget(origin: String) { records.remove(origin) }
        override fun migrate(oldOrigin: String, newOrigin: String, expected: PairedMobileDevice) { records.remove(oldOrigin); records[newOrigin] = expected }
        override fun sign(alias: String, message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(message); sign() }
    }
    private fun tls(cert: HeldCertificate): MockWebServer = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false); start()
    }
    @Test fun qrAndManualPortChangeAfterRePairRecoverBeforePreviewAndPreserveSensitiveKeys(): Unit = runBlocking {
        for ((useQr, oldSourcePort) in listOf(true to 9444, false to 9444, true to 8444, false to 8444)) {
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val server = tls(cert)
            try {
                val old = "https://192.0.2.10:8444"
                val destination = mobileOrigin(server.url("/"))
                val pin = CertificatePinner.pin(cert.certificate)
                val keys = Keys(); val device = PairedMobileDevice("new-device", "original-key", pin, "original-operation")
                keys.records[old] = device
                val unrelated = "https://unrelated.test:8444"
                keys.records[unrelated] = device.copy(id = "unrelated-device", alias = "other-key")
                var profiles = listOf(PanelProfile(old, "Панель", device.id), PanelProfile(unrelated, "Другая", "unrelated-device"))
                var selected = old
                val obsolete = MobileConnectionChange("https://192.0.2.10:885", old, "old-request", "old-nonce", pin,
                    "previous-device", approved = true, localOldUrl = "https://192.0.2.10:$oldSourcePort")
                var journal: MobileConnectionChange? = obsolete
                var archived: MobileConnectionChange? = null
                val slots = mutableMapOf<String, String>()
                val sensitive = SensitiveKeyStorage(slots::get) { value, slot -> if (value == null) slots.remove(slot) else slots[slot] = value; Unit }
                val seed = ByteArray(32) { it.toByte() }
                val envelope = sensitive.create(old, "pin-seed", seed)
                val unrelatedEnvelope = sensitive.create(unrelated, "pin-seed", seed)
                val mobile = MobileAccessClient(keys, Json, OkHttpClient.Builder().addInterceptor { chain ->
                    assertEquals("Never call any old address", destination, mobileOrigin(chain.request().url))
                    chain.proceed(chain.request())
                }.build())
                // Reproduce the 1.7.6 failure before a QR or manual address can be checked.
                val previousFailure = runCatching {
                    resumeApprovedConnectionMigration({ journal }) { mobile.validateChange(it) }
                }.exceptionOrNull()
                assertEquals(if (oldSourcePort == 8444) "Связь устройства изменилась" else "Прежняя связь с этим сервисом не найдена", previousFailure!!.message)
                assertEquals(0, server.requestCount)
                val payload = buildJsonObject {
                    put("version", 1); put("action", "connection_change"); put("url", destination)
                    put("old_url", old); put("id", "port-change"); put("nonce", "nonce"); put("server_pin", pin)
                }
                val qr = "b4vps:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
                server.enqueue(MockResponse().setBody(buildJsonObject {
                    put("ok", true); put("state", "pending"); put("device_id", device.id); put("payload", payload)
                }.toString()))
                server.enqueue(MockResponse().setBody(buildJsonObject {
                    put("ok", true); put("state", "approved"); put("device_id", device.id); put("public_url", destination)
                }.toString()))
                var pendingPreview: MobileConnectionChange? = null
                suspend fun preview(value: String) {
                    pendingPreview = previewConnectionMigration(value, mobile, {
                        resumeApprovedConnectionMigration({ reconcileConnectionMigration({ journal }, { mobile.pairedDevices() },
                            { profiles }, { archived = it }, { journal = it }) }) { error("Obsolete transfer must not replay") }; Unit
                    }, { selected to profiles }, { from, to -> sensitive.validateMigration(from, to) })
                }
                if (useQr) routePanelConnectionCode(qr, { error("A migration QR must not pair") }, ::preview)
                else preview(destination.removePrefix("https://"))
                val change = pendingPreview!!
                assertEquals(obsolete, archived); assertNull(journal)
                assertEquals(device.id, change.deviceId); assertEquals(device, keys.records[old])
                assertEquals(old, selected); assertNull(keys.records[destination])
                assertEquals(envelope, sensitive.read(old, "pin-seed"))
                // Preview uses the same retained identity; no session cookie or previous device ID.
                val request = server.takeRequest()
                assertEquals("/api/mobile/connection-change/preview", request.path)
                assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Bot4VPS-Mobile-Session"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(device.id, body.getValue("device_id").jsonPrimitive.content)
                assertTrue(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(keys.key.public); update("bot4vps-mobile-discover-v1\n${device.id}\n$destination".toByteArray(Charsets.US_ASCII))
                    verify(Base64.getDecoder().decode(body.getValue("signature").jsonPrimitive.content))
                })
                runConnectionMigration(change, { journal = it }, { mobile.confirmChange(change) },
                    { sensitive.migrate(old, destination) }, {}, { mobile.migrateChange(change.copy(approved = true)) }, {
                        profiles = profiles.map { if (it.deviceId == device.id) it.copy(url = destination) else it }
                        selected = destination; sensitive.finishMigration(old, destination)
                    })
                assertEquals("/api/mobile/connection-change/confirm", server.takeRequest().path)
                assertEquals(2, server.requestCount); assertNull(journal)
                assertEquals(destination, selected); assertEquals(device, keys.records[destination]); assertNull(keys.records[old])
                assertEquals(device.alias, keys.records[destination]!!.alias)
                val movedEnvelope = sensitive.read(destination, "pin-seed")!!
                assertEquals(envelope.copy(contextOrigin = old), movedEnvelope)
                val verificationKey = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(envelope.publicKey)))
                val proof = movedEnvelope.sign(seed, destination, device.id, "challenge", "nonce")
                assertTrue(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(verificationKey); update("bot4vps-masterkey-view-v1\n${device.id}\nchallenge\nnonce".toByteArray(Charsets.US_ASCII))
                    verify(Base64.getDecoder().decode(proof))
                })
                assertEquals(unrelatedEnvelope, sensitive.read(unrelated, "pin-seed"))
                assertEquals("unrelated-device", keys.records[unrelated]!!.id)
                assertEquals(unrelated, profiles.last().url)
            } finally { server.shutdown() }
        }
    }

    @Test fun changedIpIsConfirmedOnlyAtTheNewAddressWithoutCallingTheUnavailableOldIp(): Unit = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = tls(cert)
        try {
            val old = "https://192.0.2.10:8444" // Deliberately unreachable documentation-only IP.
            val destination = mobileOrigin(server.url("/"))
            val pin = CertificatePinner.pin(cert.certificate)
            val keys = Keys(); val device = PairedMobileDevice("device", "original-key", pin)
            keys.records[old] = device
            val requests = mutableListOf<HttpUrl>()
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("The previous IP must never be contacted", destination, mobileOrigin(chain.request().url))
                requests += chain.request().url
                chain.proceed(chain.request())
            }.build()
            val mobile = MobileAccessClient(keys, Json, transport)
            val payload = buildJsonObject {
                put("version", 1); put("action", "connection_change"); put("url", destination)
                put("old_url", old); put("id", "ip-change"); put("nonce", "nonce"); put("server_pin", pin)
            }
            server.enqueue(MockResponse().setBody(buildJsonObject {
                put("ok", true); put("state", "pending"); put("device_id", device.id); put("payload", payload)
            }.toString()))
            server.enqueue(MockResponse().setBody(buildJsonObject {
                put("ok", true); put("state", "approved"); put("device_id", device.id); put("public_url", destination)
            }.toString()))
            val source = mobile.sourceForChange(old, device.id, pin)
            val change = mobile.discoverChange(source.first, destination)
            assertEquals(device, keys.records[old]); assertNull(keys.records[destination])
            mobile.confirmChange(change)
            mobile.migrateChange(change.copy(approved = true))
            assertEquals(device, keys.records[destination]); assertNull(keys.records[old])
            assertEquals(listOf("/api/mobile/connection-change/preview", "/api/mobile/connection-change/confirm"), requests.map { it.encodedPath })
            val proof = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(device.id, proof.getValue("device_id").jsonPrimitive.content)
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(keys.key.public); update("bot4vps-mobile-discover-v1\n${device.id}\n$destination".toByteArray(Charsets.US_ASCII))
                verify(Base64.getDecoder().decode(proof.getValue("signature").jsonPrimitive.content))
            })
        } finally { server.shutdown() }
    }
    @Test fun scannedQrFindsSelectedPhoneAfterItsKeyMovedAndUsesTheSameSignedIdentity(): Unit = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = tls(cert)
        try {
            val keys = Keys()
            val profile = "https://panel.test:8444"
            val retained = "https://panel.test:885"
            val pin = CertificatePinner.pin(cert.certificate)
            val device = PairedMobileDevice("device", "original-key", pin, "original-operation")
            keys.records[retained] = device
            val destination = mobileOrigin(server.url("/"))
            val payload = buildJsonObject {
                put("version", 1); put("action", "connection_change"); put("url", destination)
                put("old_url", profile); put("id", "change"); put("nonce", "nonce"); put("server_pin", pin)
            }
            val qr = readConnectionChange("b4vps:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray()))
            val mobile = MobileAccessClient(keys, Json)
            // This raw QR produced the reported error before the profile's
            // identity was resolved. Do not relax validateChange to pin-only.
            assertThrows(MobilePairingRequired::class.java) { mobile.validateChange(qr) }
            val source = mobile.sourceForChange(profile, device.id, qr.serverPin)
            assertEquals(retained, source.first)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Bot4VPS-Mobile-Session"))
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertEquals(device.id, body.getValue("device_id").jsonPrimitive.content)
                    val message = if (request.path!!.endsWith("/preview")) {
                        assertEquals(destination, body.getValue("url").jsonPrimitive.content)
                        "bot4vps-mobile-discover-v1\n${device.id}\n$destination".toByteArray(Charsets.US_ASCII)
                    } else connectionChangeMessage(qr, device.id)
                    assertTrue(Signature.getInstance("SHA256withECDSA").run {
                        initVerify(keys.key.public); update(message); verify(Base64.getDecoder().decode(body.getValue("signature").jsonPrimitive.content))
                    })
                    return if (request.path!!.endsWith("/preview")) MockResponse().setBody(buildJsonObject {
                        put("ok", true); put("state", "pending"); put("device_id", device.id); put("payload", payload)
                    }.toString()) else MockResponse().setBody(buildJsonObject {
                        put("ok", true); put("state", "approved"); put("device_id", device.id); put("public_url", destination)
                    }.toString())
                }
            }
            val verified = mobile.discoverChange(source.first, qr.url).copy(profileOldUrl = profile)
            assertEquals(profile, verified.oldOrigin); assertEquals(retained, verified.sourceOrigin)
            assertEquals(device.id, verified.deviceId); assertEquals(qr.id, verified.id); assertEquals(qr.nonce, verified.nonce)
            mobile.confirmChange(verified)
            assertEquals(device, keys.records[retained]); assertNull(keys.records[destination])
            mobile.migrateChange(verified.copy(approved = true))
            assertNull(keys.records[retained]); assertEquals(device, keys.records[destination])
            mobile.migrateChange(verified.copy(approved = true))
            assertEquals(1, keys.records.size)
        } finally { server.shutdown() }
    }
    @Test fun missingSelectedKeyCannotBorrowAnotherPhonesOrServicesPairing() {
        val keys = Keys(); val mobile = MobileAccessClient(keys, Json)
        val pin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32))
        val otherPin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        keys.records["https://other.test:8444"] = PairedMobileDevice("other-device", "other-key", pin)
        assertThrows(MobilePairingRequired::class.java) { mobile.sourceForChange("https://panel.test:8444", "device", pin) }
        keys.records["https://panel.test:885"] = PairedMobileDevice("device", "key", otherPin)
        assertThrows(MobilePairingRequired::class.java) { mobile.sourceForChange("https://panel.test:8444", "device", pin) }
        keys.records["https://panel.test:8444"] = PairedMobileDevice("other-device", "other-key", pin)
        assertThrows(IllegalArgumentException::class.java) { mobile.sourceForChange("https://panel.test:8444", "device", pin) }
        keys.records.remove("https://panel.test:8444")
        keys.records["https://panel.test:885"] = PairedMobileDevice("device", "key", pin)
        keys.records["https://panel.test:855"] = PairedMobileDevice("device", "key", pin)
        assertThrows(IllegalArgumentException::class.java) { mobile.sourceForChange("https://panel.test:8444", "device", pin) }
        assertEquals(3, keys.records.size)
    }
    @Test fun newAddressGetsOnlyTheOriginalSignedProofAndKeysMoveAfterApproval(): Unit = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = tls(cert)
        try {
            val keys = Keys()
            val origin = "https://old.test:8444"
            val pin = CertificatePinner.pin(cert.certificate)
            val device = PairedMobileDevice("device", "original-key", pin, "original-operation")
            keys.records[origin] = device
            val destination = mobileOrigin(server.url("/"))
            val change = MobileConnectionChange(destination, origin, "id", "nonce", pin, device.id)
            val transport = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("web").value("secret").domain(url.host).build())
            }).build()
            val mobile = MobileAccessClient(keys, Json, transport)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Bot4VPS-Mobile-Session"))
                    assertEquals("/api/mobile/connection-change/confirm", request.path)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertEquals(device.id, body.getValue("device_id").jsonPrimitive.content)
                    assertTrue(Signature.getInstance("SHA256withECDSA").run {
                        initVerify(keys.key.public); update(connectionChangeMessage(change, device.id)); verify(Base64.getDecoder().decode(body.getValue("signature").jsonPrimitive.content))
                    })
                    return MockResponse().setBody("""{"ok":true,"state":"approved","device_id":"device","public_url":"$destination"}""")
                }
            }
            mobile.confirmChange(change)
            assertEquals(device, keys.records[origin]); assertNull(keys.records[destination])
            mobile.migrateChange(change.copy(approved = true))
            assertNull(keys.records[origin]); assertEquals(device, keys.records[destination])
            mobile.migrateChange(change.copy(approved = true))
            assertEquals(1, keys.records.size)
        } finally { server.shutdown() }
    }
    @Test fun wrongPinConflictAndRedirectCannotReplaceTheOldConnection(): Unit = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = tls(cert); val stranger = tls(cert)
        try {
            val keys = Keys(); val old = "https://old.test:8444"; val destination = mobileOrigin(server.url("/"))
            val pin = CertificatePinner.pin(cert.certificate)
            val device = PairedMobileDevice("phone", "original-key", pin)
            keys.records[old] = device
            val mobile = MobileAccessClient(keys, Json)
            val change = MobileConnectionChange(destination, old, "id", "nonce", pin, device.id)
            assertTrue(runCatching { mobile.confirmChange(change.copy(serverPin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32)))) }.isFailure)
            assertEquals(0, server.requestCount)
            keys.records[destination] = device.copy(id = "other", alias = "other-key")
            assertTrue(runCatching { mobile.confirmChange(change) }.isFailure); assertEquals(0, server.requestCount)
            keys.records.remove(destination)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", stranger.url("/api/mobile/connection-change/confirm")))
            assertTrue(runCatching { mobile.confirmChange(change) }.isFailure)
            assertEquals(0, stranger.requestCount); assertEquals(device, keys.records[old]); assertNull(keys.records[destination])
        } finally { server.shutdown(); stranger.shutdown() }
    }
}
