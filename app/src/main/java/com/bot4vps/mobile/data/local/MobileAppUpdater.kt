package com.bot4vps.mobile.data.local

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bot4vps.mobile.BuildConfig
import com.bot4vps.mobile.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MobileAppUpdater @Inject constructor(@ApplicationContext private val context: Context) : AppReleaseUpdater {
    // Independent client: no panel cookies, device sessions, certificate pins or headers.
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            val url = chain.request().url
            check(url.isHttps && url.host in MobileReleaseSource.allowedHosts) { "Недопустимый адрес обновления" }
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = client.newCall(Request.Builder().url(url).header("Accept", MobileReleaseSource.ACCEPT)
        .header("User-Agent", "Bot4VPS-Mobile/${BuildConfig.VERSION_NAME}").build())
    private suspend fun json(url: String): JsonElement = withContext(Dispatchers.IO) {
        request(url).execute().use { response ->
            check(response.isSuccessful) { if (response.code == 403 || response.code == 429) "Источник обновлений ограничил запросы. Попробуйте позже." else "Источник обновлений недоступен (${response.code})" }
            val body = response.body ?: error("Пустой ответ обновлений")
            val bytes = body.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1_000_000) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer, 0, minOf(buffer.size, 1_000_001 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 1_000_000) { "Ответ обновлений слишком большой" }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        }
    }
    override suspend fun check(): MobileRelease? {
        return mobileReleaseFromDirectory(json(MobileReleaseSource.MANIFEST) as? JsonObject ?: error("Некорректные метаданные"), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }
    override suspend fun download(release: MobileRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "mobile-updates").apply { mkdirs() }
        val temporary = File(directory, "download.tmp")
        val target = File(directory, "update.apk")
        temporary.delete(); target.delete()
        try {
            request(release.apkUrl).execute().use { response ->
                check(response.isSuccessful) { "Не удалось скачать APK (${response.code})" }
                val body = response.body ?: error("Пустой APK")
                check(body.contentLength() == -1L || body.contentLength() == release.size) { "Размер APK отличается от релиза" }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var previous = -1
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val length = input.read(buffer)
                        if (length < 0) break
                        count += length
                        check(count <= release.size) { "APK превышает заявленный размер" }
                        digest.update(buffer, 0, length); output.write(buffer, 0, length)
                        val progress = (count * 100 / release.size).toInt()
                        if (previous != progress) { withContext(Dispatchers.Main) { onProgress(progress) }; previous = progress }
                    }
                } }
                check(count == release.size && digest.digest().lockHex() == release.sha256) { "Контрольная сумма APK не совпадает" }
            }
            verifyArchive(temporary, release)
            check(temporary.renameTo(target)) { "Не удалось сохранить APK" }
            target
        } finally { temporary.delete() }
    }
    fun verifyArchive(file: File, release: MobileRelease) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("APK повреждён")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION")
        fun signatures(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
                .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).lockHex() }.toSet()
        @Suppress("DEPRECATION")
        fun identity(info: android.content.pm.PackageInfo) = ApkUpdateIdentity(info.packageName, info.versionName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.applicationInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: true,
            signatures(info))
        verifyApkUpdateIdentity(identity(candidate), identity(installed), release)
    }
}
