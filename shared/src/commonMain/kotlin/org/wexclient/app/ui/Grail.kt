package org.wexclient.app.ui

import androidx.compose.material.icons.filled.Circle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.util.isCtrlShortcut
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent

/**
 * Grail-стандарт диалогов-редакторов (DEX / ARSC / ELF):
 * единый хедер с бейджем, таб-чипсы, поиск, моно-ячейки таблиц.
 */

/** Каркас полноэкранного диалога-редактора. */
@Composable
fun GrailDialogShell(
    title: String,
    badge: String,
    heading: String,
    subtitle: String,
    isDirty: Boolean = false,
    /** Esc закрывает окно (для просмотров, где нечего терять). */
    closeOnEsc: Boolean = false,
    dialogWidth: Dp = 1500.dp,
    dialogHeight: Dp = 920.dp,
    onDismiss: () -> Unit,
    /** Сохранение (в фоне) для Ctrl+S и вопроса при закрытии. */
    onSave: AsyncSave? = null,
    toast: ToastState? = null,
    /** Шапка не рисуется: чтение во весь экран, когда мешает всё лишнее. */
    hideChrome: Boolean = false,
    /** Свои клавиши окна (F11 и прочее) — до Esc и Ctrl+S. */
    onKey: ((KeyEvent) -> Boolean)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val guard = rememberCloseGuard(isDirty = { isDirty }, onClose = onDismiss)
    TrackUnsaved(heading, isDirty)
    WexWindow(
        onCloseRequest = { guard.requestClose() },
        onPreviewKeyEvent = latestKeyHandler { e ->
            when {
                onKey?.invoke(e) == true -> true
                onSave != null && e.isCtrlShortcut(Key.S) -> {
                    if (isDirty) onSave {}
                    true
                }
                else -> false
            }
        },
        // Esc — последним: сначала его увидят поиск внутри и другие поля
        onKeyEvent = latestKeyHandler { e -> closeOnEsc && closeOnEscape(e) { guard.requestClose() } },
        size = androidx.compose.ui.unit.DpSize(dialogWidth, dialogHeight),
        title = (if (isDirty) "● " else "") + title
    ) {
        NoctaliaTheme {
            // Подложка как у главного окна: там поверх картинки лежат два полупрозрачных слоя
            // (фон окна и панель). С одним слоем обои в окнах-инструментах били в глаза ярче
            // Фон окна под панелью рисует тема — как у главного окна
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().then(if (hideChrome) Modifier.height(0.dp) else Modifier),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier.padding(end = 10.dp)
                            ) {
                                Text(
                                    text = badge,
                                    fontSize = 11.sp,
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = heading,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = NoctaliaPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = AppFonts.JetBrainsMono,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isDirty) {
                                        Spacer(modifier = Modifier.width(10.dp))
                                        DirtyMark()
                                    }
                                }
                            }
                            actions()
                            IconButton(onClick = { guard.requestClose() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                    content()
                }
                if (toast != null) ToastHost(toast)
              }
            }

            if (guard.asking) {
                UnsavedChangesDialog(
                    fileName = heading,
                    error = guard.saveError,
                    busy = guard.saving,
                    canSave = onSave != null,
                    onSave = { onSave?.let { guard.saveAndCloseAsync(it) } },
                    onDiscard = { guard.discard() },
                    onCancel = { guard.cancel() }
                )
            }
        }
    }
}

/** Отметка «изменено»: точка-иконка и подпись (вместо символа ●). */
@Composable
fun DirtyMark(color: Color = org.wexclient.app.theme.NoctaliaTertiary, fontSize: androidx.compose.ui.unit.TextUnit = 11.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            androidx.compose.material.icons.Icons.Default.Circle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(7.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("изменено", fontSize = fontSize, color = color, maxLines = 1)
    }
}

/** Сегмент-переключатель вкладок. */
@Composable
fun GrailTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            tabs.forEachIndexed { i, label ->
                GrailTabChip(label, i == selected) { onSelect(i) }
            }
        }
    }
}

@Composable
fun GrailTabChip(label: String, isActive: Boolean, onClick: () -> Unit) {
    // Вкладка не «щёлкает» цветом, а переходит — как подсветка строки в списке
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = if (isActive) NoctaliaPrimary.copy(alpha = 0.25f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(org.wexclient.app.ui.Motion.TINT)
    )
    val labelColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isActive) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = androidx.compose.animation.core.tween(org.wexclient.app.ui.Motion.TINT)
    )
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = background,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                fontFamily = AppFonts.JetBrainsMono
            ),
            color = labelColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** Заголовок секции внутри редактора. */
@Composable
fun GrailSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = NoctaliaPrimary
    )
}

/** Стандартное поле поиска. */
@Composable
fun GrailSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    GrailCompactField(
        value = value,
        onChange = onChange,
        placeholder = placeholder,
        modifier = modifier,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    )
}

/**
 * Компактное поле ввода (46dp): M3 OutlinedTextField при принудительной высоте
 * меньше спека 56dp топит хинт ниже центра, поэтому здесь BasicTextField
 * с ручным центрированием — хинт и ввод всегда по центру.
 */
@Composable
fun GrailCompactField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    // Поле было ростом с кнопку тулбара и распирало шапку — хватает 34 точек
    height: Dp = 34.dp,
    mono: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val baseStyle = if (mono) {
        MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono)
    } else {
        MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (focused) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .height(height)
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                it()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = baseStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NoctaliaPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = baseStyle.copy(color = MaterialTheme.colorScheme.outline),
                                    maxLines = 1
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            trailingIcon?.let {
                Spacer(modifier = Modifier.width(4.dp))
                it()
            }
        }
    }
}

/** Ячейка моноширинной таблицы. */
@Composable
fun GrailMonoCell(
    text: String,
    width: Dp,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = AppFonts.JetBrainsMono,
            fontSize = 11.sp
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .padding(end = 8.dp)
    )
}

/** Шапка таблицы. */
@Composable
fun GrailTableHeader(cells: List<Pair<String, Dp>>) {
    Row(modifier = Modifier.padding(bottom = 6.dp)) {
        cells.forEach { (label, w) ->
            GrailMonoCell(label, w, MaterialTheme.colorScheme.outline)
        }
    }
}

/** Пустое состояние списка. */
@Composable
fun GrailEmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(16.dp)
    )
}

/** Подпись-подсказка. */
@Composable
fun GrailHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}
