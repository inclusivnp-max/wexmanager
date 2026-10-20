package org.wexclient.app.editor

import org.wexclient.app.ui.tryRequestFocus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DataArray
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.wexclient.app.ui.glass
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.util.Keymap
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import kotlin.math.roundToInt

// ---------------- Мелкие элементы ----------------

/** Фокус после появления окна: поле во всплывающем окне прикрепляется не сразу. */
internal suspend fun requestFocusSafely(requester: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (requester.tryRequestFocus()) return
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolIcon(
    icon: ImageVector,
    hint: String,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        // Своя плашка: у Material она берёт inverseSurface и в тёмной теме получается белой
        tooltip = {
            PlainTooltip(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) { Text(hint, fontSize = 11.sp) }
        },
        state = rememberTooltipState()
    ) {
        // Включённый режим и недоступность показываются цветом, и цвет перетекает
        val tint by androidx.compose.animation.animateColorAsState(
            when {
                !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                active -> NoctaliaPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            androidx.compose.animation.core.tween(org.wexclient.app.ui.Motion.TINT),
            label = "tool-icon-tint"
        )
        val glow by androidx.compose.animation.animateColorAsState(
            if (active && enabled) NoctaliaPrimary.copy(alpha = 0.14f) else Color.Transparent,
            androidx.compose.animation.core.tween(org.wexclient.app.ui.Motion.TINT),
            label = "tool-icon-glow"
        )
        // IconButton сам раздвигается до 48 dp (зона касания Material), и подсветка рисовалась
        // по этому размеру: квадрат шире кнопки, обрезанный панелью сверху и снизу. Кнопка — ровно size
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides Dp.Unspecified
        ) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(size).background(glow, RoundedCornerShape(6.dp))
            ) {
                Icon(icon, contentDescription = hint, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Компактное однострочное поле ввода (моноширинное). */
@Composable
internal fun EditorInputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    error: Boolean = false,
    onKey: (KeyEvent) -> Boolean = { false },
    trailing: @Composable (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val border = when {
        error -> Color(0xFFFF6B6B)
        focused -> NoctaliaPrimary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(30.dp)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(start = 8.dp, end = 2.dp)
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = AppFonts.JetBrainsMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(NoctaliaPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent(onKey)
            )
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleChip(label: String, hint: String, active: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (active) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) { Text(hint, fontSize = 11.sp) }
        },
        state = rememberTooltipState()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 28.dp, height = 24.dp)
                .background(
                    when {
                        active -> NoctaliaPrimary.copy(alpha = 0.22f)
                        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(5.dp)
                )
                .hoverable(interaction)
                .clickable(onClick = onClick)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = hint, tint = tint, modifier = Modifier.size(15.dp))
            } else {
                Text(label, fontSize = 11.sp, fontFamily = AppFonts.JetBrainsMono, fontWeight = FontWeight.Bold, color = tint)
            }
        }
    }
}

@Composable
internal fun MenuSection(title: String) {
    Text(
        title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = NoctaliaPrimary,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
internal fun MenuAction(label: String, shortcut: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (shortcut.isNotEmpty()) {
                    Spacer(Modifier.width(24.dp).weight(1f, fill = true))
                    Text(shortcut, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, fontFamily = AppFonts.JetBrainsMono)
                }
            }
        },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier.height(32.dp).widthIn(min = 300.dp)
    )
}

@Composable
internal fun MenuToggle(
    label: String,
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onToggle: (Boolean) -> Unit
) {
    DropdownMenuItem(
        // Высота только снизу ограничена: длинная подпись переносится, а не обрезается
        text = { Text(label, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        leadingIcon = {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when {
                    icon != null -> Icon(
                        icon,
                        null,
                        tint = if (checked) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(17.dp)
                    )
                    checked -> Icon(Icons.Default.Check, null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                }
            }
        },
        trailingIcon = if (icon == null) null else {
            {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, null, tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
                }
            }
        },
        onClick = { onToggle(!checked) },
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(32.dp).widthIn(min = 320.dp)
    )
}

// ---------------- Поиск и замена ----------------

class FindState {
    var visible by mutableStateOf(false)
    var replaceVisible by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)
    /** Искать только в области, выделенной при включении (область хранит редактор). */
    var inSelection by mutableStateOf(false)

    val options: SearchOptions get() = SearchOptions(query, caseSensitive, wholeWord, regex)
}

