package com.bot4vps.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bot4vps.mobile.data.local.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ListPreferencesStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var data: DataStore<Preferences>
    private lateinit var choices: ListPreferencesStore
    private fun open() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = PreferenceDataStoreFactory.create(scope = scope) { temporary.root.resolve("lists.preferences_pb") }
        choices = ListPreferencesStore(data)
    }
    private suspend fun panel(url: String) { data.edit { it[stringPreferencesKey("base_url")] = url } }
    @Before fun setUp() = open()
    @After fun tearDown() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun choicesSurviveClosingTheStoreAndRestarting() = runBlocking {
        panel("https://one.example/")
        choices.setServerSort(ServerListSort.UPTIME, true)
        choices.setServerGroup("Домашние серверы")
        choices.setServiceFilter(ServiceKind.WIREGUARD, InstallationFilter.INSTALLED)
        choices.setServiceSort(ServiceKind.WIREGUARD, true)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        open()
        assertEquals(ServerListPreferences(ServerListSort.UPTIME, true, "Домашние серверы"), choices.serverList.first())
        assertEquals(ServiceListPreferences(InstallationFilter.INSTALLED, true), choices.serviceLists.first()[ServiceKind.WIREGUARD])
    }
    @Test fun panelsKeepIndependentGroupsSortsAndFilters() = runBlocking {
        panel("https://one.example/")
        choices.setServerSort(ServerListSort.SSL, true)
        choices.setServerGroup("Первая")
        choices.setServiceFilter(ServiceKind.WIREGUARD, InstallationFilter.INSTALLED)
        panel("https://two.example/")
        assertEquals(ServerListPreferences(), choices.serverList.first())
        assertEquals(ServiceListPreferences(), choices.serviceLists.first()[ServiceKind.WIREGUARD])
        choices.setServiceFilter(ServiceKind.WIREGUARD, InstallationFilter.ABSENT)
        panel("https://one.example/")
        assertEquals(ServerListPreferences(ServerListSort.SSL, true, "Первая"), choices.serverList.first())
        assertEquals(InstallationFilter.INSTALLED, choices.serviceLists.first()[ServiceKind.WIREGUARD]!!.filter)
        panel("https://two.example/")
        assertEquals(InstallationFilter.ABSENT, choices.serviceLists.first()[ServiceKind.WIREGUARD]!!.filter)
    }
    @Test fun serviceKindsKeepIndependentSelectionsEvenWhenNoRowsMatch() = runBlocking {
        choices.setServiceFilter(ServiceKind.WIREGUARD, InstallationFilter.INSTALLED)
        choices.setServiceFilter(ServiceKind.DOCKER, InstallationFilter.BLOCKED)
        choices.setServiceSort(ServiceKind.DOCKER, true)
        val saved = choices.serviceLists.first()
        assertEquals(ServiceListPreferences(InstallationFilter.INSTALLED), saved[ServiceKind.WIREGUARD])
        assertEquals(ServiceListPreferences(InstallationFilter.BLOCKED, true), saved[ServiceKind.DOCKER])
        assertEquals(ServiceListPreferences(), saved[ServiceKind.XUI])
        assertTrue(filterServiceServers(emptyList(), saved[ServiceKind.WIREGUARD]!!.filter, "", null).isEmpty())
        assertEquals(InstallationFilter.INSTALLED, choices.serviceLists.first()[ServiceKind.WIREGUARD]!!.filter)
    }
    @Test fun unknownValuesFallBackAndClassicIsOnlyRestoredForWireguard() = runBlocking {
        data.edit { prefs ->
            prefs[panelPreferenceKey(prefs, "server_sort")] = "REMOVED"
            prefs[booleanPreferencesKey(panelPreferenceKey(prefs, "server_descending").name)] = true
            prefs[panelPreferenceKey(prefs, "service_filter_wireguard")] = "CLASSIC"
            prefs[panelPreferenceKey(prefs, "service_filter_docker")] = "CLASSIC"
            prefs[panelPreferenceKey(prefs, "service_filter_3x-ui")] = "UNKNOWN"
        }
        assertEquals(ServerListPreferences(), choices.serverList.first())
        val saved = choices.serviceLists.first()
        assertEquals(InstallationFilter.CLASSIC, saved[ServiceKind.WIREGUARD]!!.filter)
        assertEquals(InstallationFilter.ALL, saved[ServiceKind.DOCKER]!!.filter)
        assertEquals(InstallationFilter.ALL, saved[ServiceKind.XUI]!!.filter)
    }
    @Test fun choosingAllClearsGroupWithoutResettingSortOrOtherPreferences() = runBlocking {
        choices.setServerSort(ServerListSort.STATUS, true)
        choices.setServerGroup("Первая")
        choices.setServiceFilter(ServiceKind.WIREGUARD, InstallationFilter.INSTALLED)
        choices.setServerGroup(null)
        assertEquals(ServerListPreferences(ServerListSort.STATUS, true), choices.serverList.first())
        assertEquals(InstallationFilter.INSTALLED, choices.serviceLists.first()[ServiceKind.WIREGUARD]!!.filter)
    }
}
