package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.wexclient.app.apk.JadxExport
import org.wexclient.app.apk.JadxExportOptions
import org.wexclient.app.apk.JadxExportProgress
import org.wexclient.app.apk.JadxExportResult
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.util.withUserEnv
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Выгрузка в проект, которая идёт сейчас: окно можно закрыть, работа продолжится в фоне. */
class JadxJob(val input: Path, val outDir: Path) {
    var progress by mutableStateOf(JadxExportProgress("Подготовка", 0, 0))
    var result by mutableStateOf<JadxExportResult?>(null)
    var error by mutableStateOf<String?>(null)
    var stopped by mutableStateOf(false)
    val cancel = AtomicBoolean(false)
    val startedAt = System.currentTimeMillis()

    val running: Boolean get() = result == null && error == null && !stopped

    /** Доля готового для полосы: пока задач не знаем — null (полоса «бегает»). */
    val fraction: Float?
        get() = progress.total.takeIf { it > 0 }?.let { (progress.done.toFloat() / it).coerceIn(0f, 1f) }
}

/** Установленные IDE, в которых можно открыть проект: подпись → исполняемый файл. */
object ProjectIdes {
    val found: List<Pair<String, String>> by lazy {
        listOf(
            "Android Studio" to listOf("android-studio", "studio", "studio.sh", "studio64"),
            "IntelliJ IDEA" to listOf("idea", "intellij-idea-ultimate-edition", "intellij-idea-community-edition", "idea.sh", "idea64"),
            "VS Code" to listOf("code", "codium")
        ).mapNotNull { (label, names) ->
            // which: разделитель PATH и .exe/.cmd на Windows (VS Code там — code.cmd)
            names.firstNotNullOfOrNull { org.wexclient.app.util.ProcessRunner.which(it) }?.let { label to it }
        }
    }

    fun open(command: String, dir: Path) {
        ProcessBuilder(command, dir.toString())
            .withUserEnv()
            .redirectOutput(org.wexclient.app.util.DiscardOutput)
            .redirectError(org.wexclient.app.util.DiscardOutput)
            .start()
    }
}

/**
 * APK → проект на Java (jadx). До начала — параметры, во время — ход работы
 * (окно можно свернуть), в конце — итог и куда открыть.
 */
@Composable
fun JadxExportDialog(
    input: Path,
    inputSize: Long,
    job: JadxJob?,
    onStart: (JadxExportOptions, Path) -> Unit,
    onOpenFolder: (Path) -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf(JadxExport.defaultTarget(input).fileName.toString()) }
    var gradle by remember { mutableStateOf(true) }
    var resources by remember { mutableStateOf(true) }
    var deobfuscate by remember { mutableStateOf(false) }
    var showBad by remember { mutableStateOf(true) }
    val parent = input.toAbsolutePath().parent
    val target = remember(folderName) { runCatching { parent.resolve(folderName.trim()) }.getOrNull() }
    val nameProblem = when {
        folderName.isBlank() -> "Введите имя папки"
        folderName.contains('/') || folderName.trim() == "." || folderName.trim() == ".." -> "Имя папки без «/»"
        target == null -> "Такое имя не подходит"
        Files.exists(target) -> "Такая папка уже есть — выберите другое имя"
        else -> null
    }
    val running = job?.running == true

    ModalDialog(
        onDismissRequest = { if (running) onHide() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            modifier = Modifier.width(560.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Декомпилировать в проект",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DialogClose(if (running) onHide else onDismiss)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Исходники на Java через jadx: классы, ресурсы и манифест. Проект Gradle открывается в Android Studio и IDEA.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Из:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(64.dp))
                    Text(
                        text = "${input.fileName} · ${humanSize(inputSize)}",
                        fontSize = 12.sp,
                        fontFamily = AppFonts.JetBrainsMono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when {
                    job == null -> {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("В папку:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(64.dp))
                            GrailCompactField(
                                value = folderName,
                                onChange = { folderName = it },
                                placeholder = "имя папки рядом с файлом",
                                mono = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        nameProblem?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, fontSize = 11.sp, color = NoctaliaDanger.copy(alpha = 0.8f), modifier = Modifier.padding(start = 64.dp))
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        ToolCheck("Проект Gradle (для Android Studio)", gradle) { gradle = it }
                        Spacer(modifier = Modifier.height(4.dp))
                        ToolCheck("Ресурсы и манифест читаемым XML", resources) { resources = it }
                        Spacer(modifier = Modifier.height(4.dp))
                        ToolCheck("Понятные имена вместо a, b, c (деобфускация)", deobfuscate) { deobfuscate = it }
                        Spacer(modifier = Modifier.height(4.dp))
                        ToolCheck("Показывать код, который не разобрался до конца", showBad) { showBad = it }
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
                            val ready = nameProblem == null && target != null
                            PrimaryPill("Декомпилировать", enabled = ready) {
                                if (target != null) {
                                    onStart(JadxExportOptions(gradle = gradle, resources = resources, deobfuscate = deobfuscate, showBadCode = showBad), target)
                                }
                            }
                        }
                    }
                    job.running -> RunningPart(job, onHide)
                    else -> FinishedPart(job, onOpenFolder, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun RunningPart(job: JadxJob, onHide: () -> Unit) {
    // Время идёт и без новых событий от jadx
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(job) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val p = job.progress
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = if (p.total > 0) "${p.stage}: ${grouped(p.done)} из ${grouped(p.total)}" else p.stage + "…",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    val fraction = job.fraction
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
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
    Text(
        text = "Прошло ${duration(now - job.startedAt)} · в папку «${job.outDir.fileName}»",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Можно свернуть — работа продолжится",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        ToolAction("Остановить", Icons.Default.Stop, danger = true) { job.cancel.set(true) }
        Spacer(modifier = Modifier.width(6.dp))
        PrimaryPill("Свернуть", onClick = onHide)
    }
}

@Composable
private fun FinishedPart(job: JadxJob, onOpenFolder: (Path) -> Unit, onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(14.dp))
    val result = job.result
    when {
        result != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NoctaliaOk.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Готово за ${duration(result.millis)}: ${grouped(result.classes)} ${org.wexclient.app.util.Plural.ru(result.classes.toLong(), "класс", "класса", "классов")}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (result.errors > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Не разобрались до конца: ${grouped(result.errors)} — такие места помечены в коде комментарием JADX",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.outDir.toString(),
                fontSize = 11.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProjectIdes.found.take(2).forEach { (label, command) ->
                    ToolAction(label, Icons.AutoMirrored.Filled.OpenInNew) {
                        runCatching { ProjectIdes.open(command, result.outDir) }
                        onDismiss()
                    }
                }
                PrimaryPill("Открыть в панели") {
                    onOpenFolder(result.outDir)
                    onDismiss()
                }
            }
        }
        else -> {
            Text(
                text = if (job.stopped) "Остановлено — недоделанная папка удалена" else "Не получилось: ${job.error}",
                fontSize = 12.sp,
                color = if (job.stopped) MaterialTheme.colorScheme.onSurface else NoctaliaDanger.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryPill("Закрыть", onClick = onDismiss)
            }
        }
    }
}

/** Главная кнопка окна: заливка и контур цвета темы, как «Подключиться» и «Сравнить». */
@Composable
private fun PrimaryPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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

private fun grouped(n: Int): String = String.format(Locale.forLanguageTag("ru"), "%,d", n)

internal fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 1 -> "меньше секунды"
        s < 60 -> "$s с"
        s < 3600 -> "${s / 60} мин ${s % 60} с"
        else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
    }
}
