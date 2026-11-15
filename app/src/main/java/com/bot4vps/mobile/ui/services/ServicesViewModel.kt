package com.bot4vps.mobile.ui.services

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import com.bot4vps.mobile.data.local.ServiceListPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ServicesState(val selected: ServiceKind? = null, val rows: List<ServiceServer> = emptyList(),
    val filter: InstallationFilter = InstallationFilter.ALL, val search: String = "",
    val order: List<String> = emptyList(), val loaded: Boolean = false, val loading: Boolean = false, val error: String? = null, val sortByName: Boolean = false, val revealAddresses: Boolean = false) {
    val filtered get() = filterServiceServers(rows, filter, search, null).let { list ->
        if (sortByName) list.sortedBy { it.name.lowercase() }
        else list.sortedBy { order.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    }
}

@HiltViewModel
class ServicesViewModel @Inject constructor(private val saved: SavedStateHandle, private val repo: ServiceRepository, private val realtime: RealtimeHub, private val prefs: com.bot4vps.mobile.data.local.PreferencesManager) : ViewModel() {
    private val _state = MutableStateFlow(ServicesState(selected = ServiceKind.find(saved.get<String>("selected_service"))))
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    private var choices: Map<ServiceKind, ServiceListPreferences> = emptyMap()
    init {
        viewModelScope.launch { prefs.serviceLists.collect { savedChoices ->
            choices = savedChoices
            _state.update { old ->
                val choice = savedChoices[old.selected] ?: ServiceListPreferences()
                old.copy(filter = choice.filter, sortByName = choice.sortByName)
            }
        } }
        viewModelScope.launch { prefs.revealServices.collect { value -> _state.update { it.copy(revealAddresses = value) } } }
        if (_state.value.selected != null) refresh()
    }
    fun revealAddresses() { viewModelScope.launch { prefs.setRevealServices(!_state.value.revealAddresses) } }
    fun select(kind: ServiceKind?) {
        generation++
        request?.cancel()
        saved["selected_service"] = kind?.id
        val choice = choices[kind] ?: ServiceListPreferences()
        _state.value = ServicesState(selected = kind, filter = choice.filter, sortByName = choice.sortByName,
            revealAddresses = _state.value.revealAddresses)
        refresh()
    }
    fun filter(value: InstallationFilter) {
        val kind = _state.value.selected ?: return
        choices = choices + (kind to (choices[kind] ?: ServiceListPreferences()).copy(filter = value))
        _state.update { it.copy(filter = value) }
        viewModelScope.launch { prefs.setServiceFilter(kind, value) }
    }
    fun search(value: String) { _state.update { it.copy(search = value) } }
    fun sort() {
        val kind = _state.value.selected ?: return
        val byName = !_state.value.sortByName
        choices = choices + (kind to (choices[kind] ?: ServiceListPreferences()).copy(sortByName = byName))
        _state.update { it.copy(sortByName = byName) }
        viewModelScope.launch { prefs.setServiceSort(kind, byName) }
    }
    fun refresh() { fetch(false) }
    private fun fetch(background: Boolean) {
        val kind = _state.value.selected ?: return
        if (kind == ServiceKind.SCRIPTS) return
        if (request?.isActive == true) {
            if (!background) _state.update { it.copy(loading = true) }
            return
        }
        val current = generation
        request = viewModelScope.launch {
            _state.update { it.copy(loading = !background || !it.loaded, error = if (background) it.error else null) }
            try {
                val rows = withTimeout(45_000) { repo.servers(kind) }
                if (current != generation) return@launch
                _state.update { old ->
                    val ranked = rows.sortedWith(compareBy<ServiceServer> { if (it.installed) 0 else if (it.classic) 1 else 2 }.thenBy { it.name.lowercase() }).map { it.id }
                    val order = if (old.order.isNotEmpty()) retainServerOrder(old.order, ranked) else ranked
                    old.copy(rows = rows, order = order, loaded = true, loading = false, error = null)
                }
            } catch (e: TimeoutCancellationException) {
                if (current == generation) _state.update { it.copy(error = "Сервис не ответил за 45 секунд. Повторите обновление.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) _state.update { it.copy(error = errorText(e)) } }
            finally { if (current == generation) _state.update { it.copy(loading = false) } }
        }
    }
    suspend fun observe() {
        fetch(true)
        realtime.events.filter { it.type == "snapshot" }.conflate().collect {
            fetch(true)
            request?.join()
            delay(6000)
        }
    }
}
