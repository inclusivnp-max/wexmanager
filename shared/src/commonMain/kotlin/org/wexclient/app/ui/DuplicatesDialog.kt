package org.wexclient.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.util.Plural
import org.wexclient.app.vfs.DupFile
import org.wexclient.app.vfs.DupGroup
import org.wexclient.app.vfs.DupOptions
import org.wexclient.app.vfs.DupProgress
import org.wexclient.app.vfs.DupResult
import org.wexclient.app.vfs.DuCategory
import org.wexclient.app.vfs.DuplicateFinder
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Последний поиск: окно для тех же папок открывается сразу с результатом. */
object DuplicatesSession {
    @Volatile
    var last: DupResult? = null
        private set
    var options: DupOptions = DupOptions(minSize = 1)
    var at: Long = 0L
        private set
    private var expiry: kotlinx.coroutines.Job? = null

    fun remember(result: DupResult) {
        last = result
        val stamp = System.currentTimeMillis()
        at = stamp
        // Список копий по большому диску — десятки мегабайт; держался до закрытия всех окон
        expiry?.cancel()
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        expiry = kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(KEEP_MS)
            if (at == stamp) last = null
        }
    }

    private const val KEEP_MS = 10 * 60_000L

    /** Окон не осталось — список копий больше некому показать. */
    fun forget() {
        last = null
    }

    /** Ушедшее в корзину — из сохранённого результата: при следующем открытии его уже не показывать. */
    fun drop(paths: Collection<Path>) {
        val r = last ?: return
        val gone = paths.toHashSet()
        val groups = r.groups.mapNotNull { g ->
            val left = g.files.filter { it.path !in gone }
            if (left.size >= 2) DupGroup(g.size, left) else null
        }
        last = DupResult(r.roots, groups, r.scannedFiles, r.complete, r.errors, r.millis)
    }
}

/** Как выбрать, какую копию оставить. */
private enum class KeepRule(val title: String) {
    OLDEST("самую старую"),
    NEWEST("самую новую"),
    SHORTEST("с коротким путём")
}

/**
 * Поиск одинаковых файлов в папке (или в папках обеих панелей). Отмеченные копии уходят
 * в корзину; в каждой группе хотя бы одна копия остаётся всегда.
 */
