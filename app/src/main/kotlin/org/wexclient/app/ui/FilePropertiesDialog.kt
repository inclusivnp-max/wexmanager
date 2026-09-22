package org.wexclient.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.util.Plural
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import org.wexclient.app.vfs.VfsManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Сведения для окна свойств (читаются в фоне). */
internal class FileInfo(
    val isLink: Boolean,
    val linkTarget: String?,
    val linkBroken: Boolean,
    val size: Long,
    val modified: FileTime?,
    val created: FileTime?,
    val accessed: FileTime?,
    val owner: String?,
    val group: String?,
    val permissions: Set<PosixFilePermission>?,
    val mime: String?,
    val local: Boolean
)

internal class DirStats(val files: Long, val dirs: Long, val bytes: Long, val unreadable: Long)

internal object FileProperties {
    private val ru = Locale.forLanguageTag("ru")
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss", ru)

    fun read(path: Path): FileInfo {
        val local = path.fileSystem === FileSystems.getDefault()
        val isLink = Files.isSymbolicLink(path)
        val linkTarget = if (isLink) runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() else null
        val basic = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()
        val linkBroken = isLink && basic == null
        val attrs = basic ?: runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        val posix = if (local) runCatching { Files.readAttributes(path, PosixFileAttributes::class.java) }.getOrNull() else null
        return FileInfo(
            isLink = isLink,
            linkTarget = linkTarget,
            linkBroken = linkBroken,
            size = attrs?.size() ?: 0L,
            modified = attrs?.lastModifiedTime(),
            created = attrs?.creationTime(),
            accessed = attrs?.lastAccessTime(),
            owner = posix?.owner()?.name,
            group = posix?.group()?.name,
            permissions = posix?.permissions(),
            mime = if (local && attrs?.isRegularFile == true) runCatching { Files.probeContentType(path) }.getOrNull() else null,
            local = local
        )
    }

    fun date(t: FileTime?): String =
        t?.let { dateFormat.format(it.toInstant().atZone(ZoneId.systemDefault())) } ?: "—"

    fun number(n: Long): String = String.format(ru, "%,d", n)

    /** «rwxr-xr-x» */
    fun permString(p: Set<PosixFilePermission>): String = buildString {
        for ((r, w, x) in permTriples) {
            append(if (r in p) 'r' else '-')
            append(if (w in p) 'w' else '-')
            append(if (x in p) 'x' else '-')
        }
    }

    /** «755» */
    fun octal(p: Set<PosixFilePermission>): String = permTriples.joinToString("") { (r, w, x) ->
        ((if (r in p) 4 else 0) + (if (w in p) 2 else 0) + (if (x in p) 1 else 0)).toString()
    }

    val permTriples = listOf(
        Triple(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        Triple(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE),
        Triple(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
    )

    /** Размер папки: обход без перехода по ссылкам; [progress] — примерно раз в 150 мс. */
    fun dirStats(dir: Path, cancelled: () -> Boolean, progress: (DirStats) -> Unit): DirStats? {
        var files = 0L
        var dirs = 0L
        var bytes = 0L
        var unreadable = 0L
        var lastReport = System.nanoTime()
        var stopped = false
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            fun tick(): FileVisitResult {
                if (cancelled()) {
                    stopped = true
                    return FileVisitResult.TERMINATE
                }
                val now = System.nanoTime()
                if (now - lastReport > 150_000_000L) {
                    lastReport = now
                    progress(DirStats(files, dirs, bytes, unreadable))
                }
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (d != dir) dirs++
                return tick()
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                files++
                if (!attrs.isDirectory) bytes += attrs.size()
                return tick()
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                unreadable++
                return tick()
            }
        })
        return if (stopped) null else DirStats(files, dirs, bytes, unreadable)
    }

