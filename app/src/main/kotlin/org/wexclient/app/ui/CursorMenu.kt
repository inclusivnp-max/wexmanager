package org.wexclient.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Меню у курсора. [DropdownMenu] ставит себя под или над своим родителем, и у большого
 * родителя (весь список) смещение уводило меню далеко от места щелчка. Здесь родитель —
 * точка в [offset] (относительно верхнего левого угла окружающего Box): меню открывается
 * вниз от курсора, а если не помещается — вверх.
 */
@Composable
fun CursorMenu(
    expanded: Boolean,
    offset: DpOffset,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.offset(offset.x, offset.y).size(0.dp)) {
        WexDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
        ) {
            ReturnFocusOnClose()
            content()
        }
    }
}
