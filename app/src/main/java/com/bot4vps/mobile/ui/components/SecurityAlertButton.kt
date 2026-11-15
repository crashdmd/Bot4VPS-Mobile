package com.bot4vps.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class SecurityAlerts(val count: Int = 0, val open: () -> Unit = {})
val LocalSecurityAlerts = staticCompositionLocalOf { SecurityAlerts() }

@Composable
fun SecurityAlertButton() {
    val alerts = LocalSecurityAlerts.current
    if (alerts.count > 0) IconButton(onClick = alerts.open) {
        Icon(Icons.Default.ErrorOutline, "Уведомления безопасности: ${alerts.count}", tint = Color(0xffef4444))
    }
}
