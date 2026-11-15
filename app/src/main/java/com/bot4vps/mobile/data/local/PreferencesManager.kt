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
) : AppUpdatePreferences {
    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val SESSION_COOKIE = stringPreferencesKey("session_cookie")
        val USERNAME = stringPreferencesKey("username")
        val CONNECTION = stringPreferencesKey("pending_connection")
        val THEME = stringPreferencesKey("theme")
    }

    val phoneNotifications: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("phone_notifications")] ?: false }
    override val appAutoUpdate: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("app_auto_update")] ?: true }
    override val appUpdateCheckAt: Flow<Long> = context.dataStore.data.map { it[longPreferencesKey("app_update_check_at")] ?: 0 }
    override suspend fun setAppAutoUpdate(value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("app_auto_update")] = value } }
    override suspend fun rememberAppUpdateCheck(value: Long) { context.dataStore.edit { it[longPreferencesKey("app_update_check_at")] = value } }
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

    val revealServers: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("reveal_server_addresses")] ?: false }
    val revealServices: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("reveal_service_addresses")] ?: false }
    suspend fun setRevealServers(value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("reveal_server_addresses")] = value } }
    suspend fun setRevealServices(value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("reveal_service_addresses")] = value } }
    val profiles: Flow<List<com.bot4vps.mobile.data.model.PanelProfile>> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("panel_profiles")]?.let { Json.decodeFromString<List<com.bot4vps.mobile.data.model.PanelProfile>>(it) } ?: emptyList()
    }
    val pendingTotpOffers: Flow<Set<String>> = context.dataStore.data.map { it[stringSetPreferencesKey("pending_totp_offers")].orEmpty() }
    suspend fun offerTotp(origin: String) { context.dataStore.edit { prefs ->
        val key = stringSetPreferencesKey("pending_totp_offers")
        prefs[key] = prefs[key].orEmpty() + origin
    } }
    suspend fun dismissTotpOffer(origin: String) { context.dataStore.edit { prefs ->
        val key = stringSetPreferencesKey("pending_totp_offers")
        prefs[key] = prefs[key].orEmpty() - origin
    } }
    val securitySeen: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[stringSetPreferencesKey(panelKey(prefs, "security_seen").name)].orEmpty() }
    val allSecuritySeen: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[stringSetPreferencesKey("security_seen_all")].orEmpty() }
    suspend fun rememberAllSecuritySeen(ids: Set<String>) { context.dataStore.edit { it[stringSetPreferencesKey("security_seen_all")] = ids } }
    suspend fun rememberSecuritySeen(ids: Set<String>) { context.dataStore.edit { prefs -> prefs[stringSetPreferencesKey(panelKey(prefs, "security_seen").name)] = ids } }
    suspend fun rememberProfile(profile: com.bot4vps.mobile.data.model.PanelProfile) { context.dataStore.edit { prefs ->
        val key = stringPreferencesKey("panel_profiles")
        val old = prefs[key]?.let { Json.decodeFromString<List<com.bot4vps.mobile.data.model.PanelProfile>>(it) } ?: emptyList()
        prefs[key] = Json.encodeToString(old.filterNot { it.url == profile.url } + profile)
    } }
    suspend fun migrateProfile(oldOrigin: String, newOrigin: String, deviceId: String) {
        if (oldOrigin == newOrigin) return
        context.dataStore.edit { prefs ->
            val profileKey = stringPreferencesKey("panel_profiles")
            val old = prefs[profileKey]?.let { Json.decodeFromString<List<com.bot4vps.mobile.data.model.PanelProfile>>(it) } ?: emptyList()
            val profile = old.firstOrNull { it.url == oldOrigin }
            val existing = old.firstOrNull { it.url == newOrigin }
            check(profile == null || profile.deviceId == deviceId)
            check(existing == null || existing.deviceId == deviceId)
            if (profile != null) prefs[profileKey] = Json.encodeToString(old.filterNot { it.url == oldOrigin || it.url == newOrigin } +
                profile.copy(url = newOrigin, name = if (profile.name == oldOrigin) newOrigin else profile.name))
            fun suffix(origin: String) = java.security.MessageDigest.getInstance("SHA-256").digest(PanelUrl.normalize(origin).toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            val previousSuffix = "_" + suffix(oldOrigin)
            val nextSuffix = "_" + suffix(newOrigin)
            prefs.asMap().toMap().forEach { (key, value) ->
                if (key.name.endsWith(previousSuffix)) {
                    @Suppress("UNCHECKED_CAST")
                    val newKey = stringPreferencesKey(key.name.removeSuffix(previousSuffix) + nextSuffix) as Preferences.Key<Any>
                    prefs[newKey] = value
                }
            }
            val seen = stringSetPreferencesKey("security_seen_all")
            prefs[seen] = prefs[seen].orEmpty().map { if (it.startsWith("$oldOrigin\n")) "$newOrigin\n" + it.substringAfter('\n') else it }.toSet()
            val offers = stringSetPreferencesKey("pending_totp_offers")
            if (oldOrigin in prefs[offers].orEmpty()) prefs[offers] = prefs[offers].orEmpty() - oldOrigin + newOrigin
            if (prefs[Keys.BASE_URL] == PanelUrl.normalize(oldOrigin)) {
                prefs[Keys.BASE_URL] = PanelUrl.normalize(newOrigin)
                prefs.remove(Keys.SESSION_COOKIE); prefs.remove(Keys.CONNECTION)
            }
        }
    }
    suspend fun markProfileRevoked(origin: String, expectedDeviceId: String) { context.dataStore.edit { prefs ->
        val key = stringPreferencesKey("panel_profiles")
        val old = prefs[key]?.let { Json.decodeFromString<List<com.bot4vps.mobile.data.model.PanelProfile>>(it) } ?: emptyList()
        prefs[key] = Json.encodeToString(old.map { if (it.url == origin && it.deviceId == expectedDeviceId) it.copy(revoked = true) else it })
    } }
    suspend fun markProfileDisconnected(origin: String) { context.dataStore.edit { prefs ->
        val key = stringPreferencesKey("panel_profiles")
        val old = prefs[key]?.let { Json.decodeFromString<List<com.bot4vps.mobile.data.model.PanelProfile>>(it) } ?: emptyList()
        prefs[key] = Json.encodeToString(old.map { if (it.url == origin) it.copy(localDisconnected = true) else it })
    } }
    suspend fun removeProfile(origin: String, expectedDeviceId: String): com.bot4vps.mobile.data.model.PanelRemoval {
        var result = com.bot4vps.mobile.data.model.PanelRemoval(null, emptyList(), null, false)
        context.dataStore.edit { prefs ->
            val key = stringPreferencesKey("panel_profiles")
            val old = prefs[key]?.let { Json.decodeFromString<List<com.bot4vps.mobile.data.model.PanelProfile>>(it) } ?: emptyList()
            val current = prefs[Keys.BASE_URL]?.trimEnd('/')
            result = com.bot4vps.mobile.data.model.removePanelConnection(old, origin, expectedDeviceId, current)
            if (result.removed != null) {
                prefs[key] = Json.encodeToString(result.profiles)
                val offers = stringSetPreferencesKey("pending_totp_offers")
                prefs[offers] = prefs[offers].orEmpty() - origin
                if (result.switched) {
                    if (result.nextOrigin == null) prefs.remove(Keys.BASE_URL) else prefs[Keys.BASE_URL] = PanelUrl.normalize(result.nextOrigin!!)
                    prefs.remove(Keys.USERNAME); prefs.remove(Keys.SESSION_COOKIE); prefs.remove(Keys.CONNECTION)
                }
            }
        }
        return result
    }

    val baseUrl: Flow<String?> = context.dataStore.data.map { it[Keys.BASE_URL] }
    val sessionCookie: Flow<String?> = context.dataStore.data.map { it[Keys.SESSION_COOKIE] }
    val username: Flow<String?> = context.dataStore.data.map { it[Keys.USERNAME] }
    val theme: Flow<String> = context.dataStore.data.map { normalizePanelTheme(it[Keys.THEME]) }
    suspend fun setTheme(value: String) { require(value in setOf("dark","light","glass")); context.dataStore.edit { it[Keys.THEME] = value } }

    private fun panelKey(prefs: Preferences, name: String) = panelPreferenceKey(prefs, name)
    private val lists = ListPreferencesStore(context.dataStore)
    val serverList = lists.serverList
    val serviceLists = lists.serviceLists
    suspend fun setServerSort(sort: com.bot4vps.mobile.data.model.ServerListSort, descending: Boolean) = lists.setServerSort(sort, descending)
    suspend fun setServerGroup(group: String?) = lists.setServerGroup(group)
    suspend fun setServiceFilter(kind: com.bot4vps.mobile.data.model.ServiceKind, filter: com.bot4vps.mobile.data.model.InstallationFilter) = lists.setServiceFilter(kind, filter)
    suspend fun setServiceSort(kind: com.bot4vps.mobile.data.model.ServiceKind, byName: Boolean) = lists.setServiceSort(kind, byName)
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
