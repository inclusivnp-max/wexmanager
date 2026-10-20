package org.wexclient.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset

@Composable
actual fun WexTooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier,
    delayMillis: Int,
    cursorOffset: DpOffset?,
    content: @Composable () -> Unit
) {
    Box(modifier) { content() }
}

@Composable
actual fun WexHorizontalScrollbar(state: ScrollState, modifier: Modifier, style: WexScrollbarStyle?) {
}

@Composable
actual fun WexVerticalScrollbar(state: LazyListState, modifier: Modifier, style: WexScrollbarStyle?) {
}
