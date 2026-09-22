package org.wexclient.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import java.io.File
import java.util.Properties

/** Фильтр списка по типу (на панель, не сохраняется). Папки видны всегда — чтобы можно было ходить. */
enum class TypeFilter(val title: String, val symbol: String) {
    ALL("Все файлы", "select_all"),
    FOLDERS("Только папки", "folder"),
    APK("APK / APKS / XAPK", "android"),
    ARCHIVES("Архивы", "folder_zip"),
    IMAGES("Изображения", "image"),
    VIDEO("Видео", "movie"),
    AUDIO("Аудио", "music_note"),
    CODE("Код и текст", "code"),
    ANDROID_PARTS("DEX / CLASS / ARSC / SO", "deployed_code");

    fun accepts(e: VfsEntry): Boolean = when (this) {
        ALL -> true
        FOLDERS -> e.isDirectory
        else -> e.isDirectory || acceptsFile(e)
    }

    private fun acceptsFile(e: VfsEntry): Boolean = when (this) {
        APK -> e.itemType == VfsItemType.ARCHIVE_APK || e.itemType == VfsItemType.ARCHIVE_APKS
        ARCHIVES -> e.isExtractableArchive && e.itemType != VfsItemType.ARCHIVE_APK && e.itemType != VfsItemType.ARCHIVE_APKS
        IMAGES -> e.isImage
        VIDEO -> e.isVideo
        AUDIO -> e.isAudio
        CODE -> e.itemType in codeTypes
        ANDROID_PARTS -> e.itemType in setOf(
            VfsItemType.DEX, VfsItemType.JAVA_CLASS, VfsItemType.ARSC, VfsItemType.NATIVE_SO, VfsItemType.AXML_MANIFEST
        )
        else -> true
    }

    private companion object {
        val codeTypes = setOf(
            VfsItemType.XML, VfsItemType.SMALI, VfsItemType.CODE_KOTLIN, VfsItemType.CODE_JAVA,
            VfsItemType.CODE_PYTHON, VfsItemType.CODE_LUA, VfsItemType.CODE_JAVASCRIPT,
            VfsItemType.CODE_TYPESCRIPT, VfsItemType.CODE_C_CPP, VfsItemType.CODE_RUST, VfsItemType.CODE_GO,
            VfsItemType.CODE_CSS, VfsItemType.CODE_HTML, VfsItemType.CODE_PINE, VfsItemType.BATCH_SCRIPT,
            VfsItemType.SHELL_SCRIPT, VfsItemType.JSON, VfsItemType.YAML, VfsItemType.MARKDOWN,
            VfsItemType.PROPERTIES_CONF, VfsItemType.TEXT_FILE,
            VfsItemType.CODE_SQL, VfsItemType.CODE_RUBY, VfsItemType.CODE_PHP, VfsItemType.CODE_SWIFT,
            VfsItemType.CODE_CSHARP, VfsItemType.CODE_DART, VfsItemType.CODE_OTHER,
            VfsItemType.BUILD_SCRIPT, VfsItemType.PROGUARD, VfsItemType.PATCH
        )
    }
}

/** Настройки отображения списка файлов (~/.config/wexdesktop/view.properties). */
object ViewPreferences {
    private val configFile get() = File(System.getProperty("user.home"), ".config/wexdesktop/view.properties")

    var showHidden by mutableStateOf(true)
        private set
    var foldersFirst by mutableStateOf(true)
        private set
    var naturalSort by mutableStateOf(true)
        private set
    var showThumbnails by mutableStateOf(true)
        private set
    var showSizeColumn by mutableStateOf(true)
        private set
    var showDateColumn by mutableStateOf(true)
        private set
    /** Пометки git у файлов и ветка в нижней строке */
    var showGitStatus by mutableStateOf(true)
        private set
    /** Полоса вкладок под списком файлов; по умолчанию — вверху, под панелью инструментов. */
    var tabsAtBottom by mutableStateOf(false)
        private set
    /** Плавные появления и переходы. Выключение делает работу мгновенной — полезно на слабой машине. */
    var animations by mutableStateOf(true)
        private set

    /**
     * Картинки по http(s) в просмотре Markdown. Выключено — вместо картинки плашка, и чужой
     * сервер не узнаёт, что документ открыли (в присланном файле это может быть счётчик).
     * Адреса в локальной сети всегда грузятся только по щелчку.
     */
    var remoteImages by mutableStateOf(true)
        private set
    /** Размер шрифта в деревьях и сравнении кода: Ctrl+колесо меняет, значение живёт между запусками. */
    var codeFontSize by mutableStateOf(12f)
        private set

