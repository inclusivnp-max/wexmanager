package org.wexclient.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.wexclient.app.db.SqlObject
import org.wexclient.app.db.SqlObjectKind
import org.wexclient.app.db.SqlRow
import org.wexclient.app.db.SqlValue
import org.wexclient.app.db.SqliteDb
import org.wexclient.app.editor.CodeEditorView
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.util.Plural
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.FileSystems

/** Сколько строк подгружается за раз и сколько просматривается за один заход при поиске. */
private const val PAGE_ROWS = 400
private const val SCAN_LIMIT = 200_000
private const val SAMPLE_ROWS = 150
private const val MAX_SORT_ROWS = 100_000
private const val MAX_IN_MEMORY_DB = 512L shl 20

/** Просмотр базы SQLite: таблицы, данные, схема. Только чтение. */
@Composable
fun DatabaseViewerDialog(entry: VfsEntry, vfsManager: VfsManager, onDismiss: () -> Unit) {
    var db by remember { mutableStateOf<SqliteDb?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SqlObject?>(null) }
    var showSchema by remember { mutableStateOf(false) }
    var objectFilter by remember { mutableStateOf("") }
    var rowQuery by remember { mutableStateOf("") }
    var selectedRow by remember { mutableStateOf<SqlRow?>(null) }
    val counts = remember { mutableStateMapOf<String, Long>() }
    val toast = rememberToastState()

    LaunchedEffect(entry.path) {
        // Открытие не прерывается на полпути: окно закрыли раньше — открытую базу закрываем сами
        val opened = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            runCatching {
                if (entry.path.fileSystem === FileSystems.getDefault()) {
                    SqliteDb.open(entry.path)
                } else {
                    // Файл внутри архива: читается в память целиком, журнала WAL там всё равно нет
                    SqliteDb.open(vfsManager.readBytesLimited(entry.path, MAX_IN_MEMORY_DB.toInt()))
                }
            }
        }
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
            opened.getOrNull()?.let { runCatching { it.close() } }
            return@LaunchedEffect
        }
        opened.onSuccess { sqlite ->
            db = sqlite
            selected = sqlite.objects.firstOrNull { it.kind == SqlObjectKind.TABLE && !it.internal }
                ?: sqlite.objects.firstOrNull { it.kind == SqlObjectKind.TABLE }
                ?: sqlite.objects.firstOrNull()
        }
        opened.onFailure { openError = it.message ?: it.javaClass.simpleName }
    }

    // Число строк у каждого объекта — в фоне: по дереву страниц это быстро даже на миллионах
    LaunchedEffect(db) {
        val sqlite = db ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (obj in sqlite.objects) {
                if (obj.rootPage <= 0) continue
                val n = runCatching { sqlite.countRows(obj) }.getOrDefault(-1L)
                counts[obj.name] = n
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { db?.close() } }
    }

    val current = db
    val sub = when {
        openError != null -> "не открылось"
        current == null -> "чтение…"
        else -> {
            val tables = current.objects.count { it.kind == SqlObjectKind.TABLE }
            listOfNotNull(
                "$tables " + Plural.ru(tables.toLong(), "таблица", "таблицы", "таблиц"),
                humanSize(current.fileSize),
                "страница ${current.pageSize} Б",
                current.encodingName,
                ("журнал WAL: " + current.walFrames + " " +
                    Plural.ru(current.walFrames.toLong(), "кадр", "кадра", "кадров")).takeIf { current.walFrames > 0 },
                current.schemaProblem?.let { "⚠ $it" }
            ).joinToString(" · ")
        }
    }

    GrailDialogShell(
        title = entry.name,
        badge = "SQLITE",
        heading = entry.name,
        subtitle = sub,
        closeOnEsc = true,
        onDismiss = onDismiss,
        toast = toast,
        actions = {
            val obj = selected
            if (current != null && obj != null) {
                GrailTabs(listOf("Данные", "Схема"), if (showSchema) 1 else 0) { showSchema = it == 1 }
            }
        }
    ) {
        when {
            openError != null -> GrailEmptyState("Не удалось открыть базу: $openError")
            current == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NoctaliaPrimary)
            }
            else -> Row(modifier = Modifier.fillMaxSize()) {
                ObjectList(
                    db = current,
                    counts = counts,
                    filter = objectFilter,
                    onFilter = { objectFilter = it },
                    selected = selected,
                    onSelect = {
                        selected = it
                        selectedRow = null
                        rowQuery = ""
                    }
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val obj = selected
                if (obj == null) {
                    GrailEmptyState("В базе нет ни одной таблицы")
                } else if (showSchema) {
                    SchemaView(current, obj)
                } else {
                    TableView(
                        db = current,
                        obj = obj,
                        query = rowQuery,
                        onQuery = { rowQuery = it },
                        rowCount = counts[obj.name] ?: -1L,
                        selectedRow = selectedRow,
                        onSelectRow = { selectedRow = if (selectedRow === it) null else it },
                        toast = toast
                    )
                }
            }
        }
    }
}

