package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.wexclient.app.util.WindowIcon
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

@Composable
actual fun WexWindow(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable () -> Unit
) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = rememberDialogState(size = size),
        title = title,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent
    ) {
        // Иначе в панели задач у отдельного окна — кофейный логотип Java
        LaunchedEffect(window) { WindowIcon.applyTo(window) }
        content()
    }
}
