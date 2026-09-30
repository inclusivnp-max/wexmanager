package org.wexclient.app.ui

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

enum class DiffLineType {
    SAME, ADDED, REMOVED
}

data class DiffRow(
    val type: DiffLineType,
    val lineNumOld: Int?,
    val lineNumNew: Int?,
    val textOld: String?,
    val textNew: String?
)

@Composable
fun FileDiffDialog(
    fileA: VfsEntry,
    fileB: VfsEntry,
    vfsManager: VfsManager,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var diffRows by remember { mutableStateOf<List<DiffRow>>(emptyList()) }
    var addCount by remember { mutableStateOf(0) }
    var delCount by remember { mutableStateOf(0) }
    var sameCount by remember { mutableStateOf(0) }
    var isSideBySide by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var diffError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fileA.path, fileB.path) {
        isLoading = true
        diffError = null
        val job = coroutineContext[kotlinx.coroutines.Job]
        withContext(Dispatchers.IO) {
            try {
                // Кодировка — как в редакторе (UTF-8, CP1251, UTF-16 с BOM…); огромные файлы не сравниваем
                fun read(entry: VfsEntry): List<String> {
                    val size = runCatching { vfsManager.size(entry.path) }.getOrDefault(entry.size)
                    if (size > 32L shl 20) throw java.io.IOException("«${entry.name}» больше 32 МБ")
                    val lines = org.wexclient.app.editor.TextCodec.decode(vfsManager.readBytes(entry.path)).text.lines()
                    // Перевод строки в конце файла — не отдельная пустая строка
                    return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
                }
                val result = DiffEngine.computeDiff(read(fileA), read(fileB)) { job?.isActive == false }
                diffRows = result
                addCount = result.count { it.type == DiffLineType.ADDED }
                delCount = result.count { it.type == DiffLineType.REMOVED }
                sameCount = result.count { it.type == DiffLineType.SAME }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                diffError = "Файлы слишком большие для сравнения"
            } catch (e: Exception) {
                diffError = "Не удалось сравнить: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isLoading = false
            }
        }
    }

    // Начала блоков различий: переход идёт от блока к блоку, а не по строкам одного блока.
    // Состояние, а не значение: обработчик клавиш окна живёт со времён первой композиции
    val blockStarts by remember {
        androidx.compose.runtime.derivedStateOf {
            val rows = diffRows
            rows.indices.filter { rows[it].type != DiffLineType.SAME && (it == 0 || rows[it - 1].type == DiffLineType.SAME) }
        }
    }
    // Над различием оставляем пару строк контекста
    fun anchorOf(start: Int) = (start - 3).coerceAtLeast(0)
    fun jumpToDiff(forward: Boolean) {
        val top = listState.firstVisibleItemIndex
        val target = if (forward) blockStarts.firstOrNull { anchorOf(it) > top } else blockStarts.lastOrNull { anchorOf(it) < top }
        if (target != null) scope.launch { listState.animateScrollToItem(anchorOf(target)) }
    }
    fun scrollBy(rows: Int) {
        val last = (diffRows.size - 1).coerceAtLeast(0)
        scope.launch { listState.scrollToItem((listState.firstVisibleItemIndex + rows).coerceIn(0, last)) }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(1150.dp, 800.dp)),
        title = "Сравнение файлов — ${fileA.name} ⟷ ${fileB.name}",
        onPreviewKeyEvent = latestKeyHandler { e ->
            if (e.type != KeyEventType.KeyDown) return@latestKeyHandler false
            val page = (listState.layoutInfo.visibleItemsInfo.size - 2).coerceAtLeast(1)
            when {
                e.key == Key.Escape -> { onDismiss(); true }
                // Alt+↓ / F7 — следующее различие, Alt+↑ / Shift+F7 — предыдущее
                e.key == Key.F7 -> { jumpToDiff(!e.isShiftPressed); true }
                e.isAltPressed && e.key == Key.DirectionDown -> { jumpToDiff(true); true }
                e.isAltPressed && e.key == Key.DirectionUp -> { jumpToDiff(false); true }
                e.key == Key.DirectionDown -> { scrollBy(1); true }
                e.key == Key.DirectionUp -> { scrollBy(-1); true }
                e.key == Key.PageDown -> { scrollBy(page); true }
                e.key == Key.PageUp -> { scrollBy(-page); true }
                e.key == Key.MoveHome -> { scrollBy(-diffRows.size); true }
                e.key == Key.MoveEnd -> { scrollBy(diffRows.size); true }
                else -> false
            }
        }
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.CompareArrows,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "${fileA.name} ⟷ ${fileB.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "+$addCount добавлено",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF81C784)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "-$delCount удалено",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE57373)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$sameCount совпадает",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Mode selector: Side by side vs Unified
                                Surface(
                                    onClick = { isSideBySide = !isSideBySide },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = if (isSideBySide) "Режим: Бок о бок" else "Режим: Единый",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }

                                IconButton(onClick = { jumpToDiff(false) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Предыдущее различие (Alt+↑)", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { jumpToDiff(true) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Следующее различие (Alt+↓)", tint = MaterialTheme.colorScheme.primary)
                                }

                                // Copy patch
                                IconButton(
                                    onClick = {
                                        val patch = DiffEngine.buildUnifiedDiff(fileA.name, fileB.name, diffRows)
                                        val sel = StringSelection(patch)
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать diff патч", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                                }
                            }
                        }
                    }

                    // Column Headers (for Side-by-Side)
                    if (isSideBySide) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Левый файл", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Text(fileA.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("(${fileA.formattedSize})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                VerticalDivider(modifier = Modifier.height(16.dp))
                                Row(modifier = Modifier.weight(1f).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Правый файл", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                    Text(fileB.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("(${fileB.formattedSize})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }

                    // Diff Lines List
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            WexSpinner(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (diffError != null) {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(diffError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(diffRows) { index, row ->
                                val rowBg = when (row.type) {
                                    DiffLineType.ADDED -> Color(0xFF1B5E20).copy(alpha = 0.18f)
                                    DiffLineType.REMOVED -> Color(0xFFB71C1C).copy(alpha = 0.18f)
                                    DiffLineType.SAME -> Color.Transparent
                                }

                                if (isSideBySide) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(rowBg)
                                            .padding(vertical = 1.dp)
                                    ) {
                                        // Left side (Old file)
                                        Row(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(
                                                text = row.lineNumOld?.toString() ?: "",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(36.dp)
                                            )
                                            val leftMarker = if (row.type == DiffLineType.REMOVED) "-" else " "
                                            Text(
                                                text = leftMarker,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                                color = if (row.type == DiffLineType.REMOVED) Color(0xFFEF5350) else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(14.dp)
                                            )
                                            Text(
                                                text = row.textOld ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                                color = if (row.type == DiffLineType.REMOVED) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        VerticalDivider(modifier = Modifier.height(18.dp))

                                        // Right side (New file)
                                        Row(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(
                                                text = row.lineNumNew?.toString() ?: "",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(36.dp)
                                            )
                                            val rightMarker = if (row.type == DiffLineType.ADDED) "+" else " "
                                            Text(
                                                text = rightMarker,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                                color = if (row.type == DiffLineType.ADDED) Color(0xFF66BB6A) else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(14.dp)
                                            )
                                            Text(
                                                text = row.textNew ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                                color = if (row.type == DiffLineType.ADDED) Color(0xFFC8E6C9) else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                } else {
                                    // Unified Mode
                                    val marker = when (row.type) {
                                        DiffLineType.ADDED -> "+"
                                        DiffLineType.REMOVED -> "-"
                                        DiffLineType.SAME -> " "
                                    }
                                    val lineContent = if (row.type == DiffLineType.REMOVED) row.textOld else row.textNew
                                    val textColor = when (row.type) {
                                        DiffLineType.ADDED -> Color(0xFFC8E6C9)
                                        DiffLineType.REMOVED -> Color(0xFFFFCDD2)
                                        DiffLineType.SAME -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(rowBg)
                                            .padding(horizontal = 12.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = marker,
                                            // Раньше маркер шёл шрифтом 16sp и раздувал каждую строку
                                            fontSize = 12.sp,
                                            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                            fontWeight = FontWeight.Bold,
                                            color = when (row.type) {
                                                DiffLineType.ADDED -> Color(0xFF66BB6A)
                                                DiffLineType.REMOVED -> Color(0xFFEF5350)
                                                DiffLineType.SAME -> MaterialTheme.colorScheme.outline
                                            },
                                            modifier = Modifier.width(18.dp)
                                        )
                                        Text(
                                            text = "${row.lineNumOld ?: row.lineNumNew ?: ""}",
                                            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.width(42.dp)
                                        )
                                        Text(
                                            text = lineContent ?: "",
                                            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = textColor
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

object DiffEngine {
    /** Точная динамика с таблицей — только на небольших участках: m×n на больших файлах не влезала в память. */
    private const val DP_CELLS = 4_000_000L

    /** Дальше таблицы — Хиршберг (линейная память, время m×n); ещё больше — целыми блоками. */
    private const val LINEAR_CELLS = 400_000_000L

    fun computeDiff(linesA: List<String>, linesB: List<String>, cancelled: () -> Boolean = { false }): List<DiffRow> {
        // Строки → номера: дальше сравниваются числа
        val ids = HashMap<String, Int>()
        val a = IntArray(linesA.size) { ids.getOrPut(linesA[it]) { ids.size } }
        val b = IntArray(linesB.size) { ids.getOrPut(linesB[it]) { ids.size } }
        val rows = ArrayList<DiffRow>(maxOf(a.size, b.size))
        fun checkCancel() {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Сравнение отменено")
        }
        fun same(i: Int, j: Int) = rows.add(DiffRow(DiffLineType.SAME, i + 1, j + 1, linesA[i], linesB[j]))
        fun removed(i: Int) = rows.add(DiffRow(DiffLineType.REMOVED, i + 1, null, linesA[i], null))
        fun added(j: Int) = rows.add(DiffRow(DiffLineType.ADDED, null, j + 1, null, linesB[j]))

        fun table(a0: Int, a1: Int, b0: Int, b1: Int) {
            val m = a1 - a0
            val n = b1 - b0
            val w = n + 1
            val t = IntArray((m + 1) * w)
            for (i in m - 1 downTo 0) {
                checkCancel()
                val x = a[a0 + i]
                for (j in n - 1 downTo 0) {
                    t[i * w + j] = if (x == b[b0 + j]) t[(i + 1) * w + j + 1] + 1
                    else maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < m || j < n) {
                when {
                    i < m && j < n && a[a0 + i] == b[b0 + j] -> { same(a0 + i, b0 + j); i++; j++ }
                    // Сначала удалённое, потом добавленное — как в unified diff
                    i < m && (j == n || t[(i + 1) * w + j] >= t[i * w + j + 1]) -> { removed(a0 + i); i++ }
                    else -> { added(b0 + j); j++ }
                }
            }
        }

        /** Одна строка против многих: достаточно найти её первое вхождение. */
        fun single(a0: Int, a1: Int, b0: Int, b1: Int) {
            if (a1 - a0 == 1) {
                val hit = (b0 until b1).firstOrNull { b[it] == a[a0] }
                if (hit == null) { removed(a0); for (j in b0 until b1) added(j); return }
                for (j in b0 until hit) added(j)
                same(a0, hit)
                for (j in hit + 1 until b1) added(j)
            } else {
                val hit = (a0 until a1).firstOrNull { a[it] == b[b0] }
                if (hit == null) { for (i in a0 until a1) removed(i); added(b0); return }
                for (i in a0 until hit) removed(i)
                same(hit, b0)
                for (i in hit + 1 until a1) removed(i)
            }
        }

        /** Строки, встречающиеся ровно по разу в обоих участках, в общем порядке (patience diff). */
        fun anchors(a0: Int, a1: Int, b0: Int, b1: Int): List<Long> {
            val countA = HashMap<Int, Int>()
            for (i in a0 until a1) countA.merge(a[i], 1, Int::plus)
            val countB = HashMap<Int, Int>()
            val posB = HashMap<Int, Int>()
            for (j in b0 until b1) { countB.merge(b[j], 1, Int::plus); posB[b[j]] = j }
            val pi = ArrayList<Int>()
            val pj = ArrayList<Int>()
            for (i in a0 until a1) {
                val v = a[i]
                if (countA[v] == 1 && countB[v] == 1) { pi.add(i); pj.add(posB[v]!!) }
            }
            // Наибольшая возрастающая подпоследовательность по позиции в B
            val tails = IntArray(pi.size)
            val prev = IntArray(pi.size)
            var len = 0
            for (k in pi.indices) {
                var lo = 0
                var hi = len
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (pj[tails[mid]] < pj[k]) lo = mid + 1 else hi = mid
                }
                prev[k] = if (lo > 0) tails[lo - 1] else -1
                tails[lo] = k
                if (lo == len) len++
            }
            val out = ArrayList<Long>(len)
            var k = if (len > 0) tails[len - 1] else -1
            while (k >= 0) { out.add((pi[k].toLong() shl 32) or pj[k].toLong()); k = prev[k] }
            out.reverse()
            return out
        }

        /** Где делить B, если A делится пополам (Хиршберг): две строки таблицы вместо всей. */
        fun splitPoint(a0: Int, mid: Int, a1: Int, b0: Int, b1: Int): Int {
            val n = b1 - b0
            var prev = IntArray(n + 1)
            var cur = IntArray(n + 1)
            for (i in a0 until mid) {
                checkCancel()
                val x = a[i]
                cur[0] = 0
                for (j in 1..n) cur[j] = if (x == b[b0 + j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
                val tmp = prev; prev = cur; cur = tmp
            }
            val forward = prev
            prev = IntArray(n + 1)
            cur = IntArray(n + 1)
            for (i in a1 - 1 downTo mid) {
                checkCancel()
                val x = a[i]
                cur[n] = 0
                for (j in n - 1 downTo 0) cur[j] = if (x == b[b0 + j]) prev[j + 1] + 1 else maxOf(prev[j], cur[j + 1])
                val tmp = prev; prev = cur; cur = tmp
            }
            var best = 0
            for (j in 1..n) if (forward[j] + prev[j] > forward[best] + prev[best]) best = j
            return best
        }

        // Явный стек вместо рекурсии: вложенность на больших файлах не ограничена.
        // Задача: RANGE(a0, a1, b0, b1) или SAME_RUN(i, j, count).
        val stack = ArrayList<IntArray>()
        val RANGE = 0
        val SAME_RUN = 1
        stack.add(intArrayOf(RANGE, 0, a.size, 0, b.size))
        while (stack.isNotEmpty()) {
            val task = stack.removeAt(stack.size - 1)
            if (task[0] == SAME_RUN) {
                for (k in 0 until task[3]) same(task[1] + k, task[2] + k)
                continue
            }
            checkCancel()
            var a0 = task[1]
            var a1 = task[2]
            var b0 = task[3]
            var b1 = task[4]
            while (a0 < a1 && b0 < b1 && a[a0] == b[b0]) { same(a0, b0); a0++; b0++ }
            var tail = 0
            while (a1 > a0 && b1 > b0 && a[a1 - 1] == b[b1 - 1]) { a1--; b1--; tail++ }
            if (tail > 0) stack.add(intArrayOf(SAME_RUN, a1, b1, tail))
            val m = a1 - a0
            val n = b1 - b0
            val cells = m.toLong() * n
            when {
                m == 0 -> for (j in b0 until b1) added(j)
                n == 0 -> for (i in a0 until a1) removed(i)
                m == 1 || n == 1 -> single(a0, a1, b0, b1)
                cells <= DP_CELLS -> table(a0, a1, b0, b1)
                else -> {
                    val anchored = anchors(a0, a1, b0, b1)
                    if (anchored.isNotEmpty()) {
                        // В стек в обратном порядке: сверху окажется первый участок
                        stack.add(intArrayOf(RANGE, (anchored.last() ushr 32).toInt() + 1, a1, anchored.last().toInt() + 1, b1))
                        for (k in anchored.indices.reversed()) {
                            val i = (anchored[k] ushr 32).toInt()
                            val j = anchored[k].toInt()
                            stack.add(intArrayOf(SAME_RUN, i, j, 1))
                            val pi = if (k == 0) a0 else (anchored[k - 1] ushr 32).toInt() + 1
                            val pj = if (k == 0) b0 else anchored[k - 1].toInt() + 1
                            stack.add(intArrayOf(RANGE, pi, i, pj, j))
                        }
                    } else if (cells <= LINEAR_CELLS) {
                        val mid = (a0 + a1) ushr 1
                        val k = b0 + splitPoint(a0, mid, a1, b0, b1)
                        stack.add(intArrayOf(RANGE, mid, a1, k, b1))
                        stack.add(intArrayOf(RANGE, a0, mid, b0, k))
                    } else {
                        for (i in a0 until a1) removed(i)
                        for (j in b0 until b1) added(j)
                    }
                }
            }
        }
        return rows
    }

    fun buildUnifiedDiff(nameA: String, nameB: String, rows: List<DiffRow>): String {
        val sb = StringBuilder()
        sb.append("--- $nameA\n")
        sb.append("+++ $nameB\n")
        for (r in rows) {
            when (r.type) {
                DiffLineType.SAME -> sb.append(" ${r.textOld}\n")
                DiffLineType.ADDED -> sb.append("+${r.textNew}\n")
                DiffLineType.REMOVED -> sb.append("-${r.textOld}\n")
            }
        }
        return sb.toString()
    }
}
