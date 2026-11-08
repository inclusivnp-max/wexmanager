package org.wexclient.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.wexclient.app.doc.*
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.util.WindowIcon
import org.wexclient.app.vfs.VfsEntry
import java.io.IOException
import kotlin.math.roundToInt

private data class LoadedPdf(val session: PdfBookSession, val info: PdfInfo, val state: PdfReadingState)

@Composable
actual fun PdfViewerDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    val windowState = rememberWindowState(width = 1200.dp, height = 900.dp)
    var fullscreen by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val store = remember { PdfReadingStore() }
    val book by produceState<Result<LoadedPdf>?>(null, entry.path, reload) {
        value = null
        var session: PdfBookSession? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                check(PdfTools.available && org.wexclient.app.util.ProcessRunner.isAvailable("pdfinfo")) {
                    "Установите Poppler: для чтения нужны pdfinfo и pdftoppm; для поиска и текста — pdftotext."
                }
                val info = PdfTools.info(entry.path) ?: throw IOException("PDF не открылся. Проверьте файл: он может быть повреждён или защищён паролем.")
                check(info.pages > 0) { "В документе нет страниц" }
                val opened = PdfBookSession(entry.path)
                session = opened
                LoadedPdf(opened, info, store.load(entry.path, info.pages))
            }
            value = Result.success(loaded)
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = Result.failure(e) }
        finally { session?.close() }
    }
    LaunchedEffect(fullscreen) { windowState.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating }
    Window(
        onCloseRequest = onDismiss, title = "${entry.name} — чтение", state = windowState,
        onPreviewKeyEvent = latestKeyHandler { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.key == Key.F11 -> { fullscreen = !fullscreen; true }
                e.key == Key.Escape && fullscreen -> { fullscreen = false; true }
                else -> false
            }
        }
    ) {
        LaunchedEffect(window) { WindowIcon.applyTo(window) }
        NoctaliaTheme(backdrop = false) {
            Surface(Modifier.fillMaxSize()) {
                val result = book
                when {
                    result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    result.isFailure -> Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(18.dp))
                        Text("Книга не открылась", style = MaterialTheme.typography.headlineSmall)
                        Text(result.exceptionOrNull()?.message.orEmpty(), Modifier.padding(vertical = 16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { reload++ }) { Text("Повторить") }
                            Button(onClick = onDismiss) { Text("Закрыть") }
                        }
                    }
                    else -> result.getOrThrow().let { loaded ->
                        PdfReaderContent(loaded.session, loaded.info, loaded.state, store, entry.name, fullscreen, { fullscreen = !fullscreen }, onDismiss)
                    }
                }
            }
        }
    }
}

