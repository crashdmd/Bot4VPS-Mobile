package com.bot4vps.mobile.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bot4vps.mobile.data.api.MobileDeviceKeys
import com.bot4vps.mobile.data.api.PairedMobileDevice
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidMobileDeviceKeys @Inject constructor(private val secrets: SecretStorage) : MobileDeviceKeys {
    private fun records() = secrets.readStrict("mobile_devices")?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    override fun origins(): List<String> = records().keys.toList()

    @Synchronized
    override fun device(origin: String): PairedMobileDevice? {
        val record = records()[origin] as? JsonObject ?: return null
        return PairedMobileDevice(record["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            record["alias"]?.jsonPrimitive?.contentOrNull ?: return null,
            record["server_pin"]?.jsonPrimitive?.contentOrNull,
            record["operation_alias"]?.jsonPrimitive?.contentOrNull)
    }

    @Synchronized
    override fun publicKey(origin: String): Pair<String, ByteArray> {
        return keyPair(origin, "bot4vps-mobile-", device(origin)?.alias)
    }

    @Synchronized
    override fun operationPublicKey(origin: String): Pair<String, ByteArray> = keyPair(origin, "bot4vps-masterkey-", device(origin)?.operationAlias)

    private fun derivedAlias(origin: String, prefix: String) = prefix + MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun keyPair(origin: String, prefix: String, remembered: String? = null): Pair<String, ByteArray> {
        val alias = remembered ?: derivedAlias(origin, prefix)
        val keyStore = store()
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return alias to store().getCertificate(alias).publicKey.encoded
    }

    @Synchronized
    override fun remember(origin: String, device: PairedMobileDevice) {
        secrets.write(JsonObject(records() + (origin to buildJsonObject {
            put("id", device.id); put("alias", device.alias); device.serverPin?.let { put("server_pin", it) }
            put("operation_alias", device.operationAlias ?: derivedAlias(origin, "bot4vps-masterkey-"))
        })).toString(), "mobile_devices")
    }

    @Synchronized
    override fun migrate(oldOrigin: String, newOrigin: String, expected: PairedMobileDevice) {
        if (oldOrigin == newOrigin) return
        val old = device(oldOrigin)
        val next = device(newOrigin)
        if (old == null) {
            check(next?.id == expected.id && next.alias == expected.alias && next.serverPin == expected.serverPin)
            return
        }
        check(old.id == expected.id && old.alias == expected.alias && old.serverPin == expected.serverPin)
        check(next == null || next.id == old.id && next.alias == old.alias && next.serverPin == old.serverPin)
        val record = records().getValue(oldOrigin).jsonObject
        val moved = JsonObject(record + ("operation_alias" to JsonPrimitive(old.operationAlias ?: derivedAlias(oldOrigin, "bot4vps-masterkey-"))))
        // One encrypted document commit; aliases remain unchanged in Keystore.
        secrets.write(JsonObject(records() - oldOrigin + (newOrigin to moved)).toString(), "mobile_devices")
    }

    override fun sign(alias: String, message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        val key = store().getKey(alias, null) as? PrivateKey ?: throw java.security.InvalidKeyException("Ключ телефона отсутствует")
        initSign(key); update(message); sign()
    }

    @Synchronized
    override fun forget(origin: String) {
        val device = device(origin)
        val alias = device?.alias
        val operationAlias = device?.operationAlias ?: derivedAlias(origin, "bot4vps-masterkey-")
        secrets.write(JsonObject(records() - origin).toString(), "mobile_devices")
        val shared = origins().mapNotNull(::device)
        if (alias != null && shared.none { it.alias == alias }) store().deleteEntry(alias)
        if (shared.none { it.operationAlias == operationAlias } && store().containsAlias(operationAlias)) store().deleteEntry(operationAlias)
    }
}
