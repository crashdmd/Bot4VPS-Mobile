package com.bot4vps.mobile.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

internal fun panelPreferenceKey(prefs: Preferences, name: String) = stringPreferencesKey(name + "_" +
    MessageDigest.getInstance("SHA-256").digest(prefs[stringPreferencesKey("base_url")].orEmpty().toByteArray())
        .take(12).joinToString("") { "%02x".format(it) })

data class ServerListPreferences(val sort: ServerListSort = ServerListSort.NAME,
    val descending: Boolean = false, val group: String? = null)
data class ServiceListPreferences(val filter: InstallationFilter = InstallationFilter.ALL,
    val sortByName: Boolean = false)

/** Выбор списков хранится отдельно для каждой подключённой панели и каждого сервиса. */
internal class ListPreferencesStore(private val store: DataStore<Preferences>) {
    val serverList = store.data.map { prefs ->
        val sort = ServerListSort.entries.find { it.name == prefs[panelPreferenceKey(prefs, "server_sort")] }
        ServerListPreferences(sort ?: ServerListSort.NAME,
            sort != null && prefs[booleanPreferencesKey(panelPreferenceKey(prefs, "server_descending").name)] == true,
            prefs[panelPreferenceKey(prefs, "server_group")]?.takeIf(String::isNotBlank))
    }.distinctUntilChanged()
    val serviceLists = store.data.map { prefs ->
        ServiceKind.entries.associateWith { kind ->
            val filter = InstallationFilter.entries.find { it.name == prefs[panelPreferenceKey(prefs, "service_filter_${kind.id}")] }
                ?.takeUnless { it == InstallationFilter.CLASSIC && kind != ServiceKind.WIREGUARD }
            ServiceListPreferences(filter ?: InstallationFilter.ALL,
                prefs[booleanPreferencesKey(panelPreferenceKey(prefs, "service_sort_${kind.id}").name)] == true)
        }
    }.distinctUntilChanged()
    suspend fun setServerSort(sort: ServerListSort, descending: Boolean) { store.edit { prefs ->
        prefs[panelPreferenceKey(prefs, "server_sort")] = sort.name
        prefs[booleanPreferencesKey(panelPreferenceKey(prefs, "server_descending").name)] = descending
    } }
    suspend fun setServerGroup(group: String?) { store.edit { prefs ->
        val key = panelPreferenceKey(prefs, "server_group")
        if (group.isNullOrBlank()) prefs.remove(key) else prefs[key] = group
    } }
    suspend fun setServiceFilter(kind: ServiceKind, filter: InstallationFilter) {
        require(filter != InstallationFilter.CLASSIC || kind == ServiceKind.WIREGUARD)
        store.edit { prefs -> prefs[panelPreferenceKey(prefs, "service_filter_${kind.id}")] = filter.name }
    }
    suspend fun setServiceSort(kind: ServiceKind, byName: Boolean) { store.edit { prefs ->
        prefs[booleanPreferencesKey(panelPreferenceKey(prefs, "service_sort_${kind.id}").name)] = byName
    } }
}