@OptIn(FlowPreview::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PdfReaderContent(
    session: PdfBookSession,
    info: PdfInfo,
    initial: PdfReadingState,
    store: PdfReadingStore,
    fileName: String,
    fullscreen: Boolean = false,
    onFullscreen: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    var state by remember(session) { mutableStateOf(initial.bounded(info.pages)) }
    var sidebar by remember { mutableStateOf(false) }
    var sidebarTab by remember { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PdfSearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf(state.page.toString()) }
    val scope = rememberCoroutineScope()
    val bookFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.page - 1, initialFirstVisibleItemScrollOffset = state.offset)
    val horizontal = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val palette = pdfPalette(state.paper)
    val latestState by rememberUpdatedState(state)
    val scrollMode = state.mode == PdfReadingMode.SCROLL
    var effectiveZoom by remember { mutableStateOf(state.zoom) }

    fun jump(page: Int) {
        val bounded = page.coerceIn(1, info.pages)
        state = state.copy(page = bounded, offset = 0)
        scope.launch { if (state.mode == PdfReadingMode.SCROLL) list.scrollToItem(bounded - 1); bookFocus.requestFocus() }
    }
    fun zoom(direction: Int) {
        if (state.mode == PdfReadingMode.TEXT) {
            state = state.copy(textSize = (state.textSize + direction * 2).coerceIn(14, 36))
            return
        }
        val base = if (state.fit == PdfFit.CUSTOM) state.zoom else effectiveZoom
        state = state.copy(fit = PdfFit.CUSTOM, zoom = (base + direction * .15f).coerceIn(.25f, 4f), offset = 0)
    }
    fun setMode(mode: PdfReadingMode) {
        val page = state.page
        state = state.copy(mode = mode, offset = 0)
        scope.launch { if (mode == PdfReadingMode.SCROLL) list.scrollToItem(page - 1); bookFocus.requestFocus() }
    }
    fun searchNext(backwards: Boolean) { pdfMatchIndex(hits, state.page, backwards).takeIf { it >= 0 }?.let { jump(hits[it].page) } }
    fun scrollPage(direction: Int) {
        if (scrollMode) scope.launch { list.animateScrollBy(direction * (list.layoutInfo.viewportSize.height * .88f).coerceAtLeast(200f)) }
        else jump(state.page + direction)
    }

    LaunchedEffect(session) { bookFocus.requestFocus() }
    LaunchedEffect(state.page) { pageInput = state.page.toString() }
    LaunchedEffect(list, scrollMode) {
        if (scrollMode) snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            state = state.copy(page = (index + 1).coerceIn(1, info.pages), offset = offset)
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { state }.debounce(500).collectLatest { value ->
            val error = withContext(Dispatchers.IO) { runCatching { store.save(session.path, value) }.exceptionOrNull()?.message }
            saveError = error
        }
    }
    DisposableEffect(session) {
        onDispose { runCatching { store.save(session.path, latestState) } }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) { withFrameNanos { }; searchFocus.requestFocus() } }
    LaunchedEffect(query, session) {
        hits = emptyList(); searchError = null; searching = false
        if (query.isBlank()) return@LaunchedEffect
        delay(350)
        searching = true
        try {
            val job = currentCoroutineContext().job
            hits = withContext(Dispatchers.IO) { session.search(query, info.pages) { !job.isActive } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { searchError = e.message ?: "Ошибка поиска" }
        finally { searching = false }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 1f)).testTag("pdf-reader").onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false
        else when {
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.F -> { searchOpen = true; scope.launch { withFrameNanos { }; searchFocus.requestFocus() }; true }
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.G -> { if (fullscreen) onFullscreen(); scope.launch { withFrameNanos { }; runCatching { pageFocus.requestFocus() } }; true }
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.D -> { state = state.toggleBookmark(); true }
            (e.isCtrlPressed || e.isMetaPressed) && (e.key == Key.Equals || e.key == Key.NumPadAdd) -> { zoom(1); true }
            (e.isCtrlPressed || e.isMetaPressed) && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> { zoom(-1); true }
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.Zero -> { state = state.copy(fit = PdfFit.WIDTH); true }
            e.key == Key.F3 -> { searchNext(e.isShiftPressed); true }
            e.key == Key.Escape && searchOpen -> { searchOpen = false; query = ""; bookFocus.requestFocus(); true }
            inputFocused || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed -> false
            e.key == Key.PageDown || e.key == Key.Spacebar && !e.isShiftPressed -> { scrollPage(1); true }
            e.key == Key.PageUp || e.key == Key.Spacebar && e.isShiftPressed -> { scrollPage(-1); true }
            e.key == Key.DirectionRight -> { jump(state.page + 1); true }
            e.key == Key.DirectionLeft -> { jump(state.page - 1); true }
            e.key == Key.MoveHome -> { jump(1); true }
            e.key == Key.MoveEnd -> { jump(info.pages); true }
            e.key == Key.DirectionDown || e.key == Key.DirectionUp -> {
                val direction = if (e.key == Key.DirectionDown) 1 else -1
                if (scrollMode) scope.launch { list.animateScrollBy(direction * 64f) } else jump(state.page + direction)
                true
            }
            else -> false
        }
    }) {
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(info.title ?: fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(info.author, "${info.pages} стр.", "Место чтения сохраняется").joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PdfTool("Поиск (Ctrl+F)", Icons.Default.Search, { searchOpen = !searchOpen })
                PdfTool("Горячие клавиши", Icons.Default.Keyboard, { help = true })
                PdfTool("Полный экран (F11)", Icons.Default.Fullscreen, onFullscreen)
                PdfTool("Закрыть книгу", Icons.Default.Close, onDismiss)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PdfTool("Страницы и закладки", Icons.Default.ViewSidebar, { sidebar = !sidebar }, selected = sidebar)
                PdfTool("Предыдущая страница", Icons.Default.ChevronLeft, { jump(state.page - 1) }, enabled = state.page > 1)
                OutlinedTextField(value = pageInput, onValueChange = { pageInput = it.filter(Char::isDigit).take(7) }, singleLine = true,
                    modifier = Modifier.width(82.dp).testTag("pdf-page-input").focusRequester(pageFocus).onFocusChanged { inputFocused = it.isFocused }.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                            pageInput.toIntOrNull()?.let(::jump); pageInput = state.page.toString(); bookFocus.requestFocus(); true
                        } else false
                    }, textStyle = MaterialTheme.typography.bodyMedium, label = { Text("Стр.") })
                Text("/ ${info.pages}", style = MaterialTheme.typography.labelMedium)
                PdfTool("Следующая страница", Icons.Default.ChevronRight, { jump(state.page + 1) }, enabled = state.page < info.pages)
                PdfTool(if (state.page in state.bookmarks) "Убрать закладку (Ctrl+D)" else "Добавить закладку (Ctrl+D)", if (state.page in state.bookmarks) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, { state = state.toggleBookmark() }, selected = state.page in state.bookmarks)
                Spacer(Modifier.width(12.dp))
                PdfTool("Уменьшить (Ctrl+-)", Icons.Default.ZoomOut, { if (state.mode == PdfReadingMode.TEXT) state = state.copy(textSize = (state.textSize - 2).coerceAtLeast(14)) else zoom(-1) })
                Text(if (state.mode == PdfReadingMode.TEXT) "${state.textSize} pt" else when (state.fit) { PdfFit.WIDTH -> "По ширине"; PdfFit.PAGE -> "Целиком"; PdfFit.CUSTOM -> "${(state.zoom * 100).roundToInt()}%" }, style = MaterialTheme.typography.labelMedium)
                PdfTool("Увеличить (Ctrl++)", Icons.Default.ZoomIn, { if (state.mode == PdfReadingMode.TEXT) state = state.copy(textSize = (state.textSize + 2).coerceAtMost(36)) else zoom(1) })
                PdfTool("По ширине (Ctrl+0)", Icons.Default.FitScreen, { state = state.copy(fit = PdfFit.WIDTH, offset = 0) }, selected = state.fit == PdfFit.WIDTH)
                PdfTool("Страница целиком", Icons.Default.AspectRatio, { state = state.copy(fit = PdfFit.PAGE, offset = 0) }, selected = state.fit == PdfFit.PAGE)
                PdfTool("Повернуть на 90°", Icons.Default.RotateRight, { state = state.copy(rotation = (state.rotation + 90) % 360, offset = 0) })
                Spacer(Modifier.width(8.dp))
                PdfReaderMenus(state, { state = it }, ::setMode)
            }
        }
        if (searchOpen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it.take(300) }, singleLine = true,
                    placeholder = { Text("Найти в книге…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.weight(1f).testTag("pdf-search").focusRequester(searchFocus).onFocusChanged { inputFocused = it.isFocused }.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { searchNext(e.isShiftPressed); true } else false
                    })
                if (searching) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                else Text(if (query.isBlank()) "" else if (hits.isEmpty()) "Не найдено" else "${hits.size} стр.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                PdfTool("Предыдущее совпадение (Shift+F3)", Icons.Default.KeyboardArrowUp, { searchNext(true) }, enabled = hits.isNotEmpty())
                PdfTool("Следующее совпадение (F3)", Icons.Default.KeyboardArrowDown, { searchNext(false) }, enabled = hits.isNotEmpty())
                PdfTool("Результаты поиска", Icons.Default.List, { sidebar = true })
                PdfTool("Закрыть поиск", Icons.Default.Close, { searchOpen = false; query = ""; bookFocus.requestFocus() })
            }
            searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sidebar && !fullscreen) {
                PdfReaderSidebar(session, info, state, sidebarTab, { sidebarTab = it }, hits, query, ::jump, { page -> state = state.copy(bookmarks = state.bookmarks - page) })
                VerticalDivider()
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().background(palette.desk).focusRequester(bookFocus).focusable().onPointerEvent(PointerEventType.Scroll) { e ->
                if (e.keyboardModifiers.isCtrlPressed || e.keyboardModifiers.isMetaPressed) {
                    val delta = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (delta != 0f) { zoom(if (delta < 0) 1 else -1); e.changes.forEach { it.consume() } }
                }
            }) {
                val viewportW = maxWidth.value
                val viewportH = maxHeight.value
                val size = pdfPageSize(info.pageWidth, info.pageHeight, viewportW, viewportH, state, LocalDensity.current.density)
                SideEffect {
                    val width = if (state.rotation % 180 == 0) info.pageWidth else info.pageHeight
                    effectiveZoom = (size.width / (width.toFloat() * 96f / 72f)).coerceIn(.25f, 4f)
                }
                when (state.mode) {
                    PdfReadingMode.TEXT -> key(state.page) { PdfReadingText(session, state, query, palette) { state = state.copy(offset = it) } }
                    PdfReadingMode.PAGE -> key(state.page, state.rotation, state.fit, state.zoom) {
                        val vertical = rememberScrollState(state.offset)
                        LaunchedEffect(vertical) { snapshotFlow { vertical.value }.collect { state = state.copy(offset = it) } }
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal), contentAlignment = Alignment.TopCenter) {
                                PdfBookPage(session, state.page, info, state, viewportW, viewportH, hits.any { it.page == state.page }, Modifier.padding(24.dp))
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                            if (horizontal.maxValue > 0) HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        }
                    }
                    PdfReadingMode.SCROLL -> Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                            LazyColumn(state = list, modifier = Modifier.width(maxOf(viewportW, size.width + 48).dp).fillMaxHeight().testTag("pdf-pages"), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                items(info.pages, key = { it }) { index ->
                                    PdfBookPage(session, index + 1, info, state, viewportW, viewportH, hits.any { it.page == index + 1 })
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp))
                        if (horizontal.maxValue > 0) HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                    }
                }
                if (fullscreen) PdfTool("Выйти из полного экрана (Esc)", Icons.Default.FullscreenExit, onFullscreen, Modifier.align(Alignment.TopEnd).padding(12.dp).background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Стр. ${state.page} / ${info.pages}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("pdf-progress"))
            LinearProgressIndicator(progress = { state.page.toFloat() / info.pages }, modifier = Modifier.weight(1f).height(3.dp))
            Text("${(state.page * 100f / info.pages).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            if (fullscreen) { PdfTool("Предыдущая страница", Icons.Default.ChevronLeft, { jump(state.page - 1) }); PdfTool("Следующая страница", Icons.Default.ChevronRight, { jump(state.page + 1) }) }
            else {
                PdfTool("Копировать текст страницы", Icons.Default.ContentCopy, {
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) { session.text(state.page) }
                            clipboard.setText(AnnotatedString(text))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { saveError = e.message }
                    }
                }, enabled = PdfTools.textAvailable)
            }
        }
        saveError?.let { Text("Не удалось сохранить или скопировать: $it", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Читать без лишних кликов") }, text = {
        SelectionContainer { Text("Space / PageDown — экран вперёд\nShift+Space / PageUp — экран назад\n← / → — предыдущая / следующая страница\nHome / End — начало / конец книги\nCtrl+G — перейти к странице\nCtrl+F — поиск по всей книге\nEnter / F3 — следующее совпадение\nShift+Enter / Shift+F3 — предыдущее\nCtrl+D — добавить / убрать закладку\nCtrl+колесо / Ctrl+± — масштаб\nCtrl+0 — по ширине\nF11 — полный экран, Esc — вернуться\n\nМесто чтения, тема, масштаб и закладки сохраняются отдельно для каждой книги. PDF остаётся без изменений.") }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("Понятно") } })
}
