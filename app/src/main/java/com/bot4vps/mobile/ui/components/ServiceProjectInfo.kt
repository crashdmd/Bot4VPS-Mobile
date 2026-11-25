package com.bot4vps.mobile.ui.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

data class ServiceProject(val id: String, val name: String, val author: String, val repository: String, val description: String)

@HiltViewModel
class ServiceProjectsViewModel @Inject constructor(@ApplicationContext context: Context, private val gateways: GatewayRepository): ViewModel() {
    var projects by mutableStateOf(decode(gateways.panel.json.parseToJsonElement(context.assets.open("service_projects.json").bufferedReader().use { it.readText() }).jsonObject)); private set
    init {
        viewModelScope.launch {
            try {
                val origin = gateways.address()
                val result = gateways.request(origin, "GET", "/api/service-projects")
                val fresh = decode(result)
                if (fresh.isNotEmpty()) projects = projects + fresh
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The bundled source registry supports older panels. */ }
        }
    }
    private fun decode(value: JsonObject): Map<String, ServiceProject> = value.objects("projects").mapNotNull { row ->
        val repo = row.text("repository") ?: return@mapNotNull null
        if (!Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) return@mapNotNull null
        val id = row.text("id") ?: return@mapNotNull null
        id to ServiceProject(id, row.text("name") ?: id, row.text("author") ?: repo.substringBefore('/'), repo, row.text("description").orEmpty())
    }.toMap()
}

@Composable
fun ServiceProjectInfo(id: String, title: Boolean = false, vm: ServiceProjectsViewModel = hiltViewModel(), help: (() -> Unit)? = null) {
    val project = vm.projects[id] ?: return
    val linkStyle = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (title) Text(project.name, style = MaterialTheme.typography.titleMedium)
        Text(project.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://github.com/${project.repository.substringBefore('/')}", styles = linkStyle)) { append(project.author) }
            append(" · ")
            withLink(LinkAnnotation.Url("https://github.com/${project.repository}", styles = linkStyle)) { append("GitHub ↗") }
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (help != null) TextButton(onClick = help, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(20.dp)) { Text("Помощь", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