/** Боковой список объектов базы. */
@Composable
private fun ObjectList(
    db: SqliteDb,
    counts: Map<String, Long>,
    filter: String,
    onFilter: (String) -> Unit,
    selected: SqlObject?,
    onSelect: (SqlObject) -> Unit
) {
    Column(modifier = Modifier.width(250.dp).fillMaxHeight().padding(10.dp)) {
        GrailSearchField(filter, onFilter, "Таблица…", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        val shown = db.objects.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
        if (shown.isEmpty()) {
            GrailEmptyState("Ничего не найдено")
            return@Column
        }
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                for (kind in SqlObjectKind.entries) {
                    val group = shown.filter { it.kind == kind }
                    if (group.isEmpty()) continue
                    item(key = "заголовок-${kind.name}") {
                        Text(
                            text = when (kind) {
                                SqlObjectKind.TABLE -> "Таблицы"
                                SqlObjectKind.VIEW -> "Представления"
                                SqlObjectKind.INDEX -> "Индексы"
                                SqlObjectKind.TRIGGER -> "Триггеры"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 6.dp)
                        )
                    }
                    items(group, key = { "${kind.name}-${it.name}" }) { obj ->
                        ObjectRow(obj, counts[obj.name], obj === selected) { onSelect(obj) }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ObjectRow(obj: SqlObject, count: Long?, active: Boolean, onClick: () -> Unit) {
    val tint = when (obj.kind) {
        SqlObjectKind.TABLE -> if (obj.internal) MaterialTheme.colorScheme.outline else NoctaliaPrimary
        SqlObjectKind.VIEW -> NoctaliaSecondary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (obj.kind) {
                    SqlObjectKind.TABLE -> Icons.Default.TableChart
                    SqlObjectKind.VIEW -> Icons.Default.Visibility
                    SqlObjectKind.INDEX -> Icons.Default.Storage
                    SqlObjectKind.TRIGGER -> Icons.Default.Bolt
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = obj.name,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            if (count != null && count >= 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 30.dp)
                )
            }
        }
    }
}

/** Текст CREATE TABLE выбранного объекта (или всей базы, если его нет). */
@Composable
private fun SchemaView(db: SqliteDb, obj: SqlObject) {
    val text = remember(db, obj.name) {
        obj.sql?.let { "$it;\n" }
            ?: db.objects.mapNotNull { it.sql }.joinToString(";\n\n", postfix = ";\n")
                .ifBlank { "-- у «${obj.name}» нет текста определения" }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        CodeEditorView(initialText = text, language = Languages.SQL, readOnly = true, fileName = "${obj.name}.sql")
    }
}

/** Данные таблицы: шапка со столбцами, строки порциями, панель выбранной строки. */
@Composable
private fun TableView(
    db: SqliteDb,
    obj: SqlObject,
    query: String,
    onQuery: (String) -> Unit,
    rowCount: Long,
    selectedRow: SqlRow?,
    onSelectRow: (SqlRow) -> Unit,
    toast: ToastState
) {
    // Пока набирают запрос, таблицу не перечитываем: поиск стартует, когда пальцы остановились
    val active by produceState(query, query) {
        if (query.isBlank()) value = query else {
            delay(280)
            value = query
        }
    }
    val rows = remember(db, obj.name, active) { mutableStateListOf<SqlRow>() }
    var finished by remember(db, obj.name, active) { mutableStateOf(false) }
    var loading by remember(db, obj.name, active) { mutableStateOf(false) }
    var problem by remember(db, obj.name, active) { mutableStateOf<String?>(null) }
    val cursor = remember(db, obj.name, active) { db.cursor(obj) }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    var sortColumn by remember(db, obj.name) { mutableStateOf(-1) }
    var sortDesc by remember(db, obj.name) { mutableStateOf(false) }

    // Порции читает кто-то один: иначе подгрузка при прокрутке и поиск толкались бы за один курсор
    val gate = remember(db, obj.name, active) { Mutex() }

    suspend fun loadMore() {
        if (finished) return
        gate.withLock {
            if (finished) return
            loading = true
            try {
                val batch = withContext(Dispatchers.IO) {
                    val out = ArrayList<SqlRow>(PAGE_ROWS)
                    var scanned = 0
                    while (out.size < PAGE_ROWS && !cursor.finished && scanned < SCAN_LIMIT) {
                        val part = cursor.next(512)
                        scanned += part.size
                        if (active.isBlank()) out.addAll(part) else out.addAll(part.filter { matches(it, active) })
                        if (part.isEmpty()) break
                    }
                    out
                }
                rows.addAll(batch)
                problem = cursor.problem
                if (cursor.finished) finished = true
            } finally {
                loading = false
            }
        }
    }

    // При поиске порция набирается не сразу: таблица просматривается кусками, пока не найдётся нужное
    suspend fun loadUntil(target: Int) {
        while (!finished && rows.size < target) {
            val before = rows.size
            loadMore()
            if (rows.size == before && (finished || active.isBlank())) break
        }
    }

    LaunchedEffect(db, obj.name, active) { loadUntil(PAGE_ROWS) }

    // Сортировать можно только то, что прочитано, поэтому при сортировке таблица дочитывается целиком
    LaunchedEffect(db, obj.name, active, sortColumn) {
        if (sortColumn >= 0) loadUntil(MAX_SORT_ROWS)
    }

    val ordered: List<SqlRow> = remember(rows.size, sortColumn, sortDesc) {
        if (sortColumn < 0) rows else rows.sortedWith(rowComparator(sortColumn, sortDesc))
    }

    // Докручивание до конца подгружает следующую порцию
    LaunchedEffect(listState, db, obj.name, active) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= rows.size - 40) loadUntil(last + PAGE_ROWS) }
    }

    // Столбцы считаются один раз по первым строкам: на прокрутке и при поиске ширина не прыгает
    val columnCount = rows.firstOrNull()?.values?.size ?: obj.columns.size
    val names = remember(db, obj.name, columnCount) { obj.columnNames(columnCount) }
    val headers = remember(db, obj.name, columnCount) {
        names.mapIndexed { i, name ->
            val type = obj.columns.getOrNull(i)?.type?.takeIf { it.isNotBlank() }
            if (type == null) name else "$name · $type"
        }
    }
    var widths by remember(db, obj.name) { mutableStateOf<List<Dp>>(emptyList()) }
    LaunchedEffect(db, obj.name, rows.isNotEmpty()) {
        if (widths.isEmpty() && rows.isNotEmpty()) widths = measureColumns(headers, rows.take(SAMPLE_ROWS))
    }
    val shownWidths = if (widths.size == columnCount) widths else headers.map { (it.length.coerceIn(6, 44) * 6.7f + 18f).dp }
    val numberWidth = 62.dp

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = NoctaliaPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val status = buildString {
                    if (query.isNotBlank()) {
                        append("найдено ${rows.size}")
                        if (!finished) append(", ищем…")
                    } else {
                        append(if (rowCount >= 0) "строк $rowCount" else "строк: считаем…")
                        if (!finished) append(" · показано ${rows.size}")
                    }
                    if (sortColumn >= 0) {
                        append(" · сортировка: ${names.getOrElse(sortColumn) { "" }}")
                        if (!finished && rows.size >= MAX_SORT_ROWS) append(" (первые $MAX_SORT_ROWS)")
                    }
                    if (obj.withoutRowid) append(" · без rowid")
                    problem?.let { append(" · ⚠ $it") }
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 10.sp),
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GrailSearchField(query, onQuery, "Поиск по строкам", modifier = Modifier.width(240.dp))
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val csv = buildCsv(names, ordered)
                    val selection = StringSelection(csv)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    toast.success("Скопировано строк: ${rows.size}")
                },
                enabled = rows.isNotEmpty()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать как CSV", tint = NoctaliaSecondary)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (obj.kind == SqlObjectKind.VIEW) {
            GrailEmptyState("Представление считается запросом — смотрите вкладку «Схема»")
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Узкие столбцы растягиваются на всю ширину окна, широкие — прокручиваются вбок
            val stretched = stretch(shownWidths, maxWidth - numberWidth - 12.dp)
            val totalWidth = stretched.fold(numberWidth) { acc, w -> acc + w }
            Column(modifier = Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.width(totalWidth)) {
                    Row(modifier = Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeaderCell(if (obj.withoutRowid) "#" else "rowid", numberWidth)
                        headers.forEachIndexed { i, header ->
                            HeaderCell(
                                text = header,
                                width = stretched.getOrElse(i) { 120.dp },
                                key = obj.columns.getOrNull(i)?.primaryKey == true,
                                sort = if (sortColumn != i) 0 else if (sortDesc) -1 else 1,
                                onClick = {
                                    when {
                                        sortColumn != i -> {
                                            sortColumn = i
                                            sortDesc = false
                                        }
                                        !sortDesc -> sortDesc = true
                                        else -> {
                                            sortColumn = -1
                                            sortDesc = false
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(state = listState, modifier = Modifier.width(totalWidth).weight(1f)) {
                    itemsIndexed(ordered) { index, row ->
                        DataRow(
                            row = row,
                            index = index,
                            widths = stretched,
                            numberWidth = numberWidth,
                            active = row === selectedRow,
                            onClick = { onSelectRow(row) }
                        )
                    }
                    if (loading) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
            if (rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        loading || !finished || query != active ->
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                        query.isBlank() -> GrailEmptyState("Таблица пуста")
                        else -> GrailEmptyState("Ничего не найдено")
                    }
                }
            }
        }

        selectedRow?.let { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RowDetails(row, names, toast)
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: Dp,
    key: Boolean = false,
    /** 0 — не сортируем, 1 — по возрастанию, -1 — по убыванию. */
    sort: Int = 0,
    onClick: (() -> Unit)? = null
) {
    val label = text + when (sort) {
        1 -> " ↑"
        -1 -> " ↓"
        else -> ""
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 11.sp),
        color = when {
            sort != 0 -> NoctaliaPrimary
            key -> NoctaliaPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = if (key || sort != 0) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp)
    )
}

@Composable
private fun DataRow(
    row: SqlRow,
    index: Int,
    widths: List<Dp>,
    numberWidth: Dp,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(24.dp)
            .background(
                when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    index % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (row.rowid ?: (index + 1).toLong()).toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 10.sp),
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(numberWidth).padding(horizontal = 8.dp)
        )
        row.values.forEachIndexed { i, value ->
            ValueCell(value, widths.getOrElse(i) { 120.dp })
        }
    }
}

@Composable
private fun ValueCell(value: SqlValue, width: Dp) {
    val (text, color, italic) = cellLook(value)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 11.sp),
        color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (value is SqlValue.Integer || value is SqlValue.Real) TextAlign.End else TextAlign.Start,
        modifier = Modifier.width(width).padding(horizontal = 8.dp)
    )
}

