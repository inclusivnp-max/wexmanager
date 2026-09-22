package org.wexclient.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import org.wexclient.app.util.NaturalOrder
import org.wexclient.app.vfs.VfsManager
import java.nio.file.FileSystem
import java.nio.file.Path

enum class SortField(val title: String, val symbol: String) {
    NAME("Имя", "sort_by_alpha"),
    SIZE("Размер", "straighten"),
    DATE("Дата изменения", "calendar_month"),
    TYPE("Тип", "category"),
    EXTENSION("Расширение", "extension")
}

enum class NavDirection {
    FORWARD, BACKWARD, NONE
}

class FilePaneState(
    val vfsManager: VfsManager,
    initialPath: Path,
    /** Ключ для сохранения сортировки ("left"/"right"); пустой — не сохранять. */
    private val side: String = "",
    /**
     * false — папку не читаем до первого показа вкладки. Восстановленные вкладки иначе читают
     * и сортируют все свои папки при запуске, задерживая появление окна.
     */
    listNow: Boolean = true
) {
    var currentPath by mutableStateOf(initialPath)
        private set

    val entries = mutableStateListOf<VfsEntry>()
    val selectedEntries = mutableStateListOf<VfsEntry>()

    /**
     * Номер перехода: меняется только при смене папки пользователем. Анимация смены списка
     * идёт по нему — после сохранения в архив путь переезжает в новую ФС, и список не должен «уезжать».
     */
    var navigationId by mutableStateOf(0)
        private set

    class Leaving(val navigationId: Int, val path: Path, val entries: List<VfsEntry>, val canGoUp: Boolean)

    /** Список папки, из которой только что ушли: его показывает уезжающая при анимации страница. */
    var leaving by mutableStateOf<Leaving?>(null)
        private set

    var searchQuery by mutableStateOf("")
    var isSearchOpen by mutableStateOf(false)
    var searchFocusTrigger by mutableStateOf(0)
    var isRecursiveSearch by mutableStateOf(true)
    var isSearching by mutableStateOf(false)
    var searchResultsCount by mutableStateOf<Int?>(null)

    fun requestSearchFocus() {
        isSearchOpen = true
        searchFocusTrigger++
    }

    /** В панели набирают текст (поиск, строка пути) — буквы не для перехода по именам. */
    var textInputFocused by mutableStateOf(false)
        internal set

    /** Ctrl+L: панель переключает строку пути в режим ввода. */
    var pathEditRequest by mutableStateOf(0)
        private set

    fun requestPathEdit() {
        pathEditRequest++
    }

    var sortField by mutableStateOf(if (side.isNotEmpty()) ViewPreferences.sortFor(side).first else SortField.NAME)
        private set
    var sortAscending by mutableStateOf(if (side.isNotEmpty()) ViewPreferences.sortFor(side).second else true)
        private set
    var isLoading by mutableStateOf(false)

    /** Что происходит, пока показан индикатор загрузки (распаковка архива). */
    var loadingText by mutableStateOf<String?>(null)

    /** Ошибка для показа пользователю (панель передаёт её в диалог и сбрасывает). */
    var errorMessage by mutableStateOf<String?>(null)

    private var archiveOpening = false
    private var archiveOpenCancel: java.util.concurrent.atomic.AtomicBoolean? = null

    /** Esc во время распаковки tar: прервать открытие (true — было что прерывать). */
    fun cancelArchiveOpening(): Boolean {
        val token = archiveOpenCancel ?: return false
        if (!archiveOpening || token.get()) return false
        token.set(true)
        loadingText = "Отмена…"
        return true
    }

    /** Фильтр по типу — только для этой панели и до перезапуска. */
    var typeFilter by mutableStateOf(TypeFilter.ALL)
        private set

    fun updateSort(field: SortField, ascending: Boolean) {
        sortField = field
        sortAscending = ascending
        if (side.isNotEmpty()) ViewPreferences.saveSort(side, field, ascending)
        refresh()
    }

    /** Клик по заголовку колонки: то же поле — смена направления. */
    fun toggleSort(field: SortField) {
        if (sortField == field) updateSort(field, !sortAscending) else updateSort(field, true)
    }

    fun applyTypeFilter(filter: TypeFilter) {
        if (typeFilter == filter) return
        typeFilter = filter
        refresh()
    }

    var navDirection by mutableStateOf(NavDirection.NONE)
    var recentlyReturnedChildName by mutableStateOf<String?>(null)

    /** Только что появившиеся в [arrivedIn] элементы (скопировали, переместили, распаковали) — подсветка. */
    var arrivedNames by mutableStateOf<Set<String>>(emptySet())
    private var arrivedIn: Path? = null
    /** Запрос плавно прокрутить к новым элементам; каждый вызов — новый объект. */
    var revealRequest by mutableStateOf<RevealRequest?>(null)
        private set

    fun isArrived(entry: VfsEntry): Boolean = entry.name in arrivedNames && arrivedIn == currentPath

    /** Курсор на первый из появившихся элементов, прокрутка к ним и подсветка всех. */
    fun revealArrived(names: Collection<String>) = whenLoaded { revealArrivedNow(names) }

    private fun revealArrivedNow(names: Collection<String>) {
        val wanted = names.toSet()
        val indices = entries.indices.filter { entries[it].name in wanted }
        if (indices.isEmpty()) return
        focusedIndex = indices.first()
        arrivedIn = currentPath
        arrivedNames = wanted
        revealRequest = RevealRequest(indices.first(), indices.last())
    }

    // History for back/forward
    private val backStack = mutableListOf<Path>()
    private val forwardStack = mutableListOf<Path>()

    // Scroll positions remembered per path (pathKey -> Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset))
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    // Map parent path -> name of child directory that was just exited (to refocus on return)
    private val lastExitedChildMap = mutableMapOf<String, String>()

    var focusedIndex by mutableStateOf(0)

    /** Строк на экране — шаг PageUp/PageDown (обновляет список). */
    var pageRows = 20

    /** Место на разделе текущей папки (внутри архива — null). */
    var diskSpace by mutableStateOf<org.wexclient.app.vfs.DiskSpace?>(null)
        private set

    /** git-статус текущей папки; null — не репозиторий или пометки выключены. */
    var git by mutableStateOf<org.wexclient.app.vfs.GitRepoStatus?>(null)
        private set
    private var gitGen = 0
    // Запросы одной панели — по очереди: устаревшие пропускаются, не запуская git
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val gitDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun gitMarkFor(entry: VfsEntry): org.wexclient.app.vfs.GitMark? =
        git?.markFor(entry.path, entry.isDirectory && !entry.isArchive)

    /** Набранное подряд для перехода к файлу по имени (пусто — подсказки нет). */
    var typeAheadText by mutableStateOf("")
        private set
    /** Растёт с каждой буквой — подсказка перезапускает таймер. */
    var typeAheadStamp by mutableStateOf(0)
        private set
    /** Ничего не нашлось по набранному. */
    var typeAheadMissed by mutableStateOf(false)
        private set
    private var typeAheadAt = 0L

    /** Буквы набираются подряд — пробел и Backspace тоже относятся к ним. */
    fun isTypingAhead(now: Long = System.currentTimeMillis()): Boolean =
        typeAheadText.isNotEmpty() && now - typeAheadAt <= TYPE_AHEAD_MS

    /**
     * Переход по первым буквам имени (как в Dolphin и Проводнике): буквы в течение секунды
     * складываются, одна и та же буква несколько раз перебирает файлы на неё.
     */
    fun typeAhead(ch: Char, now: Long = System.currentTimeMillis()): Boolean {
        if (entries.isEmpty()) return false
        val text = if (isTypingAhead(now)) typeAheadText + ch else ch.toString()
        typeAheadAt = now
        typeAheadText = text
        typeAheadStamp++
        jumpToTyped(text, keepCurrent = false)
        return true
    }

    /** Backspace при наборе: убрать последнюю букву. */
    fun typeAheadBackspace(now: Long = System.currentTimeMillis()) {
        typeAheadAt = now
        typeAheadText = typeAheadText.dropLast(1)
        typeAheadStamp++
        if (typeAheadText.isNotEmpty()) jumpToTyped(typeAheadText, keepCurrent = true) else typeAheadMissed = false
    }

    fun clearTypeAhead() {
        typeAheadText = ""
        typeAheadMissed = false
        typeAheadAt = 0L
    }

    /** [keepCurrent] — после Backspace: текущий файл остаётся, если подходит. */
    private fun jumpToTyped(text: String, keepCurrent: Boolean) {
        val n = entries.size
        val current = focusedIndex.coerceIn(0, n - 1)
        fun from(start: Int, test: (String) -> Boolean): Int? {
            for (k in 0 until n) {
                val i = (start + k) % n
                if (test(entries[i].name)) return i
            }
            return null
        }
        val repeated = text.length > 1 && text.all { it.equals(text[0], ignoreCase = true) }
        val found = when {
            // «ааа» — следующий файл на «а», если нет имени, начинающегося с «ааа»
            !keepCurrent && repeated && entries.none { it.name.startsWith(text, ignoreCase = true) } ->
                from(current + 1) { it.startsWith(text[0], ignoreCase = true) }
            // Первая буква — следующий подходящий после текущего
            text.length == 1 && !keepCurrent -> from(current + 1) { it.startsWith(text, ignoreCase = true) }
            // Дальше текущий остаётся, пока подходит
            else -> from(current) { it.startsWith(text, ignoreCase = true) }
                ?: from(current) { it.contains(text, ignoreCase = true) }
        }
        typeAheadMissed = found == null
        if (found != null) focusedIndex = found
    }

    // ---------- Автообновление: папку меняют снаружи ----------

    private var watchHandle: AutoCloseable? = null
    private var watchedPath: Path? = null
    private var gitWatchHandle: AutoCloseable? = null
    private var gitWatchedDir: Path? = null
    private var externalRunning = false
    private var externalAgain = false
    // Долгое чтение большой папки не повторяем чаще, чем раз в 8 его длительностей
    private var externalCostMs = 0L
    private var externalDoneAt = 0L

    private fun updateWatch() {
        val path = currentPath
        if (path == watchedPath) return
        watchHandle?.close()
        watchHandle = null
        watchedPath = path
        watchHandle = org.wexclient.app.vfs.DirWatcher.watch(path) {
            searchScope.launch { onExternalChange(path) }
        }
    }

    // Коммит, checkout и add в консоли меняют только .git — пометки обновляются по нему
    private fun updateGitWatch(status: org.wexclient.app.vfs.GitRepoStatus?) {
        val dotGit = status?.root?.resolve(".git")?.takeIf { java.nio.file.Files.isDirectory(it) }
        if (dotGit == gitWatchedDir) return
        gitWatchHandle?.close()
        gitWatchHandle = null
        gitWatchedDir = dotGit
        if (dotGit != null) {
            gitWatchHandle = org.wexclient.app.vfs.DirWatcher.watch(dotGit) {
                searchScope.launch { if (gitWatchedDir == dotGit) refreshExtras() }
            }
        }
    }

    /** Список перечитывается в фоне; курсор и выделение остаются на тех же файлах, новые подсвечиваются. */
    private suspend fun onExternalChange(path: Path) {
        if (path != currentPath || searchQuery.isNotBlank() || listing || isLoading || archiveOpening) return
        if (externalRunning) {
            externalAgain = true
            return
        }
        externalRunning = true
        try {
            val cooldown = externalCostMs * 8 - (System.currentTimeMillis() - externalDoneAt)
            if (cooldown > 0) kotlinx.coroutines.delay(cooldown)
            // Любое своё чтение папки после этого момента новее — наш результат тогда не нужен
            val gen = listGen
            val arrangement = arrangement()
            val started = System.nanoTime()
            val sorted = withContext(listDispatcher) {
                if (!java.nio.file.Files.isDirectory(path)) null
                else runCatching { arrange(vfsManager.listEntriesChecked(path).first, arrangement) }.getOrNull()
            }
            externalCostMs = (System.nanoTime() - started) / 1_000_000
            externalDoneAt = System.currentTimeMillis()
            if (gen != listGen || path != currentPath || searchQuery.isNotBlank()) return
            if (sorted == null) {
                // Папку удалили — к ближайшей существующей
                var up: Path? = path.parent
                while (up != null && !java.nio.file.Files.isDirectory(up)) up = up.parent
                if (up != null) {
                    navDirection = NavDirection.BACKWARD
                    navigateTo(up, saveHistory = false)
                }
                return
            }
            applyExternal(sorted)
        } finally {
            externalRunning = false
        }
        if (externalAgain) {
            externalAgain = false
            onExternalChange(path)
        }
    }

    private var extrasAt = 0L

    private fun applyExternal(sorted: List<VfsEntry>) {
        val focusedPath = entries.getOrNull(focusedIndex)?.path
        val before = entries.mapTo(HashSet()) { it.name }
        entries.clear()
        entries.addAll(sorted)
        retainSelection()
        val idx = focusedPath?.let { p -> entries.indexOfFirst { it.path == p } } ?: -1
        focusedIndex = if (idx >= 0) idx else focusedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        val added = entries.filter { it.name !in before }.map { it.name }
        // Пока папка не была пустой, новое подсвечиваем (без переноса курсора)
        if (before.isNotEmpty() && added.isNotEmpty() && added.size <= 200) markArrived(added)
        // git и место на диске: при новых/удалённых файлах сразу, при правке содержимого — не чаще раза в 5 с
        val namesChanged = added.isNotEmpty() || before.size != entries.size
        val now = System.currentTimeMillis()
        if (namesChanged || now - extrasAt > 5000) {
            extrasAt = now
            refreshExtras()
        }
    }

    /** Подсветить появившиеся элементы, не трогая курсор и прокрутку. */
    fun markArrived(names: Collection<String>) {
        if (arrivedIn != currentPath) arrivedNames = emptySet()
        arrivedIn = currentPath
        arrivedNames = arrivedNames + names
    }

    // Сортировка или фильтр не меняют git-статус и место на диске — не спрашиваем заново
    private var extrasKey: Pair<Path, Boolean>? = null

    private fun refreshExtras(force: Boolean = true) {
        val path = currentPath
        val key = path to ViewPreferences.showGitStatus
        if (!force && key == extrasKey) return
        extrasKey = key
        val gen = ++gitGen
        val local = path.fileSystem === java.nio.file.FileSystems.getDefault()
        if (!local) {
            git = null
            updateGitWatch(null)
            // Место на сервере знает df; у архива своего места нет
            if (path.fileSystem is org.wexclient.app.vfs.ssh.SshFileSystem) {
                searchScope.launch {
                    val space = withContext(Dispatchers.IO) { org.wexclient.app.vfs.DiskSpace.of(path) }
                    if (gen == gitGen) diskSpace = space
                }
            } else {
                diskSpace = null
            }
            return
        }
        // Пока новое не пришло, старое показываем только для той же рабочей копии
        git?.let { if (!path.toAbsolutePath().normalize().startsWith(it.root)) git = null }
        val wantGit = ViewPreferences.showGitStatus
        if (!wantGit) {
            git = null
            updateGitWatch(null)
        }
        searchScope.launch {
            val space = withContext(Dispatchers.IO) { org.wexclient.app.vfs.DiskSpace.of(path) }
            if (gen == gitGen) diskSpace = space
            if (!wantGit) return@launch
            val status = withContext(gitDispatcher) {
                if (gen != gitGen) null else runCatching { org.wexclient.app.vfs.GitStatus.read(path) }.getOrNull()
            }
            if (gen == gitGen) {
                git = status
                updateGitWatch(status)
            }
        }
    }

    // Фон для асинхронного поиска и перечитывания; отменяется, когда вкладку закрывают (dispose).
    // Поколение отсекает устаревшие результаты.
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---------- Размеры папок ----------

    /** Посчитанные размеры папок этого списка: путь → байты. */
    val folderSizes = mutableStateMapOf<String, Long>()

    /** Идёт подсчёт (чтобы показать кнопку «Остановить»). */
    var countingFolders by mutableStateOf(false)
        private set

    private var folderSizeJob: kotlinx.coroutines.Job? = null

    /**
     * Считает размеры всех папок текущего списка: по одной, начиная с первой,
     * и сразу показывает готовые. Обход идёт в фоне и прерывается уходом из папки.
     */
    fun calculateFolderSizes() {
        folderSizeJob?.cancel()
        val dirs = entries.filter { it.isDirectory && !it.isArchive }.map { it.path }
        if (dirs.isEmpty()) return
        countingFolders = true
        folderSizeJob = searchScope.launch {
            try {
                for (dir in dirs) {
                    val size = withContext(Dispatchers.IO) {
                        runCatching { measureDirectory(dir) { !isActive } }.getOrNull()
                    } ?: continue
                    folderSizes[dir.toString()] = size
                }
            } finally {
                countingFolders = false
            }
        }
    }

    fun stopFolderSizes() {
        folderSizeJob?.cancel()
        countingFolders = false
    }

    /** Размер папки обходом без перехода по ссылкам. */
    private fun measureDirectory(dir: Path, cancelled: () -> Boolean): Long {
        var bytes = 0L
        java.nio.file.Files.walkFileTree(
            dir,
            emptySet<java.nio.file.FileVisitOption>(),
            Int.MAX_VALUE,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes
                ): java.nio.file.FileVisitResult {
                    if (cancelled()) return java.nio.file.FileVisitResult.TERMINATE
                    if (!attrs.isDirectory) bytes += attrs.size()
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): java.nio.file.FileVisitResult =
                    java.nio.file.FileVisitResult.CONTINUE

                override fun preVisitDirectory(
                    d: Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes
                ): java.nio.file.FileVisitResult =
                    if (cancelled()) java.nio.file.FileVisitResult.TERMINATE else java.nio.file.FileVisitResult.CONTINUE
            }
        )
        return bytes
    }
    private var searchGen = 0
    // Текущий fd: при новом запросе убиваем, чтобы не отнимал процессор у нового
    private var searchToken: org.wexclient.app.vfs.SearchToken? = null

    fun saveScrollPosition(pathKey: String, index: Int, offset: Int) {
        scrollPositions[pathKey] = Pair(index, offset)
    }

    fun getScrollPosition(pathKey: String): Pair<Int, Int>? {
        return scrollPositions[pathKey]
    }

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val canGoUp: Boolean get() = vfsManager.getParent(currentPath) != null

    val isInsideArchive: Boolean get() = vfsManager.isInsideArchive(currentPath)

    val displayPath: String
        get() {
            return currentPath.toString()
        }


    // После сохранения архив переоткрыт: пути старой ФС переводим на новую
    private fun onArchiveReopened(old: FileSystem, fresh: FileSystem) {
        fun remap(p: Path): Path = if (p.fileSystem === old) fresh.getPath(p.toString()) else p

        val touchesCurrent = currentPath.fileSystem === old
        backStack.replaceAll(::remap)
        forwardStack.replaceAll(::remap)
        if (selectedEntries.any { it.path.fileSystem === old }) {
            val remapped = selectedEntries.map { it.copy(path = remap(it.path)) }
            selectedEntries.clear()
            selectedEntries.addAll(remapped)
        }
        if (touchesCurrent) {
            currentPath = remap(currentPath)
            refresh()
        }
    }

    fun navigateTo(
        target: Path,
        saveHistory: Boolean = true,
        currentScroll: Pair<Int, Int>? = null
    ) {
        val newPath = vfsManager.live(target)
        if (newPath == currentPath) return
        // Посчитанные размеры относятся к прежней папке
        stopFolderSizes()
        folderSizes.clear()
        val oldKey = currentPath.toString()
        if (currentScroll != null) {
            scrollPositions[oldKey] = currentScroll
        }

        if (saveHistory) {
            backStack.add(currentPath)
            forwardStack.clear()
        }

        // Remember exited child name for the parent folder
        val oldName = currentPath.fileName?.toString()
        if (oldName != null) {
            lastExitedChildMap[newPath.toString()] = oldName
        }

        enter(newPath)

        // Курсор — на папке, из которой вышли (когда список прочитан)
        val exitedChild = lastExitedChildMap[newPath.toString()]
        if (exitedChild != null) {
            whenLoaded {
                val idx = entries.indexOfFirst { it.name == exitedChild }
                if (idx != -1) {
                    focusedIndex = idx
                    // Позиции прокрутки для папки ещё нет — показать курсор
                    if (!scrollPositions.containsKey(newPath.toString())) {
                        val targetIdx = (idx - 2).coerceAtLeast(0)
                        scrollPositions[newPath.toString()] = Pair(targetIdx, 0)
                    }
                }
            }
        }
    }

    fun enterEntry(entry: VfsEntry, currentScroll: Pair<Int, Int>? = null) {
        navDirection = NavDirection.FORWARD
        recentlyReturnedChildName = null
        when {
            entry.isDirectory -> {
                navigateTo(entry.path, saveHistory = true, currentScroll = currentScroll)
            }
            entry.isArchive -> openArchiveEntry(entry, currentScroll)
        }
    }

    // zip на диске открывается мгновенно (центральный каталог); tar.* нужно распаковать, а вложенный
    // архив (base.apk в XAPK, jar в zip) JDK читает в память целиком — это в фоне, с индикатором
    private fun openArchiveEntry(entry: VfsEntry, currentScroll: Pair<Int, Int>?) {
        fun fail(e: Throwable) {
            val raw = e.message ?: e.javaClass.simpleName
            // Сообщения zipfs из JDK — по-английски и ни о чём пользователю не говорят
            val why = when {
                e is java.util.zip.ZipException || raw.contains("END header", ignoreCase = true) ||
                    raw.contains("zip file is empty", ignoreCase = true) -> "это не zip-архив или файл повреждён"
                else -> raw
            }
            errorMessage = "Не удалось открыть «${entry.name}»: $why"
        }
        val tar = org.wexclient.app.vfs.archive.ArchiveFormats.isTarFamily(entry.name)
        val nested = entry.path.fileSystem !== java.nio.file.FileSystems.getDefault()
        if (!tar && !nested) {
            try {
                navigateTo(vfsManager.openArchive(entry.path), saveHistory = true, currentScroll = currentScroll)
            } catch (e: Exception) {
                fail(e)
            }
            return
        }
        if (archiveOpening) return
        archiveOpening = true
        isLoading = true
        loadingText = "Открытие «${entry.name}» — Esc для отмены"
        val startedAt = currentPath
        val total = entry.size
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        archiveOpenCancel = cancel
        searchScope.launch {
            // Ушли в другую папку — распаковка больше не нужна
            val watcher = launch {
                androidx.compose.runtime.snapshotFlow { currentPath }.first { it != startedAt }
                cancel.set(true)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    vfsManager.openArchive(entry.path, progress = { done ->
                        if (total > 0) {
                            val pct = (done * 100 / total).coerceIn(0, 100)
                            searchScope.launch {
                                if (archiveOpening && !cancel.get() && tar) loadingText = "Распаковка «${entry.name}» — $pct% (Esc — отмена)"
                            }
                        }
                    }, cancelled = cancel::get)
                }
            }
            watcher.cancel()
            archiveOpenCancel = null
            archiveOpening = false
            // Индикатор чужого чтения папки (если ушли) не гасим
            if (!listing) isLoading = false
            loadingText = null
            result.onSuccess { root ->
                // Пока распаковывали, пользователь ушёл в другую папку или отменил — не уводим его
                if (currentPath == startedAt && !cancel.get()) navigateTo(root, saveHistory = true, currentScroll = currentScroll)
            }.onFailure { if (!cancel.get()) fail(it) }
        }
    }

    fun goUp(currentScroll: Pair<Int, Int>? = null) {
        val parent = vfsManager.getParent(currentPath)
        if (parent != null) {
            navDirection = NavDirection.BACKWARD
            recentlyReturnedChildName = currentPath.fileName?.toString()
            navigateTo(parent, saveHistory = true, currentScroll = currentScroll)
        }
    }

    fun goBack(currentScroll: Pair<Int, Int>? = null) {
        if (backStack.isNotEmpty()) {
            navDirection = NavDirection.BACKWARD
            val oldKey = currentPath.toString()
            if (currentScroll != null) {
                scrollPositions[oldKey] = currentScroll
            }
            val prev = vfsManager.live(backStack.removeAt(backStack.size - 1))
            forwardStack.add(currentPath)

            val oldName = currentPath.fileName?.toString()
            if (oldName != null) {
                lastExitedChildMap[prev.toString()] = oldName
                recentlyReturnedChildName = oldName
            }

            enter(prev)

            val exitedChild = lastExitedChildMap[prev.toString()]
            if (exitedChild != null) {
                whenLoaded {
                    val idx = entries.indexOfFirst { it.name == exitedChild }
                    if (idx != -1) focusedIndex = idx
                }
            }
        }
    }

    fun goForward(currentScroll: Pair<Int, Int>? = null) {
        if (forwardStack.isNotEmpty()) {
            navDirection = NavDirection.FORWARD
            recentlyReturnedChildName = null
            val oldKey = currentPath.toString()
            if (currentScroll != null) {
                scrollPositions[oldKey] = currentScroll
            }
            val next = vfsManager.live(forwardStack.removeAt(forwardStack.size - 1))
            backStack.add(currentPath)
            enter(next)
        }
    }

    /** Смена папки: снимок уходящего списка для анимации, сброс выделения, набора и поиска. */
    private fun enter(newPath: Path) {
        val snapshot = Leaving(navigationId, currentPath, entries.toList(), canGoUp)
        leaving = snapshot
        navigationId++
        currentPath = newPath
        clearSelection()
        clearTypeAhead()
        searchQuery = ""
        focusedIndex = 0
        refresh()
        // Анимация идёт ~0,2 с — дольше копию списка не держим
        searchScope.launch {
            kotlinx.coroutines.delay(1000)
            if (leaving === snapshot) leaving = null
        }
    }

    fun goHome() {
        navigateTo(vfsManager.getHomeDirectory())
    }

    fun goRoot() {
        navigateTo(vfsManager.getRootDirectory())
    }

    // ---------- Чтение папки ----------

    /** Поколение чтения: результат старого не затирает новый (и автообновление тоже). */
    private var listGen = 0
    private var listing = false
    private val afterLoad = ArrayList<Pair<Path, () -> Unit>>()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val listDispatcher = Dispatchers.IO.limitedParallelism(2)

    /** Выполнить, когда список текущей папки прочитан (сразу, если он уже есть). */
    fun whenLoaded(action: () -> Unit) {
        if (!listing) action() else afterLoad.add(currentPath to action)
    }

    /**
     * Перечитать папку. Обычная папка читается за миллисекунды и показывается сразу;
     * огромная или на медленном (сетевом) диске — в фоне, с индикатором, окно не замирает.
     * [force] — заново спросить и git, и место на диске.
     */
    fun refresh(force: Boolean = false) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            searchGen++ // гасим летящий поиск
            searchToken?.cancel()
            searchToken = null
            isSearching = false
            searchResultsCount = null
            val gen = ++listGen
            val path = currentPath
            // Та же папка (Ctrl+R, конец операции, пересортировка) — курсор на том же файле, а не на той же строке:
            // появившийся выше файл иначе оказывался под курсором, и Delete ушёл бы не туда
            val keepFocus = if (listedPath == path.toString()) entries.getOrNull(focusedIndex)?.path?.toString() else null
            listing = true
            // Сортировка и фильтр — там же, в фоне: у папки на сотни тысяч файлов это заметное время
            val arrangement = arrangement()
            val job = searchScope.async(listDispatcher) {
                runCatching { vfsManager.listEntriesChecked(path).let { (list, error) -> arrange(list, arrangement) to error } }
            }
            val waitStart = if (org.wexclient.app.util.Startup.enabled) System.nanoTime() else 0L
            val quick = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(QUICK_LIST_MS) { job.await() }
            }
            if (org.wexclient.app.util.Startup.enabled) {
                org.wexclient.app.util.Startup.mark("панель «$side»: ждали ${(System.nanoTime() - waitStart) / 1_000_000} мс")
            }
            if (quick != null) {
                applyListing(path, quick.getOrElse { emptyList<VfsEntry>() to (it.message ?: it.javaClass.simpleName) }, force, keepFocus)
            } else {
                entries.clear()
                isLoading = true
                if (!archiveOpening) loadingText = "Чтение папки…"
                searchScope.launch {
                    val result = job.await()
                    if (gen != listGen) return@launch
                    if (!archiveOpening) loadingText = null
                    applyListing(path, result.getOrElse { emptyList<VfsEntry>() to (it.message ?: it.javaClass.simpleName) }, force, keepFocus)
                }
            }
        } else {
            listing = false
            // В списке — находки, а не содержимое папки
            listedPath = null
            // Поиск (fd-процесс до 2с / обход дерева) — только в фоне,
            // иначе каждый кейстрок морозит UI-поток.
            val gen = ++searchGen
            searchToken?.cancel()
            val token = org.wexclient.app.vfs.SearchToken().also { searchToken = it }
            isSearching = true
            val path = currentPath
            val recursive = isRecursiveSearch
            val includeHidden = ViewPreferences.showHidden
            searchScope.launch {
                val filtered = withContext(Dispatchers.IO) {
                    org.wexclient.app.vfs.FastSearchEngine.search(
                        currentPath = path,
                        query = query,
                        recursive = recursive,
                        vfsManager = vfsManager,
                        includeHidden = includeHidden,
                        token = token
                    ) { partial ->
                        // Первые находки показываем сразу, не дожидаясь конца обхода
                        searchScope.launch {
                            if (gen != searchGen) return@launch
                            searchResultsCount = partial.size
                            applyEntries(partial)
                        }
                    }
                }
                if (searchToken === token) searchToken = null
                if (gen != searchGen) return@launch // устарел (печатают дальше)
                searchResultsCount = filtered.size
                applyEntries(filtered)
                focusedIndex = 0
                isSearching = false
            }
        }
    }

    /** Почему папку не удалось прочитать (нет прав…) — вместо «Папка пуста». */
    var listError by mutableStateOf<String?>(null)
        private set

    /** Папка, содержимое которой сейчас в [entries] (null — там находки поиска). */
    private var listedPath: String? = null

    /** [result] — уже отсортированный и отфильтрованный список (сделано в фоне). */
    private fun applyListing(path: Path, result: Pair<List<VfsEntry>, String?>, forceExtras: Boolean, keepFocus: String? = null) {
        listError = result.second
        entries.clear()
        entries.addAll(result.first)
        isLoading = false
        org.wexclient.app.util.Startup.mark("список панели «$side» готов")
        listedPath = path.toString()
        // Выделение — только из того, что реально лежит в папке (после удаления/перемещения
        // невидимые «выделенные» элементы уходили бы в следующую операцию)
        retainSelection()
        if (keepFocus != null) {
            val idx = entries.indexOfFirst { it.path.toString() == keepFocus }
            if (idx >= 0) focusedIndex = idx
        }
        if (focusedIndex >= entries.size) focusedIndex = (entries.size - 1).coerceAtLeast(0)
        isLoading = false
        listing = false
        updateWatch()
        refreshExtras(force = forceExtras)
        if (afterLoad.isNotEmpty()) {
            val pending = afterLoad.toList()
            afterLoad.clear()
            for ((p, action) in pending) if (p == path) action()
        }
    }

    private fun retainSelection() {
        if (selectedEntries.isEmpty()) return
        val byPath = entries.associateBy { it.path }
        val kept = selectedEntries.mapNotNull { byPath[it.path] }
        if (kept != selectedEntries.toList()) {
            selectedEntries.clear()
            selectedEntries.addAll(kept)
        }
    }

    /** Что влияет на порядок и состав списка — снимок для сортировки в фоне. */
    private class Arrangement(
        val showHidden: Boolean,
        val filter: TypeFilter,
        val naturalSort: Boolean,
        val foldersFirst: Boolean,
        val sortField: SortField,
        val ascending: Boolean,
        val insideArchive: Boolean
    )

    private fun arrangement() = Arrangement(
        showHidden = ViewPreferences.showHidden,
        filter = typeFilter,
        naturalSort = ViewPreferences.naturalSort,
        foldersFirst = ViewPreferences.foldersFirst,
        sortField = sortField,
        ascending = sortAscending,
        // Папка на сервере сортируется как обычная, а не «как внутри APK»
        insideArchive = isInsideArchive && currentPath.fileSystem !is org.wexclient.app.vfs.ssh.SshFileSystem
    )

    private fun applyEntries(listed: List<VfsEntry>) {
        val sorted = arrange(listed, arrangement())
        entries.clear()
        entries.addAll(sorted)
        isLoading = false
    }

    // Не immediate: уведомление приходит изнутри listEntries, и вложенный refresh удваивал список
    private val reopenListener: (java.nio.file.FileSystem, java.nio.file.FileSystem) -> Unit = { old, fresh ->
        searchScope.launch(Dispatchers.Main) { onArchiveReopened(old, fresh) }
    }

    /**
     * Вкладку закрыли: наблюдение за папкой и за .git, поиск и подсчёт размеров больше не нужны.
     * Иначе закрытая вкладка продолжала перечитывать свою папку и запускать git на каждое изменение.
     */
    /**
     * Консоль этой вкладки. У каждой вкладки своя: переключились на другую — эта уходит в фон
     * вместе со своими запущенными программами и возвращается такой же, какой была.
     */
    /** Вкладка закреплена: её не уносит «закрыть другие», и крестика у неё нет. */
    var pinned by mutableStateOf(false)

    private var ownTerminal: org.wexclient.app.terminal.TerminalPanelState? = null

    val terminal: org.wexclient.app.terminal.TerminalPanelState
        get() = ownTerminal ?: org.wexclient.app.terminal.TerminalPanelState(side.ifBlank { "pane" })
            .also { ownTerminal = it }

    /** В консоли вкладки прямо сейчас работает программа — закрытие вкладки её остановит. */
    val terminalBusy: Boolean
        get() = ownTerminal?.sessions?.any { it.exitCode == null && it.isBusy() } == true

    /** Закрыть консоль вкладки, оставив саму вкладку живой (выход из программы). */
    fun disposeTerminal() {
        ownTerminal?.dispose()
        ownTerminal = null
    }

    fun dispose() {
        // Вкладку закрыли — закрываем и её консоль вместе со всем, что в ней запущено
        disposeTerminal()
        watchHandle?.close()
        watchHandle = null
        gitWatchHandle?.close()
        gitWatchHandle = null
        searchToken?.cancel()
        searchToken = null
        folderSizeJob?.cancel()
        searchScope.cancel()
        vfsManager.removeArchiveReopenListener(reopenListener)
    }

    /** Папку ещё ни разу не читали (отложенная вкладка) — её покажут при первом открытии. */
    private var awaitingFirstListing = !listNow

    /** Вкладку показали: если её папку ещё не читали, читаем сейчас. */
    fun ensureListed() {
        if (!awaitingFirstListing) return
        awaitingFirstListing = false
        refresh()
    }

    // В конце класса: refresh() пользуется всеми полями выше
    init {
        vfsManager.addArchiveReopenListener(reopenListener)
        if (listNow) refresh()
    }

    companion object {
        const val TYPE_AHEAD_MS = 1000L

        /** Столько ждём чтения папки в потоке окна, дальше — индикатор и фон. */
        private const val QUICK_LIST_MS = 60L

        private fun arrange(listed: List<VfsEntry>, a: Arrangement): List<VfsEntry> {
            val filtered = listed.filter { e -> (a.showHidden || !e.name.startsWith(".")) && a.filter.accepts(e) }
            val hasAndroidPackageFiles = a.insideArchive || filtered.any {
                val n = it.name.lowercase()
                n == "androidmanifest.xml" || n.endsWith(".dex") || n == "resources.arsc"
            }
            // Ключ имени считается один раз на элемент, а не при каждом сравнении
            val lowerNames = HashMap<VfsEntry, String>(filtered.size * 2)
            fun lower(e: VfsEntry) = lowerNames.getOrPut(e) { e.name.lowercase() }
            val byName: Comparator<VfsEntry> =
                if (a.naturalSort) Comparator { x, y -> NaturalOrder.compare(x.name, y.name) }
                else Comparator { x, y -> lower(x).compareTo(lower(y)) }
            fun Comparator<VfsEntry>.directed(): Comparator<VfsEntry> = if (a.ascending) this else reversed()

            // При равенстве ключа (размер, дата, тип) — по имени по возрастанию
            val order: Comparator<VfsEntry> = when (a.sortField) {
                SortField.NAME -> if (hasAndroidPackageFiles) {
                    compareBy<VfsEntry> { it.apkItemPriority }
                        .thenBy { it.dexNaturalIndex }
                        .then(byName)
                        .directed()
                } else {
                    byName.directed()
                }
                SortField.SIZE -> compareBy<VfsEntry> { it.size }.directed().then(byName)
                SortField.DATE -> compareBy<VfsEntry> { it.lastModified }.directed().then(byName)
                SortField.TYPE -> compareBy<VfsEntry> { it.itemType.name }.directed().then(byName)
                SortField.EXTENSION -> compareBy<VfsEntry> { if (it.isDirectory) "" else it.extension.lowercase() }
                    .directed().then(byName)
            }

            return if (a.foldersFirst) {
                filtered.sortedWith(compareBy<VfsEntry> { !it.isDirectory && !it.isArchive }.then(order))
            } else {
                filtered.sortedWith(order)
            }
        }
    }

    /** Пока что-то выделено, клик по элементу выделяет его, а не открывает (как в MT Manager). */
    val selectionMode: Boolean get() = selectedEntries.isNotEmpty()

    /** Опорный элемент для выделения диапазона (Shift+клик). */
    var selectionAnchor by mutableStateOf(-1)
        private set

    /** Пути выделенного — проверка строки за O(1), а не проходом по всему выделению. */
    private val selectedPaths by androidx.compose.runtime.derivedStateOf {
        selectedEntries.mapTo(HashSet(selectedEntries.size * 2)) { it.path }
    }

    fun isSelected(entry: VfsEntry): Boolean = entry.path in selectedPaths

    fun toggleSelection(entry: VfsEntry) {
        if (isSelected(entry)) {
            selectedEntries.removeAll { it.path == entry.path }
        } else {
            selectedEntries.add(entry)
        }
        selectionAnchor = entries.indexOf(entry)
    }

    /** Добавить к выделению всё от опорного элемента до [index] включительно. */
    fun selectRange(index: Int) {
        if (index !in entries.indices) return
        val from = selectionAnchor.takeIf { it in entries.indices } ?: index
        val range = if (from <= index) from..index else index..from
        val already = selectedEntries.toHashSet()
        selectedEntries.addAll(range.map { entries[it] }.filter { it !in already })
        selectionAnchor = index
    }

    fun selectAll() {
        selectedEntries.clear()
        selectedEntries.addAll(entries)
    }

    fun clearSelection() {
        selectedEntries.clear()
        selectionAnchor = -1
    }

}

/** Диапазон индексов в списке, который нужно показать (сравнение по ссылке — каждый запрос новый). */
class RevealRequest(val first: Int, val last: Int)

/** Файл открывается в фоне: рядом с именем в списке — полоска прогресса. */
class OpeningFile(val path: Path) {
    /** Доля прочитанного 0..1; null — длительность неизвестна (разбор). */
    var progress by mutableStateOf<Float?>(null)

    fun report(done: Long, total: Long) {
        if (total <= 0) return
        val p = (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
        val prev = progress
        // Перерисовка — на каждый процент, не на каждый блок
        if (prev == null || p - prev >= 0.01f || (p >= 1f && prev < 1f)) progress = p
    }
}
