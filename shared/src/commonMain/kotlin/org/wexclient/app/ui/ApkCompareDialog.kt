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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.wexclient.app.apk.ApkComparison
import org.wexclient.app.apk.CompareProgress
import org.wexclient.app.apk.apkManifest
import org.wexclient.app.apk.compareApks
import org.wexclient.app.apk.DexCompareOptions
import org.wexclient.app.apk.ResourceDiff
import org.wexclient.app.apk.ApkFileChange
import org.wexclient.app.apk.DiffKind
import org.wexclient.app.apk.ManifestDiff
import org.wexclient.app.axml.ManifestInfo
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Files
import java.nio.file.Path

/** Сведения об APK для карточки выбора: версия из манифеста и вес файла. */
private data class ApkCard(
    val entry: VfsEntry,
    val info: ManifestInfo?,
    val size: Long
)

/**
 * Сравнение двух APK: сначала спрашиваем, какой из них старый, потом показываем отличия.
 * Порядок важен — «добавлено» и «удалено» читаются только относительно старой версии.
 */
@Composable
fun ApkCompareDialog(first: VfsEntry, second: VfsEntry, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<Triple<VfsEntry, VfsEntry, DexCompareOptions>?>(null) }
    val started = chosen
    if (started == null) {
        ApkComparePicker(first, second, onDismiss) { old, new, options -> chosen = Triple(old, new, options) }
    } else {
        ApkCompareWindow(started.first, started.second, started.third, onDismiss)
    }
}

// ------------------------------------------------------------------ выбор порядка

@Composable
private fun ApkComparePicker(
    first: VfsEntry,
    second: VfsEntry,
    onDismiss: () -> Unit,
    onStart: (old: VfsEntry, new: VfsEntry, options: DexCompareOptions) -> Unit
) {
    val cards by produceState<List<ApkCard>?>(null, first.path, second.path) {
        value = withContext(Dispatchers.IO) { listOf(cardOf(first), cardOf(second)) }
    }
    var options by remember { mutableStateOf(DexCompareOptions()) }
    // По коду версии обычно видно, какой пакет новее — предлагаем сразу верный порядок
    var swapped by remember(cards) {
        mutableStateOf(
            cards?.let { (a, b) ->
                val ca = a.info?.versionCode?.toLongOrNull()
                val cb = b.info?.versionCode?.toLongOrNull()
                if (ca != null && cb != null) ca > cb else false
            } ?: false
        )
    }

    ModalDialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(620.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Android, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Сравнение двух APK",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DialogClose(onDismiss)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Укажите, какой пакет старее: «добавлено» и «удалено» считаются от него",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (cards == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        WexSpinner(color = NoctaliaPrimary)
                    }
                } else {
                    val old = if (swapped) cards!![1] else cards!![0]
                    val new = if (swapped) cards!![0] else cards!![1]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ApkSideCard("Старая версия", old, NoctaliaSecondary, Modifier.weight(1f))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(onClick = { swapped = !swapped }) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Поменять", fontSize = 11.sp, color = NoctaliaPrimary)
                            }
                        }
                        ApkSideCard("Новая версия", new, NoctaliaPrimary, Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Что не считать изменением в коде",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CompareCheck(
                        "Отладочную информацию",
                        "Номера строк, имена файлов и локальных переменных",
                        options.ignoreDebugInfo
                    ) { options = options.copy(ignoreDebugInfo = it) }
                    CompareCheck(
                        "Оптимизацию компиляции",
                        "const/4 и const/16, invoke-virtual и invoke-virtual/range — одно действие",
                        options.ignoreCompilerOptimization
                    ) { options = options.copy(ignoreCompilerOptimization = it) }
                    CompareCheck(
                        "Регистры",
                        "Номера v0, p1 — компилятор раздаёт их заново при каждой сборке",
                        options.ignoreRegisters
                    ) { options = options.copy(ignoreRegisters = it) }
                    CompareCheck(
                        "Порядок инструкций",
                        "Переставленные местами команды внутри метода",
                        options.ignoreInstructionOrder
                    ) { options = options.copy(ignoreInstructionOrder = it) }
                    CompareCheck(
                        "Изменения id ресурсов",
                        "Значения 0x7f… смещаются все разом при пересборке ресурсов",
                        options.ignoreResourceIds
                    ) { options = options.copy(ignoreResourceIds = it) }

                    Spacer(modifier = Modifier.height(10.dp))
                    // Обе кнопки одной высоты и по центру ряда: иначе «Отмена» стояла выше «Сравнить»
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Отмена", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NoctaliaPrimary.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, NoctaliaPrimary),
                            modifier = Modifier.height(36.dp).clickable { onStart(old.entry, new.entry, options) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp)
                            ) {
                                Text(
                                    text = "Сравнить",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NoctaliaPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkSideCard(title: String, card: ApkCard, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.entry.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            val info = card.info
            InfoPair("Версия", info?.versionName?.takeIf { it.isNotBlank() } ?: "—")
            InfoPair("Код версии", info?.versionCode?.takeIf { it.isNotBlank() } ?: "—")
            InfoPair("Пакет", info?.packageName?.takeIf { it.isNotBlank() } ?: "—")
            InfoPair("Размер", humanSize(card.size))
        }
    }
}

