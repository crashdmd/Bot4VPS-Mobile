package com.bot4vps.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bot4vps.mobile.BuildConfig
import com.bot4vps.mobile.ui.components.*

@Composable
fun PanelAboutCard(panelVersion: String?) {
    val authorLinkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.bot4vps.mobile.R.drawable.web_logo_foreground), contentDescription="Логотип Bot4VPS", modifier=Modifier.size(60.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("УПРАВЛЕНИЕ СЕРВЕРАМИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Bot4VPS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Telegram-бот и Web UI для управления VPS и домашними серверами через SSH.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            // Share column widths across both rows; leave room for enlarged link text.
            val versionWidth = maxOf(maxWidth / 4f, 48.dp * fontScale)
            val repositoryWidth = maxOf(maxWidth / 4f, 64.dp * fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ProductVersionRow("Версия панели", panelVersion ?: "—",
                    "https://github.com/crashdmd/Bot4VPS", "Репозиторий панели на GitHub",
                    versionWidth, repositoryWidth)
                ProductVersionRow("Версия приложения", BuildConfig.VERSION_NAME,
                    "https://github.com/crashdmd/Bot4VPS-Mobile", "Репозиторий приложения на GitHub",
                    versionWidth, repositoryWidth)
            }
        }
        HorizontalDivider()
        Text(buildAnnotatedString {
            append("Автор — ")
            withLink(LinkAnnotation.Url("https://github.com/crashdmd",
                styles = TextLinkStyles(style = authorLinkStyle))) { append("crashdmd") }
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProductVersionRow(label: String, version: String, repository: String, linkDescription: String,
    versionWidth: Dp, repositoryWidth: Dp) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(version.ifBlank { "—" }, modifier = Modifier.width(versionWidth),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Box(Modifier.width(repositoryWidth), contentAlignment = Alignment.CenterEnd) {
            OutlinedButton(onClick = { uri.openUri(repository) },
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                    .semantics { contentDescription = linkDescription },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("GitHub ↗", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
