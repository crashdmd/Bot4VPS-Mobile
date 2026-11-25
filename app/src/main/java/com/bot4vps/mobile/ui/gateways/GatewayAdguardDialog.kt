package com.bot4vps.mobile.ui.gateways

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.components.*

@Composable
fun GatewayAdguardDialog(node: Gateway, data: kotlinx.serialization.json.JsonObject, vm: GatewayViewModel, dismiss: () -> Unit, install: () -> Unit, dns: (GatewayForm) -> Unit, browser: () -> Unit) {
    val context = LocalContext.current
    PanelAlertDialog(onDismissRequest = dismiss, title = { Text("AdGuard Home") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ServiceProjectInfo("adguardhome")
            InfoRow("Установка", if (data.flag("installed")) if (data.flag("managed")) "Управляемая" else "Найдена на роутере" else "Не установлен")
            InfoRow("Настройка", if (data.flag("configured")) "Завершена" else "Требуется мастер настройки")
            InfoRow("Порты веб / DNS", "${data.text("web_port") ?: "3000"} / ${data.text("dns_port") ?: "—"}")
            InfoRow("Свободно в /opt", displayBytes(data.number("free_bytes")))
            if (data.flag("setup_port_busy") && !data.flag("configured")) Text("Порт 3000 занят. Освободите его перед первым запуском.", color = MaterialTheme.colorScheme.error)
            data.strings("dns_dependencies").forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            data.strings("steps").forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!data.flag("installed")) PanelButton(onClick = { dismiss(); install() }, enabled = !vm.busy && node.controls) { Text("Выбрать версию и установить") }
            else {
                if (node.services.none { it.text("id") == "adguardhome" && it.flag("running") }) PanelButton(onClick = { dismiss(); vm.serviceAction("adguardhome", "start") }, enabled = !vm.busy && node.controls && (!data.flag("setup_port_busy") || data.flag("configured"))) { Text("Запустить сервис") }
                TextButton(onClick = browser) { Text(if (data.flag("configured")) "Открыть интерфейс AdGuard Home" else "Открыть мастер настройки") }
            }
            TextButton(enabled = !vm.busy && node.controls, onClick = {
                vm.act {
                    val config = vm.call("GET", "${vm.base}/network")
                    val instance = config.objects("dns").firstOrNull() ?: error("dnsmasq не настроен")
                    dismiss(); dns(gatewayDnsForm(config, instance))
                }
            }) { Text("Настроить DNS роутера") }
            data.objects("sources").forEach { source -> source.text("url")?.let { url -> TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("${source.text("name")} ↗") } } }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Закрыть") } })
}