@Composable
fun DuplicatesDialog(
    roots: List<Path>,
    onTrash: (paths: List<Path>, onDone: (done: List<Path>, error: String?) -> Unit) -> Unit,
    onReveal: (Path) -> Unit,
    onDismiss: () -> Unit
) {
    val searchRoots = remember(roots) {
        val n = roots.map { it.toAbsolutePath().normalize() }.distinct()
        n.filter { r -> n.none { o -> o != r && r.startsWith(o) } }
    }
    val cached = remember { DuplicatesSession.last?.takeIf { it.roots == searchRoots } }
    var options by remember { mutableStateOf(DuplicatesSession.options) }
    var result by remember { mutableStateOf(cached) }
    var progress by remember { mutableStateOf<DupProgress?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableIntStateOf(if (cached != null) 0 else 1) }
    // Отмеченные и уже ушедшие в корзину: наборы как карты без значений
    val marked = remember { mutableStateMapOf<Path, Unit>() }
    val gone = remember { mutableStateMapOf<Path, Unit>() }
    var confirm by remember { mutableStateOf(false) }
    // Флаг остановки — свой у каждого запуска: перезапуск (другой размер) останавливает прежний поиск,
    // и тот больше не трогает ход поиска на экране
    val cancel = remember { java.util.concurrent.atomic.AtomicReference(AtomicBoolean(false)) }
    // Пока отмеченное уходит в корзину, второй раз не отправляем: иначе в группе могло не остаться копии
    var trashing by remember { mutableStateOf(false) }
    val toast = rememberToastState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        val stop = AtomicBoolean(false)
        cancel.getAndSet(stop).set(true)
        failure = null
        marked.clear()
        gone.clear()
        progress = DupProgress("Сбор файлов", 0, 0, 0)
        val opts = options
        val r = withContext(Dispatchers.IO) {
            runCatching { DuplicateFinder.find(searchRoots, opts, cancelled = { stop.get() }) { if (!stop.get()) progress = it } }
        }
        progress = null
        r.onSuccess {
            result = it
            DuplicatesSession.options = opts
            // Остановленный поиск не запоминаем: в следующий раз он выглядел бы законченным
            if (it.complete) DuplicatesSession.remember(it)
        }.onFailure { failure = it.message ?: "Папка не прочиталась" }
    }
    DisposableEffect(Unit) { onDispose { cancel.get().set(true) } }

    val res = result
    // Группы без ушедшего в корзину; где осталась одна копия — это уже не дубликаты
    val groups = remember(res, gone.size) {
        res?.groups.orEmpty().mapNotNull { g ->
            val left = g.files.filter { it.path !in gone }
            if (left.size >= 2) DupGroup(g.size, left) else null
        }
    }
    val markedFiles = groups.flatMap { g -> g.files.filter { it.path in marked } }
    val markedBytes = markedFiles.sumOf { it.size }
    val wastedTotal = groups.sumOf { it.wasted }

    fun toggle(group: DupGroup, file: DupFile, on: Boolean) {
        if (!on) {
            marked.remove(file.path)
            return
        }
        // Все копии в корзину не отправляем: хотя бы одна остаётся
        if (group.files.all { it.path == file.path || it.path in marked }) {
            toast.info("В группе остаётся хотя бы одна копия")
            return
        }
        marked[file.path] = Unit
    }

    fun autoMark(keep: (DupGroup) -> DupFile) {
        marked.clear()
        DuplicateFinder.markAllBut(groups, keep).forEach { marked[it] = Unit }
    }

    val subtitle = when {
        progress != null -> "поиск… просмотрено ${grouped(progress!!.files)} ${filesWord(progress!!.files)}"
        res != null -> buildString {
            if (groups.isEmpty()) append(if (res.groups.isEmpty()) "одинаковых файлов нет" else "лишние копии убраны")
            else {
                append(grouped(groups.size.toLong())).append(' ').append(Plural.ru(groups.size.toLong(), "группа", "группы", "групп"))
                append(" · лишнего ").append(humanSize(wastedTotal))
            }
            append(" · просмотрено ").append(grouped(res.scannedFiles)).append(' ').append(filesWord(res.scannedFiles))
            if (!res.complete) append(" · поиск остановлен — показано найденное")
            if (res.errors > 0) append(" · не прочитано: ").append(res.errors)
            if (res === cached) append(" · найдено ").append(agoText(DuplicatesSession.at))
        }
        else -> failure.orEmpty()
    }

    GrailDialogShell(
        title = "Поиск дубликатов",
        badge = "ДУБЛИ",
        heading = searchRoots.joinToString("  ·  ") { it.toString() },
        subtitle = subtitle,
        closeOnEsc = true,
        dialogWidth = 1250.dp,
        dialogHeight = 860.dp,
        toast = toast,
        onKey = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Delete && !confirm && markedFiles.isNotEmpty()) {
                confirm = true
                true
            } else false
        },
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Что искать
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Не меньше:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.width(8.dp))
                ToolChoice(
                    listOf(1L to "любые", 100L * 1024 to "100 КБ", 1L shl 20 to "1 МБ", 10L shl 20 to "10 МБ", 100L shl 20 to "100 МБ"),
                    options.minSize
                ) {
                    if (it != options.minSize) {
                        options = options.copy(minSize = it)
                        runs++
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                ToolCheck("Скрытые файлы", options.includeHidden, enabled = progress == null) {
                    options = options.copy(includeHidden = it)
                    runs++
                }
                Spacer(modifier = Modifier.weight(1f))
                ToolAction("Искать заново", Icons.Default.Refresh, enabled = progress == null) { runs++ }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Какие копии отметить
            if (progress == null && groups.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Отметить все копии, кроме:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.width(8.dp))
                    KeepRule.entries.forEach { rule ->
                        KeepChip(rule.title) {
                            autoMark { g ->
                                when (rule) {
                                    KeepRule.OLDEST -> g.files.minBy { it.modified }
                                    KeepRule.NEWEST -> g.files.maxBy { it.modified }
                                    KeepRule.SHORTEST -> g.files.minBy { it.path.toString().length }
                                }
                            }
                        }
                    }
                    // Две панели: оставить копии в одной из них
                    if (searchRoots.size > 1) {
                        searchRoots.forEach { root ->
                            KeepChip("в «${root.fileName ?: root}»") {
                                marked.clear()
                                DuplicateFinder.markOutside(groups, root).forEach { marked[it] = Unit }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (marked.isNotEmpty()) {
                        Text(
                            "Снять отметки",
                            fontSize = 12.sp,
                            color = NoctaliaPrimary.copy(alpha = 0.85f),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { marked.clear() }.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val p = progress
                when {
                    p != null -> SearchProgress(p) { cancel.get().set(true) }
                    res == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                        GrailEmptyState(failure ?: "")
                        ToolAction("Попробовать ещё раз", Icons.Default.Refresh) { runs++ }
                    }
                    groups.isEmpty() -> Box(modifier = Modifier.align(Alignment.Center)) {
                        GrailEmptyState(
                            when {
                                res.groups.isNotEmpty() -> "Все лишние копии убраны"
                                !res.complete -> "Поиск остановлен — одинаковых файлов пока не нашлось"
                                else -> "Одинаковых файлов нет — всё в единственном экземпляре"
                            }
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        groups.forEachIndexed { gi, group ->
                            item(key = "g:" + group.files[0].path.toString()) {
                                GroupHeader(group, first = gi == 0)
                            }
                            items(group.files, key = { it.path.toString() }) { file ->
                                DupRow(
                                    file = file,
                                    multiRoot = searchRoots.size > 1,
                                    checked = file.path in marked,
                                    onCheck = { on -> toggle(group, file, on) },
                                    onReveal = {
                                        onReveal(file.path)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Низ: сколько отмечено и что с этим сделать
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text(
                    text = if (markedFiles.isEmpty()) "Отметьте лишние копии флажками или выберите, какие оставить"
                    else "Отмечено: ${Plural.files(markedFiles.size)} · ${humanSize(markedBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (markedFiles.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ToolAction("В корзину", Icons.Default.Delete, enabled = markedFiles.isNotEmpty() && progress == null && !trashing, danger = true) {
                    confirm = true
                }
            }
        }

        if (confirm) {
            val paths = markedFiles.map { it.path }
            ConfirmDialog(
                title = "В корзину",
                message = "${Plural.files(paths.size)} · ${humanSize(markedBytes)}.\n" +
                    "В каждой группе остаётся по копии. Место освободится, когда корзину очистят.",
                confirmText = "В корзину",
                isDestructive = true,
                onConfirm = {
                    confirm = false
                    if (trashing) return@ConfirmDialog
                    val plan = groups.map { g -> g to g.files.filter { it.path in marked } }.filter { it.second.isNotEmpty() }
                    val keep = marked.keys.toSet()
                    trashing = true
                    scope.launch {
                        // Перед удалением сверяемся с диском: оставляемая копия на месте и прежняя,
                        // а удаляемые не менялись после поиска. Иначе такие файлы не трогаем
                        val (ok, skipped) = withContext(Dispatchers.IO) {
                            val ok = ArrayList<Path>()
                            var skipped = 0
                            for ((g, marks) in plan) {
                                val keeperAlive = g.files.any { it.path !in keep && unchanged(it) }
                                for (f in marks) {
                                    if (keeperAlive && unchanged(f)) ok.add(f.path) else skipped++
                                }
                            }
                            ok to skipped
                        }
                        if (skipped > 0) toast.info("Не тронуто: ${Plural.files(skipped)} — изменились после поиска")
                        if (ok.isEmpty()) {
                            trashing = false
                            return@launch
                        }
                        onTrash(ok) { done, error ->
                            trashing = false
                            done.forEach {
                                gone[it] = Unit
                                marked.remove(it)
                            }
                            DuplicatesSession.drop(done)
                            if (done.isNotEmpty() && skipped == 0) toast.success("В корзине: ${Plural.files(done.size)}")
                            if (error != null) toast.error("В корзину не получилось: $error")
                        }
                    }
                },
                onDismiss = { confirm = false }
            )
        }
    }
}

@Composable
private fun KeepChip(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun SearchProgress(p: DupProgress, onStop: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).width(460.dp)
        ) {
            val fraction = if (p.bytesTotal > 0) (p.bytesDone.toDouble() / p.bytesTotal).toFloat().coerceIn(0f, 1f) else null
            if (fraction == null) {
                WexSpinner(color = NoctaliaPrimary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    p.bytesTotal > 0 -> "${p.stage}: ${humanSize(p.bytesDone)} из ${humanSize(p.bytesTotal)}"
                    else -> "${p.stage}: ${grouped(p.files)} ${filesWord(p.files)}"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (fraction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    color = NoctaliaPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = shortTail(p.current, 70),
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
private fun GroupHeader(group: DupGroup, first: Boolean) {
    val name = group.files[0].path.fileName.toString()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(categoryColor(DuCategory.of(name)).copy(alpha = 0.8f))
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Имя и число копий занимают всё до «лишнего», длинное имя сокращается
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${group.files.size} ${Plural.ru(group.files.size.toLong(), "копия", "копии", "копий")} · по ${humanSize(group.size)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "лишнего ${humanSize(group.wasted)}",
            fontSize = 11.sp,
            color = NoctaliaPrimary.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

@Composable
private fun DupRow(file: DupFile, multiRoot: Boolean, checked: Boolean, onCheck: (Boolean) -> Unit, onReveal: () -> Unit) {
    val rel = runCatching { file.root.relativize(file.path) }.getOrNull()
    val dir = buildString {
        if (multiRoot) append(file.root.fileName ?: file.root).append('/')
        rel?.parent?.let { append(it.toString()).append('/') }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) NoctaliaPrimary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onCheck(!checked) }
            .padding(start = 12.dp, end = 6.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheck,
            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
            modifier = Modifier.size(22.dp).noFocus()
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append(dir) }
                withStyle(SpanStyle(color = if (checked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface)) {
                    append(file.path.fileName.toString())
                }
            },
            fontSize = 12.sp,
            fontFamily = AppFonts.JetBrainsMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = DATE.format(Date(file.modified)),
            fontSize = 11.sp,
            fontFamily = AppFonts.JetBrainsMono,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.width(120.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onReveal)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Показать в панели", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
        }
    }
}

/** Файл такой же, как при поиске: на месте, обычный, тот же размер и время изменения. */
private fun unchanged(f: DupFile): Boolean = runCatching {
    val a = java.nio.file.Files.readAttributes(
        f.path,
        java.nio.file.attribute.BasicFileAttributes::class.java,
        java.nio.file.LinkOption.NOFOLLOW_LINKS
    )
    a.isRegularFile && a.size() == f.size && a.lastModifiedTime().toMillis() == f.modified
}.getOrDefault(false)

private fun grouped(n: Long): String = String.format(Locale.forLanguageTag("ru"), "%,d", n)

private fun filesWord(n: Long): String = Plural.ru(n, "файл", "файла", "файлов")

private fun shortTail(path: String, max: Int): String = if (path.length <= max) path else "…" + path.takeLast(max - 1)

private fun agoText(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        else -> "${min / 60} ч назад"
    }
}

private val DATE = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
