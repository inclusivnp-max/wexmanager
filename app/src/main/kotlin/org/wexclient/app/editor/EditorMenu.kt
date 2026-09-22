package org.wexclient.app.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.AppFonts

/**
 * Пункт меню редактора: значок, подпись и сочетание клавиш. Пустая подпись — разделитель.
 * Своя структура вместо ContextMenuItem из Compose: там только текст, а меню WEX со значками.
 */
internal class EditorMenuItem(
    val label: String,
    val shortcut: String = "",
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {}
) {
    val separator: Boolean get() = label.isEmpty()

    companion object {
        fun separator() = EditorMenuItem("")
    }
}

/** Строка меню в общем виде WEX: значок слева, сочетание справа, высота как у остальных меню. */
@Composable
internal fun EditorMenuRow(item: EditorMenuItem, onDone: () -> Unit) {
    if (item.separator) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
        return
    }
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Подпись занимает столько, сколько нужно: меню подстраивается под самую длинную
                Text(item.label, fontSize = 13.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(28.dp).weight(1f, fill = true))
                if (item.shortcut.isNotEmpty()) {
                    Text(
                        item.shortcut,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = AppFonts.JetBrainsMono
                    )
                }
            }
        },
        leadingIcon = {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                item.icon?.let {
                    Icon(it, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                }
            }
        },
        enabled = item.enabled,
        onClick = {
            onDone()
            item.onClick()
        },
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(30.dp).widthIn(min = 300.dp)
    )
}
