package org.wexclient.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.wexclient.app.doc.*
import org.wexclient.app.util.frozen

private val pdfRasterSlots = Semaphore(2)
private data class PdfRaster(val bitmap: ImageBitmap? = null, val error: String? = null)

@Composable
internal fun PdfBookPage(session: PdfBookSession, page: Int, info: PdfInfo, state: PdfReadingState, viewportW: Float, viewportH: Float, highlighted: Boolean, modifier: Modifier = Modifier, thumbnail: Boolean = false) {
    val density = if (thumbnail) 1f else maxOf(1.5f, LocalDensity.current.density)
    val requested = pdfPageSize(info.pageWidth, info.pageHeight, viewportW, viewportH, state, density)
    // Quantized sizes avoid rerendering for every individual pixel while resizing a window.
    val edge = (requested.renderEdge / 64 * 64).coerceAtMost(4096)
    var retry by remember(session, page, edge) { mutableStateOf(0) }
    val raster by produceState(PdfRaster(), session, page, edge, retry) {
        value = PdfRaster()
        var bitmap: ImageBitmap? = null
        try {
            val job = currentCoroutineContext().job
            pdfRasterSlots.withPermit {
                // Assign before the dispatcher boundary so cancellation still closes the native bitmap.
                withContext(Dispatchers.IO) {
                    val bytes = session.render(page, edge) { !job.isActive }
                    bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap().frozen() }
                }
            }
            value = PdfRaster(bitmap)
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = PdfRaster(error = e.message ?: "Страница не отрисовалась") }
        finally { bitmap?.let { runCatching { it.asSkiaBitmap().close() } } }
    }
    val bitmap = raster.bitmap
    val size = if (bitmap != null) pdfPageSize(bitmap.width.toDouble(), bitmap.height.toDouble(), viewportW, viewportH,
        // The bitmap contains source proportions, but custom zoom still uses original PDF points.
        if (state.fit == PdfFit.CUSTOM) state.copy(zoom = state.zoom * info.pageWidth.toFloat() / bitmap.width) else state, density) else requested
    val palette = pdfPalette(state.paper)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(3.dp), color = palette.sheet, shadowElevation = if (thumbnail) 1.dp else 5.dp, border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
            Box(Modifier.size(size.width.dp, size.height.dp), contentAlignment = Alignment.Center) {
                when {
                    raster.error != null -> Column(Modifier.padding(12.dp).widthIn(max = 380.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(raster.error.orEmpty(), color = palette.ink, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { retry++ }) { Text("Повторить") }
                    }
                    bitmap == null -> CircularProgressIndicator(Modifier.size(if (thumbnail) 18.dp else 28.dp), strokeWidth = 2.dp)
                    else -> {
                        val sideways = state.rotation % 180 != 0
                        Image(bitmap, "Страница $page", colorFilter = palette.filter,
                            modifier = Modifier.requiredSize((if (sideways) size.height else size.width).dp, (if (sideways) size.width else size.height).dp).graphicsLayer { rotationZ = state.rotation.toFloat() })
                    }
                }
            }
        }
        Text(page.toString(), style = MaterialTheme.typography.labelSmall, color = if (state.paper == PdfPaper.DARK) palette.ink.copy(alpha = .6f) else palette.ink.copy(alpha = .7f), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun PdfReadingText(session: PdfBookSession, state: PdfReadingState, query: String, palette: PdfPalette, onOffset: (Int) -> Unit) {
    val result by produceState<Result<String>?>(null, session, state.page) {
        value = null
        try {
            val job = currentCoroutineContext().job
            value = Result.success(withContext(Dispatchers.IO) { session.text(state.page) { !job.isActive } })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = Result.failure(e) }
    }
    val scroll = rememberScrollState(state.offset)
    val latestOffset by rememberUpdatedState(onOffset)
    LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { latestOffset(it) } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val content = result
        when {
            content == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            content.isFailure -> Text(content.exceptionOrNull()?.message.orEmpty(), color = palette.ink, modifier = Modifier.padding(32.dp))
            content.getOrThrow().isBlank() -> Column(Modifier.align(Alignment.Center).padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("На этой странице нет текста", color = palette.ink, style = MaterialTheme.typography.titleMedium)
                Text("Если это скан, переключитесь на страницы. Для поиска в сканах нужен текстовый слой OCR.", color = palette.ink.copy(alpha = .7f), modifier = Modifier.padding(top = 12.dp).widthIn(max = 460.dp))
            }
            else -> Box(Modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
                SelectionContainer {
                    Text(text = pdfHighlightedText(content.getOrThrow(), query, palette), color = palette.ink, fontFamily = FontFamily.Serif, fontSize = state.textSize.sp, lineHeight = (state.textSize * 1.65f).sp,
                        modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 36.dp, vertical = 36.dp).background(palette.sheet, RoundedCornerShape(8.dp)).padding(32.dp))
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun pdfHighlightedText(text: String, query: String, palette: PdfPalette): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var index = text.indexOf(query, ignoreCase = true)
        var count = 0
        while (index >= 0 && count++ < 10_000) {
            addStyle(androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color(0x66E9AE46), color = palette.ink), index, index + query.length)
            index = text.indexOf(query, index + query.length, ignoreCase = true)
        }
    }
}
