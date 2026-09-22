package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaPrimary

/**
 * Кнопка окон-инструментов (анализ места, дубликаты): контур и мягкая заливка цвета темы.
 * [danger] — приглушённый красный для необратимого или «в корзину».
 */
@Composable
fun ToolAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) NoctaliaDanger.copy(alpha = 0.85f) else NoctaliaPrimary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = if (enabled) 0.12f else 0.04f),
        border = BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.35f else 0.15f)),
        modifier = Modifier.height(32.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(label, fontSize = 12.sp, color = tint.copy(alpha = if (enabled) 1f else 0.4f))
        }
    }
}

/** Флажок с подписью; щелчок по подписи тоже переключает. */
@Composable
fun ToolCheck(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 14.dp).clickable(enabled = enabled) { onChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
            modifier = Modifier.size(22.dp).noFocus()
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
        )
    }
}

/** Выбор одного из вариантов «чипами». */
@Composable
fun <T> ToolChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row {
        options.forEach { (value, title) ->
            val on = value == selected
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (on) NoctaliaPrimary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, if (on) NoctaliaPrimary.copy(alpha = 0.7f) else Color.Transparent),
                modifier = Modifier.padding(end = 6.dp).clickable { onSelect(value) }
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = if (on) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}