/** Флажок настройки сравнения: строка целиком нажимается. */
@Composable
private fun CompareCheck(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp)
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
            modifier = Modifier.size(22.dp).noFocus()
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun InfoPair(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(86.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ------------------------------------------------------------------ окно сравнения

@Composable
private fun ApkCompareWindow(old: VfsEntry, new: VfsEntry, options: DexCompareOptions, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val toast = rememberToastState()
    var filter by remember { mutableStateOf<DiffKind?>(DiffKind.CHANGED) }
    var query by remember { mutableStateOf("") }

    val progress = remember(old.path, new.path, options) { mutableStateOf(CompareProgress("Подготовка")) }
    var error by remember(old.path, new.path, options) { mutableStateOf<String?>(null) }
    val result by produceState<ApkComparison?>(null, old.path, new.path, options) {
        value = null
        error = null
        try {
            value = withContext(Dispatchers.IO) {
                compareApks(old.path, new.path, options, cancelled = { !isActive }) { step -> progress.value = step }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось сравнить APK"
            org.wexclient.app.util.ErrorLog.record("Ошибка сравнения APK", e)
        }
    }

    val data = result
    val subtitle = when {
        error != null -> "ошибка сравнения"
        data == null -> "сравнение…"
        else -> buildList {
            add("${data.summary.changed} изменено")
            add("${data.summary.added} добавлено")
            add("${data.summary.removed} удалено")
            val delta = data.summary.newFileSize - data.summary.oldFileSize
            add(if (delta >= 0) "+${humanSize(delta)}" else "−${humanSize(-delta)}")
        }.joinToString(" · ")
    }

    GrailDialogShell(
        title = "${old.name} → ${new.name}",
        badge = "СРАВНЕНИЕ",
        heading = "${old.name}  →  ${new.name}",
        subtitle = subtitle,
        closeOnEsc = true,
        dialogWidth = 1280.dp,
        dialogHeight = 820.dp,
        onDismiss = onDismiss,
        toast = toast,
        actions = {
            if (data != null) {
                GrailTabs(listOf("Сводка", "Файлы", "Код", "Ресурсы", "Манифест"), tab) { tab = it }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = NoctaliaPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.clickable {
                        val saved = runCatching { saveReport(old, new, reportOf(old, new, data, options)) }
                        saved.onSuccess { toast.info("Отчёт сохранён: " + it.fileName) }
                        saved.onFailure { toast.error(it.message ?: "Не удалось сохранить отчёт") }
                    }
                ) {
                    Text(
                        text = "Отчёт",
                        fontSize = 11.sp,
                        color = NoctaliaPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    ) {
        when {
            error != null -> GrailEmptyState(error.orEmpty())
            data == null -> CompareProgressView(progress.value)
            data.changes.isEmpty() -> GrailEmptyState("Архив не содержит файлов")
            else -> when (tab) {
                0 -> SummaryTab(data)
                1 -> FilesTab(data, filter, { filter = it }, query, { query = it })
                2 -> ApkCodeDiffView(data.code, options, old.path, new.path)
                3 -> ResourcesTab(data.resources)
                else -> ManifestTab(data.manifest)
            }
        }
    }
}

/** Пока идёт разбор: что именно считается и сколько уже сделано. */
@Composable
private fun CompareProgressView(progress: CompareProgress) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(360.dp)) {
            Text(
                text = progress.stage,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            val fraction = progress.fraction
            if (fraction == null) {
                WexLinearSpinner(
                    color = NoctaliaPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction },
                    color = NoctaliaPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${progress.done} из ${progress.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun SummaryTab(data: ApkComparison) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Изменено", data.summary.changed.toString(), NoctaliaWarn, Icons.Default.Edit, Modifier.weight(1f))
            StatCard("Добавлено", data.summary.added.toString(), NoctaliaOk, Icons.Default.Add, Modifier.weight(1f))
            StatCard("Удалено", data.summary.removed.toString(), NoctaliaDanger, Icons.Default.Remove, Modifier.weight(1f))
            StatCard("Без изменений", data.summary.same.toString(), NoctaliaSecondary, Icons.Default.Check, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val delta = data.summary.newFileSize - data.summary.oldFileSize
            StatCard(
                "Размер пакета",
                humanSize(data.summary.oldFileSize) + " → " + humanSize(data.summary.newFileSize),
                if (delta > 0) NoctaliaWarn else NoctaliaOk,
                Icons.Default.Storage,
                Modifier.weight(1f)
            )
            StatCard(
                "Версия",
                (data.oldInfo?.versionName?.ifBlank { "—" } ?: "—") + " → " + (data.newInfo?.versionName?.ifBlank { "—" } ?: "—"),
                NoctaliaPrimary,
                Icons.Default.Sell,
                Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        GrailSectionLabel("Самое заметное")
        Spacer(modifier = Modifier.height(6.dp))
        val top = data.changes
            .filter { it.kind != DiffKind.SAME }
            .sortedByDescending { kotlin.math.abs(it.delta) }
            .take(12)
        if (top.isEmpty()) {
            GrailEmptyState("Пакеты совпадают по содержимому")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(top) { change -> ChangeRow(change) }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.055f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(9.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FilesTab(
    data: ApkComparison,
    filter: DiffKind?,
    onFilter: (DiffKind?) -> Unit,
    query: String,
    onQuery: (String) -> Unit
) {
    val shown = remember(data, filter, query) {
        data.changes.filter { c ->
            (filter == null || c.kind == filter) && (query.isBlank() || c.name.contains(query, ignoreCase = true))
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip("Изменённые", data.summary.changed, filter == DiffKind.CHANGED) { onFilter(DiffKind.CHANGED) }
            FilterChip("Добавленные", data.summary.added, filter == DiffKind.ADDED) { onFilter(DiffKind.ADDED) }
            FilterChip("Удалённые", data.summary.removed, filter == DiffKind.REMOVED) { onFilter(DiffKind.REMOVED) }
            FilterChip("Все", data.changes.size, filter == null) { onFilter(null) }
            Spacer(modifier = Modifier.weight(1f))
            GrailSearchField(query, onQuery, "Поиск по имени", modifier = Modifier.width(260.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (shown.isEmpty()) {
            GrailEmptyState("Ничего не подходит под отбор")
        } else {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown) { change -> ChangeRow(change) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.padding(end = 6.dp).clickable(onClick = onClick)
    ) {
        Text(
            text = "$label · $count",
            fontSize = 11.sp,
            color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ChangeRow(change: ApkFileChange) {
    val (color, icon) = when (change.kind) {
        DiffKind.ADDED -> NoctaliaOk to Icons.Default.Add
        DiffKind.REMOVED -> NoctaliaDanger to Icons.Default.Remove
        DiffKind.CHANGED -> NoctaliaWarn to Icons.Default.Edit
        DiffKind.SAME -> MaterialTheme.colorScheme.outline to Icons.Default.Check
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(color.copy(alpha = 0.035f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = change.name,
            fontSize = 12.sp,
            fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when (change.kind) {
            DiffKind.ADDED -> Text(humanSize(change.newSize), style = MaterialTheme.typography.labelSmall, color = NoctaliaOk.copy(alpha = 0.8f))
            DiffKind.REMOVED -> Text(humanSize(change.oldSize), style = MaterialTheme.typography.labelSmall, color = NoctaliaDanger.copy(alpha = 0.8f))
            else -> {
                Text(
                    text = humanSize(change.oldSize) + " → " + humanSize(change.newSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (change.delta != 0L) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (change.delta > 0) "+${humanSize(change.delta)}" else "−${humanSize(-change.delta)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = (if (change.delta > 0) NoctaliaWarn else NoctaliaOk).copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourcesTab(diff: ResourceDiff) {
    if (diff.changes.isEmpty()) {
        GrailEmptyState(
            if (diff.oldCount == 0 && diff.newCount == 0) "resources.arsc не менялся"
            else "Ресурсы совпадают"
        )
        return
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(diff, query) {
        if (query.isBlank()) diff.changes
        else diff.changes.filter { it.key.contains(query, ignoreCase = true) || it.newValue.contains(query, ignoreCase = true) }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Ресурсов: ${diff.oldCount} → ${diff.newCount} · изменено ${diff.changed}, добавлено ${diff.added}, убрано ${diff.removed}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            GrailSearchField(query, { query = it }, "Поиск по имени и значению", modifier = Modifier.width(280.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown) { change ->
                val color = when (change.kind) {
                    DiffKind.ADDED -> NoctaliaOk
                    DiffKind.REMOVED -> NoctaliaDanger
                    else -> NoctaliaWarn
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(color.copy(alpha = 0.035f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = change.key,
                        fontSize = 12.sp,
                        fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (change.kind != DiffKind.ADDED) {
                            Text(
                                text = change.oldValue.ifBlank { "—" },
                                style = MaterialTheme.typography.labelSmall,
                                color = NoctaliaDanger.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (change.kind == DiffKind.CHANGED) {
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (change.kind != DiffKind.REMOVED) {
                            Text(
                                text = change.newValue.ifBlank { "—" },
                                style = MaterialTheme.typography.labelSmall,
                                color = NoctaliaOk.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManifestTab(diff: ManifestDiff) {
    if (diff.empty) {
        GrailEmptyState("Манифесты совпадают")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (diff.fields.isNotEmpty()) {
            item { GrailSectionLabel("Поля"); Spacer(modifier = Modifier.height(6.dp)) }
            items(diff.fields) { f ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                    Text(
                        text = f.oldValue.ifBlank { "—" },
                        fontSize = 12.sp,
                        fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                        color = NoctaliaDanger.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(13.dp).padding(horizontal = 1.dp))
                    Text(
                        text = f.newValue.ifBlank { "—" },
                        fontSize = 12.sp,
                        fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                        color = NoctaliaOk.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
        listOf(
            Triple("Новые разрешения", diff.addedPermissions, NoctaliaOk.copy(alpha = 0.8f)),
            Triple("Убранные разрешения", diff.removedPermissions, NoctaliaDanger.copy(alpha = 0.8f)),
            Triple("Новые экраны", diff.addedActivities, NoctaliaOk.copy(alpha = 0.8f)),
            Triple("Убранные экраны", diff.removedActivities, NoctaliaDanger.copy(alpha = 0.8f))
        ).forEach { (title, list, color) ->
            if (list.isNotEmpty()) {
                item { GrailSectionLabel("$title · ${list.size}"); Spacer(modifier = Modifier.height(4.dp)) }
                items(list) { value ->
                    Text(
                        text = value,
                        fontSize = 12.sp,
                        fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------ отчёт

/**
 * Отчёт о сравнении в Markdown: его удобно приложить к задаче или открыть позже,
 * когда сами APK уже удалены. Длинные списки обрезаются, чтобы файл оставался читаемым.
 */
private fun reportOf(old: VfsEntry, new: VfsEntry, data: ApkComparison, options: DexCompareOptions): String = buildString {
    val limit = 500
    fun version(info: ManifestInfo?) =
        listOfNotNull(info?.versionName?.takeIf { it.isNotBlank() }, info?.versionCode?.takeIf { it.isNotBlank() }?.let { "код $it" })
            .joinToString(", ").ifEmpty { "—" }

    appendLine("# Сравнение APK")
    appendLine()
    appendLine("- Старый: `${old.name}` — ${version(data.oldInfo)}, ${humanSize(data.summary.oldFileSize)}")
    appendLine("- Новый: `${new.name}` — ${version(data.newInfo)}, ${humanSize(data.summary.newFileSize)}")
    appendLine("- Код сравнивался без учёта: ${options.label}")
    appendLine()

    appendLine("## Сводка")
    appendLine()
    appendLine("| | Файлов |")
    appendLine("|---|---:|")
    appendLine("| Изменено | ${data.summary.changed} |")
    appendLine("| Добавлено | ${data.summary.added} |")
    appendLine("| Удалено | ${data.summary.removed} |")
    appendLine("| Без изменений | ${data.summary.same} |")
    appendLine()

    val m = data.manifest
    if (!m.empty) {
        appendLine("## Манифест")
        appendLine()
        m.fields.forEach { appendLine("- ${it.label}: `${it.oldValue.ifBlank { "—" }}` → `${it.newValue.ifBlank { "—" }}`") }
        m.addedPermissions.forEach { appendLine("- + разрешение `$it`") }
        m.removedPermissions.forEach { appendLine("- − разрешение `$it`") }
        m.addedActivities.forEach { appendLine("- + экран `$it`") }
        m.removedActivities.forEach { appendLine("- − экран `$it`") }
        appendLine()
    }

    val code = data.code
    if (code.classes.isNotEmpty()) {
        appendLine("## Код")
        appendLine()
        appendLine("Классов: ${code.oldClasses} → ${code.newClasses}, методов: ${code.oldMethods} → ${code.newMethods}.")
        appendLine()
        fun section(title: String, kind: DiffKind) {
            val list = code.classes.filter { it.kind == kind }
            if (list.isEmpty()) return
            appendLine("### $title (${list.size})")
            appendLine()
            list.take(limit).forEach { c ->
                val details = buildList {
                    if (c.addedMethods.isNotEmpty()) add("+${c.addedMethods.size} мет.")
                    if (c.removedMethods.isNotEmpty()) add("−${c.removedMethods.size} мет.")
                    if (c.addedFields.isNotEmpty()) add("+${c.addedFields.size} пол.")
                    if (c.removedFields.isNotEmpty()) add("−${c.removedFields.size} пол.")
                    if (c.bodyOnly) add("изменён код или модификаторы")
                }
                appendLine("- `${c.name}`" + if (details.isEmpty()) "" else " — " + details.joinToString(", "))
            }
            if (list.size > limit) appendLine("- … и ещё ${list.size - limit}")
            appendLine()
        }
        section("Изменённые классы", DiffKind.CHANGED)
        section("Новые классы", DiffKind.ADDED)
        section("Удалённые классы", DiffKind.REMOVED)
    }

    val res = data.resources
    if (res.changes.isNotEmpty()) {
        appendLine("## Ресурсы")
        appendLine()
        res.changes.take(limit).forEach { r ->
            when (r.kind) {
                DiffKind.ADDED -> appendLine("- + `${r.key}` = `${r.newValue}`")
                DiffKind.REMOVED -> appendLine("- − `${r.key}` (было `${r.oldValue}`)")
                else -> appendLine("- `${r.key}`: `${r.oldValue}` → `${r.newValue}`")
            }
        }
        if (res.changes.size > limit) appendLine("- … и ещё ${res.changes.size - limit}")
        appendLine()
    }

    val files = data.changes.filter { it.kind != DiffKind.SAME }
    if (files.isNotEmpty()) {
        appendLine("## Файлы внутри APK")
        appendLine()
        appendLine("| Файл | Было | Стало |")
        appendLine("|---|---:|---:|")
        files.take(limit).forEach { f ->
            val before = if (f.kind == DiffKind.ADDED) "—" else humanSize(f.oldSize)
            val after = if (f.kind == DiffKind.REMOVED) "—" else humanSize(f.newSize)
            appendLine("| `${f.name}` | $before | $after |")
        }
        if (files.size > limit) appendLine("| … и ещё ${files.size - limit} | | |")
    }
}

/** Куда положить отчёт: рядом с новым APK, а если туда нельзя — в домашнюю папку. */
private fun saveReport(old: VfsEntry, new: VfsEntry, text: String): Path {
    val name = new.name.substringBeforeLast('.') + "-vs-" + old.name.substringBeforeLast('.') + ".md"
    // Пакет внутри архива — отчёт не туда: запись в архив мимо VfsManager не сохраняется (см. exportTarget)
    val beside = new.path.toAbsolutePath().parent?.takeIf { it.fileSystem === java.nio.file.FileSystems.getDefault() }?.resolve(name)
    val home = Path.of(org.wexclient.app.util.AppDirs.userHome.toString(), name)
    for (target in listOfNotNull(beside, home)) {
        val ok = runCatching { Files.write(target, text.toByteArray(Charsets.UTF_8)) }.isSuccess
        if (ok) return target
    }
    throw java.io.IOException("Не удалось записать отчёт ни рядом с APK, ни в домашнюю папку")
}

// ------------------------------------------------------------------ разбор

private fun cardOf(entry: VfsEntry): ApkCard = ApkCard(
    entry = entry,
    info = apkManifest(entry.path),
    size = runCatching { Files.size(entry.path) }.getOrDefault(entry.size)
)