/** Выбранная строка целиком: длинный текст и двоичные значения видны полностью. */
@Composable
private fun RowDetails(row: SqlRow, names: List<String>, toast: ToastState) {
    val scroll = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GrailSectionLabel(row.rowid?.let { "Строка $it" } ?: "Строка")
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val text = names.indices.joinToString("\n") { i ->
                            "${names[i]}: ${fullText(row.values.getOrNull(i))}"
                        }
                        val selection = StringSelection(text)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                        toast.success("Строка скопирована")
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Копировать строку",
                        tint = NoctaliaSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            row.values.forEachIndexed { i, value ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = names.getOrElse(i) { "столбец ${i + 1}" },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(170.dp)
                    )
                    val (text, color, italic) = cellLook(value, full = true)
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 11.sp),
                        color = color,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Текст, цвет и наклон для значения. */
@Composable
private fun cellLook(value: SqlValue, full: Boolean = false): Triple<String, Color, Boolean> = when (value) {
    is SqlValue.Null -> Triple("NULL", MaterialTheme.colorScheme.outline, true)
    is SqlValue.Integer -> Triple(value.value.toString(), MaterialTheme.colorScheme.onSurface, false)
    is SqlValue.Real -> Triple(formatReal(value.value), MaterialTheme.colorScheme.onSurface, false)
    is SqlValue.Text -> Triple(
        if (full) value.value.take(20_000) else value.value.replace('\n', '␊'),
        MaterialTheme.colorScheme.onSurface,
        false
    )
    is SqlValue.Blob -> Triple(blobText(value.bytes, full), NoctaliaSecondary, false)
}

