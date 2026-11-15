package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.AuthenticatorAccount
import com.bot4vps.mobile.data.model.validateTotp
import com.bot4vps.mobile.data.model.removeDisabledPanelTotp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Independent TOTP accounts survive disconnecting a panel; ciphertext stays on this phone. */
@Singleton
class AuthenticatorStorage @Inject constructor(private val secrets: SecretStorage, private val json: Json) {
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    @Synchronized private fun read(): List<AuthenticatorAccount> = secrets.readStrict("authenticator_accounts")?.let { json.decodeFromString(it) } ?: emptyList()
    @Synchronized private fun edit(change: (List<AuthenticatorAccount>) -> List<AuthenticatorAccount>) {
        val old = read()
        val next = change(old)
        if (next != old) { secrets.write(json.encodeToString(next), "authenticator_accounts"); changed.update { it + 1 } }
    }
    suspend fun accounts(): List<AuthenticatorAccount> = withContext(Dispatchers.IO) { read() }
    suspend fun add(account: AuthenticatorAccount) = withContext(Dispatchers.IO) {
        validateTotp(account)
        edit { old -> old.filterNot { it.id == account.id } + account }
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { edit { it.filterNot { account -> account.id == id } } }
    suspend fun removeDisabledPanel(origin: String, generations: Set<String>) = withContext(Dispatchers.IO) {
        edit { removeDisabledPanelTotp(it, origin, generations) }
    }
    suspend fun migrate(oldOrigin: String, newOrigin: String) = withContext(Dispatchers.IO) {
        edit { old -> old.map { account -> if (account.panelOrigin == oldOrigin) account.copy(panelOrigin = newOrigin,
            issuer = if (account.issuer == "Bot4VPS · $oldOrigin") "Bot4VPS · $newOrigin" else account.issuer,
            name = if (account.name == oldOrigin) newOrigin else account.name) else account } }
    }
}