    fun typeTitle(type: VfsItemType, isDirectory: Boolean, isArchive: Boolean): String = when {
        isDirectory && !isArchive -> "Папка"
        else -> when (type) {
            VfsItemType.FOLDER, VfsItemType.PARENT_DIR -> "Папка"
            VfsItemType.ARCHIVE_APK -> "Приложение Android (APK)"
            VfsItemType.ARCHIVE_APKS -> "Набор split-APK (APKS, XAPK, APKM)"
            VfsItemType.ARCHIVE_ZIP -> "Архив"
            VfsItemType.ARCHIVE_JAR -> "Java-архив (JAR)"
            VfsItemType.ARCHIVE_AAR -> "Библиотека или бандл Android"
            VfsItemType.APPIMAGE -> "Приложение Linux (AppImage)"
            VfsItemType.DEX -> "Байт-код Android (DEX)"
            VfsItemType.JAVA_CLASS -> "Класс Java (.class)"
            VfsItemType.ARSC -> "Ресурсы Android (ARSC)"
            VfsItemType.AXML_MANIFEST -> "Манифест Android"
            VfsItemType.XML -> "Документ XML"
            VfsItemType.SMALI -> "Smali"
            VfsItemType.CODE_KOTLIN -> "Код Kotlin"
            VfsItemType.CODE_JAVA -> "Код Java"
            VfsItemType.CODE_PYTHON -> "Код Python"
            VfsItemType.CODE_LUA -> "Код Lua"
            VfsItemType.CODE_JAVASCRIPT -> "Код JavaScript"
            VfsItemType.CODE_TYPESCRIPT -> "Код TypeScript"
            VfsItemType.CODE_C_CPP -> "Код C/C++"
            VfsItemType.CODE_RUST -> "Код Rust"
            VfsItemType.CODE_GO -> "Код Go"
            VfsItemType.CODE_CSS -> "Стили CSS"
            VfsItemType.CODE_HTML -> "Страница HTML"
            VfsItemType.CODE_PINE -> "Pine Script"
            VfsItemType.CODE_SQL -> "Запрос SQL"
            VfsItemType.CODE_RUBY -> "Код Ruby"
            VfsItemType.CODE_PHP -> "Код PHP"
            VfsItemType.CODE_SWIFT -> "Код Swift"
            VfsItemType.CODE_CSHARP -> "Код C#"
            VfsItemType.CODE_DART -> "Код Dart"
            VfsItemType.CODE_OTHER -> "Исходный код"
            VfsItemType.BUILD_SCRIPT -> "Скрипт сборки"
            VfsItemType.PROGUARD -> "Правила ProGuard"
            VfsItemType.SHELL_SCRIPT -> "Скрипт оболочки"
            VfsItemType.BATCH_SCRIPT -> "Скрипт Windows"
            VfsItemType.NATIVE_SO -> "Нативная библиотека (ELF)"
            VfsItemType.CERTIFICATE -> "Сертификат"
            VfsItemType.KEYSTORE -> "Ключ подписи"
            VfsItemType.IMAGE -> "Изображение"
            VfsItemType.VIDEO -> "Видео"
            VfsItemType.AUDIO -> "Аудио"
            VfsItemType.AUDIO_VIDEO -> "Медиафайл"
            VfsItemType.JSON -> "Данные JSON"
            VfsItemType.YAML -> "Данные YAML"
            VfsItemType.MARKDOWN -> "Документ Markdown"
            VfsItemType.PROPERTIES_CONF -> "Настройки"
            VfsItemType.TEXT_FILE -> "Текст"
            VfsItemType.DATABASE -> "База данных"
            VfsItemType.DOCUMENT_PDF -> "Документ PDF"
            VfsItemType.DOCUMENT -> "Документ"
            VfsItemType.SPREADSHEET -> "Таблица"
            VfsItemType.PRESENTATION -> "Презентация"
            VfsItemType.FONT -> "Шрифт"
            VfsItemType.PACKAGE -> "Пакет или образ"
            VfsItemType.BINARY -> "Двоичный файл"
            VfsItemType.PATCH -> "Заплатка"
            VfsItemType.DESKTOP_ENTRY -> "Ярлык запуска"
            VfsItemType.SPECIAL -> "Специальный файл"
            VfsItemType.REGULAR_FILE -> "Файл"
        }
    }
}