@Composable
internal fun FindBar(
    state: FindState,
    matches: SearchMatches,
    current: Int,
    readOnly: Boolean,
    focusRequester: FocusRequester,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    /** «В выделении»: включить (область — текущее выделение) или выключить. */
    onToggleInSelection: () -> Unit = {},
    /** Каретки на все совпадения. */
    onSelectAll: () -> Unit = {},
    /** Сочетания редактора, нажатые в поле поиска (Ctrl+S и т. п.): поле их само не знает. */
    onEditorKey: (KeyEvent) -> Boolean = { false }
) {
    val replaceFocus = remember { FocusRequester() }
    var focusField by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(focusField, state.replaceVisible) {
        val target = focusField ?: return@LaunchedEffect
        if (target === replaceFocus && !state.replaceVisible) return@LaunchedEffect
        requestFocusSafely(target)
        focusField = null
    }
    fun keys(e: KeyEvent, isReplace: Boolean): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        // Ctrl+F / Ctrl+R прямо из строки поиска — переход между полями, а не пустое нажатие
        when (Keymap.action(e, Keymap.GROUP_EDITOR)) {
            "editor.find" -> {
                focusField = focusRequester
                return true
            }
            "editor.replace" -> if (!readOnly) {
                state.replaceVisible = true
                focusField = replaceFocus
                return true
            }
            "editor.findInSelection" -> {
                onToggleInSelection()
                return true
            }
        }
        return when (e.key) {
            // Alt+Enter — каретки на все совпадения (как в VS Code)
            Key.Enter, Key.NumPadEnter -> {
                if (e.isAltPressed) onSelectAll()
                else if (isReplace) onReplace() else if (e.isShiftPressed) onPrev() else onNext()
                true
            }
            Key.Escape -> {
                onClose(); true
            }
            Key.F3 -> {
                if (e.isShiftPressed) onPrev() else onNext(); true
            }
            else -> onEditorKey(e)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                EditorInputField(
                    value = state.query,
                    onChange = { state.query = it },
                    placeholder = "Найти…",
                    focusRequester = focusRequester,
                    error = matches.error != null || (state.query.isNotEmpty() && matches.count == 0),
                    onKey = { keys(it, false) },
                    // Шире поля замены: справа ещё четыре переключателя
                    modifier = Modifier.width(380.dp),
                    trailing = {
                        ToggleChip("Aa", "Учитывать регистр", state.caseSensitive) { state.caseSensitive = !state.caseSensitive }
                        ToggleChip("W", "Слово целиком", state.wholeWord) { state.wholeWord = !state.wholeWord }
                        ToggleChip(".*", "Регулярное выражение", state.regex) { state.regex = !state.regex }
                        ToggleChip(
                            "",
                            "В выделении (" + Keymap.label("editor.findInSelection") + ")",
                            state.inSelection,
                            icon = Icons.Default.HighlightAlt,
                            onClick = onToggleInSelection
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                val info = when {
                    matches.error != null -> matches.error
                    state.query.isEmpty() -> ""
                    matches.count == 0 -> "Нет совпадений"
                    current >= 0 -> "${current + 1} из ${matches.count}${if (matches.truncated) "+" else ""}"
                    else -> "${matches.count} совп."
                }
                Text(
                    info,
                    fontSize = 11.sp,
                    fontFamily = AppFonts.JetBrainsMono,
                    color = if (matches.error != null || (state.query.isNotEmpty() && matches.count == 0)) Color(0xFFFF6B6B) else NoctaliaTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 90.dp, max = 260.dp)
                )
                ToolIcon(Icons.Default.ArrowUpward, "Предыдущее (Shift+Enter)", enabled = matches.count > 0, onClick = onPrev)
                ToolIcon(Icons.Default.ArrowDownward, "Следующее (Enter)", enabled = matches.count > 0, onClick = onNext)
                ToolIcon(Icons.Default.SelectAll, "Каретки на все совпадения (Alt+Enter)", enabled = matches.count > 0, onClick = onSelectAll)
                if (!readOnly) {
                    ToolIcon(Icons.Default.FindReplace, "Замена (Ctrl+R)", active = state.replaceVisible) {
                        state.replaceVisible = !state.replaceVisible
                    }
                }
                Spacer(Modifier.weight(1f))
                ToolIcon(Icons.Default.Close, "Закрыть (Esc)", onClick = onClose)
            }
            if (state.replaceVisible && !readOnly) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(22.dp))
                    EditorInputField(
                        value = state.replacement,
                        onChange = { state.replacement = it },
                        placeholder = if (state.regex) "Заменить на… ($1 — группа)" else "Заменить на…",
                        focusRequester = replaceFocus,
                        onKey = { keys(it, true) },
                        modifier = Modifier.width(380.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    SmallButton("Заменить", enabled = matches.count > 0, onClick = onReplace)
                    Spacer(Modifier.width(6.dp))
                    SmallButton("Заменить все", enabled = matches.count > 0, onClick = onReplaceAll)
                }
            }
        }
    }
}

