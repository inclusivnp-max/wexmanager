package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.vfs.FolderDiff
import org.wexclient.app.vfs.FolderDiffEntry
import org.wexclient.app.vfs.FolderDiffKind
import org.wexclient.app.vfs.FolderDiffOptions
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Отбор строк в таблице сравнения. */
private enum class FolderFilter(val title: String) {
    DIFFERENT("Различия"),
    LEFT_ONLY("Только слева"),
    RIGHT_ONLY("Только справа"),
    NEWER("Новее"),
    ALL("Все")
}

/**
 * Сравнение и синхронизация папок двух панелей. Копирование идёт обычным путём файлового
 * менеджера: с прогрессом, а заменённые файлы уходят в корзину — ничего не теряется.
 */
@Composable
fun FolderCompareDialog(
    left: Path,
    right: Path,
    /** Скопировать пути в папку; replace — заменять совпадающие имена, onDone — по окончании. */
    onCopy: (sources: List<Path>, targetDir: Path, replace: Boolean, onDone: () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var options by remember { mutableStateOf(FolderDiffOptions()) }
    var filter by remember { mutableStateOf(FolderFilter.DIFFERENT) }
    var entries by remember { mutableStateOf<List<FolderDiffEntry>?>(null) }
    var progress by remember { mutableStateOf("") }
    var runs by remember { mutableStateOf(0) }
    // Отмеченные пути — множеством (ключи карты): проверка «отмечено ли» идёт на каждую строку
    // и была по списку, O(N·C) на каждый щелчок флажка
    val checked = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(options, runs) {
        entries = null
        checked.clear()
        entries = withContext(Dispatchers.IO) {
            // Поменяли настройку или закрыли окно — прежний обход обоих деревьев (со сверкой содержимого —
            // чтение каждого файла) бросается, а не идёт до конца впустую
            FolderDiff.compare(left, right, options, cancelled = { !isActive }, onProgress = { progress = it })
        }
    }

    val list = entries
    val shown = remember(list, filter) {
        list.orEmpty().filter { entry ->
            when (filter) {
                FolderFilter.DIFFERENT -> entry.kind != FolderDiffKind.SAME
                FolderFilter.LEFT_ONLY -> entry.kind == FolderDiffKind.LEFT_ONLY
                FolderFilter.RIGHT_ONLY -> entry.kind == FolderDiffKind.RIGHT_ONLY
                FolderFilter.NEWER -> entry.kind == FolderDiffKind.LEFT_NEWER || entry.kind == FolderDiffKind.RIGHT_NEWER
                FolderFilter.ALL -> true
            }
        }
    }

    // Пока идёт копирование, второй раз не запускаем: два копирования с заменой по одному списку
    // отправляли бы в корзину только что скопированное
    var copying by remember { mutableStateOf(false) }

    fun copy(selected: List<FolderDiffEntry>, toRight: Boolean) {
        if (copying) return
        val (fromRoot, toRoot) = if (toRight) left to right else right to left
        // Группируем по папке назначения: у каждой группы своё место в другой панели
        val groups = selected
            .filter { if (toRight) it.left != null else it.right != null }
            .groupBy { it.relative.substringBeforeLast('/', "") }
        if (groups.isEmpty()) return
        copying = true
        // Пересравниваем, когда закончится последняя группа — иначе увидели бы старое
        var pending = groups.size
        groups.forEach { (parent, group) ->
            val target = if (parent.isEmpty()) toRoot else toRoot.resolve(parent)
            onCopy(group.map { fromRoot.resolve(it.relative) }, target, true) {
                pending--
                if (pending == 0) {
                    copying = false
                    runs++
                }
            }
        }
    }

    val summary = list?.let { all ->
        val leftOnly = all.count { it.kind == FolderDiffKind.LEFT_ONLY }
        val rightOnly = all.count { it.kind == FolderDiffKind.RIGHT_ONLY }
        val newer = all.count { it.kind == FolderDiffKind.LEFT_NEWER || it.kind == FolderDiffKind.RIGHT_NEWER }
        val same = all.count { it.kind == FolderDiffKind.SAME }
        "только слева $leftOnly · только справа $rightOnly · новее $newer · одинаковых $same"
    } ?: "сравнение…"

    GrailDialogShell(
        title = "Сравнение папок",
        badge = "ПАПКИ",
        heading = "${left.fileName ?: left}  ⇄  ${right.fileName ?: right}",
        subtitle = summary,
        closeOnEsc = true,
        dialogWidth = 1250.dp,
        dialogHeight = 820.dp,
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Как сравнивать
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptionCheck("Подпапки", options.recursive) { options = options.copy(recursive = it) }
                OptionCheck("Сверять содержимое", options.byContent) { options = options.copy(byContent = it) }
                OptionCheck("Скрытые", options.includeHidden) { options = options.copy(includeHidden = it) }
                Spacer(modifier = Modifier.weight(1f))
                SmallAction("Заново", Icons.Default.Refresh) { runs++ }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FolderFilter.entries.forEach { item ->
                    val selected = item == filter
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) NoctaliaPrimary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, if (selected) NoctaliaPrimary.copy(alpha = 0.7f) else Color.Transparent),
                        modifier = Modifier.clickable { filter = item }
                    ) {
                        Text(
                            text = item.title,
                            fontSize = 11.sp,
                            color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Шапка таблицы: слева путь левой панели, справа — правой
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Spacer(modifier = Modifier.width(30.dp))
                Text("Имя", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(tail(left.toString()), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(230.dp))
                Spacer(modifier = Modifier.width(40.dp))
                Text(tail(right.toString()), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(230.dp))
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        WexSpinner(color = NoctaliaPrimary, modifier = Modifier.size(26.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                    }
                    shown.isEmpty() -> GrailEmptyState(
                        if (filter == FolderFilter.DIFFERENT) "Папки совпадают" else "Под этот отбор ничего не подходит"
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(shown, key = { it.relative }) { entry ->
                            FolderRow(
                                entry = entry,
                                checked = entry.relative in checked,
                                onCheck = { on -> if (on) checked[entry.relative] = true else checked.remove(entry.relative) }
                            )
                        }
                    }
                }
            }

            // Действия внизу
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                // Отмеченные строки собираются по нажатию кнопки, а не на каждой перерисовке (каждый щелчок флажка)
                fun selected() = list.orEmpty().filter { it.relative in checked }
                Text(
                    text = if (checked.isEmpty()) "Отметьте строки или синхронизируйте всё сразу" else "Отмечено: ${checked.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                if (checked.isNotEmpty()) {
                    SmallAction("Отмеченное вправо", Icons.AutoMirrored.Filled.ArrowForward, enabled = !copying) { copy(selected(), toRight = true) }
                    Spacer(modifier = Modifier.width(6.dp))
                    SmallAction("Отмеченное влево", Icons.AutoMirrored.Filled.ArrowBack, enabled = !copying) { copy(selected(), toRight = false) }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                SmallAction("Синхронизировать вправо", Icons.AutoMirrored.Filled.ArrowForward, enabled = !copying) {
                    copy(FolderDiff.plan(list.orEmpty(), toRight = true), toRight = true)
                }
                Spacer(modifier = Modifier.width(6.dp))
                SmallAction("Синхронизировать влево", Icons.AutoMirrored.Filled.ArrowBack, enabled = !copying) {
                    copy(FolderDiff.plan(list.orEmpty(), toRight = false), toRight = false)
                }
            }
        }
    }
}

