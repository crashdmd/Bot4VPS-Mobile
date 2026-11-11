package com.bot4vps.mobile.data.model

/** Identity obtained by Android PackageManager from the downloaded APK. */
data class ApkUpdateIdentity(
    val packageName: String?, val version: String?, val code: Long,
    val debuggable: Boolean, val signerSha256: Set<String>
)

fun verifyApkUpdateIdentity(candidate: ApkUpdateIdentity, installed: ApkUpdateIdentity, release: MobileRelease) {
    check(candidate.packageName == installed.packageName && candidate.version == release.version
        && candidate.code == release.code.toLong() && candidate.code > installed.code
        && !candidate.debuggable && candidate.signerSha256.isNotEmpty()
        && installed.signerSha256.isNotEmpty() && candidate.signerSha256 == installed.signerSha256) {
        "Подпись или версия APK не соответствует приложению"
    }
}
