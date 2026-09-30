package org.wexclient.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.wexclient.app.vfs.VfsManager
import java.io.File
import java.nio.file.Path
import java.util.Properties

/**
 * Вкладки одной панели. У каждой вкладки своё состояние: путь, история переходов, прокрутка
 * и выделение — переключение возвращает ровно то, что было. Копирование и сравнение
 * по-прежнему работают между двумя панелями: вкладка просто меняет содержимое своей.
 */
class PaneTabs(
    private val vfsManager: VfsManager,
    /** «left» или «right» — под этим именем вкладки сохраняются между запусками. */
    private val side: String,
    private val home: Path
) {
    val tabs = mutableStateListOf<FilePaneState>()

    var index by mutableStateOf(0)
        private set

    val active: FilePaneState get() = tabs.getOrElse(index) { tabs.first() }

    /** Номера закреплённых вкладок из прошлого запуска — применяются при создании вкладок. */
    private var savedPins: Set<Int> = emptySet()

    init {
        val saved = load()
        if (saved.isEmpty()) {
            tabs.add(FilePaneState(vfsManager, home, side))
        } else {
            // Читаем только ту папку, которая сразу видна: остальные вкладки прочитаются,
            // когда на них переключатся (панель сама зовёт ensureListed)
            saved.forEachIndexed { at, path ->
                tabs.add(FilePaneState(vfsManager, path, side, listNow = at == index).also { it.pinned = at in savedPins })
            }
        }
    }

    /** Новая вкладка рядом с текущей, сразу открытая. */
    fun add(path: Path = active.currentPath) {
        val start = runCatching { if (java.nio.file.Files.isDirectory(path)) path else path.parent ?: home }.getOrDefault(home)
        tabs.add(index + 1, FilePaneState(vfsManager, start, side))
        index += 1
        save()
    }

    /**
     * Спросить перед закрытием вкладки. Вернул false — закрытие отложено: спрашивающий
     * сам позовёт [closeNow], если пользователь согласится.
     */
    var onCloseRequest: ((Int) -> Boolean)? = null

    /** Закрывает вкладку; последнюю и закреплённую не закрываем. */
    fun close(at: Int) {
        if (tabs.size <= 1 || at !in tabs.indices) return
        if (tabs[at].pinned) return
        if (onCloseRequest?.invoke(at) == false) return
        closeNow(at)
    }

    /** Все вкладки, кроме этой и закреплённых. */
    fun closeOthers(keep: Int) {
        val keepTab = tabs.getOrNull(keep) ?: return
        for (i in tabs.indices.reversed()) {
            val tab = tabs[i]
            if (tab === keepTab || tab.pinned) continue
            close(i)
        }
    }

    /**
     * Все незакреплённые вкладки. Панель без вкладок не остаётся: если закреплённых нет,
     * на месте закрытых открывается домашняя папка.
     */
    fun closeAll() {
        val keepPinned = tabs.any { it.pinned }
        for (i in tabs.indices.reversed()) {
            val tab = tabs[i]
            if (tab.pinned) continue
            if (!keepPinned && tabs.size == 1) break
            close(i)
        }
        if (!keepPinned && tabs.size == 1 && tabs[0].currentPath != home) {
            tabs[0].navigateTo(home)
        }
        save()
    }

    /** Закрепить или открепить вкладку. */
    fun togglePin(at: Int) {
        val tab = tabs.getOrNull(at) ?: return
        tab.pinned = !tab.pinned
        save()
    }

    /** Закрыть вкладку без вопросов — вместе с её консолью и всем, что в ней запущено. */
    fun closeNow(at: Int) {
        if (tabs.size <= 1 || at !in tabs.indices) return
        tabs.removeAt(at).dispose()
        if (index >= tabs.size) index = tabs.size - 1
        else if (at < index) index -= 1
        save()
    }

    fun select(at: Int) {
        if (at in tabs.indices) {
            index = at
            save()
        }
    }

    /** Следующая ([step] = 1) или предыдущая вкладка по кругу. */
    fun cycle(step: Int) {
        if (tabs.size < 2) return
        index = ((index + step) % tabs.size + tabs.size) % tabs.size
        save()
    }

    /** Заголовок вкладки: имя папки, для корня — «/». */
    fun title(state: FilePaneState): String =
        state.currentPath.fileName?.toString()?.takeIf { it.isNotBlank() } ?: state.currentPath.toString()

    // ---- сохранение ----

    /**
     * Пути вкладок пишутся рядом с остальными настройками. Одна вкладка — файл не трогаем,
     * чтобы обычный запуск по-прежнему открывал домашнюю папку.
     */
    fun save() {
        // Второе окно менеджера расстановку вкладок не запоминает
        if (side.isBlank()) return
        runCatching {
            val props = readProps()
            // Вкладки на сервере не запоминаем: без подключения их путь открылся бы на диске
            val paths = tabs.map { it.currentPath }
                .filter { it.fileSystem == java.nio.file.FileSystems.getDefault() }
                .map { it.toAbsolutePath().toString() }
            val pins = tabs.withIndex()
                .filter { (_, tab) -> tab.pinned && tab.currentPath.fileSystem == java.nio.file.FileSystems.getDefault() }
                .map { (i, _) -> i.toString() }
            if (paths.size <= 1 && pins.isEmpty()) {
                props.remove("$side.tabs")
                props.remove("$side.index")
                props.remove("$side.pinned")
            } else {
                props.setProperty("$side.tabs", paths.joinToString("\n"))
                props.setProperty("$side.index", index.toString())
                if (pins.isEmpty()) props.remove("$side.pinned")
                else props.setProperty("$side.pinned", pins.joinToString(","))
            }
            configDir.mkdirs()
            configFile.outputStream().use { props.store(it, "Вкладки панелей WEXdesktop") }
        }
    }

    private fun load(): List<Path> = runCatching {
        if (side.isBlank()) return emptyList()
        val props = readProps()
        val raw = props.getProperty("$side.tabs")?.split("\n")?.filter { it.isNotBlank() } ?: return emptyList()
        // Проверка папок — параллельно и с пределом: stat папки на отвалившемся сетевом диске висит без конца,
        // а зовётся это при старте из потока окна. Не ответила — вкладку оставляем, ошибку покажет чтение
        val checks = raw.mapNotNull { text -> runCatching { Path.of(text) }.getOrNull() }.map { path ->
            path to java.util.concurrent.CompletableFuture.supplyAsync({ java.nio.file.Files.isDirectory(path) }, probes)
        }
        val deadline = System.nanoTime() + PROBE_BUDGET_NS
        val paths = checks.mapNotNull { (path, check) ->
            val left = (deadline - System.nanoTime()).coerceAtLeast(0)
            val isDir = runCatching { check.get(left, java.util.concurrent.TimeUnit.NANOSECONDS) }
                .getOrElse { it is java.util.concurrent.TimeoutException }
            path.takeIf { isDir }
        }
        if (paths.isEmpty()) return emptyList()
        index = props.getProperty("$side.index")?.toIntOrNull()?.coerceIn(0, paths.size - 1) ?: 0
        savedPins = props.getProperty("$side.pinned")
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in paths.indices }
            ?.toSet()
            ?: emptySet()
        paths
    }.getOrDefault(emptyList())

    private fun readProps(): Properties {
        val props = Properties()
        if (configFile.isFile) runCatching { configFile.inputStream().use { props.load(it) } }
        return props
    }

    private companion object {
        val configDir = File(System.getProperty("user.home"), ".config/wexdesktop")
        val configFile = File(configDir, "tabs.properties")

        /** Сколько всего ждём проверки сохранённых вкладок при старте. */
        const val PROBE_BUDGET_NS = 400_000_000L

        /** Потоки проверки — фоновые: зависший stat не держит выход из программы. */
        val probes: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "wex-tab-probe").apply { isDaemon = true } }
        }
    }
}
