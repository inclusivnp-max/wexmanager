package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize

/**
 * Отдельное окно инструмента — редактор, просмотр, список. На десктопе это своё окно ОС размером [size];
 * на Android — экран во весь размер поверх файлового менеджера, системное «назад» = [onCloseRequest].
 * Обработчики клавиш те же: на Android их слышит подключённая клавиатура.
 */
@Composable
expect fun WexWindow(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable () -> Unit
)
