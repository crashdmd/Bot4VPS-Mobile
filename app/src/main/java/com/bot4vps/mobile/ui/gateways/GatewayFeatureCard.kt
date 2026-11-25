package com.bot4vps.mobile.ui.gateways

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.repository.GatewayRepository
import com.bot4vps.mobile.ui.components.*
import com.bot4vps.mobile.ui.settings.SettingRow
import com.bot4vps.mobile.ui.settings.SettingsCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class GatewayFeatureViewModel @Inject constructor(private val repository: GatewayRepository) : ViewModel() {
    var data by mutableStateOf<JsonObject?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var confirm by mutableStateOf(false); private set
    private var origin: String? = null
    init { refresh() }
    fun refresh() = viewModelScope.launch {
        try { val expected = repository.address(); origin = expected; data = repository.request(expected, "GET", "/api/settings/openwrt") }
        catch (e: CancellationException) { throw e }
        catch (e: com.bot4vps.mobile.data.api.ApiError) { if (e.status != 404) error = errorText(e) }
        catch (e: Exception) { error = errorText(e) }
    }
    fun change(enabled: Boolean) {
        if (busy) return
        viewModelScope.launch {
            busy = true; error = null
            try {
                val expected = origin ?: repository.address()
                val current = repository.request(expected, "GET", "/api/settings/openwrt")
                data = current
                if (!enabled && (current.number("gateways") ?: 0.0) > 0) confirm = true else save(enabled, false)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) } finally { busy = false }
        }
    }
    private suspend fun save(enabled: Boolean, confirmed: Boolean) {
        data = repository.request(origin ?: repository.address(), "PUT", "/api/settings/openwrt", buildJsonObject { put("enabled", enabled); put("confirmed", confirmed) })
        repository.refresh(force = true); confirm = false
    }
    fun apply() = viewModelScope.launch { if (busy) return@launch; busy = true; error = null
        try { save(false, true) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) } finally { busy = false }
    }
    fun cancel() { if (!busy) confirm = false }
}

@Composable
fun GatewayFeatureCard(vm: GatewayFeatureViewModel = hiltViewModel()) {
    val data = vm.data
    if (data == null) { vm.error?.let { ErrorCard(it) }; return }
    SettingsCard("Шлюзы OpenWrt", "Мониторинг сети и управление роутерами. При выключении раздел и блок на обзоре скрываются.") {
        SettingRow("Управление OpenWrt", "Добавлено шлюзов: ${data.text("gateways") ?: "0"}") { Switch(data.flag("enabled"), vm::change, enabled = !vm.busy) }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (vm.confirm) PanelAlertDialog(onDismissRequest = vm::cancel, title = { Text("Выключить управление OpenWrt?") }, text = {
        Text("Добавлено шлюзов: ${data.text("gateways")}. Опросы и подключения остановятся, консоли закроются. Записи и зашифрованные данные сохранятся для повторного включения. Уже запущенные действия на роутере могут завершиться самостоятельно.")
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }, confirmButton = { TextButton(onClick = { vm.apply() }, enabled = !vm.busy) { Text("Выключить") } }, dismissButton = { TextButton(onClick = vm::cancel, enabled = !vm.busy) { Text("Отмена") } })
}
