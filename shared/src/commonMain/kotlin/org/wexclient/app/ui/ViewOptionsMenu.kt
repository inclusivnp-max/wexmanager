package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.icons.SymbolStyle
import org.wexclient.app.theme.NoctaliaPrimary

/** Кнопка тулбара панели: сортировка, фильтр по типу и настройки отображения. */
@Composable
fun ViewOptionsButton(state: FilePaneState) {
    var open by remember { mutableStateOf(false) }
    val prefs = ViewPreferences
    val nonDefault = state.typeFilter != TypeFilter.ALL || !prefs.showHidden

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(30.dp).noFocus()) {
            Box {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Сортировка, фильтр и вид",
                    // В один тон с соседями: что фильтр включён, показывает точка в углу
                    tint = NoctaliaPrimary,
                    modifier = Modifier.size(18.dp)
                )
                if (nonDefault) {
                    Surface(
                        shape = CircleShape,
                        color = NoctaliaPrimary,
                        modifier = Modifier.size(6.dp).align(Alignment.TopEnd)
                    ) {}
                }
            }
        }

        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReturnFocusOnClose()
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                // --- Сортировка ---
                Column(modifier = Modifier.width(236.dp)) {
                    MenuHeader("Сортировка")
                    SortField.entries.forEach { field ->
                        OptionRow(
                            icon = field.symbol,
                            text = field.title,
                            selected = state.sortField == field,
                            trailingIcon = if (state.sortField == field) (if (state.sortAscending) "arrow_upward" else "arrow_downward") else null
                        ) {
                            // Повторный клик по текущему полю меняет направление
                            if (state.sortField == field) state.updateSort(field, !state.sortAscending)
                            else state.updateSort(field, true)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.padding(horizontal = 10.dp)) {
                        GrailTabs(listOf("Возрастание", "Убывание"), if (state.sortAscending) 0 else 1) {
                            state.updateSort(state.sortField, it == 0)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    SwitchRow("folder_copy", "Папки сверху", prefs.foldersFirst) { prefs.updateFoldersFirst(it) }
                    SwitchRow("format_list_numbered", "Числа: 2 раньше 10", prefs.naturalSort) { prefs.updateNaturalSort(it) }
                }

                VerticalDivider(modifier = Modifier.height(360.dp).padding(horizontal = 4.dp))

                // --- Фильтр и отображение ---
                Column(modifier = Modifier.width(236.dp)) {
                    MenuHeader("Показывать")
                    TypeFilter.entries.forEach { f ->
                        OptionRow(icon = f.symbol, text = f.title, selected = state.typeFilter == f) {
                            state.applyTypeFilter(f)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    MenuHeader("Вид")
                    SwitchRow("visibility", "Скрытые файлы (.*)", prefs.showHidden) { prefs.updateShowHidden(it) }
                    SwitchRow("photo_library", "Миниатюры и иконки APK", prefs.showThumbnails) { prefs.updateShowThumbnails(it) }
                    SwitchRow("data_usage", "Колонка «Размер»", prefs.showSizeColumn) { prefs.updateShowSizeColumn(it) }
                    SwitchRow("schedule", "Колонка «Дата»", prefs.showDateColumn) { prefs.updateShowDateColumn(it) }
                    SwitchRow("commit", "Статус git у файлов", prefs.showGitStatus) { prefs.updateShowGitStatus(it) }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
        color = NoctaliaPrimary,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun OptionRow(
    icon: String,
    text: String,
    selected: Boolean,
    trailing: String? = null,
    trailingIcon: String? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.16f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.35f)) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp).height(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SymbolIcon(
                SymbolRef(icon, if (selected) SymbolStyle.FILLED else SymbolStyle.OUTLINED),
                contentDescription = null,
                tint = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (trailing != null) {
                Text(trailing, fontSize = 11.sp, color = NoctaliaPrimary, fontWeight = FontWeight.Bold)
            }
            if (trailingIcon != null) {
                SymbolIcon(SymbolRef(trailingIcon), contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun SwitchRow(icon: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SymbolIcon(
            SymbolRef(icon, SymbolStyle.OUTLINED),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
        // M3 Switch не сжимается по размеру — уменьшаем масштабом
        Box(modifier = Modifier.padding(start = 6.dp).size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = NoctaliaPrimary),
                modifier = Modifier.scale(0.62f)
            )
        }
    }
}
