package org.wexclient.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Панель узкая (телефон: две панели рядом) — строки компактные: имя во всю ширину, размер и дата
 * второй строкой; столбцы «Размер» и «Дата» в заголовке ужимаются. На десктопе и планшете панели шире.
 */
val LocalCompactRows = compositionLocalOf { false }

/** Уже — компактные строки. */
val COMPACT_PANE_WIDTH = 440.dp
