package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import java.nio.file.Path

// Перенос между панелями на Android — кнопками «Копировать/Переместить в другую панель» и через буфер

@Composable
actual fun rememberFileDragSource(paths: () -> List<Path>, label: () -> String, onFinished: () -> Unit): Modifier = Modifier

@Composable
actual fun rememberFileDropTarget(onHover: (Boolean) -> Unit, onDrop: (paths: List<Path>, rootPoint: Offset?) -> Boolean): Modifier = Modifier

actual fun PointerEvent.clickCount(): Int = 1

actual val ResizeHorizontalPointerIcon: PointerIcon get() = PointerIcon.Default
