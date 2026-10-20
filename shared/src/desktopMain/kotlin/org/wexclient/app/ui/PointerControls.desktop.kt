package org.wexclient.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun WexTooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier,
    delayMillis: Int,
    cursorOffset: DpOffset?,
    content: @Composable () -> Unit
) {
    TooltipArea(
        tooltip = tooltip,
        modifier = modifier,
        delayMillis = delayMillis,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = cursorOffset ?: DpOffset(0.dp, 16.dp)),
        content = content
    )
}

@Composable
actual fun WexHorizontalScrollbar(state: ScrollState, modifier: Modifier, style: WexScrollbarStyle?) {
    if (style == null) {
        HorizontalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
        return
    }
    HorizontalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = style.toDesktop())
}

@Composable
private fun WexScrollbarStyle.toDesktop() = LocalScrollbarStyle.current.let { base ->
    base.copy(
        thickness = thickness,
        minimalHeight = minimalHeight ?: base.minimalHeight,
        unhoverColor = unhoverColor,
        hoverColor = hoverColor,
        hoverDurationMillis = hoverDurationMillis ?: base.hoverDurationMillis
    )
}

@Composable
actual fun WexVerticalScrollbar(state: LazyListState, modifier: Modifier, style: WexScrollbarStyle?) {
    if (style == null) VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
    else VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = style.toDesktop())
}