@Composable
fun FilePropertiesDialog(
    entry: VfsEntry,
    vfsManager: VfsManager,
    onDismiss: () -> Unit,
    /** Полное окно контрольных сумм (с проверкой) */
    onChecksum: (() -> Unit)? = null,
    /** Права поменяли — обновить панели */
    onChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val path = remember(entry.path) { runCatching { vfsManager.live(entry.path) }.getOrDefault(entry.path) }
    var info by remember { mutableStateOf<FileInfo?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, reload) {
        info = withContext(Dispatchers.IO) { runCatching { FileProperties.read(path) }.getOrNull() }
    }
    val special = remember(path) { runCatching { vfsManager.specialFileKind(path) }.getOrNull() }
    val isFolder = entry.isDirectory && !entry.isArchive

    // Размер папки
    var stats by remember { mutableStateOf<DirStats?>(null) }
    var statsDone by remember { mutableStateOf(false) }
    if (isFolder) {
        LaunchedEffect(path) {
            val job = coroutineContext[Job]
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Папка-ссылка: считаем то, на что она указывает
                    val root = if (path.fileSystem === FileSystems.getDefault()) path.toRealPath() else path
                    FileProperties.dirStats(root, cancelled = { job?.isActive == false }) { stats = it }
                }.getOrNull()
            }
            if (result != null) stats = result
            statsDone = true
        }
    }

    // Контрольные суммы — потоком, с прогрессом
    var md5 by remember { mutableStateOf<String?>(null) }
    var sha256 by remember { mutableStateOf<String?>(null) }
    var hashProgress by remember { mutableStateOf(0f) }
    var hashError by remember { mutableStateOf<String?>(null) }
    val hashable = !entry.isDirectory && special == null
    if (hashable) {
        LaunchedEffect(path) {
            val job = coroutineContext[Job]
            withContext(Dispatchers.IO) {
                try {
                    val total = runCatching { vfsManager.size(path) }.getOrDefault(entry.size)
                    val md = MessageDigest.getInstance("MD5")
                    val sha = MessageDigest.getInstance("SHA-256")
                    vfsManager.openInputStream(path).use { input ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var reported = -1
                        while (job?.isActive != false) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            sha.update(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (pct != reported) {
                                reported = pct
                                hashProgress = pct / 100f
                            }
                        }
                    }
                    if (job?.isActive != false) {
                        md5 = md.digest().joinToString("") { "%02x".format(it) }
                        sha256 = sha.digest().joinToString("") { "%02x".format(it) }
                    }
                } catch (e: Exception) {
                    hashError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    // Права
    var perms by remember(info) { mutableStateOf(info?.permissions) }
    var permStatus by remember { mutableStateOf<String?>(null) }
    var permError by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp,
            modifier = Modifier.width(600.dp).dialogGlass(RoundedCornerShape(16.dp)).heightIn(max = 760.dp)
        ) {
            Column {
                // Шапка
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileIconBadge(entry.itemType, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            buildString {
                                append(special?.replaceFirstChar { it.uppercase() } ?: FileProperties.typeTitle(entry.itemType, entry.isDirectory, entry.isArchive))
                                info?.mime?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    val i = info
                    Section("Общие")
                    PropRow("Папка", path.parent?.toString() ?: "/", mono = true, copyable = true)
                    if (i?.isLink == true) {
                        PropRow(
                            "Ссылка на",
                            (i.linkTarget ?: "?") + if (i.linkBroken) "  (цели нет)" else "",
                            mono = true,
                            copyable = true,
                            warn = i.linkBroken
                        )
                    }
                    if (isFolder) {
                        val st = stats
                        PropRow(
                            "Размер",
                            when {
                                st == null -> "подсчёт…"
                                else -> "${humanSize(st.bytes)} (${FileProperties.number(st.bytes)} байт)" + if (!statsDone) " …" else ""
                            }
                        )
                        PropRow(
                            "Содержимое",
                            st?.let {
                                Plural.files(it.files.toInt()) + ", " + it.dirs + " " + Plural.ru(it.dirs, "папка", "папки", "папок") +
                                    if (it.unreadable > 0) " · нет доступа: ${it.unreadable}" else ""
                            } ?: "подсчёт…"
                        )
                    } else if (i != null) {
                        PropRow("Размер", "${humanSize(i.size)} (${FileProperties.number(i.size)} байт)")
                    }
                    PropRow("Изменён", FileProperties.date(i?.modified))
                    if (i?.created != null && i.created != i.modified) PropRow("Создан", FileProperties.date(i.created))
                    PropRow("Открыт", FileProperties.date(i?.accessed))
                    if (i?.owner != null) PropRow("Владелец", "${i.owner} : ${i.group ?: "?"}")

                    val current = perms
                    if (i != null && current != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Section("Права доступа")
                        val editable = i.local && !i.isLink
                        PermissionGrid(current, enabled = editable && !applying, isFolder = isFolder) { perms = it }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                "${FileProperties.permString(current)}  ·  ${FileProperties.octal(current)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            permStatus?.let {
                                Text(it, fontSize = 12.sp, color = if (permError) NoctaliaError else NoctaliaSecondary)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (editable) {
                                Button(
                                    enabled = !applying && current != i.permissions,
                                    colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                                    onClick = {
                                        applying = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                runCatching { Files.setPosixFilePermissions(path, current) }
                                            }
                                            applying = false
                                            permError = r.isFailure
                                            permStatus = r.fold({ "Права изменены" }, { "Не удалось: ${it.message ?: it.javaClass.simpleName}" })
                                            if (r.isSuccess) onChanged()
                                            reload++
                                        }
                                    }
                                ) { Text("Применить") }
                            }
                        }
                    }

                    if (hashable) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Section("Контрольные суммы", modifier = Modifier.weight(1f))
                            if (onChecksum != null) {
                                TextButton(onClick = onChecksum) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Все суммы и проверка", fontSize = 12.sp)
                                }
                            }
                        }
                        when {
                            hashError != null -> Text("Не удалось прочитать: $hashError", color = NoctaliaError, fontSize = 12.sp)
                            md5 == null -> LinearProgressIndicator(
                                progress = { hashProgress },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                color = NoctaliaPrimary
                            )
                            else -> {
                                HashRow("MD5", md5!!)
                                HashRow("SHA-256", sha256!!)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Section("Путь")
                    PropRow("", path.toString(), mono = true, copyable = true)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = NoctaliaPrimary,
        modifier = modifier.padding(bottom = 4.dp)
    )
}

private fun copy(text: String) {
    val sel = StringSelection(text)
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel) }
}

