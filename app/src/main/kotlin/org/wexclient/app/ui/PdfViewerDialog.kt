package org.wexclient.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.wexclient.app.util.frozen
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.doc.PdfInfo
import org.wexclient.app.doc.PdfTools
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.VfsEntry
import java.io.File
import java.nio.file.Path

private const val MIN_DPI = 48
private const val MAX_DPI = 288

/**
 * Просмотр PDF: страницы идут подряд, как в браузере, и подгружаются по мере прокрутки.
 * Отрисовкой занимается poppler — ставить его в зависимости не нужно, он есть в системе.
 */
@Composable
fun PdfViewerDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    val path = entry.path
    var dpi by remember { mutableStateOf(110) }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(1) }
    var fullscreen by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()

    val cacheDir = remember(path) { cacheFor(path) }
    DisposableEffect(cacheDir) {
        onDispose { runCatching { cacheDir.deleteRecursively() } }
    }

    val info by produceState<PdfInfo?>(null, path) {
        value = withContext(Dispatchers.IO) { PdfTools.info(path) }
    }

    // Номер страницы в шапке — по тому, что сейчас вверху экрана
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { currentPage = it + 1 }
    }

    val doc = info
    val subtitle = when {
        !PdfTools.available -> "нужен пакет ${PdfTools.PACKAGE}"
        doc == null -> "чтение…"
        else -> listOfNotNull(
            "${doc.pages} " + org.wexclient.app.util.Plural.ru(doc.pages.toLong(), "страница", "страницы", "страниц"),
            doc.pageSizeMm,
            doc.title?.let { "«$it»" },
            doc.author,
            doc.version?.let { "PDF $it" },
            "зашифрован".takeIf { doc.encrypted }
        ).joinToString(" · ")
    }

    GrailDialogShell(
        title = entry.name,
        badge = "PDF",
        heading = entry.name,
        subtitle = subtitle,
        closeOnEsc = !fullscreen,
        dialogWidth = if (fullscreen) screenSize().first else 1150.dp,
        dialogHeight = if (fullscreen) screenSize().second else 900.dp,
        hideChrome = fullscreen,
        onKey = { event ->
            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else when (event.key) {
                androidx.compose.ui.input.key.Key.F11 -> { fullscreen = !fullscreen; true }
                androidx.compose.ui.input.key.Key.Escape -> if (fullscreen) { fullscreen = false; true } else false
                else -> false
            }
        },
        onDismiss = onDismiss,
        actions = {
            if (doc != null) {
                GrailTabs(listOf("Страницы", "Текст"), tab) { tab = it }
                if (tab == 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { dpi = (dpi - 24).coerceAtLeast(MIN_DPI) },
                        modifier = Modifier.size(28.dp).noFocus()
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Мельче", tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                    }
                    Text("${dpi * 100 / 96}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    IconButton(
                        onClick = { dpi = (dpi + 24).coerceAtMost(MAX_DPI) },
                        modifier = Modifier.size(28.dp).noFocus()
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Крупнее", tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { night = !night },
                        modifier = Modifier.size(28.dp).noFocus()
                    ) {
                        Icon(
                            if (night) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (night) "Обычные цвета" else "Тёмное чтение",
                            tint = if (night) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { fullscreen = !fullscreen },
                        modifier = Modifier.size(28.dp).noFocus()
                    ) {
                        Icon(
                            if (fullscreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (fullscreen) "Выйти из полного экрана (F11)" else "Во весь экран (F11)",
                            tint = NoctaliaPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    ) {
        when {
            !PdfTools.available -> GrailEmptyState(
                "Для показа PDF нужен пакет ${PdfTools.PACKAGE} — он даёт pdftoppm и pdftotext."
            )
            doc == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WexSpinner(color = NoctaliaPrimary)
            }
            doc.pages == 0 -> GrailEmptyState("В документе нет страниц — возможно, файл повреждён")
            tab == 1 -> PdfTextTab(path)
            else -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Страница $currentPage из ${doc.pages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(
                        onClick = { scope.launch { listState.scrollToItem((currentPage - 2).coerceAtLeast(0)) } },
                        modifier = Modifier.size(24.dp).noFocus()
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Предыдущая", tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = { scope.launch { listState.scrollToItem(currentPage.coerceAtMost(doc.pages - 1)) } },
                        modifier = Modifier.size(24.dp).noFocus()
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Следующая", tint = NoctaliaPrimary, modifier = Modifier.size(15.dp))
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    if (matches.isNotEmpty()) {
                        Text(
                            text = "нашлось на ${matches.size} " +
                                org.wexclient.app.util.Plural.ru(matches.size.toLong(), "странице", "страницах", "страницах"),
                            style = MaterialTheme.typography.labelSmall,
                            color = NoctaliaPrimary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    if (searching) {
                        WexSpinner(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = NoctaliaPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    GrailSearchField(
                        value = query,
                        onChange = { text ->
                            query = text
                            matches = emptyList()
                        },
                        placeholder = "Поиск по тексту",
                        modifier = Modifier.width(240.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = NoctaliaPrimary.copy(alpha = 0.16f),
                        modifier = Modifier.clickable {
                            if (query.isNotBlank() && !searching) {
                                searching = true
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) {
                                        PdfTools.findPages(path, query, doc.pages)
                                    }
                                    matches = found
                                    searching = false
                                    found.firstOrNull()?.let { listState.scrollToItem(it - 1) }
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Искать",
                            tint = NoctaliaPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(15.dp)
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(vertical = 10.dp)
                ) {
                    items((1..doc.pages).toList()) { page ->
                        PdfPage(
                            path = path,
                            page = page,
                            dpi = dpi,
                            cacheDir = cacheDir,
                            highlighted = page in matches,
                            night = night,
                            // Размер листа считаем из самого документа: A4, письмо и слайды — разные
                            widthDp = (doc.pageWidth / 72.0 * dpi).toInt().dp,
                            heightDp = (doc.pageHeight / 72.0 * dpi).toInt().dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    path: Path,
    page: Int,
    dpi: Int,
    cacheDir: File,
    highlighted: Boolean,
    night: Boolean,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp
) {
    val image by produceState<ImageBitmap?>(null, path, page, dpi) {
        value = withContext(Dispatchers.IO) {
            val file = PdfTools.renderPage(path, page, dpi, cacheDir) ?: return@withContext null
            // Промежуточную картинку Skia закрываем сразу: пиксели уже скопированы в ImageBitmap
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).use { it.toComposeImageBitmap().frozen() }
            }.getOrNull()
        }
    }
    // Страница ушла с экрана или перерисована в другом разрешении — её пиксели закрываем сами: сборщик мусора
    // нативную память не видит, и при прокрутке длинного PDF страницы по ~8 МБ копились сотнями.
    // Уже записанные кадры держат свою ссылку на пиксели, так что закрытие их не ломает
    androidx.compose.runtime.DisposableEffect(image) {
        val shown = image
        onDispose { shown?.let { runCatching { it.asSkiaBitmap().close() } } }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 14.dp)) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (night) Color(0xFF1A1A1A) else Color.White,
            shadowElevation = 4.dp,
            border = if (highlighted) androidx.compose.foundation.BorderStroke(2.dp, NoctaliaPrimary) else null
        ) {
            val bitmap = image
            if (bitmap == null) {
                Box(
                    modifier = Modifier.width(widthDp).height(heightDp).background(Color(0xFFF3F3F3)),
                    contentAlignment = Alignment.Center
                ) {
                    WexSpinner(modifier = Modifier.size(22.dp), color = NoctaliaPrimary)
                }
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Страница $page",
                    contentScale = ContentScale.FillWidth,
                    // Тёмное чтение: страница выворачивается наизнанку прямо при отрисовке
                    colorFilter = if (night) {
                        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix(
                                floatArrayOf(
                                    -1f, 0f, 0f, 0f, 255f,
                                    0f, -1f, 0f, 0f, 255f,
                                    0f, 0f, -1f, 0f, 255f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                        )
                    } else null,
                    modifier = Modifier.width(widthDp)
                )
            }
        }
        Text(
            text = page.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun PdfTextTab(path: Path) {
    val text by produceState<String?>(null, path) {
        value = withContext(Dispatchers.IO) { PdfTools.text(path) }
    }
    val content = text
    when {
        content == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            WexSpinner(color = NoctaliaPrimary)
        }
        content.isBlank() -> GrailEmptyState("Текстового слоя нет — похоже, страницы отсканированы картинками")
        else -> {
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize().verticalScroll(vertical)) {
                Text(
                    text = content,
                    fontSize = 12.sp,
                    fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(horizontal).padding(16.dp)
                )
            }
        }
    }
}

/** Размер экрана в точках: полноэкранное чтение растягивает окно на весь монитор. */
private fun screenSize(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val size = runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    return ((size?.width ?: 1600).dp) to ((size?.height ?: 900).dp)
}

/** Своя папка под страницы: имя файла и время правки отличают версии одного и того же PDF. */
private fun cacheFor(path: Path): File {
    val stamp = runCatching { java.nio.file.Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
    val key = Integer.toHexString((path.toString() + stamp).hashCode())
    return File(System.getProperty("user.home"), ".cache/wexdesktop/pdf/$key")
}
