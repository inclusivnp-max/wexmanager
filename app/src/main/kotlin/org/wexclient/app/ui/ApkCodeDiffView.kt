package org.wexclient.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.apk.ApkDiff
import org.wexclient.app.apk.DexClassChange
import org.wexclient.app.apk.DexCompareOptions
import org.wexclient.app.apk.DexDiff
import org.wexclient.app.apk.DiffKind
import org.wexclient.app.apk.SmaliNormalizer
import org.wexclient.app.dex.DexEditorEngine
import org.wexclient.app.dex.DexSource
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaWarn
import java.nio.file.Path

/** Узел дерева пакетов: вложенные пакеты и классы этого уровня. */
private class PackageNode(val name: String, val path: String) {
    val children = LinkedHashMap<String, PackageNode>()
    val classes = ArrayList<DexClassChange>()
    var added = 0
    var changed = 0
    var removed = 0
}

/** Строка плоского списка, в который разворачивается дерево. */
private sealed interface TreeRow {
    val depth: Int

    data class Package(val node: PackageNode, override val depth: Int) : TreeRow
    data class Klass(val change: DexClassChange, override val depth: Int) : TreeRow
}

/**
 * Код двух APK: слева дерево пакетов с изменившимися классами, справа — сравнение smali.
 * Совпавшие классы в дереве не показываются: в живом приложении их сотни тысяч.
 */
