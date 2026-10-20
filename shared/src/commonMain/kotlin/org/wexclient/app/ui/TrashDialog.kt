package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.ConflictPolicy
import org.wexclient.app.vfs.Trash
import org.wexclient.app.vfs.TrashItem
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Размер для людей: «12,4 МБ». */
internal fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/** Корзина: восстановить, удалить навсегда, очистить. */
@Composable
fun TrashDialog(
    onDismiss: () -> Unit,
    /** Восстановленные пути — обновить панели и показать */
    onRestored: (List<Path>) -> Unit
) {
    val scope = rememberCoroutineScope()
    // Свои уведомления: общие остались бы под затемнением окна
    val toast = rememberToastState()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    val selected = remember { mutableStateListOf<TrashItem>() }
    var filter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val confirmDelete = rememberArmedConfirm()
    val confirmEmpty = rememberArmedConfirm()
    val home = remember { Paths.get(org.wexclient.app.util.AppDirs.userHome.toString()) }
    val listFocus = remember { FocusRequester() }

    suspend fun reload() {
        items = withContext(Dispatchers.IO) { runCatching { Trash.list() }.getOrDefault(emptyList()) }
        selected.retainAll(items.orEmpty().toSet())
    }
    LaunchedEffect(Unit) {
        // Список появляется вместе с окном, не сразу: ждём его, иначе Enter, Del и Ctrl+A не работали
        launch { org.wexclient.app.editor.requestFocusSafely(listFocus) }
        reload()
    }

    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                toast.error(e.message ?: e.javaClass.simpleName)
            } finally {
                confirmDelete.reset()
                confirmEmpty.reset()
                reload()
                busy = false
            }
        }
    }

    val visible = items.orEmpty().filter { filter.isBlank() || it.originalPath.toString().contains(filter, ignoreCase = true) }
    fun pretty(p: Path?): String = when {
        p == null -> ""
        p.startsWith(home) -> "~/" + home.relativize(p)
        else -> p.toString()
    }

    fun restoreSelected() {
        if (busy || selected.isEmpty()) return
        val targets = selected.toList()
        work {
            val restored = ArrayList<Path>()
            val failure = withContext(Dispatchers.IO) {
                var error: Exception? = null
                for (item in targets) {
                    try {
                        // Занятое место — рядом с пометкой «(восстановлено)»
                        val target = if (Files.exists(item.originalPath, LinkOption.NOFOLLOW_LINKS)) Trash.freeRestoreTarget(item) else item.originalPath
                        restored.add(Trash.restore(item, target))
                    } catch (e: Exception) {
                        error = e
                    }
                }
                error
            }
            selected.clear()
            // Что успели вернуть — показываем, даже если часть не удалась
            if (restored.isNotEmpty()) onRestored(restored)
            when {
                failure != null -> toast.error("Восстановлено ${restored.size} из ${targets.size}: ${failure.message ?: failure.javaClass.simpleName}")
                restored.size == 1 -> toast.success("Восстановлено: ${restored[0].fileName}")
                else -> toast.success("Восстановлено: ${restored.size}")
            }
        }
    }

    // Первое нажатие спрашивает, второе удаляет; двойной щелчок или двойной Del — не удаляют
    fun deleteSelected() {
        if (busy || selected.isEmpty()) return
        confirmEmpty.reset()
        if (!confirmDelete.press()) return
        val targets = selected.toList()
        work {
            withContext(Dispatchers.IO) { targets.forEach { Trash.delete(it) } }
            selected.clear()
            toast.success("Удалено навсегда: ${targets.size}")
        }
    }

    fun emptyTrash() {
        if (busy || items.orEmpty().isEmpty()) return
        confirmDelete.reset()
        if (!confirmEmpty.press()) return
        work {
            val n = withContext(Dispatchers.IO) { Trash.empty() }
            selected.clear()
            toast.success("Корзина очищена: $n")
        }
    }

    ModalDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = !busy) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(820.dp).dialogGlass(RoundedCornerShape(16.dp)).height(620.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> if (!busy) onDismiss()
                        // Раньше флажков и кнопок: иначе Enter нажимал то, что под фокусом
                        Key.Enter, Key.NumPadEnter -> restoreSelected()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .onKeyEvent { e ->
                    // Сюда доходят только клавиши, которые не забрало поле фильтра
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        e.key == Key.Delete -> deleteSelected()
                        e.key == Key.A && e.isCtrlPressed -> {
                            selected.clear()
                            selected.addAll(visible)
                        }
                        else -> return@onKeyEvent false
                    }
                    true
                }
        ) {
            Box {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Корзина", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                items?.let { "${org.wexclient.app.util.Plural.items(it.size)} · та же корзина, что в Dolphin и Nautilus" } ?: "Чтение…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (busy) WexSpinner(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        placeholder = { Text("Фильтр по имени или пути") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .focusRequester(listFocus)
                            .focusable()
                    ) {
                        val list = items
                        when {
                            list == null -> WexSpinner(modifier = Modifier.align(Alignment.Center), color = NoctaliaPrimary)
                            visible.isEmpty() -> Text(
                                if (list.isEmpty()) "Корзина пуста" else "Ничего не найдено",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            else -> LazyColumn(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                items(visible, key = { it.trashDir.toString() + "/" + it.name }) { item ->
                                    TrashRow(
                                        item = item,
                                        checked = item in selected,
                                        folder = pretty(item.originalPath.parent),
                                        onToggle = {
                                            if (item in selected) selected.remove(item) else selected.add(item)
                                            confirmDelete.reset()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (selected.size == visible.size) selected.clear()
                                else {
                                    selected.clear()
                                    selected.addAll(visible)
                                }
                            },
                            enabled = visible.isNotEmpty() && !busy
                        ) {
                            Text(if (visible.isNotEmpty() && selected.size == visible.size) "Снять выделение" else "Выбрать все")
                        }
                        Text(
                            "Enter — восстановить · Del — навсегда",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            enabled = !busy && items.orEmpty().isNotEmpty(),
                            border = BorderStroke(1.dp, NoctaliaError.copy(alpha = if (confirmEmpty.armed) 1f else 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NoctaliaError),
                            onClick = ::emptyTrash
                        ) {
                            Text(if (confirmEmpty.armed) "Точно очистить?" else "Очистить корзину")
                        }
                        OutlinedButton(
                            enabled = !busy && selected.isNotEmpty(),
                            border = BorderStroke(1.dp, NoctaliaError.copy(alpha = if (confirmDelete.armed) 1f else 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NoctaliaError),
                            onClick = ::deleteSelected
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (confirmDelete.armed) "Точно удалить?" else "Удалить навсегда")
                        }
                        Button(
                            enabled = !busy && selected.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                            onClick = ::restoreSelected
                        ) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (selected.size > 1) "Восстановить (${selected.size})" else "Восстановить")
                        }
                    }
                }
                // Над кнопками, а не поверх них
                ToastHost(toast, Modifier.padding(bottom = 64.dp))
            }
        }
    }
}

@Composable
private fun TrashRow(item: TrashItem, checked: Boolean, folder: String, onToggle: () -> Unit) {
    val size by produceState<Long?>(null, item) { value = withContext(Dispatchers.IO) { Trash.sizeOf(item) } }
    val name = item.originalPath.fileName?.toString() ?: item.name
    val type = remember(item) { VfsEntry.detectType(name, item.isDirectory) }
    Surface(
        color = if (checked) NoctaliaPrimary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).noFocus().clickable(onClick = onToggle)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                modifier = Modifier.size(20.dp).noFocus()
            )
            Spacer(modifier = Modifier.width(10.dp))
            FileIconBadge(type, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    folder,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(size?.let(::humanSize) ?: "…", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                val deleted = item.deletedAt
                Text(
                    when {
                        deleted == null -> ""
                        deleted.toLocalDate() == LocalDate.now() -> "сегодня, " + deleted.format(timeFormat)
                        deleted.toLocalDate() == LocalDate.now().minusDays(1) -> "вчера, " + deleted.format(timeFormat)
                        else -> deleted.format(dateFormat)
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Имена уже заняты в папке назначения: заменить, пропустить или оставить оба. */
@Composable
fun ConflictDialog(
    conflicts: List<String>,
    total: Int,
    move: Boolean,
    onDecision: (ConflictPolicy) -> Unit,
    onCancel: () -> Unit,
    /** «копируемых элементов», «распаковываемых файлов»… */
    noun: String = if (move) "перемещаемых элементов" else "копируемых элементов",
    /** Назначение на диске: заменённое уходит в корзину; в архиве и на сервере корзины нет — копия для отмены. */
    targetOnDisk: Boolean = true
) {
    ModalDialog(onDismissRequest = onCancel) {
        // Фокус сразу на безопасном варианте: ничего не теряется
        val keepFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { keepFocus.requestFocus() } }
        // null — фокус на «Отмена»
        var focused by remember { mutableStateOf<ConflictPolicy?>(ConflictPolicy.KEEP_BOTH) }
        fun Modifier.tracks(policy: ConflictPolicy?) = onFocusChanged { if (it.isFocused) focused = policy }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(600.dp).dialogGlass(RoundedCornerShape(16.dp)).onPreviewKeyEvent {
                dialogKeys(it, { focused?.let(onDecision) ?: onCancel() }, onCancel)
            }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Такие файлы уже есть", style = MaterialTheme.typography.titleLarge, color = NoctaliaPrimary)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    when {
                        conflicts.size == 1 && total == 1 -> "В папке назначения уже есть «${conflicts[0]}»."
                        conflicts.size == total -> "В папке назначения уже есть все $total $noun:"
                        else -> "В папке назначения уже есть ${conflicts.size} из $total $noun:"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (conflicts.size > 1 || total > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        conflicts.take(6).forEach { Text("• $it", fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (conflicts.size > 6) Text("… и ещё ${conflicts.size - 6}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    (if (targetOnDisk) "Заменённые файлы попадут в корзину — замену можно отменить (Ctrl+Z)."
                    else "Корзины там нет: заменённое сохранится копией на этом компьютере до выхода — замену можно отменить (Ctrl+Z).") +
                        " «Оставить оба» добавит к новым именам «(копия)».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel, modifier = Modifier.tracks(null)) { Text("Отмена") }
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { onDecision(ConflictPolicy.SKIP) },
                        modifier = Modifier.tracks(ConflictPolicy.SKIP)
                    ) { Text("Пропустить", maxLines = 1, softWrap = false) }
                    OutlinedButton(
                        onClick = { onDecision(ConflictPolicy.KEEP_BOTH) },
                        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = if (focused == ConflictPolicy.KEEP_BOTH) 1f else 0.5f)),
                        modifier = Modifier.focusRequester(keepFocus).tracks(ConflictPolicy.KEEP_BOTH)
                    ) { Text("Оставить оба", maxLines = 1, softWrap = false) }
                    Button(
                        onClick = { onDecision(ConflictPolicy.REPLACE) },
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                        modifier = Modifier.tracks(ConflictPolicy.REPLACE)
                    ) { Text("Заменить", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** Щелчок не забирает клавиатуру (Compose Desktop переводит фокус на всё, что нажали). */
internal fun Modifier.noFocus(): Modifier = focusProperties { canFocus = false }
