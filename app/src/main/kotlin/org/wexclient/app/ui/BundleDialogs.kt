package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.apk.BundleConvertResult
import org.wexclient.app.apk.BundleInfo
import org.wexclient.app.apk.BundlePartKind
import org.wexclient.app.apk.BundleProgress
import org.wexclient.app.apk.SplitBundle
import org.wexclient.app.signing.SigningPreferences
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.ApkIconLoader
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Что сделать с набором split-APK — порядок как в списке окна. */
enum class BundleAction(val title: String, val hint: String) {
    INSTALL("Установить", "на устройство через ADB — все части сразу"),
    OPEN("Открыть", "посмотреть содержимое набора как архив"),
    SIGN("Подпись", "подписать все части одним ключом"),
    CONVERT("Конвертировать в APK", "собрать части в один APK и подписать")
}

/** Последний выбор: в следующий раз отмечен он. */
object BundleActionMemory {
    var last: BundleAction = BundleAction.INSTALL
}

/**
 * Окно по щелчку на .apks / .xapk / .apkm: простой список «установить, открыть, подпись,
 * конвертировать в APK». Стрелки — выбор, Enter или двойной щелчок — выполнить, Esc — закрыть.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BundleActionDialog(entry: VfsEntry, onChoose: (BundleAction) -> Unit, onDismiss: () -> Unit) {
    val onDisk = entry.path.fileSystem === FileSystems.getDefault()
    // Подпись и конвертация пишут файлы рядом с набором — только для файлов на диске
    fun available(a: BundleAction) = a == BundleAction.INSTALL || a == BundleAction.OPEN || onDisk
    var selected by remember { mutableStateOf(BundleActionMemory.last.takeIf { available(it) } ?: BundleAction.OPEN) }
    val info by produceState<BundleInfo?>(null, entry.path) {
        if (onDisk) value = withContext(Dispatchers.IO) { runCatching { SplitBundle.inspect(entry.path) }.getOrNull() }
    }
    val focus = remember { FocusRequester() }

    fun choose(a: BundleAction) {
        if (!available(a)) return
        BundleActionMemory.last = a
        onChoose(a)
    }

    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Фокус — когда окно уже показано (оно появляется с анимацией): иначе стрелки и Enter уходят мимо
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            modifier = Modifier
                .width(470.dp)
                .dialogGlass(RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) {
                        // Отпускание Enter/Esc не должно дойти до окна под нами
                        return@onPreviewKeyEvent e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Escape
                    }
                    val list = BundleAction.entries.filter { available(it) }
                    when (e.key) {
                        Key.DirectionDown -> {
                            selected = list[(list.indexOf(selected) + 1).coerceAtMost(list.lastIndex)]
                            true
                        }
                        Key.DirectionUp -> {
                            selected = list[(list.indexOf(selected) - 1).coerceAtLeast(0)]
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            choose(selected)
                            true
                        }
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BundleIcon(entry, size = 40)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append("Набор split-APK")
                                info?.let { append(" · частей: ").append(it.parts.size) }
                                append(" · ").append(humanSize(entry.size))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                    DialogClose(onDismiss)
                }
                info?.let { i ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = listOfNotNull(i.packageName, i.summary()).joinToString(" · "),
                        fontSize = 11.sp,
                        fontFamily = AppFonts.JetBrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                BundleAction.entries.forEach { a ->
                    val enabled = available(a)
                    val on = a == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) NoctaliaPrimary.copy(alpha = 0.10f) else Color.Transparent)
                            .combinedClickable(
                                enabled = enabled,
                                onClick = { selected = a },
                                onDoubleClick = { choose(a) }
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = on,
                            onClick = null,
                            enabled = enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = NoctaliaPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = a.title,
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = if (enabled) a.hint else "только для файлов на этом компьютере",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.height(36.dp)) {
                        Text("Отмена", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    DialogPill("ОК", enabled = available(selected)) { choose(selected) }
                }
            }
        }
    }
}

/** Иконка приложения из набора (если есть) со значком частей, иначе — значок набора. */
@Composable
fun BundleIcon(entry: VfsEntry, size: Int) {
    var icon by remember(entry.path) { mutableStateOf<ImageBitmap?>(ApkIconLoader.getCachedIcon(entry.path, entry.lastModified)) }
    LaunchedEffect(entry.path) {
        if (icon == null) icon = ApkIconLoader.loadIcon(entry.path, entry.lastModified)
    }
    Box(modifier = Modifier.size(size.dp)) {
        val bmp = icon
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)))
        } else {
            Surface(shape = RoundedCornerShape(10.dp), color = BundleTint.copy(alpha = 0.14f), modifier = Modifier.size(size.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Android, contentDescription = null, tint = BundleTint, modifier = Modifier.size((size * 0.6f).dp))
                }
            }
        }
        BundleCornerBadge(modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp), size = (size * 0.4f).dp)
    }
}

/**
 * Набор → один APK. Сначала — что получится (сколько частей, каким ключом подпишется, как
 * назовётся файл), потом ход работы с остановкой, в конце — итог и «Показать в панели».
 */
