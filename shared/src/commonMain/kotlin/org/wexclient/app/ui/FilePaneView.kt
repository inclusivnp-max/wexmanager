package org.wexclient.app.ui


import androidx.compose.foundation.layout.RowScope

import androidx.compose.ui.draw.clip

import androidx.compose.material.icons.filled.Deselect

import androidx.compose.material.icons.filled.Category

import androidx.compose.material.icons.filled.Contrast

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Terminal as OutlinedTerminal
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import org.wexclient.app.util.Keymap
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaWarn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FilePaneView(
    state: FilePaneState,
    /** Вкладки этой панели; null — панель без вкладок. */
    tabs: PaneTabs? = null,
    isActive: Boolean,
    onActivate: () -> Unit,
    onOpenFile: (VfsEntry) -> Unit,
    onCopyOther: (VfsEntry) -> Unit,
    onDelete: (VfsEntry) -> Unit,
    onProperties: (VfsEntry) -> Unit,
    onMoveOther: ((VfsEntry) -> Unit)? = null,
    onRename: ((VfsEntry) -> Unit)? = null,
    onCompress: ((VfsEntry) -> Unit)? = null,
    onExtract: ((VfsEntry, ExtractAction) -> Unit)? = null,
    onOpenTerminal: ((Path) -> Unit)? = null,
    onNewFolder: (() -> Unit)? = null,
    onNewFile: (() -> Unit)? = null,
    /** Показать содержимое (F3/F4 из меню), когда щелчок делает другое. */
    onViewContent: ((VfsEntry) -> Unit)? = null,
    /** Импорт файла ключа подписи. */
    onImportKey: ((VfsEntry) -> Unit)? = null,
    onAnalyzeApk: ((VfsEntry) -> Unit)? = null,
    onInspectDex: ((VfsEntry) -> Unit)? = null,
    onOpenApkDetail: ((VfsEntry) -> Unit)? = null,
    onSignApk: ((VfsEntry) -> Unit)? = null,
    onInstallApk: ((VfsEntry) -> Unit)? = null,
    /** Щелчок по набору split-APK: окно «установить, открыть, подпись, конвертировать». */
    onOpenBundle: ((VfsEntry) -> Unit)? = null,
    onConvertBundle: ((VfsEntry) -> Unit)? = null,
    onOpenHex: ((VfsEntry) -> Unit)? = null,
    onChecksum: ((VfsEntry) -> Unit)? = null,
    onDiffWithOther: ((VfsEntry) -> Unit)? = null,
    onBatchRename: ((List<VfsEntry>) -> Unit)? = null,
    onOpenInOtherPane: ((Path) -> Unit)? = null,
    onMessage: ((String) -> Unit)? = null,
    /** Встроенная консоль этой панели (кнопка в шапке и нижняя часть панели). */
    terminal: org.wexclient.app.terminal.TerminalPanelState? = null,
    /**
     * Вернуть клавиатуру файловому менеджеру: после закрытия поиска/строки пути фокус
     * иначе остаётся «нигде», и Ctrl+F, стрелки, F-клавиши перестают работать.
     */
    onReturnFocus: (() -> Unit)? = null,
    /** Файл, который сейчас открывается (полоска прогресса у его строки). */
    opening: OpeningFile? = null,
    /** Кнопка ▶ у AppImage. */
    onLaunchAppImage: ((VfsEntry) -> Unit)? = null,
    /** Дополнительные пункты меню файла. */
    rowActions: (VfsEntry) -> List<RowAction> = { emptyList() },
    /** Дополнительные пункты меню пустого места (вставить…). */
    paneActions: () -> List<RowAction> = { emptyList() },
    /** Корзина из панели быстрого доступа. */
    onOpenTrash: (() -> Unit)? = null,
    /** Отключиться от сервера, на котором сейчас панель. */
    onDisconnect: ((org.wexclient.app.vfs.remote.RemoteFileSystem) -> Unit)? = null,
    /** Файлы бросили в папку (из этой панели, соседней или другой программы). */
    onDropFiles: ((paths: List<Path>, targetDir: Path, action: DropAction) -> Unit)? = null,
    /** Перетаскивание из панели закончилось (файлы могли унести в другую программу). */
    onDragFinished: () -> Unit = {},
    /** Соседняя панель справа от этой: стрелки «в другую панель» смотрят туда, где она. */
    otherPaneOnRight: Boolean = true,
    /** Отступы нижней строки от краёв: со стороны соседней панели там язычок истории. */
    footerInsetStart: androidx.compose.ui.unit.Dp = 0.dp,
    footerInsetEnd: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    // Переход между панелями по Tab виден плавно, а не скачком. Значения — State, читаются при рисовании
    // рамки: иначе каждый кадр анимации перекомпоновывал всю панель (и обе при смене активной)
    val borderColor = androidx.compose.animation.animateColorAsState(
        targetValue = if (isActive) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(Motion.TINT),
        label = "pane-border"
    )
    val borderWidth = androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isActive) 2.dp else 1.dp,
        animationSpec = tween(Motion.TINT),
        label = "pane-border-width"
    )
    // Перетаскивание над панелью (объявлено ниже) — рамка другого цвета

    // Path editing state
    var isEditingPath by remember { mutableStateOf(false) }
    var pathInputVal by remember { mutableStateOf(TextFieldValue("")) }
    // Подсказки пути: папки, подходящие под набранное
    var pathHints by remember { mutableStateOf<List<Path>>(emptyList()) }
    var pathHintIndex by remember { mutableStateOf(-1) }
    val pathFocusRequester = remember { FocusRequester() }

    // Search focus & state
    val searchFocusRequester = remember { FocusRequester() }
    var isSearchFocused by remember { mutableStateOf(false) }

    // Текст поиска — в state, выделение — здесь (повторный Ctrl+F выделяет запрос целиком)
    var searchValue by remember { mutableStateOf(TextFieldValue(state.searchQuery)) }
    val shownSearch = if (searchValue.text == state.searchQuery) searchValue
    else TextFieldValue(state.searchQuery, TextRange(state.searchQuery.length))

    LaunchedEffect(state.searchFocusTrigger) {
        if (state.searchFocusTrigger > 0) {
            state.isSearchOpen = true
            searchValue = TextFieldValue(state.searchQuery, TextRange(0, state.searchQuery.length))
            // Поле появляется по ходу анимации ширины — ждём, пока его можно будет сфокусировать
            repeat(30) {
                androidx.compose.runtime.withFrameNanos { }
                if (searchFocusRequester.tryRequestFocus()) return@LaunchedEffect
            }
        }
    }

    fun closeSearch() {
        state.searchQuery = ""
        state.isSearchOpen = false
        state.refresh()
        onReturnFocus?.invoke()
    }

    // Debounce живого поиска: refresh через 120мс после кейстрока.
    // Сам поиск выполняется в фоне (FilePaneState), устаревший fd убивается.
    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.isNotEmpty()) {
            delay(120)
            state.refresh()
        }
    }

    // Скрытые файлы / папки сверху / естественная сортировка поменялись — перечитать список
    var seenListVersion by remember { mutableStateOf(ViewPreferences.listVersion) }
    LaunchedEffect(ViewPreferences.listVersion) {
        if (seenListVersion != ViewPreferences.listVersion) {
            seenListVersion = ViewPreferences.listVersion
            // Порядок и состав из уже прочитанного — диск заново не читаем
            state.refresh(source = ListSource.REARRANGE)
        }
    }

    // Ошибки панели (архив не открылся и т.п.) — в общий диалог
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        state.errorMessage = null
        onMessage?.invoke(msg)
    }

    // Модификаторы клавиатуры в момент клика: Ctrl — выделить, Shift — диапазон
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current

    fun focusFound(entry: VfsEntry) {
        val idx = state.entries.indexOfFirst { it.name == entry.name }
        if (idx >= 0) {
            state.focusedIndex = idx
            state.recentlyReturnedChildName = entry.name
        }
        onActivate()
        onReturnFocus?.invoke()
    }

    // Результат поиска: закрыть поиск, открыть его папку и выделить сам элемент
    fun revealInFolder(entry: VfsEntry) {
        val parent = entry.path.parent ?: return
        state.isSearchOpen = false
        if (state.vfsManager.live(parent) == state.vfsManager.live(state.currentPath)) {
            state.searchQuery = ""
            state.refresh()
        } else {
            state.navigateTo(parent)
        }
        state.whenLoaded { focusFound(entry) }
    }

    fun runScript(entry: VfsEntry) {
        val term = terminal ?: return
        val dir = entry.path.toAbsolutePath().parent ?: return
        try {
            term.runCommand(dir, org.wexclient.app.terminal.ScriptRunner.command(entry.path))
        } catch (e: Exception) {
            onMessage?.invoke(e.message ?: "Не удалось запустить «${entry.name}»")
        }
    }

    // Pane background context menu state
    var showPaneBgMenu by remember { mutableStateOf(false) }
    var paneBgMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    // Перетаскивание: над панелью или над папкой в ней; брошенное ждёт выбора действия
    var paneDropHover by remember { mutableStateOf(false) }
    var dropRowPath by remember { mutableStateOf<Path?>(null) }
    var pendingDrop by remember { mutableStateOf<PendingDrop?>(null) }
    var listBoxOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val clickSeries = remember { ClickSeries() }
    val localDensity = androidx.compose.ui.platform.LocalDensity.current

    fun requestDrop(paths: List<Path>, dir: Path, rootPoint: androidx.compose.ui.geometry.Offset?): Boolean {
        if (onDropFiles == null) return false
        val target = runCatching { dir.toAbsolutePath().normalize() }.getOrDefault(dir)
        // Бросили туда же, откуда взяли, или папку в саму себя — ничего не делаем
        if (paths.all { p -> p.fileSystem === dir.fileSystem && p.toAbsolutePath().normalize().parent == target }) return false
        if (paths.any { p -> p.fileSystem === dir.fileSystem && target.startsWith(p.toAbsolutePath().normalize()) }) return false
        val local = rootPoint?.let { it - listBoxOrigin } ?: androidx.compose.ui.geometry.Offset(40f, 40f)
        with(localDensity) {
            pendingDrop = PendingDrop(paths, dir, DpOffset(local.x.toDp(), local.y.toDp()))
        }
        onActivate()
        return true
    }

    // Automatically clear recently returned child highlight after duration
    LaunchedEffect(state.recentlyReturnedChildName) {
        if (state.recentlyReturnedChildName != null) {
            delay(1600)
            state.recentlyReturnedChildName = null
        }
    }
    // Подсветка скопированного/перемещённого сюда гаснет чуть позже — пока до него докручиваем
    LaunchedEffect(state.arrivedNames) {
        if (state.arrivedNames.isNotEmpty()) {
            delay(2200)
            state.arrivedNames = emptySet()
        }
    }

    fun startPathEditing() {
        val currentStr = state.currentPath.toString()
        pathInputVal = TextFieldValue(
            text = currentStr,
            selection = TextRange(0, currentStr.length)
        )
        isEditingPath = true
        state.textInputFocused = true
    }
    // Флаг обновляется и сразу (из обработчиков), и после перерисовки — на случай других путей
    SideEffect { state.textInputFocused = isSearchFocused || isEditingPath }

    fun resolveEnteredPath(raw: String): Path? {
        val trimmed = raw.trim()
        // Панель на сервере или телефоне: путь — там же, «~» — домашняя папка там
        val remote = state.currentPath.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem
        if (remote != null && !trimmed.startsWith("ssh://")) {
            val expanded = when {
                trimmed == "~" -> remote.home
                trimmed.startsWith("~/") -> remote.home + trimmed.substring(1)
                trimmed.startsWith("/") -> trimmed
                else -> state.currentPath.resolve(trimmed).toString()
            }
            return runCatching { remote.getPath(expanded).takeIf { Files.isDirectory(it) } }.getOrNull()
        }
        val userHome = org.wexclient.app.util.AppDirs.userHome.toString()
        val expanded = when {
            trimmed == "~" -> userHome
            trimmed.startsWith("~/") || trimmed.startsWith("~\\") -> userHome + trimmed.substring(1)
            else -> trimmed
        }
        return try {
            val p = Paths.get(expanded)
            if (Files.exists(p)) p else null
        } catch (e: Exception) {
            null
        }
    }

    LaunchedEffect(state.pathEditRequest) {
        if (state.pathEditRequest > 0) startPathEditing()
    }

    LaunchedEffect(isEditingPath) {
        if (isEditingPath) {
            pathFocusRequester.requestFocus()
        }
    }

    var paneWidthPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // До первого измерения — по ширине окна (панелей две): иначе первый кадр на телефоне — со столбцами
    val windowWidthPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    val measuredPx = if (paneWidthPx > 0) paneWidthPx else windowWidthPx / 2
    val compactRows = measuredPx > 0 && with(androidx.compose.ui.platform.LocalDensity.current) { measuredPx.toDp() } < COMPACT_PANE_WIDTH
    androidx.compose.runtime.CompositionLocalProvider(LocalCompactRows provides compactRows) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { paneWidthPx = it.width }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onActivate
            )
            // Рамка — в фазе рисования (поверх содержимого, как border у Surface): анимация смены панели
            // и подсветка при перетаскивании меняют только её картинку
            .drawWithContent {
                drawContent()
                val drop = paneDropHover || dropRowPath != null
                val w = if (drop) 2.dp.toPx() else borderWidth.value.toPx()
                val radius = 12.dp.toPx() - w / 2
                drawRoundRect(
                    color = if (drop) NoctaliaSecondary else borderColor.value,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w)
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        var paneColumnHeight by remember { mutableStateOf(0) }
        var paneColumnWidth by remember { mutableStateOf(0) }
        val paneDensity = androidx.compose.ui.platform.LocalDensity.current.density
        // В узкой панели строка пути важнее кнопок: лишние прячем, они есть на клавишах и в меню
        val roomy = paneColumnWidth == 0 || paneColumnWidth / paneDensity > 430f
        Column(
            modifier = Modifier.fillMaxSize().onSizeChanged {
                paneColumnHeight = it.height
                paneColumnWidth = it.width
            }
        ) {
            // Pane Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back / Forward / Up buttons
                IconButton(
                    onClick = {
                        val curScroll = state.getScrollPosition(state.currentPath.toString())
                        state.goBack(curScroll)
                    },
                    enabled = state.canGoBack,
                    modifier = Modifier.size(30.dp).noFocus()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (state.canGoBack) NoctaliaPrimary else MaterialTheme.colorScheme.outline
                    )
                }

                IconButton(
                    onClick = {
                        val curScroll = state.getScrollPosition(state.currentPath.toString())
                        state.goForward(curScroll)
                    },
                    enabled = state.canGoForward,
                    modifier = Modifier.size(30.dp).noFocus()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (state.canGoForward) NoctaliaPrimary else MaterialTheme.colorScheme.outline
                    )
                }

                IconButton(
                    onClick = {
                        val curScroll = state.getScrollPosition(state.currentPath.toString())
                        state.goUp(curScroll)
                    },
                    enabled = state.canGoUp,
                    modifier = Modifier.size(30.dp).noFocus()
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "Up",
                        tint = if (state.canGoUp) NoctaliaPrimary else MaterialTheme.colorScheme.outline
                    )
                }

                // Bookmarks & Quick Volumes Dropdown
                var showBookmarksMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showBookmarksMenu = true },
                        modifier = Modifier.size(30.dp).noFocus()
                    ) {
                        val isCurrentBookmarked = org.wexclient.app.vfs.BookmarksManager.isBookmarked(state.currentPath)
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = "Быстрый доступ / Диски",
                            tint = if (isCurrentBookmarked) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    WexDropdownMenu(
                        expanded = showBookmarksMenu,
                        onDismissRequest = { showBookmarksMenu = false },
                        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
                    ) {
                        ReturnFocusOnClose()
                        val isCurBookmarked = org.wexclient.app.vfs.BookmarksManager.isBookmarked(state.currentPath)
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isCurBookmarked) Icons.Default.Check else Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = NoctaliaPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isCurBookmarked) "Удалить из закладок" else "Закрепить текущую папку",
                                        fontWeight = FontWeight.Bold,
                                        color = NoctaliaPrimary
                                    )
                                }
                            },
                            onClick = {
                                org.wexclient.app.vfs.BookmarksManager.toggleBookmark(state.currentPath)
                                showBookmarksMenu = false
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Диски в /mnt и /run/media — не в потоке окна
                        val quickLocs by androidx.compose.runtime.produceState(emptyList<org.wexclient.app.vfs.BookmarkItem>(), showBookmarksMenu) {
                            value = withContext(PlaceProbe.dispatcher) { org.wexclient.app.vfs.BookmarksManager.getQuickLocations() }
                        }
                        quickLocs.forEach { item ->
                            val isCurrent = try {
                                java.nio.file.Paths.get(item.pathStr).toAbsolutePath().normalize() == state.currentPath.toAbsolutePath().normalize()
                            } catch (_: Throwable) { false }

                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            SymbolIcon(
                                                item.icon,
                                                contentDescription = null,
                                                tint = if (isCurrent) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrent) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.pathStr,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        if (!item.isSystem) {
                                            IconButton(
                                                onClick = {
                                                    org.wexclient.app.vfs.BookmarksManager.removeBookmark(item.pathStr)
                                                },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(Icons.Default.Clear, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    try {
                                        val p = java.nio.file.Paths.get(item.pathStr)
                                        state.navigateTo(p)
                                    } catch (_: Throwable) {}
                                    showBookmarksMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Editable / Clickable Path Area
                if (isEditingPath) {
                    // Подсказки считаются в фоне и с задержкой: список папки может быть долгим
                    LaunchedEffect(pathInputVal.text, isEditingPath) {
                        delay(120)
                        val text = pathInputVal.text
                        val found = withContext(Dispatchers.IO) {
                            PathSuggestions.forInput(text, state.currentPath, state.vfsManager)
                        }
                        // Пока считали, могли набрать дальше
                        if (pathInputVal.text == text) {
                            pathHints = found
                            if (pathHintIndex >= found.size) pathHintIndex = -1
                        }
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            pathHints = emptyList()
                            pathHintIndex = -1
                        }
                    }
                  Box(modifier = Modifier.weight(1f)) {
                    // Modern text input mode with clear visibility, zero clipping, and selection
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.5.dp, NoctaliaPrimary),
                        modifier = Modifier.fillMaxWidth().height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = NoctaliaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))

                            fun applyHint(path: Path) {
                                val text = PathSuggestions.completion(path)
                                pathInputVal = TextFieldValue(text, selection = TextRange(text.length))
                                pathHintIndex = -1
                            }

                            BasicTextField(
                                value = pathInputVal,
                                onValueChange = {
                                    pathInputVal = it
                                    pathHintIndex = -1
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(NoctaliaPrimary),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(pathFocusRequester)
                                    // Предварительный перехват: стрелки и Tab иначе съедает само поле ввода
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        val hints = pathHints
                                        when {
                                            // Стрелки ходят по подсказкам, не трогая текст
                                            event.key == Key.DirectionDown && hints.isNotEmpty() -> {
                                                pathHintIndex = (pathHintIndex + 1).coerceAtMost(hints.size - 1)
                                                true
                                            }
                                            event.key == Key.DirectionUp && hints.isNotEmpty() -> {
                                                pathHintIndex = (pathHintIndex - 1).coerceAtLeast(-1)
                                                true
                                            }
                                            event.key == Key.Tab && hints.isNotEmpty() -> {
                                                applyHint(hints[pathHintIndex.coerceAtLeast(0)])
                                                true
                                            }
                                            event.key == Key.Enter -> {
                                                val picked = hints.getOrNull(pathHintIndex)
                                                if (picked != null) {
                                                    // Выбрана подсказка — переходим в неё
                                                    state.navigateTo(picked)
                                                    isEditingPath = false
                                                    onReturnFocus?.invoke()
                                                } else {
                                                    val p = resolveEnteredPath(pathInputVal.text)
                                                    if (p != null) state.navigateTo(p)
                                                    isEditingPath = false
                                                    onReturnFocus?.invoke()
                                                }
                                                true
                                            }
                                            event.key == Key.Escape -> {
                                                // Первый Esc убирает подсказки, второй — выходит из правки
                                                if (hints.isNotEmpty()) {
                                                    pathHints = emptyList()
                                                    pathHintIndex = -1
                                                } else {
                                                    isEditingPath = false
                                                    onReturnFocus?.invoke()
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                            )

                            if (pathInputVal.text.isNotEmpty()) {
                                IconButton(
                                    onClick = { pathInputVal = TextFieldValue("") },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Очистить",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val p = resolveEnteredPath(pathInputVal.text)
                                    if (p != null) {
                                        state.navigateTo(p)
                                    }
                                    isEditingPath = false
                                    onReturnFocus?.invoke()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Перейти",
                                    tint = NoctaliaPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    val hints = pathHints
                    if (hints.isNotEmpty()) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        androidx.compose.ui.window.Popup(
                            alignment = Alignment.TopStart,
                            offset = androidx.compose.ui.unit.IntOffset(0, with(density) { 38.dp.roundToPx() }),
                            properties = androidx.compose.ui.window.PopupProperties(focusable = false)
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(min = 360.dp, max = 640.dp)
                                    .glass(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.surfaceContainer)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .padding(vertical = 4.dp)
                            ) {
                                hints.forEachIndexed { index, hint ->
                                    val active = index == pathHintIndex
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (active) NoctaliaPrimary.copy(alpha = 0.18f) else Color.Transparent)
                                            .clickable {
                                                // Щелчок — сразу переход в эту папку
                                                state.navigateTo(hint)
                                                isEditingPath = false
                                                onReturnFocus?.invoke()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (active) NoctaliaPrimary else NoctaliaSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = hint.fileName?.toString() ?: hint.toString(),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (active) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Text(
                                    text = "Tab — дополнить · ↑↓ — выбрать · Enter — перейти",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                  }
                } else {
                    // Clickable breadcrumb path bar: clicking anywhere immediately enters edit mode
                    val breadcrumbScroll = rememberScrollState()
                    val breadcrumbScope = rememberCoroutineScope()
                    // Длинный путь обрезается с начала: последняя папка всегда на виду
                    LaunchedEffect(state.currentPath, breadcrumbScroll.maxValue) {
                        if (breadcrumbScroll.maxValue > 0) breadcrumbScroll.scrollTo(breadcrumbScroll.maxValue)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clickable { startPathEditing() }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(breadcrumbScroll)
                                    // Колесо мыши листает путь вбок: вертикального движения тут нет
                                    .onScrollWheel { event ->
                                        val change = event.changes.firstOrNull() ?: return@onScrollWheel
                                        val delta = change.scrollDelta.y + change.scrollDelta.x
                                        if (delta != 0f) {
                                            breadcrumbScope.launch { breadcrumbScroll.scrollBy(delta * 64f) }
                                            change.consume()
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (state.isInsideArchive) {
                                    val archiveLabel = remember(state.currentPath) { state.vfsManager.archiveLabel(state.currentPath) ?: "АРХИВ" }
                                    val readOnly = remember(state.currentPath) { state.vfsManager.isReadOnlyArchive(state.currentPath) }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = NoctaliaTertiary.copy(alpha = 0.2f),
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            if (readOnly) {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    contentDescription = "Только чтение",
                                                    tint = NoctaliaTertiary,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                            }
                                            Text(
                                                text = archiveLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = NoctaliaTertiary
                                            )
                                        }
                                    }
                                }

                                val pathSegments = remember(state.currentPath) {
                                    val list = mutableListOf<Pair<String, Path>>()
                                    var cur: Path? = state.currentPath
                                    while (cur != null) {
                                        val name = cur.fileName?.toString() ?: if (cur.parent == null) "/" else ""
                                        if (name.isNotEmpty()) {
                                            list.add(0, name to cur)
                                        }
                                        cur = cur.parent
                                    }
                                    if (list.isEmpty() || list.first().first != "/") {
                                        list.add(0, "/" to Paths.get("/"))
                                    }
                                    list
                                }

                                pathSegments.forEachIndexed { idx, (segName, segPath) ->
                                    Text(
                                        text = segName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = if (idx == pathSegments.size - 1) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (idx == pathSegments.size - 1) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .clickable { state.navigateTo(segPath) }
                                            .padding(horizontal = 3.dp, vertical = 2.dp)
                                    )
                                    if (idx < pathSegments.size - 1 && segName != "/") {
                                        Text(
                                            text = "/",
                                            color = MaterialTheme.colorScheme.outline,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 1.dp)
                                        )
                                    }
                                }
                            }

                            // Copy path button
                            IconButton(
                                onClick = {
                                    org.wexclient.app.util.TextClipboard.copy(state.currentPath.toAbsolutePath().toString())
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Копировать путь",
                                    tint = NoctaliaSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            // Direct edit button
                            IconButton(
                                onClick = { startPathEditing() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Ввести путь",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Search field with smooth horizontal expansion animation
                val isSearchVisible = state.isSearchOpen || state.searchQuery.isNotEmpty()
                val targetSearchWidth = when {
                    !isSearchVisible -> 0.dp
                    isSearchFocused -> 300.dp
                    state.searchQuery.isNotEmpty() -> 240.dp
                    else -> 170.dp
                }
                val animatedSearchWidth by animateDpAsState(
                    targetValue = targetSearchWidth,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    label = "searchBarWidth"
                )

                if (animatedSearchWidth > 12.dp) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (isSearchFocused) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .width(animatedSearchWidth)
                            .height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (state.isSearching) {
                                WexSpinner(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = NoctaliaPrimary
                                )
                            } else {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Поиск",
                                    tint = if (isSearchFocused) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (state.searchQuery.isEmpty()) {
                                    Text(
                                        text = if (state.isRecursiveSearch) "Поиск везде (Rust fd)..." else "Поиск в папке...",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        style = TextStyle(
                                            fontFamily = AppFonts.JetBrainsMono
                                        )
                                    )
                                }
                                BasicTextField(
                                    value = shownSearch,
                                    onValueChange = { v ->
                                        searchValue = v
                                        if (v.text != state.searchQuery) {
                                            state.searchQuery = v.text
                                            // Пустой запрос — сразу синхронный листинг,
                                            // непустой подхватит debounce выше
                                            if (v.text.isEmpty()) state.refresh()
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(NoctaliaPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                        .onFocusChanged {
                                            isSearchFocused = it.isFocused
                                            state.textInputFocused = it.isFocused || isEditingPath
                                        }
                                        .onKeyEvent {
                                            if (it.key == Key.Escape) {
                                                if (it.type == KeyEventType.KeyDown) closeSearch()
                                                true
                                            } else if (it.key == Key.Enter && it.type == KeyEventType.KeyDown && state.searchQuery.isNotBlank()) {
                                                // Enter — искать сразу, без ожидания
                                                state.refresh()
                                                true
                                            } else false
                                        },
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            innerTextField()
                                        }
                                    }
                                )
                            }

                            // Search result count badge
                            if (state.searchQuery.isNotBlank() && !state.isSearching && state.searchResultsCount != null) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (state.searchResultsCount == 0) MaterialTheme.colorScheme.error.copy(alpha = 0.25f) else NoctaliaPrimary.copy(alpha = 0.18f),
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                ) {
                                    Text(
                                        text = "${state.searchResultsCount}",
                                        fontSize = 10.sp,
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.searchResultsCount == 0) MaterialTheme.colorScheme.error else NoctaliaPrimary,
                                        // Строка обрезается по самим цифрам: иначе запас шрифта сверху и снизу
                                        // разный и число стоит не по центру плашки
                                        lineHeight = 12.sp,
                                        style = androidx.compose.ui.text.TextStyle(
                                            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                                            )
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Recursive search toggle button (Rust fd)
                            IconButton(
                                onClick = {
                                    state.isRecursiveSearch = !state.isRecursiveSearch
                                    state.refresh()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.TravelExplore,
                                    contentDescription = if (state.isRecursiveSearch) "Рекурсивный поиск включен (Rust fd)" else "Поиск только в текущей папке",
                                    tint = if (state.isRecursiveSearch) NoctaliaSecondary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        state.searchQuery = ""
                                        state.refresh()
                                        // Печатать новый запрос можно сразу
                                        runCatching { searchFocusRequester.requestFocus() }
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Очистить",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            // Close search button
                            IconButton(
                                onClick = { closeSearch() },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Закрыть поиск",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                } else {
                    IconButton(
                        onClick = { state.requestSearchFocus() },
                        modifier = Modifier.size(30.dp).noFocus()
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Поиск (" + Keymap.label("app.search.name") + ")",
                            tint = NoctaliaPrimary
                        )
                    }
                }

                if (roomy && tabs != null) {
                    IconButton(
                        onClick = {
                            onActivate()
                            tabs.add()
                        },
                        modifier = Modifier.size(30.dp).noFocus()
                    ) {
                        Icon(
                            // Значок вкладки, а не ещё один плюс: рядом стоит «создать файл».
                            // Он сплошной, поэтому при общем размере выглядел бы крупнее соседних линейных
                            Icons.Default.Tab,
                            contentDescription = "Новая вкладка (" + Keymap.label("tabs.new") + ")",
                            tint = NoctaliaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (roomy && (onNewFolder != null || onNewFile != null)) {
                    var showCreateMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showCreateMenu = true },
                            modifier = Modifier.size(30.dp).noFocus()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Создать папку или файл", tint = NoctaliaPrimary)
                        }
                        WexDropdownMenu(
                            expanded = showCreateMenu,
                            onDismissRequest = { showCreateMenu = false },
                            modifier = Modifier.width(210.dp)
                        ) {
                            ReturnFocusOnClose()
                            onNewFolder?.let { cb ->
                                MenuEntry("create_new_folder", "Папку…", trailing = Keymap.label("pane.newFolder")) {
                                    showCreateMenu = false
                                    onActivate()
                                    cb()
                                }
                            }
                            onNewFile?.let { cb ->
                                MenuEntry("note_add", "Файл…", trailing = Keymap.label("pane.newFile")) {
                                    showCreateMenu = false
                                    onActivate()
                                    cb()
                                }
                            }
                        }
                    }
                }

                if (roomy) ViewOptionsButton(state)

                if (roomy && terminal != null) {
                    // Цвет — из темы, как у соседних кнопок; открытая консоль — залитый значок с подложкой.
                    // Без 48 dp зоны касания Material: иначе подложка рисуется кругом шире кнопки и лезет на соседей
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified
                    ) {
                        IconButton(
                            onClick = { terminal.toggle(state.currentPath) },
                            modifier = Modifier
                                .size(30.dp)
                                // Кнопка не забирает фокус: иначе после открытия он оставался на ней, а не уходил в консоль
                                .noFocus()
                                .background(
                                    animateColorAsState(
                                        if (terminal.visible) NoctaliaPrimary.copy(alpha = 0.16f) else Color.Transparent,
                                        tween(Motion.TINT),
                                        label = "terminal-toggle"
                                    ).value,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        ) {
                            Icon(
                                if (terminal.visible) Icons.Default.Terminal else Icons.Outlined.OutlinedTerminal,
                                contentDescription = if (terminal.visible) "Скрыть консоль (" + Keymap.label("app.terminal2") + ")" else "Консоль (" + Keymap.label("app.terminal2") + ")",
                                tint = NoctaliaPrimary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { state.refresh() },
                    modifier = Modifier.size(30.dp).noFocus()
                ) {
                    // Пока папка читается, значок крутится: видно, что список ещё не готов.
                    // Анимация живёт только это время: заведённая всегда, она просила кадр за кадром
                    // и в простое, а все окна программы рисуются в одном потоке — тормозил и редактор
                    val angle = if (state.isLoading && ViewPreferences.animations) {
                        rememberInfiniteTransition(label = "refresh-spin").animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                            label = "refresh-angle"
                        )
                    } else null
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = if (state.isLoading) "Читаем папку" else "Обновить",
                        tint = NoctaliaPrimary,
                        modifier = Modifier.graphicsLayer { rotationZ = angle?.value ?: 0f }
                    )
                }
            }

            // То же, но сверху — если в настройках выбрано «вкладки под панелью инструментов»
            androidx.compose.animation.AnimatedVisibility(
                visible = tabs != null && tabs.tabs.size > 1 && !ViewPreferences.tabsAtBottom,
                enter = androidx.compose.animation.expandVertically(tween(Motion.ENTER)) + fadeIn(tween(Motion.ENTER)),
                exit = androidx.compose.animation.shrinkVertically(tween(Motion.EXIT)) + fadeOut(tween(Motion.EXIT))
            ) {
                tabs?.let { PaneTabStrip(tabs = it, onActivate = onActivate, atBottom = false) }
            }

            // Быстрый доступ: пользовательские места с иконками, «+», прокрутка
            QuickAccessBar(
                otherPaneOnRight = otherPaneOnRight,
                currentPath = state.currentPath,
                onNavigate = { state.navigateTo(it) },
                onOpenInOtherPane = onOpenInOtherPane,
                onMessage = { msg -> onMessage?.invoke(msg) },
                onOpenTrash = onOpenTrash,
                onDisconnect = onDisconnect
            )

            // Table Header with Sorting
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val nameLabel = when (state.sortField) {
                        SortField.TYPE -> "Имя · по типу"
                        SortField.EXTENSION -> "Имя · по расширению"
                        else -> "Имя"
                    }
                    val nameActive = state.sortField in setOf(SortField.NAME, SortField.TYPE, SortField.EXTENSION)
                    SortHeaderCell(
                        text = nameLabel,
                        active = nameActive,
                        ascending = state.sortAscending,
                        modifier = Modifier.weight(1f)
                    ) { state.toggleSort(if (nameActive) state.sortField else SortField.NAME) }

                    // В узкой панели размер и дата — второй строкой у имени; здесь остаются только переключатели сортировки
                    if (ViewPreferences.showSizeColumn) {
                        SortHeaderCell(
                            text = if (compactRows) "Разм." else "Размер",
                            active = state.sortField == SortField.SIZE,
                            ascending = state.sortAscending,
                            modifier = Modifier.width(if (compactRows) 56.dp else 80.dp)
                        ) { state.toggleSort(SortField.SIZE) }
                    }

                    if (ViewPreferences.showDateColumn) {
                        SortHeaderCell(
                            text = "Дата",
                            active = state.sortField == SortField.DATE,
                            ascending = state.sortAscending,
                            modifier = Modifier.width(if (compactRows) 52.dp else 120.dp)
                        ) { state.toggleSort(SortField.DATE) }
                    }
                }
            }

            // File List with Expressive Motion & Smooth Directory Transitions
            val terminalShown = terminal?.visible == true
            val listWeight = when {
                !terminalShown -> 1f
                terminal!!.maximized -> 0.0001f
                else -> 1f - org.wexclient.app.terminal.TerminalPreferences.heightFraction
            }
            val paneDropTarget = rememberFileDropTarget(
                onHover = { paneDropHover = it },
                onDrop = { paths, point -> requestDrop(paths, state.currentPath, point) }
            )
            // Высота панели выделения над списком: столько места оставляем под последней строкой
            var selectionBarPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            Box(
                modifier = Modifier
                    .weight(listWeight)
                    // Индикатор загрузки — по центру панели, а не у левого края
                    .fillMaxWidth()
                    .onGloballyPositioned { listBoxOrigin = it.positionInRoot() }
                    .then(if (onDropFiles != null && !state.isSearching) paneDropTarget else Modifier)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                // Main-пасс: строки (FileItemRow) получают событие раньше и делают
                                // consume() при ПКМ — тогда фоновое меню не показываем (иначе 2 диалога).
                                // Initial-пасс здесь нельзя: родитель сработал бы ДО строки.
                                val event = awaitPointerEvent()
                                // Строка срабатывает при отпускании — номер щелчка к этому времени уже известен
                                if (event.type == PointerEventType.Press) {
                                    clickSeries.count = event.clickCount()
                                }
                                // Щелчок по списку отдаёт клавиатуру файловому менеджеру (из консоли, поиска, строки пути)
                                if (event.type == PointerEventType.Press) {
                                    (onReturnFocus ?: terminal?.onRequestOuterFocus.takeIf { org.wexclient.app.terminal.TerminalFocus.active })?.invoke()
                                }
                                if (event.changes.all { it.isConsumed }) continue
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    val pos = event.changes.first().position
                                    paneBgMenuOffset = DpOffset(pos.x.toDp(), pos.y.toDp())
                                    showPaneBgMenu = true
                                }
                            }
                        }
                    }
            ) {
                if (state.isLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        WexSpinner(color = NoctaliaPrimary)
                        state.loadingText?.let {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = state.navigationId,
                        transitionSpec = {
                            if (state.navDirection == NavDirection.FORWARD) {
                                (slideInHorizontally(
                                    animationSpec = tween(Motion.NAV, easing = FastOutSlowInEasing),
                                    initialOffsetX = { fullWidth -> fullWidth / 4 }
                                ) + fadeIn(tween(Motion.NAV_FADE))).togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(Motion.NAV, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> -fullWidth / 4 }
                                    ) + fadeOut(tween(Motion.NAV_FADE))
                                )
                            } else if (state.navDirection == NavDirection.BACKWARD) {
                                (slideInHorizontally(
                                    animationSpec = tween(Motion.NAV, easing = FastOutSlowInEasing),
                                    initialOffsetX = { fullWidth -> -fullWidth / 4 }
                                ) + fadeIn(tween(Motion.NAV_FADE))).togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(Motion.NAV, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> fullWidth / 4 }
                                    ) + fadeOut(tween(Motion.NAV_FADE))
                                )
                            } else {
                                fadeIn(tween(Motion.NAV_FADE)).togetherWith(fadeOut(tween(Motion.NAV_FADE)))
                            }
                        }
                    ) { navId ->
                        // Текущая страница или уезжающая при анимации: у уезжающей — свой снимок списка
                        val live = navId == state.navigationId
                        val leavingPage = if (live) null else state.leaving?.takeIf { it.navigationId == navId }
                        val currentKey = remember(navId) { (leavingPage?.path ?: state.currentPath).toString() }
                        val shownEntries: List<VfsEntry> = if (live) state.entries else leavingPage?.entries.orEmpty()
                        val showUp = if (live) state.canGoUp else leavingPage?.canGoUp == true
                        val savedPos = remember(currentKey) { state.getScrollPosition(currentKey) ?: (0 to 0) }
                        val listState = rememberLazyListState(
                            initialFirstVisibleItemIndex = savedPos.first,
                            initialFirstVisibleItemScrollOffset = savedPos.second
                        )

                        // Save scroll position whenever user scrolls
                        LaunchedEffect(listState) {
                            snapshotFlow {
                                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                            }.collect { (idx, offset) ->
                                // Уезжающая страница уже не своя: её позиция не должна затирать сохранённую
                                if (navId == state.navigationId) state.saveScrollPosition(currentKey, idx, offset)
                            }
                        }

                        LaunchedEffect(listState, live) {
                            if (!live) return@LaunchedEffect
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.size }
                                .collect { if (it > 2) state.pageRows = it - 1 }
                        }

                        // Курсор ушёл за край — плавно показать. Индекс читается потоком, а не ключом эффекта:
                        // ключ читался в композиции, и каждая стрелка перекомпоновывала всю страницу списка
                        // state в ключе: при смене вкладки с тем же navigationId AnimatedContent оставляет эту
                        // страницу, и эффект иначе продолжал бы следить за прежней вкладкой
                        LaunchedEffect(state, listState, live) {
                            androidx.compose.runtime.snapshotFlow { state.focusedIndex }.collectLatest { focused ->
                                if (live && focused in state.entries.indices) {
                                    val target = if (state.canGoUp) focused + 1 else focused
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    if (visible.isNotEmpty() && visible.none { it.index == target }) {
                                        listState.animateScrollToItem((target - 2).coerceAtLeast(0))
                                    }
                                }
                            }
                        }

                        // Плавно показать то, что только что сюда скопировали или переместили
                        LaunchedEffect(state.revealRequest) {
                            if (!live) return@LaunchedEffect
                            val request = state.revealRequest ?: return@LaunchedEffect
                            // Раскладка с новыми элементами — со следующего кадра
                            androidx.compose.runtime.withFrameNanos { }
                            val shift = if (state.canGoUp) 1 else 0
                            val first = request.first + shift
                            val last = request.last + shift
                            val layout = listState.layoutInfo
                            fun shown(index: Int) = layout.visibleItemsInfo.any {
                                it.index == index && it.offset >= layout.viewportStartOffset &&
                                    it.offset + it.size <= layout.viewportEndOffset - layout.afterContentPadding
                            }
                            if (shown(first) && shown(last)) return@LaunchedEffect
                            // Два элемента над первым новым — чтобы было видно, куда он встал
                            listState.animateScrollToItem((first - 2).coerceAtLeast(0))
                        }

                        // Полоса прокрутки списка: в покое — тонкая нить, под курсором расширяется
                        val barInteraction = remember { MutableInteractionSource() }
                        val barHovered by barInteraction.collectIsHoveredAsState()
                        val barWidth by animateDpAsState(
                            // В покое — почти незаметная нить, под курсором чуть шире
                            if (barHovered) 7.dp else 2.dp,
                            tween(Motion.TINT),
                            label = "list-scrollbar"
                        )
                        // Панель выделения лежит поверх списка — последняя строка прокручивается выше её кнопок
                        val selectionPad by animateDpAsState(
                            if (state.selectionMode) {
                                with(androidx.compose.ui.platform.LocalDensity.current) { selectionBarPx.toDp() } - SELECTION_BAR_FADE
                            } else 0.dp,
                            tween(Motion.ENTER),
                            label = "selection-pad"
                        )
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 36.dp + selectionPad.coerceAtLeast(0.dp))
                            ) {
                                // Parent directory entry ("..")
                                if (showUp) {
                                    item {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                // Считается каждый щелчок: «..» всегда на одном месте, и быстрые
                                                // щелчки поднимают на столько же уровней, сколько их было
                                                // (в том числе во время перелистывания — «вверх» берётся от текущего пути)
                                                .clickable {
                                                    val currentScroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                                                    state.goUp(currentScroll)
                                                },
                                            color = Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = NoctaliaSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = "..",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = NoctaliaSecondary
                                                )
                                            }
                                        }
                                    }
                                }

                                itemsIndexed(shownEntries, key = { _, it -> it.path.toString() }) { idx, entry ->
                                    // Всё, что меняется у одной-двух строк (выделение, подсветка, цель перетаскивания,
                                    // размер папки), — через derivedStateOf: иначе каждое такое изменение
                                    // перекомпоновывало все видимые строки (при зажатом Insert — 30 раз в секунду)
                                    // state в ключах: вкладки с той же папкой и тем же navigationId переиспользуют строки
                                    val isRecentlyReturned by remember(state, entry.path) {
                                        androidx.compose.runtime.derivedStateOf { entry.name == state.recentlyReturnedChildName || state.isArrived(entry) }
                                    }
                                    val subtitle by remember(state, entry.path) {
                                        androidx.compose.runtime.derivedStateOf {
                                            if (state.isRecursiveSearch && state.searchQuery.isNotBlank()) {
                                                try {
                                                    state.currentPath.relativize(entry.path.parent ?: entry.path).toString().takeIf { it.isNotEmpty() && it != "." }
                                                } catch (_: Exception) {
                                                    entry.path.parent?.toString()
                                                }
                                            } else null
                                        }
                                    }
                                    val selectedHere by remember(state, entry.path) { androidx.compose.runtime.derivedStateOf { state.isSelected(entry) } }
                                    val dropHere by remember(entry.path) { androidx.compose.runtime.derivedStateOf { dropRowPath == entry.path } }
                                    val folderSizeHere by remember(state, entry.path) {
                                        androidx.compose.runtime.derivedStateOf { if (entry.isDirectory) state.folderSizes[entry.path.toString()] else null }
                                    }

                                    // Стрелки перерисовывают только две строки, а не весь экран
                                    val focusedHere by remember(state, idx, live) { androidx.compose.runtime.derivedStateOf { live && idx == state.focusedIndex } }
                                    val dragSource = rememberFileDragSource(
                                        paths = {
                                            val group = if (state.isSelected(entry)) state.selectedEntries.toList() else listOf(entry)
                                            group.map { it.path }
                                        },
                                        label = {
                                            val n = if (state.isSelected(entry)) state.selectedEntries.size else 1
                                            if (n == 1) entry.name else org.wexclient.app.util.Plural.items(n)
                                        },
                                        onFinished = onDragFinished
                                    )
                                    val rowDrop = if (entry.isDirectory && !entry.isArchive && onDropFiles != null) {
                                        rememberFileDropTarget(
                                            onHover = { over ->
                                                if (over) dropRowPath = entry.path
                                                else if (dropRowPath == entry.path) dropRowPath = null
                                            },
                                            onDrop = { paths, point -> requestDrop(paths, entry.path, point) }
                                        )
                                    } else Modifier
                                    FileItemRow(
                                        otherPaneOnRight = otherPaneOnRight,
                                        // Плавное появление и переезд строк: удаление и вставка больше не «прыгают»
                                        dragModifier = dragSource.then(rowDrop).then(Modifier.animateItem()),
                                        folderSize = folderSizeHere,
                                        isDropTarget = dropHere,
                                        entry = entry,
                                        isSelected = selectedHere,
                                        isFocused = isActive && focusedHere,
                                        isRecentlyReturned = isRecentlyReturned,
                                        subtitle = subtitle,
                                        opening = opening?.takeIf { it.path == entry.path },
                                        isCut = entry.path in org.wexclient.app.vfs.FileClipboard.cutPaths,
                                        gitMark = if (ViewPreferences.showGitStatus) state.gitMarkFor(entry) else null,
                                        actions = { rowActions(entry) },
                                        onClick = click@{
                                            if (!live) return@click
                                            // Открытие — одним щелчком: второй щелчок двойного пришёлся бы уже на новый список
                                            // («..» поднимал на два уровня, папка открывала то, что оказалось под курсором)
                                            if (clickSeries.repeated) return@click
                                            onActivate()
                                            state.focusedIndex = idx
                                            val currentScroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                                            val mods = windowInfo.keyboardModifiers
                                            when {
                                                mods.isShiftPressed -> state.selectRange(idx)
                                                // Ctrl+клик или режим выделения (после зажатия) — выделяем, не открываем
                                                mods.isCtrlPressed || mods.isMetaPressed || state.selectionMode -> state.toggleSelection(entry)
                                                entry.itemType == org.wexclient.app.vfs.VfsItemType.ARCHIVE_APKS && onOpenBundle != null -> {
                                                    onOpenBundle(entry)
                                                }
                                                entry.itemType == org.wexclient.app.vfs.VfsItemType.ARCHIVE_APK && onOpenApkDetail != null -> {
                                                    onOpenApkDetail(entry)
                                                }
                                                entry.isDirectory || entry.isArchive -> {
                                                    state.enterEntry(entry, currentScroll)
                                                }
                                                else -> {
                                                    onOpenFile(entry)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            onActivate()
                                            state.focusedIndex = idx
                                            state.toggleSelection(entry)
                                        },
                                        onSwipeSelect = {
                                            if (live) {
                                                onActivate()
                                                state.focusedIndex = idx
                                                state.swipeSelect(entry)
                                            }
                                        },
                                        onRightClick = {
                                            onActivate()
                                            state.focusedIndex = idx
                                            // Меню действует на эту строку, поэтому чужое выделение снимаем,
                                            // а выделение с этой строкой оставляем — меню работает по всему набору
                                            if (state.selectedEntries.isNotEmpty() && !state.isSelected(entry)) {
                                                state.selectedEntries.clear()
                                            }
                                        },
                                        onCopyOther = { onCopyOther(entry) },
                                        onMoveOther = onMoveOther?.let { { it(entry) } },
                                        onRename = onRename?.let { { it(entry) } },
                                        onDelete = { onDelete(entry) },
                                        onCompress = onCompress?.let { { it(entry) } },
                                        onExtract = onExtract?.let { { e, a -> it(e, a) } },
                                        onOpenTerminal = onOpenTerminal?.let { { it(entry.path) } },
                                        onProperties = { onProperties(entry) },
                                        onAnalyzeApk = { onAnalyzeApk?.invoke(entry) },
                                        onInspectDex = { onInspectDex?.invoke(entry) },
                                        onSignApk = { onSignApk?.invoke(entry) },
                                        onInstallApk = onInstallApk?.let { { it(entry) } },
                                        // Сборка читает набор как файл на диске: в архиве и на сервере её нет
                                        onConvertBundle = onConvertBundle?.takeIf { entry.path.fileSystem === java.nio.file.FileSystems.getDefault() }?.let { { it(entry) } },
                                        onOpenHex = onOpenHex?.let { { it(entry) } },
                                        onChecksum = onChecksum?.let { { it(entry) } },
                                        onDiffWithOther = onDiffWithOther?.let { { it(entry) } },
                                        selectionMode = state.selectionMode,
                                        onRun = when {
                                            entry.itemType == org.wexclient.app.vfs.VfsItemType.APPIMAGE && onLaunchAppImage != null -> {
                                                { onActivate(); onLaunchAppImage(entry) }
                                            }
                                            terminal != null && org.wexclient.app.terminal.ScriptRunner.isRunnable(entry.path, entry.isDirectory) -> {
                                                { onActivate(); runScript(entry) }
                                            }
                                            else -> null
                                        },
                                        onNewFolder = onNewFolder?.let { cb -> { onActivate(); cb() } },
                                        onNewFile = onNewFile?.let { cb -> { onActivate(); cb() } },
                                        onViewContent = onViewContent?.let { cb -> { onActivate(); cb(entry) } },
                                        onImportKey = if (onImportKey != null && entry.itemType == org.wexclient.app.vfs.VfsItemType.KEYSTORE) {
                                            { onActivate(); onImportKey(entry) }
                                        } else null,
                                        onRevealInFolder = if (state.searchQuery.isNotBlank()) {
                                            { revealInFolder(entry) }
                                        } else null
                                    )
                                }

                                if (live && state.entries.isEmpty() && !state.isLoading && !state.isSearching) {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 40.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            if (state.searchQuery.isNotBlank()) {
                                                Text(
                                                    text = "Ничего не найдено по запросу \"${state.searchQuery}\"",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = if (state.isRecursiveSearch) {
                                                        "Поиск выполнен во всех подпапках (Rust fd)"
                                                    } else {
                                                        "Поиск выполнен только в текущей папке"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            } else {
                                                val problem = state.listError
                                                Text(
                                                    text = problem ?: "Папка пуста",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (problem != null) NoctaliaDanger else MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val canScroll = listState.canScrollForward || listState.canScrollBackward
                            androidx.compose.animation.AnimatedVisibility(
                                visible = canScroll,
                                enter = fadeIn(tween(Motion.ENTER)),
                                exit = fadeOut(tween(Motion.EXIT)),
                                modifier = Modifier.align(Alignment.CenterEnd)
                            ) {
                                WexVerticalScrollbar(
                                    state = listState,
                                    style = WexScrollbarStyle(
                                        thickness = barWidth,
                                        hoverDurationMillis = Motion.TINT,
                                        unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                        hoverColor = NoctaliaPrimary.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(14.dp)
                                        .hoverable(barInteraction)
                                )
                            }
                        }
                    }
                }

                // Режим выделения, как в MT: кнопки поверх списка, на затемнении от нижнего края
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.selectionMode,
                    enter = fadeIn(tween(Motion.ENTER)) +
                        androidx.compose.animation.slideInVertically(tween(Motion.ENTER)) { it / 3 },
                    exit = fadeOut(tween(Motion.EXIT)) +
                        androidx.compose.animation.slideOutVertically(tween(Motion.EXIT)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { if (it.height > 0) selectionBarPx = it.height }
                ) {
                    SelectionBar(state, compact = compactRows)
                }

                // Переход по первым буквам: что набрано
                TypeAheadHint(state, Modifier.align(Alignment.BottomEnd).padding(10.dp))

                // Что сделать с брошенными файлами — как в Dolphin
                pendingDrop?.let { drop ->
                    CursorMenu(
                        expanded = true,
                        onDismissRequest = { pendingDrop = null },
                        offset = drop.offset,
                        modifier = Modifier.width(260.dp)
                    ) {
                        val what = if (drop.paths.size == 1) "«${drop.paths[0].fileName}»" else org.wexclient.app.util.Plural.items(drop.paths.size)
                        Text(
                            "$what → «${drop.dir.fileName ?: drop.dir}»",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        val local = drop.paths.all { it.fileSystem === java.nio.file.FileSystems.getDefault() } &&
                            drop.dir.fileSystem === java.nio.file.FileSystems.getDefault()
                        val choices = buildList {
                            add(Triple(Icons.Default.ContentCopy, "Копировать сюда", DropAction.COPY))
                            add(Triple(Icons.AutoMirrored.Filled.DriveFileMove, "Переместить сюда", DropAction.MOVE))
                            if (local) add(Triple(Icons.Default.Link, "Создать ссылки", DropAction.LINK))
                        }
                        for ((icon, label, action) in choices) {
                            DropdownMenuItem(
                                modifier = Modifier.height(34.dp),
                                leadingIcon = { Icon(icon, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                                text = { Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp)) },
                                onClick = {
                                    pendingDrop = null
                                    onDropFiles?.invoke(drop.paths, drop.dir, action)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                            text = { Text("Отмена", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)) },
                            onClick = { pendingDrop = null },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                }

                // Empty Space Context Menu (Pane level)
                CursorMenu(
                    expanded = showPaneBgMenu,
                    onDismissRequest = { showPaneBgMenu = false },
                    offset = paneBgMenuOffset,
                    // Шире прежнего: «Сравнить папки панелей» и «Найти дубликаты в обеих панелях» не обрезаются
                    modifier = Modifier.width(300.dp)
                ) {
                    val extra = paneActions()
                    for (action in extra) {
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(action.icon, contentDescription = null, tint = action.tint ?: NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                            text = {
                                Text(
                                    action.label,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = action.shortcut?.let { sc ->
                                { Text(sc, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            },
                            onClick = {
                                showPaneBgMenu = false
                                onActivate()
                                action.onClick()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                    if (extra.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))
                    }
                    DropdownMenuItem(
                        modifier = Modifier.height(32.dp),
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                        text = { Text("Создать папку", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                        trailingIcon = { Text("F7", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            showPaneBgMenu = false
                            onActivate()
                            onNewFolder?.invoke()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    )

                    if (onNewFile != null) {
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                            text = { Text("Создать файл", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                            trailingIcon = { Text("Shift+F4", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = {
                                showPaneBgMenu = false
                                onActivate()
                                onNewFile()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }

                    DropdownMenuItem(
                        modifier = Modifier.height(32.dp),
                        leadingIcon = {
                            Icon(
                                if (state.countingFolders) Icons.Default.Stop else Icons.Default.DataUsage,
                                contentDescription = null,
                                tint = NoctaliaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        text = {
                            Text(
                                if (state.countingFolders) "Остановить подсчёт" else "Посчитать размеры папок",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                                maxLines = 1
                            )
                        },
                        onClick = {
                            showPaneBgMenu = false
                            if (state.countingFolders) state.stopFolderSizes() else state.calculateFolderSizes()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    )

                    DropdownMenuItem(
                        modifier = Modifier.height(32.dp),
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                        text = { Text("Обновить", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                        trailingIcon = { Text("Ctrl+R", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            showPaneBgMenu = false
                            state.refresh(force = true)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    )

                    if (terminal != null) {
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                            text = { Text("Открыть консоль здесь", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                            trailingIcon = { Text("Ctrl+`", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = {
                                showPaneBgMenu = false
                                if (!terminal.hasSessions) terminal.show(state.currentPath)
                                else terminal.newSession(state.currentPath)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                    if (onOpenTerminal != null) {
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = NoctaliaSecondary, modifier = Modifier.size(16.dp)) },
                            text = { Text("Открыть во внешнем терминале", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                            onClick = {
                                showPaneBgMenu = false
                                onOpenTerminal(state.currentPath)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))

                    DropdownMenuItem(
                        modifier = Modifier.height(32.dp),
                        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) },
                        text = { Text("Выбрать все", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                        trailingIcon = { Text("Ctrl+A", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            showPaneBgMenu = false
                            state.selectAll()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    )

                    if (state.selectedEntries.isNotEmpty()) {
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                            text = { Text("Снять все выделения", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), maxLines = 1) },
                            trailingIcon = { Text("Esc", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = {
                                showPaneBgMenu = false
                                state.clearSelection()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                }
            }

            // Вкладки панели — внизу, над строкой состояния; показываем, только когда их больше одной
            androidx.compose.animation.AnimatedVisibility(
                visible = tabs != null && tabs.tabs.size > 1 && ViewPreferences.tabsAtBottom,
                enter = androidx.compose.animation.expandVertically(tween(Motion.ENTER), expandFrom = Alignment.Bottom) +
                    fadeIn(tween(Motion.ENTER)),
                exit = androidx.compose.animation.shrinkVertically(tween(Motion.EXIT), shrinkTowards = Alignment.Bottom) +
                    fadeOut(tween(Motion.EXIT))
            ) {
                tabs?.let { PaneTabStrip(tabs = it, onActivate = onActivate, atBottom = true) }
            }

            // Footer / Stats Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp + footerInsetStart, end = 12.dp + footerInsetEnd, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.searchQuery.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.isSearching) {
                                WexSpinner(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = NoctaliaPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Поиск...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NoctaliaPrimary
                                )
                            } else {
                                Text(
                                    text = "Найдено: ${state.entries.size} файлов${if (state.isRecursiveSearch) " (во всех подпапках)" else " (в папке)"}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (state.entries.isEmpty()) NoctaliaDanger else NoctaliaSecondary
                                )
                            }
                        }
                    } else {
                        if (!(compactRows && state.selectionMode)) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "Элементов: ${state.entries.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (state.typeFilter != TypeFilter.ALL) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = NoctaliaPrimary.copy(alpha = 0.16f),
                                    modifier = Modifier.clickable { state.applyTypeFilter(TypeFilter.ALL) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Фильтр: ${state.typeFilter.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NoctaliaPrimary
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Icon(Icons.Default.Clear, contentDescription = "Сбросить фильтр", tint = NoctaliaPrimary, modifier = Modifier.size(10.dp))
                                    }
                                }
                            }
                            if (!ViewPreferences.showHidden) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "скрытые не показаны",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                            }
                            val git = state.git
                            if (git != null && ViewPreferences.showGitStatus) {
                                Spacer(modifier = Modifier.width(10.dp))
                                GitBranchLabel(git)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.selectedEntries.isNotEmpty()) {
                            val totalSelectedSize = state.selectedEntries.sumOf { it.size }
                            Text(
                                text = "Выбрано: ${state.selectedEntries.size} (${formatBytes(totalSelectedSize)})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = NoctaliaPrimary,
                                maxLines = 1
                            )
                        }
                        val space = state.diskSpace
                        if (space != null) {
                            Spacer(modifier = Modifier.width(12.dp))
                            DiskSpaceLabel(space)
                        }
                    }
                }
            }

            // Встроенная консоль (внизу панели)
            if (terminalShown && terminal != null) {
                val terminalWeight by animateFloatAsState(
                    targetValue = if (terminal.maximized) 1f else org.wexclient.app.terminal.TerminalPreferences.heightFraction,
                    animationSpec = tween(Motion.ENTER, easing = FastOutSlowInEasing),
                    label = "terminal-weight"
                )
                val terminalAppear by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = tween(Motion.ENTER, easing = FastOutSlowInEasing),
                    label = "terminal-appear"
                )
                if (!terminal.maximized) {
                    org.wexclient.app.terminal.TerminalSplitter(totalHeightPx = { paneColumnHeight.toFloat() })
                }
                org.wexclient.app.terminal.TerminalPanel(
                    state = terminal,
                    currentDir = state.currentPath,
                    onOpenPath = { target ->
                        val dir = if (java.nio.file.Files.isDirectory(target)) target else target.parent
                        if (dir != null) {
                            state.navigateTo(dir)
                            if (dir != target) {
                                state.whenLoaded {
                                    val idx = state.entries.indexOfFirst { it.name == target.fileName?.toString() }
                                    if (idx >= 0) {
                                        state.focusedIndex = idx
                                        onOpenFile(state.entries[idx])
                                    }
                                }
                            }
                        }
                        onActivate()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Разворот консоли и её появление — плавные, а не скачком
                        .weight(terminalWeight)
                        .alpha(terminalAppear)
                )
            }
        }
    }
    }
}

@Composable
private fun SortHeaderCell(text: String, active: Boolean, ascending: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (active) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (active) {
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (ascending) "По возрастанию" else "По убыванию",
                tint = color,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

/** «Свободно 120,4 ГБ из 931,5 ГБ» и полоска занятого места. */
@Composable
private fun DiskSpaceLabel(space: org.wexclient.app.vfs.DiskSpace) {
    val used = space.usedFraction
    val barColor = when {
        used >= 0.95f -> NoctaliaDanger
        used >= 0.85f -> NoctaliaWarn
        else -> NoctaliaSecondary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "свободно ${humanSize(space.free)} из ${humanSize(space.total)}",
            style = MaterialTheme.typography.labelSmall,
            color = if (used >= 0.95f) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(5.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f), RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(used.coerceAtLeast(0.04f))
                    .background(barColor, RoundedCornerShape(3.dp))
            )
        }
    }
}

/** Ветка git и число изменений в нижней строке. */
@Composable
private fun GitBranchLabel(git: org.wexclient.app.vfs.GitRepoStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Commit, contentDescription = "Ветка git", tint = NoctaliaTertiary, modifier = Modifier.size(13.dp))
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = git.branchLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = NoctaliaTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp)
        )
        if (git.changedCount > 0) {
            Text(
                text = " · изменений: ${git.changedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = gitMarkColor(org.wexclient.app.vfs.GitMark.MODIFIED),
                maxLines = 1
            )
        }
    }
}

/** Набранные буквы при переходе по имени; гаснет через секунду без нажатий. */
@Composable
private fun TypeAheadHint(state: FilePaneState, modifier: Modifier) {
    val text = state.typeAheadText
    LaunchedEffect(state.typeAheadStamp) {
        if (state.typeAheadText.isNotEmpty()) {
            delay(FilePaneState.TYPE_AHEAD_MS + 150)
            state.clearTypeAhead()
        }
    }
    var shown by remember { mutableStateOf("") }
    if (text.isNotEmpty()) shown = text
    val missed = state.typeAheadMissed
    androidx.compose.animation.AnimatedVisibility(
        visible = text.isNotEmpty(),
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(260)),
        modifier = modifier
    ) {
        val accent = if (missed) NoctaliaDanger else NoctaliaPrimary
        val hintShape = RoundedCornerShape(8.dp)
        Surface(
            shape = hintShape,
            color = Color.Transparent,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
            modifier = Modifier.glass(hintShape, MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = shown.replace(' ', '␣'),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    color = accent,
                    maxLines = 1
                )
                if (missed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("нет такого имени", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
        }
    }
}

/** Брошенные файлы ждут выбора: копировать, переместить или сослаться. */
private class PendingDrop(val paths: List<Path>, val dir: Path, val offset: DpOffset)

/** Номер щелчка в серии (AWT clickCount) последнего нажатия в списке; обычное поле — перерисовки не нужны. */
private class ClickSeries {
    var count = 1

    /** Второй и дальше щелчок двойного/тройного. */
    val repeated: Boolean get() = count >= 2
}

/** Полоса вкладок панели: имя папки, крестик, активная подсвечена. */
/**
 * Меню вкладки по правому щелчку: путь, закрепление, быстрый доступ и закрытие.
 * Пункты — те же компактные строки со значками, что и в остальных меню программы.
 */
@Composable
private fun PaneTabMenu(tabs: PaneTabs, at: Int, expanded: Boolean, onDismiss: () -> Unit) {
    val tab = tabs.tabs.getOrNull(at) ?: return
    val clipboard = LocalClipboardManager.current
    var addPlace by remember { mutableStateOf(false) }
    val local = tab.currentPath.fileSystem == java.nio.file.FileSystems.getDefault()
    val pathText = remember(tab.currentPath) { tab.currentPath.toAbsolutePath().toString() }
    val others = tabs.tabs.count { it !== tab && !it.pinned }
    val closable = tabs.tabs.count { !it.pinned }

    WexDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(248.dp)) {
        MenuEntry("content_copy", "Копировать путь") {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(pathText))
            onDismiss()
        }
        MenuEntry("push_pin", if (tab.pinned) "Открепить" else "Закрепить", checked = tab.pinned) {
            tabs.togglePin(at)
            onDismiss()
        }
        MenuEntry("bookmark_add", "В быстрый доступ…", enabled = local) {
            onDismiss()
            addPlace = true
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        MenuEntry("close", "Закрыть вкладку", enabled = !tab.pinned && tabs.tabs.size > 1, trailing = Keymap.label("tabs.close")) {
            onDismiss()
            tabs.close(at)
        }
        MenuEntry("clear_all", "Закрыть другие", enabled = others > 0) {
            onDismiss()
            tabs.closeOthers(at)
        }
        MenuEntry("delete_sweep", "Закрыть все", enabled = closable > 0, destructive = true) {
            onDismiss()
            tabs.closeAll()
        }
    }

    if (addPlace) {
        PlaceEditDialog(
            title = "В быстрый доступ",
            initial = org.wexclient.app.vfs.BookmarkItem(
                title = tab.currentPath.fileName?.toString() ?: pathText,
                pathStr = pathText
            ),
            currentPath = pathText,
            onConfirm = { title, path, icon ->
                org.wexclient.app.vfs.BookmarksManager.add(title, path, icon)
                addPlace = false
            },
            onDismiss = { addPlace = false }
        )
    }
}

@Composable
private fun PaneTabStrip(tabs: PaneTabs, onActivate: () -> Unit, atBottom: Boolean) {
    val scroll = rememberScrollState()
    // Внизу полоса примыкает к списку файлов, поэтому отделяем её линией и лёгкой подложкой
    Column(modifier = Modifier.fillMaxWidth()) {
        if (atBottom) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (atBottom) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f) else Color.Transparent
                )
                .horizontalScroll(scroll)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
        tabs.tabs.forEachIndexed { index, tab ->
            val active = index == tabs.index
            // Новая вкладка «раскрывается», закрытая — складывается
            var shown by remember(tab) { mutableStateOf(false) }
            LaunchedEffect(tab) { shown = true }
            androidx.compose.animation.AnimatedVisibility(
                visible = shown,
                enter = androidx.compose.animation.expandHorizontally(tween(Motion.ENTER)) + fadeIn(tween(Motion.ENTER)),
                exit = androidx.compose.animation.shrinkHorizontally(tween(Motion.EXIT)) + fadeOut(tween(Motion.EXIT))
            ) {
            val background by androidx.compose.animation.animateColorAsState(
                targetValue = if (active) NoctaliaPrimary.copy(alpha = 0.18f) else Color.Transparent,
                animationSpec = tween(Motion.TINT),
                label = "tab-background"
            )
            var menuOpen by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = background,
                border = if (active) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f)) else null,
                modifier = Modifier
                    .clickable {
                        onActivate()
                        tabs.select(index)
                    }
                    // Правый щелчок по вкладке — её меню. Ловим на Initial: обычный жест
                    // отдаёт только левую кнопку
                    .pointerInput(index) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    onActivate()
                                    menuOpen = true
                                }
                            }
                        }
                    }
            ) {
                PaneTabMenu(
                    tabs = tabs,
                    at = index,
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, end = 3.dp, top = 3.dp, bottom = 3.dp)
                ) {
                    // Значок и подпись перекрашиваются вместе с подложкой вкладки
                    val tint by animateColorAsState(
                        if (active) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                        tween(Motion.TINT),
                        label = "tab-tint"
                    )
                    val label by animateColorAsState(
                        if (active) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(Motion.TINT),
                        label = "tab-label"
                    )
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = tabs.title(tab),
                        fontSize = 11.sp,
                        color = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp)
                    )
                    // Закреплённую вкладку не закрывают крестиком: вместо него булавка
                    IconButton(
                        onClick = { if (tab.pinned) tabs.togglePin(index) else tabs.close(index) },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            if (tab.pinned) Icons.Default.PushPin else Icons.Default.Close,
                            contentDescription = if (tab.pinned) "Открепить вкладку"
                            else "Закрыть вкладку (" + Keymap.label("tabs.close") + ")",
                            tint = if (tab.pinned) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            }
        }
        }
    }
}

/** Высота плавного перехода над кнопками панели выделения: затемнение здесь сходит на нет. */
private val SELECTION_BAR_FADE = 36.dp

/** Затемнение от нижнего края: прозрачное наверху, плотнее книзу, с мягким (smoothstep) нарастанием. */
private val SELECTION_BAR_SCRIM = androidx.compose.ui.graphics.Brush.verticalGradient(
    *Array(9) { i ->
        val t = i / 8f
        t to Color.Black.copy(alpha = 0.78f * t * t * (3f - 2f * t))
    }
)

/** Кнопки режима выделения (как нижняя панель MT Manager при множественном выборе) — поверх списка, без плашки. */
@Composable
private fun SelectionBar(state: FilePaneState, compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SELECTION_BAR_SCRIM)
            .padding(start = 6.dp, end = 6.dp, top = SELECTION_BAR_FADE, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionChip(Icons.Default.SelectAll, "Все", compact) { state.selectAll() }
        SelectionChip(Icons.Default.Contrast, if (compact) "Инверс." else "Инверсия", compact) { state.invertSelection() }
        SelectionChip(Icons.Default.Category, if (compact) "Тип" else "По типу", compact) { state.selectSameType() }
        SelectionChip(Icons.Default.Deselect, "Снять", compact) { state.clearSelection() }
    }
}

/** Кнопка режима выделения; в узкой панели — значок с подписью под ним (как нижняя панель MT), чтобы влезли все. */
@Composable
private fun RowScope.SelectionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, compact: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val base = Modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
        .clickable(onClick = onClick)
    if (compact) {
        Column(
            modifier = base.weight(1f).padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = NoctaliaPrimary, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    } else {
        Row(modifier = base.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}
