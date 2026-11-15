package com.bot4vps.mobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/** App dialogs consistently place confirmation on the left and cancellation on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PanelAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, icon = icon, title = title,
        text = text, properties = properties, confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { confirmButton() }
                if (dismissButton != null) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { dismissButton() }
                }
            }
        })
}
