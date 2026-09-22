package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.ContentMatch
import org.wexclient.app.vfs.ContentQuery
import org.wexclient.app.vfs.ContentSearch
import org.wexclient.app.vfs.ContentSearchResult
import org.wexclient.app.vfs.SearchToken
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Path
import java.nio.file.Paths

/** Строка результатов: заголовок файла или найденная строка. */
internal sealed class SearchRow(val id: Long) {
    class FileHeader(id: Long, val path: Path) : SearchRow(id) {
        var count by mutableIntStateOf(0)
    }

    class Hit(id: Long, val match: ContentMatch, val header: FileHeader) : SearchRow(id)
}

/** Поиск по содержимому; живёт между открытиями окна — результаты не пропадают. */
class ContentSearchState {
    var query by mutableStateOf("")
    var regex by mutableStateOf(false)
    var caseSensitive by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var includeHidden by mutableStateOf(false)
    var respectIgnore by mutableStateOf(true)
    var mask by mutableStateOf("")

    /** Папка, в которой искали последний раз */
    var dir by mutableStateOf<Path?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<ContentSearchResult?>(null)
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var fileCount by mutableIntStateOf(0)
        private set
    internal var selected by mutableStateOf<SearchRow.Hit?>(null)

    internal val rows = mutableStateListOf<SearchRow>()
    internal val collapsed = mutableStateMapOf<Path, Boolean>()

    private var token: SearchToken? = null
    private var gen = 0
    private var nextId = 0L
    private var lastHeader: SearchRow.FileHeader? = null
    private var startedKey: Any? = null

    internal fun currentQuery() = ContentQuery(
        text = query,
        regex = regex,
        caseSensitive = caseSensitive,
        wholeWord = wholeWord,
        includeHidden = includeHidden,
        respectIgnore = respectIgnore,
        globs = ContentQuery.parseGlobs(mask)
    )

    /** Условия поменялись с последнего запуска (или папка другая). */
    fun isStale(dir: Path): Boolean = startedKey != (dir to currentQuery())

    fun cancel() {
        gen++
        token?.cancel()
        token = null
        running = false
    }

    /** Окно закрыли посреди поиска — остановить; при следующем открытии поиск начнётся заново. */
    fun stopIfRunning() {
        if (!running) return
        cancel()
        startedKey = null
    }

    fun start(scope: CoroutineScope, dir: Path) {
        cancel()
        val q = currentQuery()
        startedKey = dir to q
        this.dir = dir
        rows.clear()
        collapsed.clear()
        matchCount = 0
        fileCount = 0
        selected = null
        result = null
        lastHeader = null
        if (q.text.isEmpty()) return
        val g = gen
        val t = SearchToken().also { token = it }
        running = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    ContentSearch.search(dir, q, t) { batch ->
                        // Пачки — в поток окна по порядку; итог придёт после них
                        scope.launch { if (g == gen) append(batch) }
                    }
                } catch (e: Exception) {
                    ContentSearchResult(0, 0, false, e.message ?: e.javaClass.simpleName, "")
                }
            }
            if (g != gen) return@launch
            result = r
            running = false
            if (token === t) token = null
        }
    }

    private fun append(batch: List<ContentMatch>) {
        for (m in batch) {
            val header = lastHeader?.takeIf { it.path == m.path }
                ?: SearchRow.FileHeader(nextId++, m.path).also {
                    rows.add(it)
                    lastHeader = it
                    fileCount++
                }
            header.count++
            val hit = SearchRow.Hit(nextId++, m, header)
            rows.add(hit)
            matchCount++
            if (selected == null) selected = hit
        }
    }
}