@Composable
private fun PropRow(label: String, value: String, mono: Boolean = false, copyable: Boolean = false, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(110.dp)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (mono) 11.5.sp else 12.sp
            ),
            color = if (warn) NoctaliaError else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (copyable) {
            IconButton(onClick = { copy(value) }, modifier = Modifier.size(24.dp).noFocus()) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun PermissionGrid(
    perms: Set<PosixFilePermission>,
    enabled: Boolean,
    isFolder: Boolean,
    onChange: (Set<PosixFilePermission>) -> Unit
) {
    val rows = listOf("Владелец", "Группа", "Остальные")
    val cols = listOf("Чтение", "Запись", if (isFolder) "Вход" else "Запуск")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.width(110.dp))
            cols.forEach {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
            }
        }
        FileProperties.permTriples.forEachIndexed { index, triple ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                Text(rows[index], fontSize = 12.sp, modifier = Modifier.width(110.dp))
                listOf(triple.first, triple.second, triple.third).forEach { p ->
                    Row(modifier = Modifier.width(80.dp)) {
                        Checkbox(
                            checked = p in perms,
                            enabled = enabled,
                            onCheckedChange = { on -> onChange(if (on) perms + p else perms - p) },
                            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                            modifier = Modifier.size(28.dp).noFocus()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HashRow(label: String, hash: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NoctaliaPrimary,
                modifier = Modifier.width(64.dp)
            )
            Text(
                text = hash,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { copy(hash) }, modifier = Modifier.size(22.dp).noFocus()) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", modifier = Modifier.size(13.dp))
            }
        }
    }
}