@Composable
internal fun ApkCodeDiffView(
    diff: DexDiff,
    options: DexCompareOptions,
    oldApk: Path,
    newApk: Path,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DexClassChange?>(null) }
    var showStats by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val root = remember(diff) { buildTree(diff.classes) }
    val rows = remember(root, expanded.toMap(), query) {
        if (query.isBlank()) flatten(root, expanded) else emptyList()
    }
    val found = remember(diff, query) {
        if (query.isBlank()) emptyList()
        else diff.classes.filter { it.name.contains(query, ignoreCase = true) }.take(2000)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Ctrl+колесо меняет размер шрифта дерева и сравнения; значение сохраняется
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                        val keys = event.keyboardModifiers
                        if (!keys.isCtrlPressed && !keys.isMetaPressed) continue
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy == 0f) continue
                        event.changes.forEach { it.consume() }
                        ViewPreferences.zoomCode(if (dy < 0) 1f else -1f)
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Классов: ${diff.oldClasses} → ${diff.newClasses} · методов: ${diff.oldMethods} → ${diff.newMethods}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = NoctaliaPrimary.copy(alpha = 0.14f),
                modifier = Modifier.clickable { showStats = true }
            ) {
                Text(
                    text = "Статистика",
                    fontSize = 11.sp,
                    color = NoctaliaPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            GrailSearchField(query, { query = it }, "Поиск класса", modifier = Modifier.width(260.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.width(430.dp).fillMaxHeight()
            ) {
                if (query.isNotBlank()) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        items(found) { change ->
                            ClassRow(change, depth = 0, selected = selected?.name == change.name) { selected = change }
                        }
                        if (found.isEmpty()) {
                            item { GrailEmptyState("Ничего не нашлось") }
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        items(rows, key = { row ->
                            when (row) {
                                is TreeRow.Package -> "p:" + row.node.path
                                is TreeRow.Klass -> "c:" + row.change.name
                            }
                        }) { row ->
                            when (row) {
                                is TreeRow.Package -> PackageRow(
                                    node = row.node,
                                    depth = row.depth,
                                    open = expanded[row.node.path] == true
                                ) { expanded[row.node.path] = expanded[row.node.path] != true }
                                is TreeRow.Klass -> ClassRow(
                                    change = row.change,
                                    depth = row.depth,
                                    selected = selected?.name == row.change.name
                                ) { selected = row.change }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            val chosen = selected
            if (chosen == null) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Выберите класс слева — покажу, чем он отличается",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                SmaliDiffPane(
                    change = chosen,
                    options = options,
                    oldApk = oldApk,
                    newApk = newApk,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }

    if (showStats) {
        StatsDialog(diff) { showStats = false }
    }
}

// ------------------------------------------------------------------ дерево

private fun buildTree(changes: List<DexClassChange>): PackageNode {
    val root = PackageNode("", "")
    for (change in changes) {
        val parts = change.name.split('.')
        val packageParts = if (parts.size > 1) parts.dropLast(1) else emptyList()
        var node = root
        var path = ""
        for (part in packageParts) {
            path = if (path.isEmpty()) part else "$path.$part"
            node = node.children.getOrPut(part) { PackageNode(part, path) }
            node.count(change.kind)
        }
        node.classes += change
        if (node === root) root.count(change.kind)
    }
    sortTree(root)
    return root
}

private fun PackageNode.count(kind: DiffKind) {
    when (kind) {
        DiffKind.ADDED -> added++
        DiffKind.REMOVED -> removed++
        DiffKind.CHANGED -> changed++
        DiffKind.SAME -> Unit
    }
}

private fun sortTree(node: PackageNode) {
    node.classes.sortBy { it.name }
    val sorted = node.children.entries.sortedBy { it.key }
    val copy = LinkedHashMap(node.children)
    node.children.clear()
    sorted.forEach { node.children[it.key] = copy[it.key]!! }
    node.children.values.forEach { sortTree(it) }
}

/** Дерево в плоский список: видны только раскрытые ветки. */
private fun flatten(root: PackageNode, expanded: Map<String, Boolean>): List<TreeRow> {
    val rows = ArrayList<TreeRow>()
    fun walk(node: PackageNode, depth: Int) {
        for (child in node.children.values) {
            rows += TreeRow.Package(child, depth)
            if (expanded[child.path] == true) walk(child, depth + 1)
        }
        for (cls in node.classes) rows += TreeRow.Klass(cls, depth)
    }
    walk(root, 0)
    return rows
}

@Composable
private fun PackageRow(node: PackageNode, depth: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp)
    ) {
        Icon(
            if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp)
        )
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size((ViewPreferences.codeFontSize + 2f).dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = node.name,
            fontSize = (ViewPreferences.codeFontSize + 1.5f).sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val marks = buildList {
            if (node.changed > 0) add(node.changed to NoctaliaWarn.copy(alpha = 0.62f))
            if (node.added > 0) add(node.added to NoctaliaOk.copy(alpha = 0.62f))
            if (node.removed > 0) add(node.removed to NoctaliaDanger.copy(alpha = 0.62f))
        }
        marks.forEach { (count, color) ->
            Text(
                text = count.toString(),
                fontSize = ViewPreferences.codeFontSize.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

@Composable
private fun ClassRow(change: DexClassChange, depth: Int, selected: Boolean, onClick: () -> Unit) {
    val simple = change.name.substringAfterLast('.')
    val color = when (change.kind) {
        DiffKind.ADDED -> NoctaliaOk.copy(alpha = 0.78f)
        DiffKind.REMOVED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) NoctaliaPrimary.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(start = (depth * 14 + 14).dp, top = 2.dp, bottom = 2.dp, end = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    when (change.kind) {
                        DiffKind.ADDED -> NoctaliaOk.copy(alpha = 0.6f)
                        DiffKind.REMOVED -> NoctaliaDanger.copy(alpha = 0.55f)
                        else -> NoctaliaWarn.copy(alpha = 0.6f)
                    },
                    androidx.compose.foundation.shape.CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = simple,
            fontSize = (ViewPreferences.codeFontSize - 0.5f).sp,
            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
            color = color,
            // Пропавший класс перечёркнут — как в MT Manager
            textDecoration = if (change.kind == DiffKind.REMOVED) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (change.kind == DiffKind.CHANGED) {
            val marks = buildList {
                if (change.addedMethods.isNotEmpty()) add("+${change.addedMethods.size}")
                if (change.removedMethods.isNotEmpty()) add("−${change.removedMethods.size}")
            }
            if (marks.isNotEmpty()) {
                Text(
                    text = marks.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun StatsDialog(diff: DexDiff, onDismiss: () -> Unit) {
    ModalDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            modifier = Modifier.width(360.dp).dialogGlass(RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Статистика",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DialogClose(onDismiss)
                }
                Spacer(modifier = Modifier.height(10.dp))
                StatLine("Изменённые классы", diff.changed, NoctaliaWarn)
                StatLine("Добавленные классы", diff.added, NoctaliaOk)
                StatLine("Удалённые классы", diff.removed, NoctaliaDanger)
                Spacer(modifier = Modifier.height(8.dp))
                StatLine("Всего классов было", diff.oldClasses, MaterialTheme.colorScheme.onSurfaceVariant)
                StatLine("Всего классов стало", diff.newClasses, MaterialTheme.colorScheme.onSurfaceVariant)
                StatLine("Методов было", diff.oldMethods, MaterialTheme.colorScheme.onSurfaceVariant)
                StatLine("Методов стало", diff.newMethods, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

// ------------------------------------------------------------------ сравнение smali

/** Пара текстов класса: как было и как стало. */
private data class SmaliPair(val old: List<String>, val new: List<String>, val error: String?)

@Composable
private fun SmaliDiffPane(
    change: DexClassChange,
    options: DexCompareOptions,
    oldApk: Path,
    newApk: Path,
    modifier: Modifier = Modifier
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()

    val pair by produceState<SmaliPair?>(null, change.name, oldApk, newApk) {
        value = null
        value = withContext(Dispatchers.IO) {
            val old = if (change.kind == DiffKind.ADDED) emptyList() else smaliOf(oldApk, change.name)
            val new = if (change.kind == DiffKind.REMOVED) emptyList() else smaliOf(newApk, change.name)
            SmaliPair(
                old = old ?: emptyList(),
                new = new ?: emptyList(),
                error = when {
                    old == null && change.kind != DiffKind.ADDED -> "Не удалось разобрать класс в старом пакете"
                    new == null && change.kind != DiffKind.REMOVED -> "Не удалось разобрать класс в новом пакете"
                    else -> null
                }
            )
        }
    }

    val data = pair
    val rows = remember(data, options) {
        if (data == null) emptyList()
        else DiffEngine.computeDiff(
            data.old.map { SmaliNormalizer.line(it, options) ?: "" },
            data.new.map { SmaliNormalizer.line(it, options) ?: "" }
        )
    }
    // Начала блоков различий: по ним ходят стрелки «‹ ›»
    val blocks = remember(rows) {
        buildList {
            rows.forEachIndexed { index, row ->
                val prevSame = index == 0 || rows[index - 1].type == DiffLineType.SAME
                if (row.type != DiffLineType.SAME && prevSame) add(index)
            }
        }
    }
    var block by remember(rows) { mutableStateOf(0) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = change.name,
                    fontSize = 12.sp,
                    fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                    color = NoctaliaPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (blocks.isNotEmpty()) {
                    Text(
                        text = "${(block + 1).coerceAtMost(blocks.size)}/${blocks.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    IconButton(
                        onClick = {
                            block = (block - 1 + blocks.size) % blocks.size
                            scope.launch { listState.scrollToItem(blocks[block]) }
                        },
                        modifier = Modifier.size(24.dp).noFocus()
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Предыдущее отличие", tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = {
                            block = (block + 1) % blocks.size
                            scope.launch { listState.scrollToItem(blocks[block]) }
                        },
                        modifier = Modifier.size(24.dp).noFocus()
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Следующее отличие", tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text("Старый файл", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                Text("Новый файл", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
            }

            when {
                data == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WexSpinner(color = NoctaliaPrimary)
                }
                data.error != null -> GrailEmptyState(data.error!!)
                rows.isEmpty() -> GrailEmptyState("Различий в коде класса нет — изменились только объявления")
                else -> {
                    val hScroll = rememberScrollState()
                    // Ширина самой длинной строки: одна на все строки, иначе прокрутка не знает,
                    // докуда ехать, и полоса остаётся пустой
                    val longest = remember(data) {
                        maxOf(
                            data.old.maxOfOrNull { it.length } ?: 0,
                            data.new.maxOfOrNull { it.length } ?: 0
                        )
                    }
                    val contentWidth = ((longest + 2) * (ViewPreferences.codeFontSize - 1f) * 0.62f).dp
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                            itemsIndexed(rows) { _, row ->
                                SmaliDiffRow(row, data, hScroll, contentWidth)
                            }
                        }
                        // Строки smali длинные: без полосы не видно, что они уезжают вправо
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 2.dp, vertical = 2.dp)
                        ) {
                            androidx.compose.foundation.HorizontalScrollbar(
                                adapter = androidx.compose.foundation.rememberScrollbarAdapter(hScroll),
                                modifier = Modifier.fillMaxWidth().height(9.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmaliDiffRow(
    row: DiffRow,
    data: SmaliPair,
    hScroll: androidx.compose.foundation.ScrollState,
    contentWidth: androidx.compose.ui.unit.Dp
) {
    val oldText = row.lineNumOld?.let { data.old.getOrNull(it - 1) }
    val newText = row.lineNumNew?.let { data.new.getOrNull(it - 1) }
    val oldBackground = if (row.type == DiffLineType.REMOVED) NoctaliaDanger.copy(alpha = 0.10f) else Color.Transparent
    val newBackground = if (row.type == DiffLineType.ADDED) NoctaliaOk.copy(alpha = 0.10f) else Color.Transparent

    Row(modifier = Modifier.fillMaxWidth().height((ViewPreferences.codeFontSize + 6f).dp)) {
        SmaliHalf(row.lineNumOld, oldText, oldBackground, hScroll, contentWidth, Modifier.weight(1f))
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)))
        SmaliHalf(row.lineNumNew, newText, newBackground, hScroll, contentWidth, Modifier.weight(1f))
    }
}

@Composable
private fun SmaliHalf(
    number: Int?,
    text: String?,
    background: Color,
    hScroll: androidx.compose.foundation.ScrollState,
    contentWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxHeight().background(background), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = number?.toString().orEmpty(),
            fontSize = (ViewPreferences.codeFontSize - 2f).sp,
            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(46.dp).padding(end = 6.dp)
        )
        val line = text.orEmpty()
        Text(
            text = remember(line) { highlightSmali(line) },
            fontSize = (ViewPreferences.codeFontSize - 1f).sp,
            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            // Ширина у всех строк одна — тогда общая полоса прокрутки ходит по самой длинной
            modifier = Modifier.horizontalScroll(hScroll).width(contentWidth)
        )
    }
}

/**
 * Smali класса из APK. Перебираем dex по одному и разбираем только тот, где класс нашёлся —
 * держать в памяти весь код пакета ради одного класса не нужно.
 */
private fun smaliOf(apk: Path, className: String): List<String>? {
    val entries = ApkDiff.entries(apk)
    for (name in ApkDiff.dexNames(entries)) {
        val bytes = ApkDiff.read(apk, name, limit = 128 * 1024 * 1024) ?: continue
        val engine = runCatching { DexEditorEngine(listOf(DexSource(name, bytes))) }.getOrNull() ?: continue
        try {
            val node = engine.classes.firstOrNull { it.fullName == className } ?: continue
            val text = runCatching { engine.disassembleToSmali(node) }.getOrNull() ?: return null
            return text.lines()
        } finally {
            runCatching { engine.close() }
        }
    }
    return null
}

/**
 * Раскраска строки smali тем же лексером, что и в редакторе: директивы, опкоды,
 * регистры, типы и строки видно с первого взгляда.
 */
private fun highlightSmali(line: String): androidx.compose.ui.text.AnnotatedString {
    if (line.isBlank()) return androidx.compose.ui.text.AnnotatedString(line)
    val tokens = ArrayList<org.wexclient.app.editor.lang.Token>()
    runCatching {
        org.wexclient.app.editor.lang.Languages.SMALI.lexer.tokenize(line, 0, tokens)
    }.onFailure { return androidx.compose.ui.text.AnnotatedString(line) }
    return androidx.compose.ui.text.buildAnnotatedString {
        append(line)
        for (token in tokens) {
            val style = org.wexclient.app.editor.lang.SyntaxColors.style(token.type) ?: continue
            val start = token.start.coerceIn(0, line.length)
            val end = token.end.coerceIn(start, line.length)
            if (end > start) addStyle(style, start, end)
        }
    }
}