@Composable
internal fun SmallButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (primary) NoctaliaPrimary else Color.Transparent,
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .height(28.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 12.sp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    primary -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

// ---------------- Переход к строке ----------------

@Composable
internal fun GotoLinePopup(ctrl: CodeEditorController, onDone: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val snap = ctrl.snapshot
    val caret = ctrl.value.selection.end
    val curLine = snap.lineOf(caret)
    LaunchedEffect(Unit) { requestFocusSafely(focus) }
    fun go() {
        val parts = input.trim().split(':', ',', ' ').filter { it.isNotEmpty() }
        val line = parts.getOrNull(0)?.toIntOrNull() ?: return onDone()
        val col = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val l = (line - 1).coerceIn(0, snap.lineCount - 1)
        val ls = snap.lineStart(l)
        val le = snap.lineEnd(l)
        val off = (ls + col - 1).coerceIn(ls, le)
        ctrl.select(off, off, center = true)
        onDone()
    }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 60),
        onDismissRequest = onDone,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 10.dp,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
        ) {
            Column(Modifier.padding(12.dp).width(320.dp)) {
                Text("Перейти к строке", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NoctaliaPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Сейчас: ${curLine + 1}:${caret - snap.lineStart(curLine) + 1} · всего строк ${snap.lineCount}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                EditorInputField(
                    value = input,
                    onChange = { v -> input = v.filter { it.isDigit() || it == ':' } },
                    placeholder = "строка[:колонка]",
                    focusRequester = focus,
                    onKey = { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            go(); true
                        } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            onDone(); true
                        } else false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------- Структура ----------------

private fun kindColor(kind: ItemKind): Color = when (kind) {
    ItemKind.TYPE -> Color(0xFFC4A7FF)
    ItemKind.FUNCTION -> Color(0xFF93C5FD)
    ItemKind.PROPERTY -> Color(0xFF7DD3FC)
    ItemKind.CONSTANT -> Color(0xFFFCA5A5)
    ItemKind.TAG -> NoctaliaPrimary
    ItemKind.MODULE -> NoctaliaSecondary
    ItemKind.DIRECTIVE -> NoctaliaTertiary
    else -> Color(0xFF86EFAC)
}

@Composable
private fun OutlineRow(item: OutlineItem, selected: Boolean, current: Boolean, indent: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> NoctaliaPrimary.copy(alpha = 0.22f)
                    current -> NoctaliaPrimary.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(start = (6 + if (indent) item.depth.coerceAtMost(8) * 12 else 0).dp, end = 6.dp, top = 3.dp, bottom = 3.dp)
    ) {
        KindBadge(item.kind)
        Spacer(Modifier.width(6.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                fontSize = 12.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = if (current) NoctaliaPrimary else kindColor(item.kind).let { if (item.kind == ItemKind.TAG) MaterialTheme.colorScheme.onSurface else it },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.detail.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    item.detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            (item.line + 1).toString(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Элемент структуры, в котором стоит курсор (самый вложенный). */
internal fun currentOutlineItem(ctrl: CodeEditorController): OutlineItem? {
    val line = ctrl.snapshot.lineOf(ctrl.value.selection.end)
    return ctrl.outline.lastOrNull { it.line <= line && it.endLine >= line }
        ?: ctrl.outline.lastOrNull { it.line <= line }
}

@Composable
internal fun StructurePanel(ctrl: CodeEditorController, onNavigate: (OutlineItem) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("") }
    val outline = ctrl.outline
    val items = remember(outline, filter) {
        if (filter.isBlank()) outline
        else outline.filter { FuzzyMatcher.score(filter, it.name) != FuzzyMatcher.NO_MATCH }
            .sortedByDescending { FuzzyMatcher.score(filter, it.name) }
    }
    val current = currentOutlineItem(ctrl)
    val listState = rememberLazyListState()
    LaunchedEffect(current, filter) {
        if (filter.isBlank() && current != null) {
            val idx = items.indexOf(current)
            val visible = listState.layoutInfo.visibleItemsInfo
            if (idx >= 0 && visible.none { it.index == idx }) listState.scrollToItem(maxOf(0, idx - 3))
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Структура", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NoctaliaPrimary)
                Spacer(Modifier.width(6.dp))
                Text(outline.size.toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                ToolIcon(Icons.Default.Close, "Скрыть (Alt+7)", size = 22.dp, onClick = onClose)
            }
            EditorInputField(filter, { filter = it }, "Фильтр…", modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Spacer(Modifier.height(4.dp))
            if (items.isEmpty()) {
                Text(
                    if (outline.isEmpty()) "Для этого файла структура не определена" else "Ничего не найдено",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(12.dp)
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { i, it -> "$i:${it.offset}" }) { _, item ->
                    OutlineRow(item, false, item === current, indent = filter.isBlank()) { onNavigate(item) }
                }
            }
        }
    }
}

@Composable
internal fun StructurePopup(ctrl: CodeEditorController, onNavigate: (OutlineItem) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val outline = ctrl.outline
    val items = remember(outline, filter) {
        if (filter.isBlank()) outline
        else outline.filter { FuzzyMatcher.score(filter, it.name) != FuzzyMatcher.NO_MATCH }
            .sortedByDescending { FuzzyMatcher.score(filter, it.name) }
    }
    val current = currentOutlineItem(ctrl)
    LaunchedEffect(Unit) {
        requestFocusSafely(focus)
        if (current != null) selected = outline.indexOf(current).coerceAtLeast(0)
    }
    LaunchedEffect(filter) { if (filter.isNotBlank()) selected = 0 }
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selected } || visible.lastOrNull()?.index == selected) {
            listState.scrollToItem(maxOf(0, selected - 5))
        }
    }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 40),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 12.dp,
            modifier = Modifier
                .glass(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.surfaceContainerHigh)
                .width(560.dp)
                .heightIn(max = 520.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Структура файла", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NoctaliaPrimary)
                    Spacer(Modifier.weight(1f))
                    Text("↑↓ — выбор · Enter — перейти", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(6.dp))
                EditorInputField(
                    filter, { filter = it }, "Имя (нечёткий поиск, CamelHumps)…",
                    focusRequester = focus,
                    modifier = Modifier.fillMaxWidth(),
                    onKey = { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else when (e.key) {
                            Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(items.size - 1); true }
                            Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                            Key.PageDown -> { selected = (selected + 10).coerceAtMost(items.size - 1); true }
                            Key.PageUp -> { selected = (selected - 10).coerceAtLeast(0); true }
                            Key.Enter, Key.NumPadEnter -> {
                                items.getOrNull(selected)?.let(onNavigate)
                                onDismiss(); true
                            }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    itemsIndexed(items, key = { i, it -> "$i:${it.offset}" }) { i, item ->
                        OutlineRow(item, i == selected, item === current, indent = filter.isBlank()) {
                            onNavigate(item)
                            onDismiss()
                        }
                    }
                }
                if (items.isEmpty()) {
                    Text("Нет элементов", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

// ---------------- Строка состояния ----------------

@Composable
internal fun EditorStatusBar(
    ctrl: CodeEditorController,
    readOnly: Boolean,
    languageName: String,
    onGoto: () -> Unit,
    onNextError: () -> Unit,
    onFormatInfo: String?,
    toastInfo: (String) -> Unit,
    onConfirmReload: (java.nio.charset.Charset) -> Unit
) {
    val snap = ctrl.snapshot
    val v = ctrl.value
    val caret = v.selection.end.coerceIn(0, v.text.length)
    val line = snap.lineOf(caret)
    val col = TextOps.columnOf(v.text, caret, EditorPreferences.tabSize) + 1
    val selLen = v.selection.length
    // По разметке строк, а не копией выделения: после Ctrl+A на большом файле строка состояния
    // копировала весь текст на каждую перерисовку
    val selLines = if (selLen > 0) snap.lineOf(v.selection.max) - snap.lineOf(v.selection.min) + 1 else 0
    val diags = ctrl.diagnostics
    val errors = diags.count { it.severity == Severity.ERROR }
    val warnings = diags.size - errors
    val lineDiag = diags.firstOrNull { d ->
        val dl = snap.lineOf(d.start.coerceIn(0, v.text.length))
        dl == line
    }
    val outlined = MaterialTheme.colorScheme.outline
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp)
        ) {
            StatusCell("${line + 1}:$col", "Перейти к строке (Ctrl+G)", onClick = onGoto)
            val caretCount = ctrl.extraCarets.size + 1
            if (caretCount > 1) StatusText("кареток: $caretCount", NoctaliaTertiary)
            if (selLen > 0) {
                StatusText("$selLen симв." + if (selLines > 1) ", $selLines стр." else "", outlined)
            }
            StatusText("${snap.lineCount} стр.", outlined)
            if (errors + warnings > 0) {
                DiagnosticsCell(errors, warnings, onNextError)
            } else if (EditorPreferences.diagnostics) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Проблем нет",
                    tint = Color(0xFF86EFAC),
                    modifier = Modifier.padding(horizontal = 6.dp).size(13.dp)
                )
            }
            if (lineDiag != null) {
                Text(
                    lineDiag.message,
                    fontSize = 11.sp,
                    color = if (lineDiag.severity == Severity.ERROR) Color(0xFFFF6B6B) else Color(0xFFE5C07B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            ctrl.linked?.let {
                StatusText(if (it.isRename) "Переименование: Enter — готово" else "Шаблон: Tab — дальше, Esc — выход", NoctaliaTertiary)
            }
            if (readOnly) {
                Icon(Icons.Default.Lock, "Только чтение", tint = outlined, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                StatusText("только чтение", outlined)
            }
            LanguageCell(ctrl, languageName)
            IndentCell(ctrl)
            SeparatorCell(ctrl, readOnly)
            if (ctrl.charset != null) EncodingCell(ctrl, readOnly, onConfirmReload)
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text,
        fontSize = 11.sp,
        fontFamily = AppFonts.JetBrainsMono,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

/** Ошибки и предупреждения в строке состояния — иконками, клик — к следующей проблеме. */
@Composable
private fun DiagnosticsCell(errors: Int, warnings: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        if (errors > 0) {
            Icon(Icons.Default.Error, contentDescription = "Ошибки", tint = Color(0xFFFF6B6B), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text("$errors", fontSize = 11.sp, fontFamily = AppFonts.JetBrainsMono, color = Color(0xFFFF6B6B))
        }
        if (warnings > 0) {
            if (errors > 0) Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Warning, contentDescription = "Предупреждения", tint = Color(0xFFE5C07B), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text("$warnings", fontSize = 11.sp, fontFamily = AppFonts.JetBrainsMono, color = Color(0xFFE5C07B))
        }
    }
}

@Composable
private fun StatusCell(text: String, hint: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 11.sp, fontFamily = AppFonts.JetBrainsMono, color = color, maxLines = 1)
    }
}

@Composable
private fun LanguageCell(ctrl: CodeEditorController, name: String) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Box {
        StatusCell(name, "Язык подсветки") { open = true; filter = "" }
        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 520.dp)) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { requestFocusSafely(focus) }
            EditorInputField(filter, { filter = it }, "Язык…", focusRequester = focus, modifier = Modifier.width(260.dp).padding(horizontal = 8.dp))
            if (ctrl.languageOverride != null) {
                MenuAction("Определять автоматически") {
                    ctrl.languageOverride = null
                    open = false
                }
                HorizontalDivider()
            }
            val list = Languages.all.filter {
                filter.isBlank() || FuzzyMatcher.score(filter, it.name) != FuzzyMatcher.NO_MATCH ||
                    it.extensions.any { e -> e.equals(filter, true) }
            }.sortedBy { if (it === Languages.PLAIN) "" else it.name.lowercase() }
            for (lang in list) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lang.name, fontSize = 13.sp, color = if (lang === ctrl.language) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                lang.extensions.take(4).joinToString(" ") { ".$it" },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                    },
                    onClick = {
                        ctrl.languageOverride = lang
                        open = false
                    },
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
}

@Composable
private fun IndentCell(ctrl: CodeEditorController) {
    var open by remember { mutableStateOf(false) }
    val unit = ctrl.indentUnit
    val label = if (unit == "\t") "Табы" else "${unit.length} пробела".let { if (unit.length >= 5) "${unit.length} пробелов" else it }
    Box {
        StatusCell(label, "Отступ") { open = true }
        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSection("Отступ для новых строк")
            MenuToggle("Табуляция", unit == "\t") { ctrl.indentUnit = "\t"; open = false }
            for (n in listOf(2, 3, 4, 8)) {
                MenuToggle("$n пробел" + when (n) { 2, 3, 4 -> "а"; else -> "ов" }, unit == " ".repeat(n)) {
                    ctrl.indentUnit = " ".repeat(n); open = false
                }
            }
            MenuSection("Ширина табуляции")
            for (n in listOf(2, 4, 8)) {
                MenuToggle("$n", EditorPreferences.tabSize == n) { EditorPreferences.updateTabSize(n); open = false }
            }
            HorizontalDivider()
            MenuAction("Преобразовать отступы в пробелы") {
                ctrl.applyEdit(ctrl.value.copy(text = TextOps.convertIndentation(ctrl.value.text, false, EditorPreferences.tabSize)))
                if (ctrl.indentUnit == "\t") ctrl.indentUnit = " ".repeat(EditorPreferences.tabSize)
                open = false
            }
            MenuAction("Преобразовать отступы в табы") {
                ctrl.applyEdit(ctrl.value.copy(text = TextOps.convertIndentation(ctrl.value.text, true, EditorPreferences.tabSize)))
                ctrl.indentUnit = "\t"
                open = false
            }
        }
    }
}

@Composable
private fun SeparatorCell(ctrl: CodeEditorController, readOnly: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        StatusCell(ctrl.lineSeparator.label, "Перевод строки") { if (!readOnly) open = true }
        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSection("Перевод строки при сохранении")
            for (sep in LineSeparator.values()) {
                MenuToggle(sep.title, ctrl.lineSeparator == sep) {
                    ctrl.changeLineSeparator(sep)
                    open = false
                }
            }
        }
    }
}

@Composable
private fun EncodingCell(ctrl: CodeEditorController, readOnly: Boolean, onConfirmReload: (java.nio.charset.Charset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cs = ctrl.charset ?: return
    Box {
        StatusCell(TextFileFormat.charsetLabel(cs) + if (ctrl.hasBom) " BOM" else "", "Кодировка") { open = true }
        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 560.dp)) {
            if (ctrl.originalBytes != null) {
                MenuSection("Открыть заново в кодировке")
                for (c in TextCodec.menuCharsets) {
                    MenuToggle(TextFileFormat.charsetLabel(c), false) {
                        open = false
                        onConfirmReload(c)
                    }
                }
            }
            if (!readOnly) {
                HorizontalDivider()
                MenuSection("Сохранять в кодировке")
                for (c in TextCodec.menuCharsets) {
                    MenuToggle(TextFileFormat.charsetLabel(c), c == cs) {
                        ctrl.convertCharset(c)
                        open = false
                    }
                }
                if (TextCodec.supportsBom(cs)) {
                    HorizontalDivider()
                    MenuToggle("Записывать BOM", ctrl.hasBom) {
                        ctrl.convertCharset(cs, it)
                        open = false
                    }
                }
            }
        }
    }
}

// ---------------- Меню инструментов и вида ----------------

@Composable
private fun ActionItem(
    label: String,
    shortcut: String,
    action: EditorAction,
    needsWrite: Boolean,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    act: (EditorAction) -> Unit
) {
    MenuAction(label, shortcut, enabled = !needsWrite || !readOnly) {
        onDismiss()
        act(action)
    }
}

@Composable
internal fun ToolsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    readOnly: Boolean,
    act: (EditorAction) -> Unit
) {
    WexDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.heightIn(max = 640.dp)) {
        val groups: List<Pair<String, List<Triple<String, String, EditorAction>>>> = listOf(
            "Правка" to listOf(
                // Сочетания, которые можно переназначить, — из биндов, а не записанные здесь
                Triple("Дублировать строку/выделение", Keymap.label("editor.duplicate"), EditorAction.Duplicate),
                Triple("Удалить строку", Keymap.label("editor.deleteLine"), EditorAction.DeleteLine),
                Triple("Переместить строку вверх", Keymap.label("editor.moveUp"), EditorAction.MoveUp),
                Triple("Переместить строку вниз", Keymap.label("editor.moveDown"), EditorAction.MoveDown),
                Triple("Объединить строки", "Ctrl+Shift+J", EditorAction.JoinLines),
                Triple("Расширить выделение", "Ctrl+W", EditorAction.ExpandSelection),
                Triple("Сузить выделение", "Ctrl+Shift+W", EditorAction.ShrinkSelection)
            ),
            "Код" to listOf(
                Triple("Автодополнение", Keymap.label("editor.complete"), EditorAction.Complete),
                Triple("Форматировать", Keymap.label("editor.format"), EditorAction.Format),
                Triple("Закомментировать строки", Keymap.label("editor.comment"), EditorAction.LineComment),
                Triple("Блочный комментарий", "Ctrl+Shift+/", EditorAction.BlockComment),
                Triple("Переименовать вхождения", "Shift+F6", EditorAction.Rename),
                Triple("К парной скобке", "Ctrl+Shift+M", EditorAction.MatchingBracket),
                Triple("Следующая ошибка", "F2", EditorAction.NextError),
                Triple("Предыдущая ошибка", "Shift+F2", EditorAction.PrevError),
                Triple("Перейти к строке", Keymap.label("editor.goto"), EditorAction.GotoLine),
                Triple("Структура файла", Keymap.label("editor.structure"), EditorAction.StructurePopup)
            ),
            "Несколько кареток" to listOf(
                Triple("Каретка на следующее вхождение", Keymap.label("editor.nextOccurrence"), EditorAction.NextOccurrence),
                Triple("Каретки на все вхождения", Keymap.label("editor.allOccurrences"), EditorAction.AllOccurrences),
                Triple("Добавить каретку выше", Keymap.label("editor.caretAbove"), EditorAction.CaretAbove),
                Triple("Добавить каретку ниже", Keymap.label("editor.caretBelow"), EditorAction.CaretBelow),
                Triple("Каретки в концы выделенных строк", Keymap.label("editor.caretsLineEnds"), EditorAction.CaretsLineEnds)
            ),
            "Свёртка" to listOf(
                Triple("Свернуть блок", "Ctrl+Shift+[", EditorAction.Fold),
                Triple("Развернуть блок", "Ctrl+Shift+]", EditorAction.Unfold),
                Triple("Свернуть всё", "Ctrl+Shift+−", EditorAction.FoldAll),
                Triple("Развернуть всё", "Ctrl+Shift+=", EditorAction.UnfoldAll)
            ),
            "Строки (выделенные или все)" to listOf(
                Triple("Сортировать по возрастанию", "", EditorAction.SortAsc),
                Triple("Сортировать по убыванию", "", EditorAction.SortDesc),
                Triple("Удалить повторяющиеся", "", EditorAction.Unique),
                Triple("Обратный порядок", "", EditorAction.Reverse),
                Triple("Удалить пустые строки", "", EditorAction.RemoveBlank),
                Triple("Убрать пробелы в конце строк", "", EditorAction.TrimTrailing)
            ),
            "Преобразовать выделение" to listOf(
                Triple("ВЕРХНИЙ / нижний регистр", "Ctrl+Shift+U", EditorAction.ToggleCase),
                Triple("camelCase", "", EditorAction.Camel),
                Triple("PascalCase", "", EditorAction.Pascal),
                Triple("snake_case", "", EditorAction.Snake),
                Triple("SCREAMING_SNAKE", "", EditorAction.Screaming),
                Triple("kebab-case", "", EditorAction.Kebab),
                Triple("Base64: закодировать", "", EditorAction.Base64Encode),
                Triple("Base64: раскодировать", "", EditorAction.Base64Decode),
                Triple("URL: закодировать", "", EditorAction.UrlEncode),
                Triple("URL: раскодировать", "", EditorAction.UrlDecode),
                Triple("Текст → HEX", "", EditorAction.HexEncode),
                Triple("HEX → текст", "", EditorAction.HexDecode),
                Triple("Экранировать строку", "", EditorAction.Escape),
                Triple("Снять экранирование", "", EditorAction.Unescape),
                Triple("\\uXXXX → символы", "", EditorAction.UnicodeUnescape)
            )
        )
        groups.forEachIndexed { gi, (title, items) ->
            if (gi > 0) HorizontalDivider()
            MenuSection(title)
            for ((label, shortcut, action) in items) {
                ActionItem(label, shortcut, action, action !in READ_ONLY_ACTIONS, readOnly, onDismiss, act)
            }
        }
    }
}