@Composable
fun BundleConvertDialog(
    entry: VfsEntry,
    onReveal: (Path) -> Unit,
    onFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    val input = entry.path
    // Имя результата ищется среди файлов на диске — не в потоке окна
    val target by produceState(input.resolveSibling(input.fileName.toString().substringBeforeLast('.') + ".apk"), input) {
        value = withContext(Dispatchers.IO) { runCatching { SplitBundle.defaultApkTarget(input) }.getOrDefault(value) }
    }
    val key = remember { SigningPreferences.defaultKey }
    val schemes = remember { SigningPreferences.schemes.copy(v4 = false) }
    // Набор не прочитался — пишем почему, а не «Чтение набора…» навсегда
    var inspectError by remember { mutableStateOf<String?>(null) }
    val info by produceState<BundleInfo?>(null, input) {
        value = withContext(Dispatchers.IO) {
            runCatching { SplitBundle.inspect(input) }
                .onFailure { inspectError = it.message ?: it.javaClass.simpleName }
                .getOrNull()
        }
    }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(BundleProgress("Подготовка")) }
    var result by remember { mutableStateOf<BundleConvertResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    val cancel = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }

    fun start() {
        if (running) return
        running = true
        startedAt = System.currentTimeMillis()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // Свободное имя — ещё раз прямо перед записью: подпись заменяет существующий файл,
                    // а рядом мог появиться APK с тем же именем
                    val out = SplitBundle.defaultApkTarget(input)
                    SplitBundle.convertToApk(input, out, key, schemes, cancelled = { cancel.get() }) { progress = it }
                }
            }
            running = false
            r.onSuccess {
                result = it
                onFinished()
            }.onFailure { e ->
                if (cancel.get()) stopped = true else error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    ModalDialog(
        onDismissRequest = { if (running) cancel.set(true) else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        // Во время конвертации Esc — «прервать», окно остаётся с ходом отмены
        dismissible = !running
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            modifier = Modifier.width(560.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BundleIcon(entry, size = 36)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Конвертировать в APK",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary
                        )
                        Text(
                            text = entry.name,
                            fontSize = 11.sp,
                            fontFamily = AppFonts.JetBrainsMono,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DialogClose(if (running) ({ cancel.set(true) }) else onDismiss)
                }
                Spacer(modifier = Modifier.height(12.dp))
                val res = result
                when {
                    res != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NoctaliaOk.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Готово за ${duration(System.currentTimeMillis() - startedAt)}: ${res.output.fileName} · ${humanSize(res.size)}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Частей слито: ${res.parts} · подписан ключом «${res.sign.key.title}» (${res.sign.schemes.label})" +
                                (res.sign.verifyProblem?.let { " · проверка: $it" } ?: " · подпись проверена"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (res.obbSkipped > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "OBB-файлов в наборе: ${res.obbSkipped} — в APK они не входят, их кладут в Android/obb отдельно",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                            ToolAction("Показать в панели", Icons.Default.MyLocation) {
                                onReveal(res.output)
                                onDismiss()
                            }
                            DialogPill("Закрыть", onClick = onDismiss)
                        }
                    }
                    running -> {
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                delay(500)
                                now = System.currentTimeMillis()
                            }
                        }
                        val p = progress
                        Text(
                            text = if (p.total > 0) "${p.stage}: ${progressText(p)}" else p.stage + "…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (p.total > 0) {
                            LinearProgressIndicator(
                                progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                                color = NoctaliaPrimary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                        } else {
                            WexLinearSpinner(
                                color = NoctaliaPrimary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Прошло ${duration(now - startedAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ToolAction("Остановить", Icons.Default.Stop, danger = true) { cancel.set(true) }
                        }
                    }
                    error != null || stopped -> {
                        Text(
                            text = if (stopped) "Остановлено — файл не создан" else "Не получилось: $error",
                            fontSize = 12.sp,
                            color = if (stopped) MaterialTheme.colorScheme.onSurface else NoctaliaDanger.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            DialogPill("Закрыть", onClick = onDismiss)
                        }
                    }
                    else -> {
                        // Что получится — до начала
                        val i = info
                        Text(
                            text = when {
                                i == null -> inspectError?.let { "Набор не прочитался: $it" } ?: "Чтение набора…"
                                else -> "Соберёт части (${i.summary()}) в один APK и подпишет его ключом «${key.title}» (${schemes.label})."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Файл: ${target.fileName}",
                            fontSize = 12.sp,
                            fontFamily = AppFonts.JetBrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (i != null && i.obbFiles > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "OBB (${i.obbFiles}) в APK не входят — их кладут в Android/obb отдельно",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (i != null && i.parts.any { it.kind == BundlePartKind.ASSET_PACK }) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Пакеты ресурсов войдут в APK целиком — он будет примерно ${humanSize(i.totalApkBytes)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss, modifier = Modifier.height(36.dp)) {
                                Text("Отмена", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            DialogPill("Конвертировать", enabled = i != null && i.parts.isNotEmpty()) { start() }
                        }
                    }
                }
            }
        }
    }
}

private fun progressText(p: BundleProgress): String =
    if (p.stage.startsWith("Распаковка") || p.stage.startsWith("Подпись")) "${humanSize(p.done)} из ${humanSize(p.total)}"
    else "${p.done} из ${p.total}"

/** Главная кнопка маленьких окон: заливка и контур цвета темы. */
@Composable
fun DialogPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = NoctaliaPrimary.copy(alpha = if (enabled) 0.18f else 0.06f),
        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = if (enabled) 1f else 0.3f)),
        modifier = Modifier.height(36.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NoctaliaPrimary.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}
