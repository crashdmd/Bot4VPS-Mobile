package com.bot4vps.mobile.data.model

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

@Serializable
data class AuthenticatorAccount(
    val id: String = UUID.randomUUID().toString(), val name: String, val issuer: String = "",
    val secret: String, val algorithm: String = "SHA1", val digits: Int = 6, val period: Int = 30,
    val panelOrigin: String? = null, val generation: String? = null, val active: Boolean = true, val setupId: String? = null,
    val enableRequestId: String? = null
)

fun removeDisabledPanelTotp(accounts: List<AuthenticatorAccount>, origin: String, generations: Set<String>): List<AuthenticatorAccount> =
    accounts.filterNot { it.panelOrigin == origin && it.generation != null && it.generation in generations }

fun decodeTotpSecret(secret: String): ByteArray {
    val text = secret.uppercase(Locale.ROOT).replace(" ", "").replace("-", "").trimEnd('=')
    require(text.length in 16..1024 && text.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }) { "Введите секрет TOTP в Base32" }
    val out = ArrayList<Byte>(); var bits = 0; var buffer = 0
    text.forEach { char ->
        buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(char); bits += 5
        if (bits >= 8) { bits -= 8; out.add((buffer shr bits).toByte()); buffer = buffer and ((1 shl bits) - 1) }
    }
    require(buffer == 0 && out.size >= 10) { "Секрет TOTP повреждён" }
    return out.toByteArray()
}

fun validateTotp(account: AuthenticatorAccount): AuthenticatorAccount {
    require(account.name.isNotBlank() && account.name.length <= 200 && account.issuer.length <= 200) { "Укажите название аккаунта" }
    require(account.algorithm in setOf("SHA1", "SHA256", "SHA512") && account.digits in setOf(6, 8) && account.period in 15..120) { "Параметры TOTP не поддерживаются" }
    decodeTotpSecret(account.secret)
    return account
}

fun totpCode(account: AuthenticatorAccount, seconds: Long): String {
    validateTotp(account); require(seconds >= 0)
    val mac = Mac.getInstance("Hmac${account.algorithm}")
    val bytes = decodeTotpSecret(account.secret)
    val hash = try {
        mac.init(SecretKeySpec(bytes, "Hmac${account.algorithm}"))
        mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / account.period).array())
    } finally { bytes.fill(0) }
    val offset = hash.last().toInt() and 15
    val number = ((hash[offset].toInt() and 127) shl 24) or ((hash[offset + 1].toInt() and 255) shl 16) or
        ((hash[offset + 2].toInt() and 255) shl 8) or (hash[offset + 3].toInt() and 255)
    val modulus = if (account.digits == 8) 100_000_000 else 1_000_000
    hash.fill(0)
    return (number % modulus).toString().padStart(account.digits, '0')
}

fun readTotpQr(value: String): AuthenticatorAccount {
    require(value.length <= 4096) { "QR-код слишком длинный" }
    val uri = URI(value)
    require(uri.scheme == "otpauth" && uri.host == "totp" && uri.fragment == null && uri.userInfo == null) { "Нужен QR-код TOTP-аутентификатора" }
    fun decode(text: String) = URLDecoder.decode(text, "UTF-8")
    val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { it.split('=', limit = 2) }
    require(pairs.all { it.size == 2 }) { "Некорректный QR-код TOTP" }
    val params = pairs.associate { decode(it[0]) to decode(it[1]) }
    require(params.size == pairs.size) { "Повторяющиеся параметры TOTP" }
    val label = decode(uri.rawPath.orEmpty().removePrefix("/"))
    val parts = label.split(':', limit = 2)
    return validateTotp(AuthenticatorAccount(name = parts.last().trim(), issuer = params["issuer"] ?: if (parts.size == 2) parts.first() else "",
        secret = params["secret"] ?: error("В QR нет секрета TOTP"), algorithm = params["algorithm"]?.uppercase(Locale.ROOT) ?: "SHA1",
        digits = params["digits"]?.let { it.toIntOrNull() ?: error("Неверное число цифр") } ?: 6,
        period = params["period"]?.let { it.toIntOrNull() ?: error("Неверный период TOTP") } ?: 30))
}