@Composable
private fun FolderRow(entry: FolderDiffEntry, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val (mark, color) = kindMark(entry.kind)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheck(!checked) }
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheck,
            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
            modifier = Modifier.size(22.dp).noFocus()
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (entry.directory) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = entry.relative,
            fontSize = 12.sp,
            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SideCell(entry.left, highlight = entry.kind == FolderDiffKind.LEFT_NEWER || entry.kind == FolderDiffKind.LEFT_ONLY)
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Text(mark, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
        SideCell(entry.right, highlight = entry.kind == FolderDiffKind.RIGHT_NEWER || entry.kind == FolderDiffKind.RIGHT_ONLY)
    }
}

@Composable
private fun SideCell(side: org.wexclient.app.vfs.FolderSide?, highlight: Boolean) {
    Row(modifier = Modifier.width(230.dp), verticalAlignment = Alignment.CenterVertically) {
        if (side == null) {
            Text("—", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            return@Row
        }
        Text(
            text = if (side.directory) "папка" else humanSize(side.size),
            fontSize = 11.sp,
            color = if (highlight) NoctaliaPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(78.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = DATE.format(Date(side.modified)),
            fontSize = 11.sp,
            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
            color = if (highlight) NoctaliaPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.outline
        )
    }
}

/** Знак между колонками: куда «смотрит» разница. Цвета приглушены — они подсказка, а не сигнал тревоги. */
private fun kindMark(kind: FolderDiffKind): Pair<String, Color> = when (kind) {
    FolderDiffKind.LEFT_ONLY -> "›" to NoctaliaOk.copy(alpha = 0.7f)
    FolderDiffKind.RIGHT_ONLY -> "‹" to NoctaliaOk.copy(alpha = 0.7f)
    FolderDiffKind.LEFT_NEWER -> "»" to NoctaliaWarn.copy(alpha = 0.7f)
    FolderDiffKind.RIGHT_NEWER -> "«" to NoctaliaWarn.copy(alpha = 0.7f)
    FolderDiffKind.DIFFERENT -> "≠" to NoctaliaDanger.copy(alpha = 0.65f)
    FolderDiffKind.SAME -> "=" to Color.Gray.copy(alpha = 0.6f)
}

@Composable
private fun OptionCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 14.dp).clickable { onChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
            modifier = Modifier.size(22.dp).noFocus()
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SmallAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = NoctaliaPrimary.copy(alpha = if (enabled) 0.12f else 0.05f),
        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = if (enabled) 0.35f else 0.15f)),
        modifier = Modifier.height(32.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = NoctaliaPrimary)
        }
    }
}

private val DATE = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())

/** Конец длинного пути: последняя папка важнее начала. */
private fun tail(path: String, max: Int = 34): String =
    if (path.length <= max) path else "…" + path.takeLast(max - 1)
