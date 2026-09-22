package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.doc.XlsxCell
import org.wexclient.app.doc.XlsxDocument
import org.wexclient.app.doc.XlsxSheet
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.VfsEntry

/** Рабочая копия листа: правки, вставки и удаления живут здесь до сохранения. */
private class SheetEdit(sheet: XlsxSheet) {
    val cells = mutableStateMapOf<String, XlsxCell>().apply { putAll(sheet.cells) }
    var rows by mutableStateOf(sheet.rows)
    var columns by mutableStateOf(sheet.columns)
    var dirty by mutableStateOf(false)

    fun text(ref: String): String = cells[ref]?.text.orEmpty()

    fun set(ref: String, text: String) {
        val (row, column) = XlsxDocument.parseRef(ref)
        val old = cells[ref]
        if (old?.text == text) return
        if (text.isEmpty() && old?.formula == null) cells.remove(ref)
        else cells[ref] = XlsxCell(ref, row, column, text, old?.formula, old?.styleId)
        if (row > rows) rows = row
        if (column > columns) columns = column
        dirty = true
    }

    fun insertRow(at: Int) = shift { cell -> if (cell.row >= at) cell.row + 1 to cell.column else null }
        .also { rows += 1 }

    fun deleteRow(at: Int) {
        cells.keys.filter { XlsxDocument.parseRef(it).first == at }.forEach { cells.remove(it) }
        shift { cell -> if (cell.row > at) cell.row - 1 to cell.column else null }
        rows = (rows - 1).coerceAtLeast(1)
    }

    fun insertColumn(at: Int) = shift { cell -> if (cell.column >= at) cell.row to cell.column + 1 else null }
        .also { columns += 1 }

    fun deleteColumn(at: Int) {
        cells.keys.filter { XlsxDocument.parseRef(it).second == at }.forEach { cells.remove(it) }
        shift { cell -> if (cell.column > at) cell.row to cell.column - 1 else null }
        columns = (columns - 1).coerceAtLeast(1)
    }

    /** Перенос ячеек по новому адресу: сначала снимаем все, потом кладём обратно. */
    private fun shift(move: (XlsxCell) -> Pair<Int, Int>?) {
        val moved = ArrayList<XlsxCell>()
        val stay = ArrayList<XlsxCell>()
        for (cell in cells.values) {
            val target = move(cell)
            if (target == null) stay += cell
            else {
                val ref = XlsxDocument.ref(target.first, target.second)
                moved += cell.copy(ref = ref, row = target.first, column = target.second)
            }
        }
        cells.clear()
        (stay + moved).forEach { cells[it.ref] = it }
        dirty = true
    }
}

private val ROW_HEADER = 56.dp
private val CELL_WIDTH = 132.dp
private val CELL_HEIGHT = 24.dp

/**
 * Книга Excel: листы вкладками, таблица с буквами столбцов и правкой ячеек.
 * Правки копятся в памяти и уходят в файл по Ctrl+S — как в обычном редакторе.
 */
