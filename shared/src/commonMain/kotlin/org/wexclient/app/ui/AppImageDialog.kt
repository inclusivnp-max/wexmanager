package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.appimage.AppImageInfo
import org.wexclient.app.appimage.AppImages
import org.wexclient.app.appimage.InstalledApp
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val AppImageBlue = Color(0xFF60A5FA)

/**
 * AppImage: сведения из образа, запуск, установка в меню (как Gear Lever), обновление,
 * удаление из системы и распаковка.
 */
@Composable
fun AppImageDialog(
    entry: VfsEntry,
    onDismiss: () -> Unit,
    /** Консольное приложение — во встроенную консоль: (папка, команда) */
    onRunInTerminal: (Path, String) -> Unit,
    onMessage: (String) -> Unit,
    /** Файлы поменялись: обновить панели и показать [Path] (папку или файл) */
    onChanged: (Path?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<AppImageInfo?>(null) }
    var installed by remember { mutableStateOf<InstalledApp?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val confirmRemove = rememberArmedConfirm()
    val folder = remember { AppImages.appImagesDir() }
    val home = remember { Paths.get(org.wexclient.app.util.AppDirs.userHome.toString()) }
    var move by remember { mutableStateOf(AppImages.moveByDefault()) }

    fun pretty(p: Path): String = if (p.startsWith(home)) "~/" + home.relativize(p) else p.toString()

    suspend fun reload(path: Path) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val i = AppImages.inspect(path)
                Triple(i, AppImages.installedFor(i), i.icon?.let { AppImages.iconBitmap(it, 128) })
            }
        }
        loaded.onSuccess { (i, inst, bmp) ->
            info = i
            installed = inst
            icon = bmp
            loadError = null
        }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(entry.path) { reload(entry.path) }

    fun work(label: String, action: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        status = null
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                status = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
                confirmRemove.reset()
            }
        }
    }

    ModalDialog(onDismissRequest = { if (busy == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = busy == null) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, AppImageBlue.copy(alpha = 0.35f)),
            modifier = Modifier.width(620.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column {
                if (busy != null) {
                    WexLinearSpinner(modifier = Modifier.fillMaxWidth().height(3.dp), color = AppImageBlue)
                } else {
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 18.dp)) {
                    val i = info
                    // Шапка: иконка приложения, имя, версия, описание
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AppImageBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val bmp = icon
                            if (bmp != null) {
                                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(52.dp))
                            } else {
                                Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = AppImageBlue, modifier = Modifier.size(34.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = i?.name ?: AppImages.nameFromFile(entry.path),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                val version = i?.version
                                if (version != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = AppImageBlue.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            version,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = AppImageBlue,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = i?.comment ?: "Приложение AppImage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismiss, enabled = busy == null) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val error = loadError
                    when {
                        error != null -> NoticeCard(Icons.Default.Warning, NoctaliaError, "Не удалось прочитать образ: $error")
                        i == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 18.dp)) {
                            WexSpinner(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppImageBlue)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Чтение образа…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        else -> {
                            AppInfoRow("Файл", i.path.fileName.toString())
                            AppInfoRow("Папка", pretty(i.path.parent ?: i.path))
                            AppInfoRow("Размер", runCatching { formatBytes(Files.size(i.path)) }.getOrDefault("—"))
                            AppInfoRow(
                                "Архитектура",
                                (i.arch ?: "неизвестна") + if (i.foreignArch) " — не для этого компьютера (${AppImages.systemArch()})" else "",
                                valueColor = if (i.foreignArch) NoctaliaError else null
                            )
                            AppInfoRow(
                                "Формат",
                                when (i.type) {
                                    2 -> listOfNotNull("AppImage 2", i.compression?.let { "squashfs $it" }, "${i.fileCount} файлов").joinToString(" · ")
                                    1 -> "AppImage 1 (устаревший, ISO 9660)"
                                    else -> "не распознан — возможно, это не AppImage"
                                }
                            )
                            if (i.categories.isNotEmpty()) AppInfoRow("Категории", i.categories.joinToString(", "))
                            if (i.terminal) AppInfoRow("Запуск", "в терминале (консольное приложение)")

                            Spacer(modifier = Modifier.height(12.dp))
                            val inst = installed
                            when {
                                inst != null && inst.sameFile -> NoticeCard(
                                    Icons.Default.CheckCircle, NoctaliaPrimary,
                                    "Установлено: ярлык «${inst.desktopFile.fileName}» в меню приложений"
                                )
                                inst != null -> NoticeCard(
                                    Icons.Default.Info, AppImageBlue,
                                    "Установлена ${inst.version?.let { "версия $it" } ?: "другая версия"}: ${pretty(inst.appImage)}" +
                                        if (i.version != null && inst.version != i.version) ". Можно обновить до ${i.version}" else ""
                                )
                                else -> NoticeCard(
                                    Icons.Default.Info, MaterialTheme.colorScheme.outline,
                                    "Не установлено — будет в ${pretty(folder)} и в меню приложений"
                                )
                            }
                            // Файл уже в папке приложений — переносить нечего
                            val outside = !i.path.toAbsolutePath().normalize().startsWith(folder)
                            if (outside && inst?.sameFile != true) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                    Checkbox(
                                        checked = move,
                                        onCheckedChange = { move = it },
                                        enabled = busy == null,
                                        colors = CheckboxDefaults.colors(checkedColor = AppImageBlue)
                                    )
                                    Text(
                                        "Переместить файл (здесь он исчезнет), иначе — копия",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            status?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (it.startsWith("Ошибка")) NoctaliaError else NoctaliaPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = busy == null && i.type != 0,
                                    onClick = {
                                        work("Распаковка…") {
                                            val parent = i.path.parent ?: return@work
                                            val dest = withContext(Dispatchers.IO) { AppImages.extract(i, parent) }
                                            status = "Распаковано в «${dest.fileName}»"
                                            onChanged(dest)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Распаковать")
                                }
                                if (inst != null) {
                                    OutlinedButton(
                                        enabled = busy == null,
                                        border = BorderStroke(1.dp, NoctaliaError.copy(alpha = if (confirmRemove.armed) 1f else 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NoctaliaError),
                                        onClick = {
                                            // Двойной щелчок не удаляет: второе нажатие — не раньше чем через полсекунды
                                            if (!confirmRemove.press()) return@OutlinedButton
                                            work("Удаление…") {
                                                val removedFile = withContext(Dispatchers.IO) { AppImages.uninstall(inst) }
                                                val name = i.name
                                                onChanged(null)
                                                if (removedFile && inst.appImage == i.path.toAbsolutePath().normalize()) {
                                                    onMessage("«$name» удалено из системы вместе с файлом")
                                                    onDismiss()
                                                } else {
                                                    status = "Ярлык удалён из меню" + if (removedFile) ", файл из ${pretty(folder)} удалён" else ""
                                                    reload(i.path)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (confirmRemove.armed) "Точно удалить?" else "Удалить из системы")
                                    }
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (inst?.sameFile != true && i.type != 0) {
                                    val update = inst != null
                                    OutlinedButton(
                                        enabled = busy == null && !i.foreignArch,
                                        border = BorderStroke(1.dp, AppImageBlue.copy(alpha = 0.7f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppImageBlue),
                                        onClick = {
                                            work(if (update) "Обновление…" else "Установка…") {
                                                val result = withContext(Dispatchers.IO) { AppImages.install(i, move, replace = inst) }
                                                onMessage("«${i.name}» ${if (update) "обновлено" else "установлено"} — есть в меню приложений")
                                                onChanged(result.appImage)
                                                reload(result.appImage)
                                                status = if (update) "Обновлено" else "Установлено в ${pretty(result.appImage)}"
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.DownloadForOffline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (update) "Обновить" else "Установить")
                                    }
                                }
                                Button(
                                    enabled = busy == null && !i.foreignArch && i.type != 0,
                                    colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                                    onClick = {
                                        val app = i
                                        work("Запуск…") {
                                            if (app.terminal) {
                                                val dir = app.path.parent ?: return@work
                                                withContext(Dispatchers.IO) { AppImages.makeExecutable(app.path) }
                                                onRunInTerminal(dir, org.wexclient.app.terminal.ScriptRunner.word("./" + app.path.fileName.toString()))
                                            } else {
                                                withContext(Dispatchers.IO) { AppImages.launch(app.path) }
                                            }
                                            onMessage("Запущено: ${app.name}")
                                            onDismiss()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Запустить")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppInfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NoticeCard(icon: ImageVector, tint: Color, text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun formatBytes(size: Long): String {
    if (size < 1024) return "$size Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = size / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}