private fun formatReal(v: Double): String =
    if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) v.toLong().toString() + ".0" else v.toString()

private fun blobText(bytes: ByteArray, full: Boolean): String {
    val size = humanSize(bytes.size.toLong())
    if (!full) return "BLOB $size"
    val hex = bytes.take(64).joinToString(" ") { "%02X".format(it) }
    return "BLOB $size · $hex" + if (bytes.size > 64) " …" else ""
}

/** Полное значение для буфера обмена. */
private fun fullText(value: SqlValue?): String = when (value) {
    null -> ""
    is SqlValue.Null -> "NULL"
    is SqlValue.Integer -> value.value.toString()
    is SqlValue.Real -> value.value.toString()
    is SqlValue.Text -> value.value
    is SqlValue.Blob -> value.bytes.joinToString("") { "%02x".format(it) }
}

/** Ищем подстроку по всем значениям строки. */
private fun matches(row: SqlRow, query: String): Boolean {
    val needle = query.lowercase()
    return row.values.any { value ->
        when (value) {
            is SqlValue.Text -> value.value.contains(needle, ignoreCase = true)
            is SqlValue.Integer -> value.value.toString().contains(needle)
            is SqlValue.Real -> value.value.toString().contains(needle)
            else -> false
        }
    } || row.rowid?.toString()?.contains(needle) == true
}

