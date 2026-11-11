package com.bot4vps.mobile.ui.scripts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.model.ScriptInfo
import com.bot4vps.mobile.data.model.scriptRunValues
import com.bot4vps.mobile.data.model.scriptParameterError
import com.bot4vps.mobile.data.model.NativeWorkflow
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import com.bot4vps.mobile.data.model.ServerSummary
import com.bot4vps.mobile.data.repository.ServerRepository
import com.bot4vps.mobile.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.bot4vps.mobile.data.api.errorText

enum class ScriptRunStep { SERVER, PARAMETERS, CONFIRM }

data class ScriptsUiState(
    val scripts: List<ScriptInfo> = emptyList(),
    val servers: List<ServerSummary> = emptyList(),
    val selectedServerId: String? = null,
    val activeScript: ScriptInfo? = null,
    val runStep: ScriptRunStep = ScriptRunStep.SERVER,
    val paramValues: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false, val launchedTask: String? = null
)

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val taskRepo: TaskRepository,
    private val serverRepo: ServerRepository, private val workflow: NativeWorkflow
) : ViewModel() {
    private val initialServerId = saved.get<String>("serverId")?.takeIf(String::isNotBlank)
    private val _ui = MutableStateFlow(ScriptsUiState())
    val ui: StateFlow<ScriptsUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_ui.value.isLoading || _ui.value.busy) return
        _ui.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val scripts = taskRepo.scripts()
                val servers = serverRepo.getServers()
                _ui.update {
                    it.copy(
                        scripts = scripts,
                        servers = servers,
                        selectedServerId = it.selectedServerId?.takeIf { id -> servers.any { server -> server.id == id } },
                        isLoading = false
                    )
                }
            } catch(e: CancellationException) { throw e } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, message = errorText(e)) }
            }
        }
    }

    fun selectServer(id: String) {
        if (!_ui.value.busy) _ui.update { it.copy(selectedServerId = id, message = null) }
    }

    fun openRun(script: ScriptInfo) {
        if (_ui.value.busy) return
        val defaults = script.params.associate { it.name to when (it.type) {
            "bool" -> "false"
            "select" -> it.options.firstOrNull()?.value.orEmpty()
            else -> ""
        } }
        _ui.update { it.copy(activeScript = script, selectedServerId = initialServerId?.takeIf { id -> it.servers.any { server -> server.id == id } }, runStep = ScriptRunStep.SERVER, paramValues = defaults, message = null) }
    }

    fun taskOpened() { _ui.update { it.copy(launchedTask=null) } }
    fun file(script:ScriptInfo, delete:Boolean=false):String {
        val id=if(delete) "DELETE /api/files" else "POST /api/files/write"
        workflow.put(id,buildJsonObject { put("root","scripts"); put("name",script.name); put("project","") })
        return id
    }
    fun closeRun() { if (!_ui.value.busy) _ui.update { it.copy(activeScript = null, selectedServerId = null, paramValues = emptyMap(), message = null) } }

    fun nextStep() {
        val state = _ui.value
        if (state.busy || state.isLoading) return
        if (state.servers.none { it.id == state.selectedServerId }) {
            _ui.update { it.copy(message = "Выберите сервер") }; return
        }
        when (state.runStep) {
            ScriptRunStep.SERVER -> _ui.update { it.copy(runStep = ScriptRunStep.PARAMETERS, message = null) }
            ScriptRunStep.PARAMETERS -> {
                val script = state.activeScript ?: return
                val error = scriptParameterError(script, state.paramValues)
                _ui.update { it.copy(runStep = if (error == null) ScriptRunStep.CONFIRM else it.runStep, message = error) }
            }
            ScriptRunStep.CONFIRM -> Unit
        }
    }

    fun previousStep() {
        if (!_ui.value.busy) _ui.update { it.copy(runStep = if (it.runStep == ScriptRunStep.CONFIRM) ScriptRunStep.PARAMETERS else ScriptRunStep.SERVER, message = null) }
    }

    fun setParam(name: String, value: String) {
        if (!_ui.value.busy) _ui.update { it.copy(paramValues = it.paramValues + (name to value), message = null) }
    }

    fun run() {
        val state = _ui.value
        if (state.busy || state.runStep != ScriptRunStep.CONFIRM) return
        val script = state.activeScript ?: return
        val serverId = state.selectedServerId?.takeIf { id -> state.servers.any { it.id == id } } ?: run {
            _ui.update { it.copy(message = "Выберите сервер") }
            return
        }
        scriptParameterError(script, state.paramValues)?.let { error -> _ui.update { it.copy(message = error, runStep = ScriptRunStep.PARAMETERS) }; return }
        val values = scriptRunValues(script, state.paramValues)
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val r = taskRepo.enqueue(script.name, serverId, values)
                val id = r.task?.id ?: r.taskId ?: r.id
                _ui.update {
                    it.copy(
                        busy = false,
                        activeScript = if(r.ok || id != null) null else it.activeScript, launchedTask=id,
                        message = if (r.ok || id != null) "Запущено: ${id ?: script.name}" else (r.error ?: r.detail ?: "Ошибка")
                    )
                }
            } catch(e: CancellationException) { throw e } catch (e: Exception) {
                _ui.update { it.copy(busy = false, message = errorText(e)) }
            }
        }
    }
}
