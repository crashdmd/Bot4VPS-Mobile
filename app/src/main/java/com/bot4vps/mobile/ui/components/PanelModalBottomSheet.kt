package com.bot4vps.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A bottom-aligned modal: outside touches keep edits, back and Close dismiss. */
@Composable
fun PanelModalBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    scrimColor: Color = Color.Black.copy(alpha = .32f), content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(scrimColor).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Surface(modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * .95f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = containerColor) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onDismissRequest) { Icon(Icons.Default.Close, "Закрыть") }
                    }
                    content()
                }
            }
        }
    }
}