    /** Меняется при любой настройке, влияющей на состав/порядок списка. */
    var listVersion by mutableStateOf(0)
        private set

    private val sorts = HashMap<String, Pair<SortField, Boolean>>()

    init {
        load()
    }

    fun updateShowHidden(v: Boolean) = update(affectsList = true) { showHidden = v }
    fun updateFoldersFirst(v: Boolean) = update(affectsList = true) { foldersFirst = v }
    fun updateNaturalSort(v: Boolean) = update(affectsList = true) { naturalSort = v }
    fun updateShowThumbnails(v: Boolean) = update(affectsList = false) { showThumbnails = v }
    fun updateShowSizeColumn(v: Boolean) = update(affectsList = false) { showSizeColumn = v }
    fun updateShowDateColumn(v: Boolean) = update(affectsList = false) { showDateColumn = v }
    fun updateShowGitStatus(v: Boolean) = update(affectsList = true) { showGitStatus = v }
    fun updateTabsAtBottom(v: Boolean) = update(affectsList = false) { tabsAtBottom = v }
    fun updateAnimations(v: Boolean) = update(affectsList = false) { animations = v }
    fun updateRemoteImages(v: Boolean) = update(affectsList = false) { remoteImages = v }
    fun updateCodeFontSize(v: Float) = update(affectsList = false) { codeFontSize = v.coerceIn(8f, 22f) }
    fun zoomCode(delta: Float) = updateCodeFontSize(if (delta == 0f) 12f else codeFontSize + delta)

    fun sortFor(side: String): Pair<SortField, Boolean> = sorts[side] ?: (SortField.NAME to true)

    fun saveSort(side: String, field: SortField, ascending: Boolean) {
        if (sorts[side] == field to ascending) return
        sorts[side] = field to ascending
        save()
    }

    private fun update(affectsList: Boolean, block: () -> Unit) {
        block()
        if (affectsList) listVersion++
        save()
    }

    private fun load() {
        try {
            val f = configFile
            if (!f.exists()) return
            val p = Properties()
            f.inputStream().use { p.load(it) }
            fun bool(key: String, def: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: def
            showHidden = bool("showHidden", showHidden)
            foldersFirst = bool("foldersFirst", foldersFirst)
            naturalSort = bool("naturalSort", naturalSort)
            showThumbnails = bool("showThumbnails", showThumbnails)
            showSizeColumn = bool("showSizeColumn", showSizeColumn)
            showDateColumn = bool("showDateColumn", showDateColumn)
            showGitStatus = bool("showGitStatus", showGitStatus)
            tabsAtBottom = bool("tabsAtBottom", tabsAtBottom)
            animations = bool("animations", animations)
            remoteImages = bool("remoteImages", remoteImages)
            codeFontSize = p.getProperty("codeFontSize")?.toFloatOrNull()?.coerceIn(8f, 22f) ?: codeFontSize
            for (name in p.stringPropertyNames().filter { it.startsWith("sort.") }) {
                val (field, dir) = p.getProperty(name).split(',').let { it[0] to it.getOrNull(1) }
                val sf = runCatching { SortField.valueOf(field) }.getOrNull() ?: continue
                sorts[name.removePrefix("sort.")] = sf to (dir != "desc")
            }
        } catch (_: Throwable) {
            // значения по умолчанию
        }
    }

    private fun save() {
        try {
            val f = configFile
            f.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("showHidden", showHidden.toString())
            p.setProperty("foldersFirst", foldersFirst.toString())
            p.setProperty("naturalSort", naturalSort.toString())
            p.setProperty("showThumbnails", showThumbnails.toString())
            p.setProperty("showSizeColumn", showSizeColumn.toString())
            p.setProperty("showDateColumn", showDateColumn.toString())
            p.setProperty("showGitStatus", showGitStatus.toString())
            p.setProperty("tabsAtBottom", tabsAtBottom.toString())
            p.setProperty("animations", animations.toString())
            p.setProperty("remoteImages", remoteImages.toString())
            p.setProperty("codeFontSize", codeFontSize.toString())
            for ((side, s) in sorts) {
                p.setProperty("sort.$side", "${s.first.name},${if (s.second) "asc" else "desc"}")
            }
            org.wexclient.app.util.AtomicFiles.writeProperties(f, p, "WEX Desktop View Preferences")
        } catch (_: Throwable) {
            // не критично
        }
    }
}
