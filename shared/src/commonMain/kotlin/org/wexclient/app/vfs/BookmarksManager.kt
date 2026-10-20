package org.wexclient.app.vfs

import androidx.compose.runtime.mutableStateListOf
import org.wexclient.app.icons.SymbolRef
import java.io.File
import java.nio.file.Path

data class BookmarkItem(
    val title: String,
    val pathStr: String,
    val isSystem: Boolean = false,
    val icon: SymbolRef = SymbolRef.FOLDER
)

/**
 * Места быстрого доступа: чипы под строкой пути и пользовательская часть меню «Диски».
 * Хранятся по порядку в ~/.config/wexdesktop/places.tsv (название, путь, иконка).
 */
object BookmarksManager {
    private const val HEADER = "# WEX Desktop places v2: title<TAB>path<TAB>icon (\\t \\n \\\\ escaped)"
    private const val HEADER_V2_PREFIX = "# WEX Desktop places v2"

    private val home: String get() = org.wexclient.app.util.AppDirs.userHome.toString()
    private val isWindows = File.separatorChar == '\\'

    /** Windows: C:\, D:\… — список букв без обращения к самим дискам (сетевой не вешает поток). */
    private fun windowsDrives(): List<String> = File.listRoots().map { it.path }

    private val configDir: File get() = org.wexclient.app.util.AppDirs.config.toFile()
    private val placesFile: File get() = File(configDir, "places.tsv")
    private val legacyFile: File get() = File(configDir, "bookmarks.tsv")

    /** Пользовательские места в порядке отображения на панели. */
    val userBookmarks = mutableStateListOf<BookmarkItem>()

    init {
        load()
    }

    /** Системные места (дом, корень, XDG-папки, диски) + пользовательские, без повторов пути. */
    fun getQuickLocations(): List<BookmarkItem> {
        val list = mutableListOf<BookmarkItem>()
        list.add(BookmarkItem("Дом (~)", home, isSystem = true, icon = SymbolRef.parse("HOME")))
        if (isWindows) {
            windowsDrives().forEach { list.add(BookmarkItem("Диск ${it.trimEnd('\\')}", it, isSystem = true, icon = SymbolRef.parse("DRIVE"))) }
        } else {
            list.add(BookmarkItem("Корень (/)", "/", isSystem = true, icon = SymbolRef.parse("ROOT")))
        }

        xdgDir("DOWNLOAD")?.let { list.add(BookmarkItem("Загрузки", it, isSystem = true, icon = SymbolRef.parse("DOWNLOADS"))) }
        xdgDir("DOCUMENTS")?.let { list.add(BookmarkItem("Документы", it, isSystem = true, icon = SymbolRef("description", codepoint = 0xe873))) }
        xdgDir("DESKTOP")?.let { list.add(BookmarkItem("Рабочий стол", it, isSystem = true, icon = SymbolRef.parse("DESKTOP"))) }

        // Смонтированные диски (/media/$USER, /run/media/$USER, /mnt)
        val userName = System.getProperty("user.name")
        for (mRoot in listOf(File("/media/$userName"), File("/run/media/$userName"), File("/mnt"))) {
            if (mRoot.isDirectory) {
                mRoot.listFiles()?.filter { it.isDirectory && it.canRead() }?.forEach { drive ->
                    list.add(BookmarkItem("Диск: ${drive.name}", drive.absolutePath, isSystem = true, icon = SymbolRef.parse("DRIVE")))
                }
            }
        }

        val systemPaths = list.map { it.pathStr }.toSet()
        list.addAll(userBookmarks.filter { it.pathStr !in systemPaths })
        return list
    }

    fun isBookmarked(path: Path): Boolean {
        val pStr = normalize(path)
        return userBookmarks.any { it.pathStr == pStr }
    }

    fun toggleBookmark(path: Path, customTitle: String? = null) {
        val pStr = normalize(path)
        val existing = userBookmarks.find { it.pathStr == pStr }
        if (existing != null) {
            userBookmarks.remove(existing)
        } else {
            val title = customTitle ?: path.fileName?.toString() ?: pStr
            userBookmarks.add(BookmarkItem(title, pStr))
        }
        save()
    }

    fun removeBookmark(pathStr: String) {
        userBookmarks.removeAll { it.pathStr == pathStr }
        save()
    }

    fun add(title: String, pathStr: String, icon: SymbolRef) {
        userBookmarks.add(BookmarkItem(title.trim(), expand(pathStr), icon = icon))
        save()
    }

    fun update(old: BookmarkItem, title: String, pathStr: String, icon: SymbolRef) {
        val idx = userBookmarks.indexOf(old)
        val item = BookmarkItem(title.trim(), expand(pathStr), icon = icon)
        if (idx >= 0) userBookmarks[idx] = item else userBookmarks.add(item)
        save()
    }

    fun remove(item: BookmarkItem) {
        userBookmarks.remove(item)
        save()
    }

