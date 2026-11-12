package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.Bot4VpsApi
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.local.AppLockManager
import com.bot4vps.mobile.data.local.SensitiveKeyStorage
import com.bot4vps.mobile.data.model.MeResponse
import com.bot4vps.mobile.di.ApiFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bot4vps.mobile.data.api.connectToPanel
import javax.inject.Inject
import javax.inject.Singleton
import com.bot4vps.mobile.data.api.SessionCookieJar
import com.bot4vps.mobile.data.api.PanelUrl
import com.bot4vps.mobile.data.api.MobileAccessClient
import com.bot4vps.mobile.data.api.readMobileInvitation

@Singleton
class AuthRepository @Inject constructor(
    private val prefs: PreferencesManager,
    private val apiFactory: ApiFactory,
    private val cookies: SessionCookieJar,
    private val mobile: MobileAccessClient,
    private val operationKeys: SensitiveKeyStorage,
    private val appLock: AppLockManager
) {
    val baseUrl: Flow<String?> = prefs.baseUrl
    val username: Flow<String?> = prefs.username

    private suspend fun api(): Bot4VpsApi {
        val url = prefs.baseUrl.first()
            ?: throw IllegalStateException("Base URL not set")
        return withContext(Dispatchers.IO) { apiFactory.create(url) }
    }

    suspend fun setBaseUrl(url: String) {
        val normalized = PanelUrl.normalize(url)
        if (prefs.baseUrl.first() != normalized) {
            cookies.clear()
            prefs.clearSession()
        }
        prefs.setBaseUrl(normalized)
    }

    suspend fun checkSession(): MeResponse = connectToPanel {
        val base = prefs.baseUrl.first() ?: error("Укажите адрес панели")
        mobile.sessionAsync(PanelUrl.endpoint(base, "/api/me"))
        val result = api().me()
        check(result.authenticated) { "Сервер не подтвердил мобильный вход. Обновите Bot4VPS до версии с входом по ключу телефона." }
        if (prefs.username.first().isNullOrBlank()) prefs.setUsername(result.user ?: "Мобильный доступ")
        result
    }

    suspend fun pairMobile(code: String): Unit = connectToPanel {
        val target = readMobileInvitation(code.trim())?.url ?: prefs.baseUrl.first() ?: error("Укажите адрес панели")
        mobile.pair(target, code)
        setBaseUrl(target)
    }
    fun isPairedApiAddress(baseUrl: String): Boolean = mobile.isPaired(PanelUrl.endpoint(baseUrl, "/api/me"))

    suspend fun logout() {
        prefs.baseUrl.first()?.let { base ->
            // Old HTTP settings need no network request to disconnect.
            val seedId = appLock.sensitiveSeedId
            withContext(Dispatchers.IO) {
                runCatching { PanelUrl.endpoint(base, "/api/me") }.getOrNull()?.let { url ->
                    mobile.forget(url) { origin -> operationKeys.remove(origin, seedId) }
                }
            }
        }
        cookies.clear()
        prefs.clearSession()
    }
}
