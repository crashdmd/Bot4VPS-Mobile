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
    private fun records() = secrets.read("mobile_devices")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    override fun device(origin: String): PairedMobileDevice? {
        val record = records()[origin] as? JsonObject ?: return null
        return PairedMobileDevice(record["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            record["alias"]?.jsonPrimitive?.contentOrNull ?: return null,
            record["server_pin"]?.jsonPrimitive?.contentOrNull)
    }

    @Synchronized
    override fun publicKey(origin: String): Pair<String, ByteArray> {
        return keyPair(origin, "bot4vps-mobile-")
    }

    @Synchronized
    override fun operationPublicKey(origin: String): Pair<String, ByteArray> = keyPair(origin, "bot4vps-masterkey-")

    private fun keyPair(origin: String, prefix: String): Pair<String, ByteArray> {
        val alias = prefix + MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
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
        })).toString(), "mobile_devices")
    }

    override fun sign(alias: String, message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        val key = store().getKey(alias, null) as? PrivateKey ?: throw java.security.InvalidKeyException("Ключ телефона отсутствует")
        initSign(key); update(message); sign()
    }

    @Synchronized
    override fun forget(origin: String) {
        val alias = device(origin)?.alias
        secrets.write(JsonObject(records() - origin).toString(), "mobile_devices")
        if (alias != null) store().deleteEntry(alias)
        val operationAlias = "bot4vps-masterkey-" + MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
        if (store().containsAlias(operationAlias)) store().deleteEntry(operationAlias)
    }
}
