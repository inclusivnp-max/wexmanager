package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.wexclient.app.util.WindowIcon
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

@Composable
actual fun WexWindow(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    independent: Boolean,
    content: @Composable () -> Unit
) {
    if (independent) {
        // Документ не диалог владельца: иначе AWT отбрасывает ввод в менеджер,
        // пока «Расположение» пытается передать ему фокус.
        Window(
            onCloseRequest = onCloseRequest,
            state = rememberWindowState(size = size),
            title = title,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent
        ) {
            LaunchedEffect(window) { WindowIcon.applyTo(window) }
            content()
        }
        return
    }
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