/**
 * Поиск текста во всех файлах папки (ripgrep): Enter — открыть файл на найденной строке,
 * Shift+Enter — показать файл в панели.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentSearchDialog(
    state: ContentSearchState,
    dir: Path,
    onOpen: (ContentMatch) -> Unit,
    onReveal: (ContentMatch) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val queryFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val home = remember { Paths.get(System.getProperty("user.home")) }
    fun pretty(p: Path): String = if (p.startsWith(home)) "~/" + home.relativize(p) else p.toString()

    // Поле появляется вместе с окном, не сразу: ждём его, иначе набирать можно было только после щелчка
    LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(queryFocus) }
    DisposableEffect(state) { onDispose { state.stopIfRunning() } }
    // Поиск при наборе — с паузой, чтобы не запускать его на каждую букву
    val q = state.currentQuery()
    LaunchedEffect(q, dir) {
        if (!state.isStale(dir)) return@LaunchedEffect
        if (q.text.isEmpty()) {
            state.start(scope, dir)
            return@LaunchedEffect
        }
        delay(if (state.dir == null || state.dir == dir) 350 else 0)
        state.start(scope, dir)
    }

    val visible by remember {
        derivedStateOf {
            state.rows.filter { it !is SearchRow.Hit || state.collapsed[it.header.path] != true }
        }
    }

    fun moveSelection(delta: Int) {
        val hits = visible.filterIsInstance<SearchRow.Hit>()
        if (hits.isEmpty()) return
        val cur = hits.indexOf(state.selected)
        val next = if (cur < 0) 0 else (cur + delta).coerceIn(0, hits.size - 1)
        state.selected = hits[next]
    }

    // Выбранная строка — в видимой части списка
    LaunchedEffect(state.selected) {
        val sel = state.selected ?: return@LaunchedEffect
        val idx = visible.indexOf(sel)
        if (idx < 0) return@LaunchedEffect
        val info = listState.layoutInfo.visibleItemsInfo
        val fully = info.any {
            it.index == idx && it.offset >= listState.layoutInfo.viewportStartOffset &&
                it.offset + it.size <= listState.layoutInfo.viewportEndOffset
        }
        if (!fully) listState.animateScrollToItem((idx - 3).coerceAtLeast(0))
    }

    fun activate(reveal: Boolean) {
        if (state.isStale(dir)) {
            state.start(scope, dir)
            return
        }
        val m = state.selected?.match ?: return
        if (reveal) onReveal(m) else onOpen(m)
    }

    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(1000.dp).dialogGlass(RoundedCornerShape(16.dp)).height(700.dp).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Escape -> onDismiss()
                    Key.DirectionDown -> moveSelection(1)
                    Key.DirectionUp -> moveSelection(-1)
                    Key.PageDown -> moveSelection(12)
                    Key.PageUp -> moveSelection(-12)
                    Key.Enter, Key.NumPadEnter -> activate(reveal = e.isShiftPressed)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(26.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Поиск в файлах", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            pretty(dir) + " и подпапки",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { state.query = it.replace("\n", "") },
                        singleLine = true,
                        placeholder = { Text(if (state.regex) "Регулярное выражение" else "Что найти", color = MaterialTheme.colorScheme.outline) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(queryFocus)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = state.mask,
                        onValueChange = { state.mask = it.replace("\n", "") },
                        singleLine = true,
                        placeholder = { Text("маска: *.kt, !*.min.js", color = MaterialTheme.colorScheme.outline) },
                        leadingIcon = { Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.width(260.dp).heightIn(min = 56.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OptionChip("Aa", "Учитывать регистр букв", state.caseSensitive) { state.caseSensitive = !state.caseSensitive }
                    OptionChip("Слово", "Только целые слова", state.wholeWord) { state.wholeWord = !state.wholeWord }
                    OptionChip(".*", "Регулярное выражение (синтаксис ripgrep)", state.regex) { state.regex = !state.regex }
                    OptionChip("Скрытые", "Искать и в скрытых файлах и папках (.*)", state.includeHidden) { state.includeHidden = !state.includeHidden }
                    OptionChip(".gitignore", "Пропускать то, что исключено в .gitignore", state.respectIgnore) { state.respectIgnore = !state.respectIgnore }
                    Spacer(modifier = Modifier.weight(1f))
                    SearchStatus(state)
                }
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                ) {
                    val error = state.result?.error
                    when {
                        visible.isNotEmpty() -> LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                            items(visible, key = { it.id }) { row ->
                                when (row) {
                                    is SearchRow.FileHeader -> ResultFileHeader(
                                        row = row,
                                        relativeDir = runCatching { dir.relativize(row.path.parent ?: row.path).toString() }.getOrDefault(""),
                                        collapsed = state.collapsed[row.path] == true,
                                        onToggle = {
                                            if (state.collapsed[row.path] == true) state.collapsed.remove(row.path)
                                            else state.collapsed[row.path] = true
                                            if (state.selected?.header === row) state.selected = null
                                        }
                                    )
                                    is SearchRow.Hit -> ResultLine(
                                        hit = row,
                                        selected = state.selected === row,
                                        onClick = {
                                            state.selected = row
                                            onOpen(row.match)
                                        },
                                        onReveal = { onReveal(row.match) }
                                    )
                                }
                            }
                        }
                        state.running -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = NoctaliaPrimary)
                        error != null -> Text(
                            error,
                            color = NoctaliaDanger,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                        state.query.isEmpty() -> Text(
                            "Введите текст — поиск идёт по всем файлам папки и подпапок.\nДвоичные файлы и файлы больше 20 МБ пропускаются.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        state.result != null -> Text(
                            "Ничего не найдено",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "↑↓ — выбор · Enter — открыть на этой строке · Shift+Enter — показать файл в панели · Esc — закрыть",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun SearchStatus(state: ContentSearchState) {
    val r = state.result
    val text = when {
        state.running -> "Поиск… найдено ${state.matchCount}"
        r == null -> ""
        r.error != null -> "Ошибка"
        else -> buildString {
            append("Совпадений: ${state.matchCount} в файлах: ${state.fileCount}")
            if (r.truncated) append(" (показаны первые ${ContentSearch.MAX_MATCHES})")
            if (r.engine.isNotEmpty()) append(" · ").append(r.engine)
        }
    }
    Text(
        text,
        fontSize = 12.sp,
        color = when {
            r?.error != null -> NoctaliaDanger
            r?.truncated == true -> NoctaliaWarn
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionChip(label: String, hint: String, on: Boolean, onToggle: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 450
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (on) NoctaliaPrimary.copy(alpha = 0.18f) else Color.Transparent,
            border = BorderStroke(1.dp, if (on) NoctaliaPrimary.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.noFocus().clickable(onClick = onToggle)
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ResultFileHeader(row: SearchRow.FileHeader, relativeDir: String, collapsed: Boolean, onToggle: () -> Unit) {
    val name = row.path.fileName?.toString() ?: row.path.toString()
    val type = remember(name) { VfsEntry.detectType(name, false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).noFocus().clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
            contentDescription = if (collapsed) "Развернуть" else "Свернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        FileIconBadge(type, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            if (relativeDir.isEmpty() || relativeDir == ".") "" else relativeDir,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(shape = RoundedCornerShape(8.dp), color = NoctaliaSecondary.copy(alpha = 0.16f)) {
            Text(
                row.count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NoctaliaSecondary,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun ResultLine(hit: SearchRow.Hit, selected: Boolean, onClick: () -> Unit, onReveal: () -> Unit) {
    val m = hit.match
    val highlight = NoctaliaPrimary
    val text = remember(m, highlight) {
        buildAnnotatedString {
            if (m.clippedStart) append("…")
            var pos = 0
            for (r in m.ranges.sortedBy { it.first }) {
                if (r.first < pos) continue
                append(m.preview.substring(pos, r.first))
                withStyle(SpanStyle(background = highlight.copy(alpha = 0.3f), color = highlight, fontWeight = FontWeight.Bold)) {
                    append(m.preview.substring(r.first, r.last + 1))
                }
                pos = r.last + 1
            }
            append(m.preview.substring(pos))
            if (m.clippedEnd) append("…")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) NoctaliaPrimary.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(6.dp))
            .noFocus()
            .clickable(onClick = onClick)
            .padding(start = 30.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            m.line.toString(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            IconButton(onClick = onReveal, modifier = Modifier.size(24.dp).noFocus()) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Показать в панели", tint = NoctaliaSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