private val READ_ONLY_ACTIONS = setOf(
    EditorAction.ExpandSelection, EditorAction.ShrinkSelection, EditorAction.MatchingBracket, EditorAction.NextError,
    EditorAction.PrevError, EditorAction.GotoLine, EditorAction.StructurePopup, EditorAction.Fold, EditorAction.Unfold,
    EditorAction.FoldAll, EditorAction.UnfoldAll, EditorAction.NextOccurrence, EditorAction.AllOccurrences,
    EditorAction.CaretAbove, EditorAction.CaretBelow, EditorAction.CaretsLineEnds
)

@Composable
internal fun ViewMenu(expanded: Boolean, onDismiss: () -> Unit) {
    val p = EditorPreferences
    WexDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.heightIn(max = 640.dp)) {
        MenuSection("Вид")
        MenuToggle("Перенос строк (" + Keymap.label("editor.wrap") + ")", p.softWrap, Icons.AutoMirrored.Filled.WrapText) { p.updateSoftWrap(it) }
        MenuToggle("Миникарта", p.showMinimap, Icons.Default.Map) { p.updateShowMinimap(it) }
        MenuToggle("Панель структуры (Alt+7)", p.showOutline, Icons.Default.AccountTree) { p.updateShowOutline(it) }
        MenuToggle("Номера строк", p.showLineNumbers, Icons.Default.FormatListNumbered) { p.updateShowLineNumbers(it) }
        MenuToggle("Закреплённые заголовки", p.stickyHeaders, Icons.Default.PushPin) { p.updateStickyHeaders(it) }
        MenuToggle("Пробелы и табы", p.showWhitespace, Icons.Default.SpaceBar) { p.updateShowWhitespace(it) }
        MenuToggle("Направляющие отступов", p.showIndentGuides, Icons.AutoMirrored.Filled.FormatIndentIncrease) { p.updateShowIndentGuides(it) }
        MenuToggle("Подсветка текущей строки", p.highlightCurrentLine, Icons.Default.Highlight) { p.updateHighlightCurrentLine(it) }
        MenuToggle("Подсветка вхождений", p.highlightOccurrences, Icons.Default.FindInPage) { p.updateHighlightOccurrences(it) }
        MenuToggle("Лигатуры шрифта", p.ligatures, Icons.Default.TextFormat) { p.updateLigatures(it) }
        HorizontalDivider()
        MenuSection("Ввод")
        MenuToggle("Подсказки при вводе", p.autoPopup, Icons.Default.Lightbulb) { p.updateAutoPopup(it) }
        MenuToggle("Автозакрытие скобок", p.autoClose, Icons.Default.DataArray) { p.updateAutoClose(it) }
        HorizontalDivider()
        MenuSection("Проверка кода")
        MenuToggle("Ошибки на лету", p.diagnostics, Icons.Default.ErrorOutline) { p.updateDiagnostics(it) }
        MenuToggle("Внешние проверщики", p.externalChecks, Icons.AutoMirrored.Filled.FactCheck) { p.updateExternalChecks(it) }
    }
}

