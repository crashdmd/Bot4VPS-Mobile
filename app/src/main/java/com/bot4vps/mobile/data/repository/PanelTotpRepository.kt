package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.local.AuthenticatorStorage
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PanelTotpRepository @Inject constructor(private val security: SecurityRepository, private val storage: AuthenticatorStorage, private val prefs: PreferencesManager) {
    suspend fun enable(origin: String, enableRequestId: String? = null, username: String? = null): AuthenticatorAccount {
        val state = security.refreshFor(origin)
        check(state.origin == origin) { "Текущий сервис изменился. Начните добавление заново." }
        check(state.supported == true) { "Для включения 2FA обновите панель Bot4VPS." }
        val saved = storage.accounts()
        saved.lastOrNull { it.panelOrigin == origin && !it.active && it.setupId != null && it.enableRequestId == enableRequestId }?.let { pending ->
            try { return confirm(pending) }
            catch (e: com.bot4vps.mobile.data.api.ApiError) {
                if (!state.twoFactorEnabled && e.status in setOf(404, 410)) storage.remove(pending.id) else throw e
            }
        }
        saved.firstOrNull { state.twoFactorEnabled && it.panelOrigin == origin && it.active && it.generation != null && it.generation == state.totpGeneration }?.let { return it }
        val transfer = enableRequestId != null && state.requests.any { it.id == enableRequestId && it.purpose == "primary_transfer" }
        check(!state.twoFactorEnabled || transfer) { "2FA уже включена на сервисе. Используйте сохранённый код или подтверждение доверенного телефона." }
        val setup = security.setupTotp(origin, enableRequestId)
        check(setup["registered"]?.jsonPrimitive?.booleanOrNull != true) { "2FA уже включена на сервисе." }
        val account = AuthenticatorAccount(name = username?.ifBlank { origin } ?: prefs.username.first().orEmpty().ifBlank { origin }, issuer = "Bot4VPS · $origin",
            secret = setup.getValue("secret").jsonPrimitive.content, panelOrigin = origin, active = false, setupId = setup.getValue("id").jsonPrimitive.content, enableRequestId = enableRequestId)
        storage.add(account)
        return confirm(account)
    }

    suspend fun confirm(account: AuthenticatorAccount): AuthenticatorAccount {
        val state = security.refreshFor(account.panelOrigin ?: error("Это код стороннего сервиса"))
        check(account.panelOrigin == state.origin) { "Переключитесь на панель, для которой начата регистрация" }
        val generation = security.confirmTotp(state.origin, account.setupId ?: error("Начните регистрацию заново"), totpCode(account, state.now().toLong()))
        return account.copy(active = true, generation = generation, setupId = null).also { storage.add(it) }
    }
}
