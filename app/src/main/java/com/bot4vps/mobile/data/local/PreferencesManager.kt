package com.bot4vps.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.bot4vps.mobile.data.api.PanelUrl
import com.bot4vps.mobile.data.model.GroupDisplaySettings
import com.bot4vps.mobile.data.model.decodeGroupDisplaySettings
import com.bot4vps.mobile.data.model.normalizePanelTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bot4vps_prefs")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val SESSION_COOKIE = stringPreferencesKey("session_cookie")
        val USERNAME = stringPreferencesKey("username")
        val CONNECTION = stringPreferencesKey("pending_connection")
        val THEME = stringPreferencesKey("theme")
    }

    val phoneNotifications: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("phone_notifications")] ?: false }
    val appAutoUpdate: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("app_auto_update")] ?: true }
    val appUpdateCheckAt: Flow<Long> = context.dataStore.data.map { it[longPreferencesKey("app_update_check_at")] ?: 0 }
    suspend fun setAppAutoUpdate(value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("app_auto_update")] = value } }
    suspend fun rememberAppUpdateCheck(value: Long) { context.dataStore.edit { it[longPreferencesKey("app_update_check_at")] = value } }
    suspend fun setPhoneNotifications(enabled: Boolean) { context.dataStore.edit { if (enabled && it[booleanPreferencesKey("phone_notifications")] != true) it.remove(stringSetPreferencesKey(panelKey(it, "phone_seen").name)); it[booleanPreferencesKey("phone_notifications")] = enabled } }
    val phoneCategories: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[stringSetPreferencesKey("phone_categories")] ?: setOf("online", "tasks", "services", "ssl", "updates", "system", "backups") }
    suspend fun setPhoneCategory(category: String, enabled: Boolean) { context.dataStore.edit { prefs ->
        val key = stringSetPreferencesKey("phone_categories")
        val old = prefs[key] ?: setOf("online", "tasks", "services", "ssl", "updates", "system", "backups")
        prefs[key] = if (enabled) old + category else old - category
    } }
    val phoneSeen: Flow<Set<String>?> = context.dataStore.data.map { prefs -> prefs[panelKey(prefs, "phone_seen").let { stringSetPreferencesKey(it.name) }] }
    suspend fun rememberPhoneSeen(ids: Set<String>) { context.dataStore.edit { prefs -> prefs[stringSetPreferencesKey(panelKey(prefs, "phone_seen").name)] = ids } }
    val phoneRead: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[stringSetPreferencesKey(panelKey(prefs, "phone_read").name)].orEmpty() }
    suspend fun rememberPhoneRead(ids: Set<String>, recent: Set<String>) { context.dataStore.edit { prefs ->
        val key = stringSetPreferencesKey(panelKey(prefs, "phone_read").name)
        prefs[key] = (prefs[key].orEmpty() + ids).intersect(recent)
    } }

    val baseUrl: Flow<String?> = context.dataStore.data.map { it[Keys.BASE_URL] }
    val sessionCookie: Flow<String?> = context.dataStore.data.map { it[Keys.SESSION_COOKIE] }
    val username: Flow<String?> = context.dataStore.data.map { it[Keys.USERNAME] }
    val theme: Flow<String> = context.dataStore.data.map { normalizePanelTheme(it[Keys.THEME]) }
    suspend fun setTheme(value: String) { require(value in setOf("dark","light","glass")); context.dataStore.edit { it[Keys.THEME] = value } }

    private fun panelKey(prefs: Preferences, name: String) = stringPreferencesKey(name + "_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(prefs[Keys.BASE_URL].orEmpty().toByteArray()).take(12).joinToString("") { "%02x".format(it) })
    val groupDisplay: Flow<GroupDisplaySettings> = context.dataStore.data.map { prefs ->
        runCatching { decodeGroupDisplaySettings(prefs[panelKey(prefs, "groups")] ?: "{}") }.getOrDefault(GroupDisplaySettings())
    }
    suspend fun editGroupDisplay(change: (GroupDisplaySettings) -> GroupDisplaySettings) {
        context.dataStore.edit { prefs ->
            val key = panelKey(prefs, "groups")
            val old = runCatching { decodeGroupDisplaySettings(prefs[key] ?: "{}") }.getOrDefault(GroupDisplaySettings())
            prefs[key] = Json.encodeToString(change(old))
        }
    }
    val pendingConnection: Flow<String?> = context.dataStore.data.map { it[Keys.CONNECTION] }
    suspend fun rememberConnection(value: String?) { context.dataStore.edit { if(value==null) it.remove(Keys.CONNECTION) else it[Keys.CONNECTION]=value } }
    val pendingMaintenance: Flow<String?> = context.dataStore.data.map { it[panelKey(it, "maintenance")] }
    suspend fun rememberMaintenance(value: String?) { context.dataStore.edit { prefs ->
        val key = panelKey(prefs, "maintenance")
        if (value == null) prefs.remove(key) else prefs[key] = value
    } }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.BASE_URL] = PanelUrl.normalize(url) }
    }

    suspend fun setSession(cookie: String?, username: String?) {
        context.dataStore.edit { prefs ->
            if (cookie != null) prefs[Keys.SESSION_COOKIE] = cookie else prefs.remove(Keys.SESSION_COOKIE)
            if (username != null) prefs[Keys.USERNAME] = username else prefs.remove(Keys.USERNAME)
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.SESSION_COOKIE)
            it.remove(Keys.USERNAME)
        }
    }

    suspend fun setUsername(username: String?) {
        context.dataStore.edit {
            if (username == null) it.remove(Keys.USERNAME) else it[Keys.USERNAME] = username
            it.remove(Keys.SESSION_COOKIE)
        }
    }
}