/** Команды редактора (меню, контекстное меню, горячие клавиши). */
enum class EditorAction {
    Undo, Redo, Duplicate, DeleteLine, MoveUp, MoveDown, JoinLines, ExpandSelection, ShrinkSelection,
    Complete, Format, LineComment, BlockComment, Rename, MatchingBracket, NextError, PrevError, GotoLine, StructurePopup,
    Fold, Unfold, FoldAll, UnfoldAll,
    SortAsc, SortDesc, Unique, Reverse, RemoveBlank, TrimTrailing,
    ToggleCase, Camel, Pascal, Snake, Screaming, Kebab,
    Base64Encode, Base64Decode, UrlEncode, UrlDecode, HexEncode, HexDecode, Escape, Unescape, UnicodeUnescape,
    NextOccurrence, AllOccurrences, CaretAbove, CaretBelow, CaretsLineEnds
}

// ---------------- Верхняя панель ----------------

@Composable
internal fun EditorToolbar(
    ctrl: CodeEditorController,
    fileName: String,
    readOnly: Boolean,
    canEditToggle: Boolean,
    editing: Boolean,
    onToggleEditing: () -> Unit,
    canSave: Boolean,
    saveButtonText: String,
    errorPrefix: String,
    onSave: () -> Unit,
    onFind: () -> Unit,
    onFormat: () -> Unit,
    formatLabel: String?,
    onCopyAll: () -> Unit,
    act: (EditorAction) -> Unit
) {
    var toolsOpen by remember { mutableStateOf(false) }
    var viewOpen by remember { mutableStateOf(false) }
    val fontSizeSp = EditorPreferences.fontSizeSp
    val lang = ctrl.language
    // Чтение версии — перерисовка кнопок отмены/повтора после каждой правки
    @Suppress("UNUSED_VARIABLE") val version = ctrl.version
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = NoctaliaPrimary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.4f))
            ) {
                Text(
                    lang.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFamily = AppFonts.JetBrainsMono, fontSize = 10.sp),
                    color = NoctaliaPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            if (fileName.isNotEmpty()) {
                Text(
                    fileName,
                    style = TextStyle(fontFamily = AppFonts.JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NoctaliaSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val saveError = ctrl.saveError
                when {
                    saveError != null -> Text(
                        "$errorPrefix: ${saveError.lineSequence().first()}",
                        fontSize = 11.sp,
                        fontFamily = AppFonts.JetBrainsMono,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ctrl.isDirty -> org.wexclient.app.ui.DirtyMark(NoctaliaTertiary)
                }
            }
            if (!readOnly) {
                ToolIcon(Icons.AutoMirrored.Filled.Undo, "Отменить (" + Keymap.label("editor.undo") + ")", enabled = ctrl.history.canUndo) { act(EditorAction.Undo) }
                ToolIcon(Icons.AutoMirrored.Filled.Redo, "Повторить (" + Keymap.label("editor.redo") + ")", enabled = ctrl.history.canRedo) { act(EditorAction.Redo) }
            }
            ToolIcon(Icons.Default.Search, "Найти и заменить (" + Keymap.label("editor.find") + " / " + Keymap.label("editor.replace") + ")", onClick = onFind)
            if (!readOnly && formatLabel != null) {
                ToolIcon(Icons.Default.AutoFixHigh, "Форматировать: $formatLabel (" + Keymap.label("editor.format") + ")", onClick = onFormat)
            }
            ToolIcon(Icons.AutoMirrored.Filled.Toc, "Структура (Alt+7)", active = EditorPreferences.showOutline) {
                EditorPreferences.updateShowOutline(!EditorPreferences.showOutline)
            }
            ToolIcon(Icons.AutoMirrored.Filled.WrapText, "Перенос строк (" + Keymap.label("editor.wrap") + ")", active = EditorPreferences.softWrap) {
                EditorPreferences.updateSoftWrap(!EditorPreferences.softWrap)
            }
            ToolIcon(Icons.Default.Map, "Миникарта", active = EditorPreferences.showMinimap) {
                EditorPreferences.updateShowMinimap(!EditorPreferences.showMinimap)
            }
            Box {
                ToolIcon(Icons.Default.MoreVert, "Инструменты") { toolsOpen = true }
                ToolsMenu(toolsOpen, { toolsOpen = false }, readOnly, act)
            }
            Box {
                ToolIcon(Icons.Default.Settings, "Вид и настройки редактора") { viewOpen = true }
                ViewMenu(viewOpen) { viewOpen = false }
            }
            // Масштаб
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                ToolIcon(Icons.Default.Remove, "Уменьшить шрифт (Ctrl+−)", size = 24.dp) { EditorPreferences.updateFontSize(fontSizeSp - 1f) }
                val zoomPercent = (fontSizeSp / 13f * 100).roundToInt()
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (fontSizeSp != 13f) NoctaliaPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (fontSizeSp != 13f) NoctaliaPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { EditorPreferences.resetFontSize() }
                ) {
                    Text(
                        "$zoomPercent%",
                        fontFamily = AppFonts.JetBrainsMono,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (fontSizeSp != 13f) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                ToolIcon(Icons.Default.Add, "Увеличить шрифт (Ctrl+=)", size = 24.dp) { EditorPreferences.updateFontSize(fontSizeSp + 1f) }
            }
            ToolIcon(Icons.Default.ContentCopy, "Копировать весь текст", onClick = onCopyAll)
            if (canEditToggle) {
                ToolIcon(
                    if (editing) Icons.Default.Visibility else Icons.Default.Edit,
                    if (editing) "Режим чтения" else "Режим правки",
                    active = editing,
                    onClick = onToggleEditing
                )
            }
            if (canSave) {
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = onSave,
                    enabled = ctrl.isDirty,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NoctaliaPrimary,
                        disabledContainerColor = NoctaliaPrimary.copy(alpha = 0.2f),
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(saveButtonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}