    fun move(item: BookmarkItem, delta: Int) {
        val from = userBookmarks.indexOf(item)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, userBookmarks.lastIndex)
        if (to == from) return
        userBookmarks.removeAt(from)
        userBookmarks.add(to, item)
        save()
    }

    fun resetToDefaults() {
        userBookmarks.clear()
        userBookmarks.addAll(defaults())
        save()
    }

    /** "~/x" -> абсолютный путь; лишние слэши и "." убираются. */
    fun expand(raw: String): String {
        val t = raw.trim()
        val expanded = when {
            t == "~" -> home
            t.startsWith("~/") -> home + t.substring(1)
            else -> t
        }
        return try {
            normalize(java.nio.file.Paths.get(expanded))
        } catch (_: Exception) {
            expanded
        }
    }

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    fun defaults(): List<BookmarkItem> {
        val list = mutableListOf(BookmarkItem("Домой", home, icon = SymbolRef.parse("HOME")))
        if (isWindows) windowsDrives().forEach { list.add(BookmarkItem(it.trimEnd('\\'), it, icon = SymbolRef.parse("DRIVE"))) }
        else list.add(BookmarkItem("Корень", "/", icon = SymbolRef.parse("ROOT")))
        xdgDir("DOWNLOAD")?.let { list.add(BookmarkItem("Загрузки", it, icon = SymbolRef.parse("DOWNLOADS"))) }
        xdgDir("DESKTOP")?.let { list.add(BookmarkItem("Стол", it, icon = SymbolRef.parse("DESKTOP"))) }
        listOf("IdeaProjects", "Проекты", "Projects", "AndroidStudioProjects")
            .map { File(home, it) }
            .firstOrNull { it.isDirectory }
            ?.let { list.add(BookmarkItem("Проекты", it.absolutePath, icon = SymbolRef("terminal", codepoint = 0xeb8e))) }
        return list
    }

    /** Папка из ~/.config/user-dirs.dirs (с запасным английским именем), если существует. */
    fun xdgDir(key: String): String? {
        val fromConfig = try {
            org.wexclient.app.util.AppDirs.configRoot.resolve("user-dirs.dirs").toFile().takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("XDG_${key}_DIR=") }
                ?.substringAfter('=')?.trim()?.trim('"')
                ?.replace("\$HOME", home)
        } catch (_: Exception) {
            null
        }
        val fallback = when (key) {
            "DOWNLOAD" -> "Downloads"
            "DESKTOP" -> "Desktop"
            "DOCUMENTS" -> "Documents"
            else -> key.lowercase()
        }
        // Если папка задана в user-dirs.dirs — только она; XDG_*_DIR="$HOME/" означает «отключена»
        val homeNorm = File(home).absoluteFile.normalize()
        val candidate = File(fromConfig ?: "$home/$fallback").absoluteFile.normalize()
        return candidate.takeIf { it != homeNorm && it.isDirectory }?.path
    }

    private fun load() {
        userBookmarks.clear()
        try {
            val file = placesFile
            if (file.isFile) {
                val lines = file.readLines()
                // v1 писал поля как есть; v2 экранирует табуляцию и переводы строк
                val escaped = lines.firstOrNull()?.startsWith(HEADER_V2_PREFIX) == true
                fun field(v: String) = if (escaped) org.wexclient.app.history.NavigationHistoryManager.unescapeField(v) else v
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val parts = line.split('\t')
                    if (parts.size >= 2) {
                        userBookmarks.add(BookmarkItem(field(parts[0]), field(parts[1]), icon = SymbolRef.parse(parts.getOrNull(2))))
                    }
                }
                return
            }
            // Первый запуск: стандартные чипы + закладки из старого bookmarks.tsv
            userBookmarks.addAll(defaults())
            if (legacyFile.isFile) {
                legacyFile.readLines().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size >= 2 && userBookmarks.none { it.pathStr == parts[1] }) {
                        userBookmarks.add(BookmarkItem(parts[0], parts[1], icon = SymbolRef.parse(parts.getOrNull(2))))
                    }
                }
            }
            save()
        } catch (_: Throwable) {
            if (userBookmarks.isEmpty()) userBookmarks.addAll(defaults())
        }
    }

    private fun save() {
        try {
            configDir.mkdirs()
            val text = buildString {
                appendLine(HEADER)
                userBookmarks.forEach { b ->
                    // Название — в одну строку; путь (в Linux перевод строки бывает и в именах папок) экранируется
                    val esc = org.wexclient.app.history.NavigationHistoryManager::escapeField
                    val title = b.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    appendLine("${esc(title)}\t${esc(b.pathStr)}\t${b.icon.encode()}")
                }
            }
            org.wexclient.app.util.AtomicFiles.write(placesFile) { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {
            // не критично
        }
    }

    /** Для тестов: перечитать файл. */
    internal fun reload() = load()
}