/** Ширина столбцов по первым строкам: моноширинный шрифт — ширина считается по числу знаков. */
private fun measureColumns(names: List<String>, sample: List<SqlRow>): List<Dp> = names.mapIndexed { i, name ->
    var chars = name.length
    for (row in sample) {
        val value = row.values.getOrNull(i) ?: continue
        val length = when (value) {
            is SqlValue.Null -> 4
            is SqlValue.Integer -> value.value.toString().length
            is SqlValue.Real -> value.value.toString().length
            is SqlValue.Text -> value.value.length
            is SqlValue.Blob -> 12
        }
        if (length > chars) chars = length
    }
    (chars.coerceIn(6, 44) * 6.7f + 18f).dp
}

/**
 * Порядок как в SQLite: пусто, числа, текст, двоичное. Внутри чисел — по величине,
 * внутри текста — без учёта регистра.
 */
private fun rowComparator(column: Int, desc: Boolean): Comparator<SqlRow> {
    fun rank(v: SqlValue?): Int = when (v) {
        null, is SqlValue.Null -> 0
        is SqlValue.Integer, is SqlValue.Real -> 1
        is SqlValue.Text -> 2
        is SqlValue.Blob -> 3
    }
    val base = Comparator<SqlRow> { a, b ->
        val x = a.values.getOrNull(column)
        val y = b.values.getOrNull(column)
        val byKind = rank(x).compareTo(rank(y))
        if (byKind != 0) return@Comparator byKind
        when {
            x is SqlValue.Text && y is SqlValue.Text -> String.CASE_INSENSITIVE_ORDER.compare(x.value, y.value)
            x is SqlValue.Blob && y is SqlValue.Blob -> x.bytes.size.compareTo(y.bytes.size)
            else -> numberOf(x).compareTo(numberOf(y))
        }
    }
    return if (desc) base.reversed() else base
}

private fun numberOf(v: SqlValue?): Double = when (v) {
    is SqlValue.Integer -> v.value.toDouble()
    is SqlValue.Real -> v.value
    else -> 0.0
}

/** Раздаёт свободную ширину столбцам соразмерно их содержимому. */
private fun stretch(widths: List<Dp>, available: Dp): List<Dp> {
    if (widths.isEmpty()) return widths
    val sum = widths.fold(0.dp) { acc, w -> acc + w }
    if (sum.value <= 0f || sum >= available) return widths
    val extra = available - sum
    return widths.map { it + extra * (it.value / sum.value) }
}

/** Загруженные строки в виде CSV (разделитель — точка с запятой, как ждёт таблица). */
private fun buildCsv(names: List<String>, rows: List<SqlRow>): String {
    fun quote(s: String): String =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    return buildString {
        append(names.joinToString(";") { quote(it) })
        append('\n')
        for (row in rows) {
            append(names.indices.joinToString(";") { i -> quote(fullText(row.values.getOrNull(i))) })
            append('\n')
        }
    }
}
