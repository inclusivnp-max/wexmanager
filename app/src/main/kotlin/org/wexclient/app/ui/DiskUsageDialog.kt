package org.wexclient.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.util.Plural
import org.wexclient.app.vfs.DiskUsage
import org.wexclient.app.vfs.DuCategory
import org.wexclient.app.vfs.DuNode
import org.wexclient.app.vfs.DuProgress
import org.wexclient.app.vfs.DuResult
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Последний подсчёт: повторно окно для той же папки открывается сразу, «Заново» пересчитает. */
object DiskUsageSession {
    var last: DuResult? = null
        private set
    var at: Long = 0L
        private set

    fun remember(result: DuResult) {
        // Огромное дерево не держим в памяти после закрытия окна
        val nodes = result.top.files + result.top.dirs
        last = if (nodes <= 400_000) result else null
        at = System.currentTimeMillis()
    }
}

/**
 * Анализ места: что занимает папку. Слева — содержимое по убыванию размера,
 * справа — плитки, площадь которых равна доле места, ниже — сводка по видам файлов.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiskUsageDialog(
    root: Path,
    /** В корзину; по окончании — ушедшие пути и текст ошибки, если что-то не ушло. */
    onTrash: (paths: List<Path>, onDone: (done: List<Path>, error: String?) -> Unit) -> Unit,
    /** Открыть в панели папку элемента и встать на него. */
    onReveal: (Path) -> Unit,
    onDismiss: () -> Unit
) {
    val cached = remember { DiskUsageSession.last?.takeIf { it.root == root } }
    var result by remember { mutableStateOf(cached) }
    var failure by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<DuProgress?>(null) }
    var oneFs by remember { mutableStateOf(true) }
    var runs by remember { mutableIntStateOf(if (cached != null) 0 else 1) }
    var current by remember { mutableStateOf(cached?.top) }
    var selected by remember { mutableStateOf(cached?.top?.children?.firstOrNull()) }
    var hovered by remember { mutableStateOf<DuNode?>(null) }
    // Дерево меняется на месте (корзина, пересчёт папки) — номер версии перерисовывает всё, что от него зависит
    var version by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<DuNode?>(null) }
    var rescanning by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        cancel.set(false)
        failure = null
        progress = DuProgress(0, 0, 0, root.toString())
        val r = withContext(Dispatchers.IO) {
            runCatching { DiskUsage.scan(root, oneFs, cancelled = { cancel.get() }) { progress = it } }
        }
        progress = null
        r.onSuccess {
            result = it
            DiskUsageSession.remember(it)
            current = it.top
            selected = it.top.children.firstOrNull()
            hovered = null
            version++
        }.onFailure { failure = it.message ?: "Папка не прочиталась" }
    }
    // Пересчёт папки — со своим флагом: окно закрыли — обход диска в фоне не продолжается
    val closed = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            cancel.set(true)
            closed.set(true)
        }
    }

    val cur = current
    val children = remember(cur, version) { cur?.children?.toList().orEmpty() }
    val largest by produceState(emptyList<DuNode>(), cur, version, tab) {
        if (tab != 1 || cur == null) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { DiskUsage.largestFiles(cur, 200) }.getOrDefault(emptyList()) }
    }
    val categories by produceState(emptyList<Triple<DuCategory, Long, Long>>(), cur, version) {
        if (cur == null) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { DiskUsage.byCategory(cur) }.getOrDefault(emptyList()) }
    }
    val shownList = if (tab == 0) children else largest

    fun scrollTo(node: DuNode?) {
        val idx = shownList.indexOf(node)
        if (idx < 0) return
        val row = idx + if (tab == 0 && cur?.parent != null) 1 else 0
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == row } || visible.lastOrNull()?.index == row) {
            scope.launch { listState.animateScrollToItem((row - 3).coerceAtLeast(0)) }
        }
    }

    fun openDir(node: DuNode) {
        if (!node.isDir) return
        current = node
        selected = node.children.firstOrNull()
        hovered = null
        tab = 0
        scope.launch { listState.scrollToItem(0) }
    }

    fun goUp() {
        val from = current ?: return
        val up = from.parent ?: return
        current = up
        selected = from
        hovered = null
        tab = 0
        val idx = up.children.indexOf(from) + if (up.parent != null) 1 else 0
        scope.launch { listState.scrollToItem((idx - 3).coerceAtLeast(0)) }
    }

    fun moveSelection(delta: Int) {
        if (shownList.isEmpty()) return
        val idx = shownList.indexOf(selected)
        val next = shownList[(if (idx < 0) 0 else idx + delta).coerceIn(0, shownList.lastIndex)]
        selected = next
        scrollTo(next)
    }

    fun rescan(node: DuNode) {
        val res = result ?: return
        if (node.parent == null) {
            runs++
            return
        }
        if (rescanning) return
        rescanning = true
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { DiskUsage.scan(node.pathFrom(res.root), oneFs, cancelled = { closed.get() }).top } }
            rescanning = false
            fresh.onSuccess { f ->
                val placed = DiskUsage.replace(node, f)
                // Пока считали, могли зайти внутрь и выбрать там файл — переводим на узлы нового дерева
                // (если папку за это время убрали из дерева, подставлять некуда)
                if (placed.parent != null) {
                    current = DiskUsage.remap(current, node, placed)
                    selected = DiskUsage.remap(selected, node, placed)
                    hovered = DiskUsage.remap(hovered, node, placed)
                }
                version++
                toast.success("«${node.name}» пересчитана: ${humanSize(placed.size)}")
            }.onFailure { toast.error("Не пересчиталось: ${it.message ?: "ошибка"}") }
        }
    }

    val res = result
    val subtitle = when {
        progress != null -> "подсчёт… ${grouped(progress!!.files)} ${filesWord(progress!!.files)} · ${humanSize(progress!!.bytes)}"
        res != null -> buildString {
            append(humanSize(res.top.size))
            append(" · ").append(grouped(res.top.files)).append(' ').append(filesWord(res.top.files))
            append(" · ").append(grouped(res.top.dirs)).append(' ').append(Plural.ru(res.top.dirs, "папка", "папки", "папок"))
            if (!res.complete) append(" · подсчёт остановлен — показано то, что успели")
            if (res.errors > 0) append(" · без доступа: ").append(res.errors)
            if (res === cached) append(" · подсчитано ").append(ago(DiskUsageSession.at))
            else append(" · за ").append(seconds(res.millis))
        }
        else -> failure.orEmpty()
    }

    GrailDialogShell(
        title = "Анализ места",
        badge = "МЕСТО",
        heading = root.toString(),
        subtitle = subtitle,
        closeOnEsc = true,
        dialogWidth = 1400.dp,
        dialogHeight = 880.dp,
        toast = toast,
        onKey = { e ->
            if (e.type != KeyEventType.KeyDown || confirm != null || progress != null) false
            else when (e.key) {
                Key.DirectionDown -> { moveSelection(1); true }
                Key.DirectionUp -> { moveSelection(-1); true }
                Key.PageDown -> { moveSelection(15); true }
                Key.PageUp -> { moveSelection(-15); true }
                Key.Enter, Key.NumPadEnter -> { selected?.let { if (it.isDir) openDir(it) }; true }
                Key.Backspace -> { goUp(); true }
                Key.Delete -> { selected?.takeIf { it.parent != null }?.let { confirm = it }; true }
                else -> false
            }
        },
        onDismiss = onDismiss
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val p = progress
            when {
                p != null -> ScanProgress(p) { cancel.set(true) }
                res == null || cur == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    GrailEmptyState(failure ?: "Нечего показать")
                    ToolAction("Попробовать ещё раз", Icons.Default.Refresh) { runs++ }
                }
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // Путь внутри анализа, «вверх» и пересчёт
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolAction("Вверх", Icons.Default.ArrowUpward, enabled = cur.parent != null) { goUp() }
                        Spacer(modifier = Modifier.width(10.dp))
                        DuBreadcrumbs(cur.lineage(), root.toString(), onOpen = { openDir(it) }, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(10.dp))
                        ToolCheck("Только этот раздел", oneFs) {
                            oneFs = it
                            runs++
                        }
                        ToolAction("Заново", Icons.Default.Refresh) { runs++ }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(modifier = Modifier.weight(0.46f).fillMaxHeight()) {
                            GrailTabs(listOf("Содержимое", "Крупные файлы"), tab) { tab = it }
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (shownList.isEmpty() && cur.parent == null) {
                                    GrailEmptyState(if (tab == 0) "Папка пуста" else "Файлов нет")
                                }
                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                    if (tab == 0 && cur.parent != null) {
                                        item(key = "..") { UpRow { goUp() } }
                                    }
                                    if (tab == 0) {
                                        items(children, key = { it.name }) { node ->
                                            DuRow(
                                                node = node,
                                                total = cur.size,
                                                barMax = cur.size,
                                                note = null,
                                                selected = node === selected,
                                                onClick = { selected = node },
                                                onDoubleClick = { if (node.isDir) openDir(node) }
                                            )
                                        }
                                    } else {
                                        val top = largest.firstOrNull()?.size ?: 0L
                                        val base = cur.pathFrom(res.root)
                                        itemsIndexed(largest, key = { i, _ -> i }) { _, node ->
                                            DuRow(
                                                node = node,
                                                total = cur.size,
                                                barMax = top,
                                                note = runCatching { base.relativize(node.pathFrom(res.root).parent).toString() }.getOrNull()
                                                    ?.ifEmpty { "в этой папке" },
                                                selected = node === selected,
                                                onClick = { selected = node },
                                                onDoubleClick = { selected = node }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(0.54f).fillMaxHeight()) {
                            DuTreemap(
                                node = cur,
                                version = version,
                                selected = selected,
                                hovered = hovered,
                                onHover = { hovered = it },
                                onSelect = { node ->
                                    selected = node
                                    if (tab == 0) scrollTo(node)
                                },
                                onOpen = { openDir(it) },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            HoverLine(hovered ?: selected, cur, res.root)
                            Spacer(modifier = Modifier.height(8.dp))
                            CategoryBar(categories, cur.size)
                        }
                    }

                    // Выбранное и что с ним сделать
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) {
                        val sel = selected
                        Text(
                            text = sel?.let { describe(it) } ?: "Выберите папку или файл",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (rescanning) {
                            CircularProgressIndicator(color = NoctaliaPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        }
                        ToolAction("Открыть", Icons.Default.FolderOpen, enabled = sel?.isDir == true) { sel?.let { openDir(it) } }
                        ToolAction("Пересчитать", Icons.Default.Refresh, enabled = sel?.isDir == true && !rescanning) { sel?.let { rescan(it) } }
                        ToolAction("Показать в панели", Icons.Default.MyLocation, enabled = sel != null) {
                            sel?.let {
                                onReveal(it.pathFrom(res.root))
                                onDismiss()
                            }
                        }
                        ToolAction("В корзину", Icons.Default.Delete, enabled = sel?.parent != null, danger = true) { confirm = sel }
                    }
                }
            }
        }
        // Подтверждение — внутри окна анализа: иначе оно открылось бы под ним, от главного окна
        confirm?.let { node ->
            val path = result?.root?.let { node.pathFrom(it) }
            ConfirmDialog(
                title = "В корзину",
                message = buildString {
                    append("«").append(node.name).append("» — ").append(humanSize(node.size))
                    if (node.isDir) append(", ").append(grouped(node.files)).append(' ').append(filesWord(node.files))
                    append(".\nМесто освободится, когда корзину очистят; до тех пор всё можно вернуть.")
                },
                confirmText = "В корзину",
                isDestructive = true,
                onConfirm = {
                    confirm = null
                    if (path != null) {
                        onTrash(listOf(path)) { done, error ->
                            if (done.isNotEmpty()) {
                                val parent = node.parent
                                val index = parent?.children?.indexOf(node) ?: -1
                                // Смотрели внутрь удалённого — поднимаемся к его папке
                                if (current?.isWithin(node) == true) current = parent
                                if (hovered?.isWithin(node) == true) hovered = null
                                DiskUsage.detach(node)
                                // Выделение — на строку, вставшую на место удалённой
                                if (selected?.isWithin(node) != false) {
                                    selected = if (parent != null && parent === current) {
                                        parent.children.getOrNull(index) ?: parent.children.lastOrNull()
                                    } else null
                                }
                                version++
                                toast.success("«${node.name}» в корзине")
                            }
                            if (error != null) toast.error("В корзину не получилось: $error")
                        }
                    }
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

@Composable
private fun ScanProgress(p: DuProgress, onStop: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
            CircularProgressIndicator(color = NoctaliaPrimary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Просмотрено ${grouped(p.files)} ${filesWord(p.files)} · ${humanSize(p.bytes)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                tail(p.current, 90),
                fontSize = 11.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(16.dp))
            ToolAction("Остановить", Icons.Default.Stop, onClick = onStop)
        }
    }
}

@Composable
private fun DuBreadcrumbs(chain: List<DuNode>, rootLabel: String, onOpen: (DuNode) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    // Конец пути важнее начала — держим его в виду
    LaunchedEffect(chain.lastOrNull()) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Row(modifier = modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        chain.forEachIndexed { i, n ->
            if (i > 0) Text("/", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            val last = i == chain.lastIndex
            Text(
                text = if (i == 0) rootLabel else n.name,
                fontSize = 12.sp,
                fontFamily = AppFonts.JetBrainsMono,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !last) { onOpen(n) }
                    .padding(horizontal = 4.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    ) {
        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("..", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuRow(
    node: DuNode,
    total: Long,
    barMax: Long,
    note: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val fraction = if (barMax > 0) (node.size.toDouble() / barMax).toFloat().coerceIn(0f, 1f) else 0f
    val extra = when {
        note != null -> note
        node.otherDevice -> "другой раздел — не считали"
        node.error != null -> node.error
        else -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (extra != null) 40.dp else 30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) NoctaliaPrimary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 8.dp)
    ) {
        CategoryGlyph(node)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                fontSize = 12.5.sp,
                fontWeight = if (node.isDir) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (extra != null) {
                Text(
                    text = extra,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (node.isDir) {
            Text(
                text = grouped(node.files) + " ф.",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(76.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        SizeBar(fraction, node.category)
        Text(
            text = percent(node.size, total),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.width(50.dp)
        )
        Text(
            text = humanSize(node.size),
            fontSize = 12.sp,
            fontFamily = AppFonts.JetBrainsMono,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(84.dp)
        )
    }
}

@Composable
private fun CategoryGlyph(node: DuNode) {
    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (node.isDir) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(categoryColor(node.category).copy(alpha = 0.8f))
            )
        }
    }
}

@Composable
private fun SizeBar(fraction: Float, category: DuCategory) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                    .background(
                        if (category == DuCategory.FOLDER) NoctaliaPrimary.copy(alpha = 0.55f)
                        else categoryColor(category).copy(alpha = 0.75f)
                    )
            )
        }
    }
}

@Composable
private fun HoverLine(node: DuNode?, current: DuNode, root: Path) {
    val text = node?.let {
        val rel = runCatching { current.pathFrom(root).relativize(it.pathFrom(root)).toString() }.getOrDefault(it.name)
        buildString {
            append(rel.ifEmpty { it.name })
            append(" · ").append(humanSize(it.size))
            append(" · ").append(percent(it.size, current.size))
            if (it.isDir) append(" · ").append(grouped(it.files)).append(' ').append(filesWord(it.files))
        }
    } ?: "Наведите на плитку — здесь будет её размер"
    Text(
        text = text,
        fontSize = 11.sp,
        fontFamily = AppFonts.JetBrainsMono,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBar(categories: List<Triple<DuCategory, Long, Long>>, total: Long) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            if (total > 0) {
                categories.forEach { (cat, bytes, _) ->
                    val f = bytes.toFloat() / total
                    if (f > 0.003f) {
                        Box(modifier = Modifier.weight(f).fillMaxHeight().background(categoryColor(cat).copy(alpha = 0.72f)))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categories.forEach { (cat, bytes, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(categoryColor(cat).copy(alpha = 0.8f))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${cat.title} ${humanSize(bytes)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · ${grouped(count)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/** Плитка на холсте: узел (null — «ещё N мелких»), место и глубина (0 — содержимое папки, 1 — внутри плитки-папки). */
private class TmCell(val node: DuNode?, val label: String, val size: Long, val box: TmBox, val depth: Int, val owner: TmCell?)

private class TmLabel(val layout: TextLayoutResult, val at: Offset)

private const val MAX_TILES = 160
private const val MAX_INNER = 48

@Composable
private fun DuTreemap(
    node: DuNode,
    version: Int,
    selected: DuNode?,
    hovered: DuNode?,
    onHover: (DuNode?) -> Unit,
    onSelect: (DuNode) -> Unit,
    onOpen: (DuNode) -> Unit,
    modifier: Modifier
) {
    var area by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val nameColor = MaterialTheme.colorScheme.onSurface
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val folderTone = MaterialTheme.colorScheme.onSurfaceVariant
    val restTone = MaterialTheme.colorScheme.outline
    val emptyTone = MaterialTheme.colorScheme.surfaceVariant

    val cells = remember(node, version, area) {
        buildCells(node, area.width.toFloat(), area.height.toFloat(), with(density) { 18.dp.toPx() }, with(density) { 3.dp.toPx() })
    }
    val labels = remember(cells, nameColor) {
        val out = ArrayList<TmLabel>()
        val pad = with(density) { 5.dp.toPx() }
        val nameStyle = TextStyle(fontSize = 11.sp, color = nameColor, fontWeight = FontWeight.Medium)
        val sizeStyle = TextStyle(fontSize = 10.sp, color = dimColor)
        val innerStyle = TextStyle(fontSize = 9.5.sp, color = dimColor)
        for (c in cells) {
            val b = c.box
            val maxW = (b.w - pad * 2).toInt()
            if (maxW < with(density) { 30.dp.toPx() }) continue
            if (c.depth == 0) {
                if (b.h < with(density) { 17.dp.toPx() }) continue
                val isDir = c.node?.isDir == true
                val title = if (isDir) "${c.label}  ·  ${humanSize(c.size)}" else c.label
                val name = measurer.measure(title, nameStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                out.add(TmLabel(name, Offset(b.x + pad, b.y + with(density) { 2.dp.toPx() })))
                if (!isDir && b.h > with(density) { 36.dp.toPx() }) {
                    val size = measurer.measure(humanSize(c.size), sizeStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                    out.add(TmLabel(size, Offset(b.x + pad, b.y + with(density) { 2.dp.toPx() } + name.size.height)))
                }
            } else if (c.node != null && b.w > with(density) { 64.dp.toPx() } && b.h > with(density) { 24.dp.toPx() }) {
                val name = measurer.measure(c.label, innerStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                out.add(TmLabel(name, Offset(b.x + pad, b.y + with(density) { 2.dp.toPx() })))
            }
        }
        out
    }

    fun hit(pos: Offset): TmCell? = cells.lastOrNull { it.box.contains(pos.x, pos.y) }

    Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(emptyTone.copy(alpha = 0.25f))) {
        if (node.size <= 0L) {
            GrailEmptyState("Здесь ничего не занимает места")
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { area = it }
                .pointerInput(cells) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                PointerEventType.Move, PointerEventType.Enter ->
                                    onHover(e.changes.firstOrNull()?.position?.let { hit(it)?.node })
                                PointerEventType.Exit -> onHover(null)
                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(cells) {
                    detectTapGestures(
                        onTap = { pos -> hit(pos)?.let { c -> (c.owner ?: c).node?.let(onSelect) } },
                        onDoubleTap = { pos ->
                            hit(pos)?.let { c ->
                                val top = (c.owner ?: c).node
                                if (top != null && top.isDir) onOpen(top)
                            }
                        }
                    )
                }
        ) {
            val inset = 1.5f
            for (c in cells) {
                val b = c.box
                if (b.w < 2f || b.h < 2f) continue
                val fill = when {
                    c.node == null -> restTone.copy(alpha = 0.14f)
                    c.node.isDir -> folderTone.copy(alpha = if (c.depth == 0) 0.16f else 0.26f)
                    else -> categoryColor(c.node.category).copy(alpha = if (c.depth == 0) 0.46f else 0.52f)
                }
                val r = if (c.depth == 0) 5f else 3f
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(b.x + inset, b.y + inset),
                    size = Size((b.w - inset * 2).coerceAtLeast(0f), (b.h - inset * 2).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(r, r)
                )
            }
            for (l in labels) drawText(l.layout, topLeft = l.at)
            // Выбранное — контур цвета темы, под мышью — светлый контур
            for (c in cells) {
                val n = c.node ?: continue
                val isSel = n === selected
                val isHover = n === hovered
                if (!isSel && !isHover) continue
                val b = c.box
                drawRoundRect(
                    color = if (isSel) NoctaliaPrimary.copy(alpha = 0.9f) else nameColor.copy(alpha = 0.55f),
                    topLeft = Offset(b.x + inset, b.y + inset),
                    size = Size((b.w - inset * 2).coerceAtLeast(0f), (b.h - inset * 2).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(5f, 5f),
                    style = Stroke(width = if (isSel) 2.5f else 1.5f)
                )
            }
        }
    }
}

/** Плитки папки: крупное — своими плитками, мелочь — одной общей; внутри больших папок — их содержимое. */
private fun buildCells(node: DuNode, w: Float, h: Float, header: Float, pad: Float): List<TmCell> {
    if (w <= 0f || h <= 0f) return emptyList()
    val kids = node.children.filter { it.size > 0 }
    val shown = kids.take(MAX_TILES)
    val rest = kids.drop(MAX_TILES)
    val entries = ArrayList<Pair<DuNode?, Long>>(shown.size + 1)
    shown.forEach { entries.add(it to it.size) }
    val restSize = rest.sumOf { it.size }
    if (restSize > 0) entries.add(null to restSize)
    entries.sortByDescending { it.second }
    val boxes = Treemap.layout(entries.map { it.second.toDouble() }, 0f, 0f, w, h)
    val cells = ArrayList<TmCell>(entries.size * 4)
    entries.forEachIndexed { i, (n, size) ->
        val b = boxes[i]
        if (b.w < 1f || b.h < 1f) return@forEachIndexed
        val cell = TmCell(n, n?.name ?: "ещё ${rest.size} мелких", size, b, 0, null)
        cells.add(cell)
        // Содержимое папки внутри её плитки: видно, из чего она состоит, не заходя внутрь
        if (n != null && n.isDir && b.w > header * 2.5f && b.h > header * 2.2f) {
            val inner = TmBox(b.x + pad, b.y + header, b.w - pad * 2, b.h - header - pad)
            val sub = n.children.filter { it.size > 0 }
            val subShown = sub.take(MAX_INNER)
            val subRest = sub.drop(MAX_INNER).sumOf { it.size }
            val subEntries = ArrayList<Pair<DuNode?, Long>>(subShown.size + 1)
            subShown.forEach { subEntries.add(it to it.size) }
            if (subRest > 0) subEntries.add(null to subRest)
            subEntries.sortByDescending { it.second }
            val subBoxes = Treemap.layout(subEntries.map { it.second.toDouble() }, inner.x, inner.y, inner.w, inner.h)
            subEntries.forEachIndexed { j, (c, s) ->
                val sb = subBoxes[j]
                if (sb.w >= 2f && sb.h >= 2f) cells.add(TmCell(c, c?.name ?: "", s, sb, 1, cell))
            }
        }
    }
    return cells
}

/** Приглушённые цвета видов файлов: различимы, но не кричат. */
internal fun categoryColor(c: DuCategory): Color = when (c) {
    DuCategory.FOLDER -> Color(0xFF8C8480)
    DuCategory.VIDEO -> Color(0xFF8E7FB8)
    DuCategory.IMAGE -> Color(0xFF5FA096)
    DuCategory.AUDIO -> Color(0xFFB8955F)
    DuCategory.ARCHIVE -> Color(0xFFB27572)
    DuCategory.DOCUMENT -> Color(0xFF6F90B6)
    DuCategory.CODE -> Color(0xFF82A36E)
    DuCategory.BINARY -> Color(0xFFA08A74)
    DuCategory.OTHER -> Color(0xFF85858C)
}

private fun describe(n: DuNode): String = buildString {
    append("«").append(n.name).append("» — ").append(humanSize(n.size))
    n.parent?.let { p -> append(" · ").append(percent(n.size, p.size)).append(" от «").append(p.name).append("»") }
    if (n.isDir) append(" · ").append(grouped(n.files)).append(' ').append(filesWord(n.files))
    if (n.modified > 0) append(" · изменено ").append(DATE.format(Date(n.modified)))
}

private fun percent(part: Long, whole: Long): String {
    if (whole <= 0) return "—"
    val p = part * 100.0 / whole
    return when {
        p >= 10 -> String.format(Locale.forLanguageTag("ru"), "%.0f %%", p)
        p >= 0.1 -> String.format(Locale.forLanguageTag("ru"), "%.1f %%", p)
        part > 0 -> "<0,1 %"
        else -> "0 %"
    }
}

private fun grouped(n: Long): String = String.format(Locale.forLanguageTag("ru"), "%,d", n)

private fun filesWord(n: Long): String = Plural.ru(n, "файл", "файла", "файлов")

private fun seconds(ms: Long): String =
    if (ms < 1000) "$ms мс" else String.format(Locale.forLanguageTag("ru"), "%.1f с", ms / 1000.0)

private fun ago(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        else -> "${min / 60} ч назад"
    }
}

private fun tail(path: String, max: Int): String = if (path.length <= max) path else "…" + path.takeLast(max - 1)

private val DATE = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