@Composable
fun SpreadsheetViewerDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    val path = entry.path
    var reloads by remember { mutableStateOf(0) }
    var sheetIndex by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    val toast = rememberToastState()

    val saver = rememberBackgroundSave()
    val loaded by produceState<Result<XlsxDocument>?>(null, path, reloads) {
        value = withContext(Dispatchers.IO) { runCatching { XlsxDocument.openAny(path) } }
    }

    val result = loaded
    val doc = result?.getOrNull()
    val sheet = doc?.sheets?.getOrNull(sheetIndex)
    // Рабочие копии листов: правки, вставки и удаления копятся до Ctrl+S
    val states = remember(doc) { doc?.sheets?.associate { it.name to SheetEdit(it) } ?: emptyMap() }
    val state = sheet?.let { states[it.name] }
    val dirty = states.values.any { it.dirty }

    val subtitle = when {
        result == null -> "чтение…"
        doc == null -> "не открылось"
        sheet == null -> "нет листов"
        else -> listOf(
            "${doc.sheets.size} " + org.wexclient.app.util.Plural.ru(doc.sheets.size.toLong(), "лист", "листа", "листов"),
            "${state?.rows ?: sheet.rows} × ${state?.columns ?: sheet.columns}",
            "${state?.cells?.size ?: sheet.cells.size} " +
                org.wexclient.app.util.Plural.ru((state?.cells?.size ?: sheet.cells.size).toLong(), "ячейка", "ячейки", "ячеек")
        ).joinToString(" · ")
    }

    GrailDialogShell(
        title = entry.name,
        badge = doc?.format ?: "XLSX",
        heading = entry.name,
        subtitle = subtitle,
        isDirty = dirty,
        closeOnEsc = !dirty,
        dialogWidth = 1280.dp,
        dialogHeight = 860.dp,
        onDismiss = onDismiss,
        toast = toast,
        onSave = if (doc == null || doc.readOnly) null else { done ->
            // Изменённые листы переписываются целиком: так переживают и вставленные строки
            val snapshot = states.filterValues { it.dirty }.mapValues { (_, edit) -> edit.cells.toMap() }
            saver.run(
                write = { doc.saveSheets(snapshot) },
                done = { error ->
                    if (error == null) reloads++
                    done(error)
                }
            )
        },
        actions = {
            if (doc != null) {
                if (doc.sheets.size > 1) {
                    GrailTabs(doc.sheets.map { it.name }, sheetIndex) {
                        sheetIndex = it
                        selected = null
                        editing = null
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                // Текущий лист — в CSV рядом с книгой (с учётом несохранённых правок)
                val current = state
                if (current != null && sheet != null) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = NoctaliaPrimary.copy(alpha = 0.10f),
                        modifier = Modifier.padding(end = 6.dp).clickable {
                            val name = path.fileName.toString().substringBeforeLast('.') + "-" + sheet.name + ".csv"
                            val target = path.toAbsolutePath().parent.resolve(name)
                            val saved = runCatching {
                                java.nio.file.Files.writeString(target, doc.toCsv(current.cells, current.rows, current.columns))
                            }
                            if (saved.isSuccess) toast.info("Сохранено: $name") else toast.error("Не записалось: " + saved.exceptionOrNull()?.message)
                        }
                    ) {
                        Text("В CSV", fontSize = 11.sp, color = NoctaliaPrimary, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                // Новый лист пишется в книгу сразу: он пустой, терять нечего
                if (!doc.readOnly) Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = NoctaliaPrimary.copy(alpha = 0.14f),
                    modifier = Modifier.clickable {
                        saver.run(
                            write = { doc.addSheet("Лист ${doc.sheets.size + 1}") },
                            done = { error ->
                                if (error == null) reloads++ else toast.error(error)
                            }
                        )
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Лист", fontSize = 11.sp, color = NoctaliaPrimary)
                    }
                }
            }
        }
    ) {
        when {
            result == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NoctaliaPrimary)
            }
            doc == null -> GrailEmptyState(
                "Не удалось прочитать книгу: " + (result.exceptionOrNull()?.message ?: "неизвестная ошибка")
            )
            sheet == null -> GrailEmptyState("В книге нет листов")
            else -> Column(modifier = Modifier.fillMaxSize()) {
                // Строка формул: что в выбранной ячейке на самом деле
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = selected ?: "—",
                        fontSize = 12.sp,
                        fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                        fontWeight = FontWeight.SemiBold,
                        color = NoctaliaPrimary,
                        modifier = Modifier.width(70.dp)
                    )
                    Icon(
                        Icons.Default.Functions,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val cell = selected?.let { state?.cells?.get(it) }
                    Text(
                        text = cell?.formula ?: cell?.text.orEmpty(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (dirty) "Ctrl+S — сохранить" else "двойной щелчок — правка, правая кнопка по номеру — вставить",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) NoctaliaPrimary else MaterialTheme.colorScheme.outline
                    )
                }

                val horizontal = rememberScrollState()
                val edit = state ?: return@Column
                Column(modifier = Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                    // Шапка с буквами столбцов
                    Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                        HeaderCell("", ROW_HEADER)
                        for (column in 1..edit.columns) {
                            HeaderCell(
                                text = XlsxDocument.columnName(column),
                                width = CELL_WIDTH,
                                menu = listOf(
                                    Triple("Вставить столбец слева", Icons.Default.Add) { edit.insertColumn(column) },
                                    Triple("Вставить столбец справа", Icons.Default.Add) { edit.insertColumn(column + 1) },
                                    Triple("Удалить столбец", Icons.Default.Remove) { edit.deleteColumn(column) }
                                )
                            )
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items((1..edit.rows).toList()) { row ->
                            Row {
                                HeaderCell(
                                    text = row.toString(),
                                    width = ROW_HEADER,
                                    menu = listOf(
                                        Triple("Вставить строку выше", Icons.Default.Add) { edit.insertRow(row) },
                                        Triple("Вставить строку ниже", Icons.Default.Add) { edit.insertRow(row + 1) },
                                        Triple("Удалить строку", Icons.Default.Remove) { edit.deleteRow(row) }
                                    )
                                )
                                for (column in 1..edit.columns) {
                                    val ref = XlsxDocument.ref(row, column)
                                    val cell = edit.cells[ref]
                                    val text = cell?.text.orEmpty()
                                    val changed = sheet.cells[ref]?.text.orEmpty() != text
                                    if (editing == ref) {
                                        CellEditor(
                                            initial = editText,
                                            onDone = { value ->
                                                edit.set(ref, value)
                                                editing = null
                                            },
                                            onCancel = { editing = null }
                                        )
                                    } else {
                                        CellBox(
                                            text = text,
                                            selected = selected == ref,
                                            changed = changed,
                                            formula = cell?.formula != null,
                                            onClick = { selected = ref },
                                            onEdit = {
                                                selected = ref
                                                if (!doc.readOnly) {
                                                    editText = text
                                                    editing = ref
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Заголовок строки или столбца; по правой кнопке — вставка и удаление. */
@Composable
private fun HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    menu: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> = emptyList()
) {
    var open by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .height(CELL_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(if (menu.isEmpty()) Modifier else Modifier.headerClicks { open = true })
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (menu.isNotEmpty()) {
            WexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ReturnFocusOnClose()
                for ((label, icon, action) in menu) {
                    MenuEntryIcon(label, icon) {
                        open = false
                        action()
                    }
                }
            }
        }
    }
}

/** Пункт меню заголовка: значок и подпись. */
@Composable
private fun MenuEntryIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
        },
        onClick = onClick,
        modifier = Modifier.height(32.dp)
    )
}

/** И левая, и правая кнопка открывают меню заголовка — попасть проще. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.headerClicks(onOpen: () -> Unit): Modifier =
    this.combinedClickable(onClick = onOpen, onLongClick = onOpen)

@Composable
private fun CellBox(
    text: String,
    selected: Boolean,
    changed: Boolean,
    formula: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val number = text.toDoubleOrNull() != null
    Box(
        contentAlignment = if (number) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .width(CELL_WIDTH)
            .height(CELL_HEIGHT)
            .background(
                when {
                    selected -> NoctaliaPrimary.copy(alpha = 0.18f)
                    changed -> NoctaliaPrimary.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
            .then(
                if (selected) Modifier.cellBorder(NoctaliaPrimary) else Modifier
            )
            .cellClicks(onClick = onClick, onDoubleClick = onEdit)
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (formula) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (number) TextAlign.End else TextAlign.Start
        )
    }
}

@Composable
private fun CellEditor(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    // Текст сразу выделен: набор заменяет содержимое, как в самом Excel
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initial,
                selection = androidx.compose.ui.text.TextRange(0, initial.length)
            )
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = Modifier
            .width(CELL_WIDTH)
            .height(CELL_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            .cellBorder(NoctaliaPrimary, 1.5.dp)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(NoctaliaPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.Tab -> { onDone(value.text); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                }
        )
    }
}


/** Рамка вокруг ячейки без отдельного Surface: так строка остаётся лёгкой. */
private fun Modifier.cellBorder(color: Color, width: androidx.compose.ui.unit.Dp = 1.dp): Modifier =
    this.border(BorderStroke(width, color), RoundedCornerShape(2.dp))

/** Щелчок выбирает ячейку, двойной — открывает правку. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.cellClicks(onClick: () -> Unit, onDoubleClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
