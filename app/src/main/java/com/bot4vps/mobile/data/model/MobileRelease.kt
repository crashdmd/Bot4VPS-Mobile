package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

object MobileReleaseSource {
    const val DIRECTORY = "https://api.github.com/repos/crashdmd/Bot4VPS-Mobile/contents/apk"
    const val MANIFEST = "$DIRECTORY/release.json?ref=main"
    const val ACCEPT = "application/vnd.github.raw+json"
    val allowedHosts = setOf("api.github.com")
}

data class MobileRelease(val version: String, val code: Int, val size: Long, val sha256: String, val apkUrl: String, val notes: String)

private fun versionParts(value: String): List<Int> {
    require(value.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}(?:\\.[0-9]{1,5})?"))) { "Некорректная версия приложения" }
    return value.split('.').map(String::toInt).let { if (it.size == 2) it + 0 else it }
}

fun newerMobileVersion(candidate: String, current: String): Boolean {
    val next = versionParts(candidate); val now = versionParts(current)
    return next.zip(now).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

fun mobileReleaseFromDirectory(json: JsonObject, currentVersion: String, currentCode: Int): MobileRelease? {
    val version = json["version"]?.jsonPrimitive?.contentOrNull ?: error("В метаданных нет версии")
    val code = json["versionCode"]?.jsonPrimitive?.intOrNull
    val filename = json["filename"]?.jsonPrimitive?.contentOrNull
    val size = json["size"]?.jsonPrimitive?.longOrNull
    val checksum = json["sha256"]?.jsonPrimitive?.contentOrNull
    require(filename == "Bot4VPS-Mobile-$version-release.apk" && code != null && code > 0
        && size != null && size in 1..49_000_000 && checksum != null && checksum.matches(Regex("[a-f0-9]{64}"))) { "Метаданные обновления не прошли проверку" }
    val newer = newerMobileVersion(version, currentVersion)
    if ((!newer && versionParts(version) != versionParts(currentVersion)) || code <= currentCode) return null
    return MobileRelease(version, code, size, checksum, "${MobileReleaseSource.DIRECTORY}/$filename?ref=main",
        (json["changelog"]?.jsonPrimitive?.contentOrNull ?: "").take(30_000))
}
