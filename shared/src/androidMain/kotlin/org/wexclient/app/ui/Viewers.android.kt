package org.wexclient.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.util.AppDirs
import org.wexclient.app.util.ImageScale
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Шапка окна просмотра на Android: название, подпись, закрыть. */
@Composable
private fun ViewerBar(title: String, subtitle: String?, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = NoctaliaPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Закрыть") }
        }
    }
}

/** Файл как настоящий файл на диске: из архива или с удалённой машины — копией в кэше (плееру и PdfRenderer нужен путь). */
private fun localCopy(entry: VfsEntry, folder: String): File {
    if (entry.path.fileSystem === FileSystems.getDefault()) return entry.path.toFile()
    val dir = AppDirs.cache.resolve(folder).also { Files.createDirectories(it) }
    val target = dir.resolve(entry.name)
    Files.newInputStream(entry.path).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    return target.toFile()
}

@Composable
actual fun ImageViewerDialog(entry: VfsEntry, siblingImages: List<VfsEntry>, vfsManager: VfsManager, onDismiss: () -> Unit) {
    val images = siblingImages.ifEmpty { listOf(entry) }
    val start = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { images.size }
    val current = images.getOrNull(pager.currentPage) ?: entry
    WexWindow(onCloseRequest = onDismiss, title = current.name, size = DpSize(1080.dp, 750.dp)) {
        NoctaliaTheme(backdrop = false) {
            Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                ViewerBar(current.name, "${pager.currentPage + 1} из ${images.size}", onDismiss)
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                    ZoomableImage(images[page], vfsManager)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(entry: VfsEntry, vfsManager: VfsManager) {
    val image by produceState<Result<ImageBitmap>?>(null, entry.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageScale.decodeScaled(vfsManager.readBytes(entry.path), maxPx = 4096, maxSourcePx = 16384, maxSourcePixels = 120_000_000)
                    ?: error("Картинка повреждена или слишком большая")
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val r = image) {
            null -> WexSpinner(color = NoctaliaPrimary)
            else -> r.fold(
                onSuccess = { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = entry.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                // Двойное касание — увеличить вдвое или вернуть как было
                                detectTapGestures(onDoubleTap = {
                                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                                })
                            }
                            .transformable(transform, canPan = { scale > 1f })
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                },
                onFailure = { Text(it.message ?: "Не открылась", color = MaterialTheme.colorScheme.error) }
            )
        }
    }
}

@Composable
actual fun VideoPlayerDialog(entry: VfsEntry, siblingVideos: List<VfsEntry>, onDismiss: () -> Unit) {
    val file by produceState<Result<File>?>(null, entry.path) {
        value = withContext(Dispatchers.IO) { runCatching { localCopy(entry, "video") } }
    }
    WexWindow(onCloseRequest = onDismiss, title = entry.name, size = DpSize(1020.dp, 720.dp)) {
        NoctaliaTheme(backdrop = false) {
            Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                ViewerBar(entry.name, null, onDismiss)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val r = file) {
                        null -> WexSpinner(color = NoctaliaPrimary)
                        else -> r.fold(
                            onSuccess = { f ->
                                AndroidView(
                                    factory = { context ->
                                        VideoView(context).apply {
                                            val controller = MediaController(context)
                                            controller.setAnchorView(this)
                                            setMediaController(controller)
                                            setVideoPath(f.absolutePath)
                                            setOnPreparedListener { start() }
                                        }
                                    },
                                    onRelease = { it.stopPlayback() },
                                    modifier = Modifier.fillMaxSize()
                                )
                            },
                            onFailure = { Text(it.message ?: "Не открылось", color = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}

/** Открытый PDF: страницы рисует PdfRenderer — по одной за раз (так он устроен). */
private class PdfDocument(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount

    fun aspect(index: Int): Float = synchronized(renderer) {
        renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    suspend fun render(index: Int, widthPx: Int): ImageBitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val w = widthPx.coerceIn(200, 2400)
                    val h = (w.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.asImageBitmap()
                }
            }
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }
}

@Composable
actual fun PdfViewerDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    val doc by produceState<Result<PdfDocument>?>(null, entry.path) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDocument(localCopy(entry, "pdf")) } }
    }
    DisposableEffect(doc) {
        val opened = doc?.getOrNull()
        onDispose { opened?.close() }
    }
    WexWindow(onCloseRequest = onDismiss, title = entry.name, size = DpSize(1000.dp, 900.dp)) {
        NoctaliaTheme(backdrop = false) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val opened = doc?.getOrNull()
                ViewerBar(entry.name, opened?.let { "Страниц: ${it.pageCount}" }, onDismiss)
                when (val r = doc) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { WexSpinner(color = NoctaliaPrimary) }
                    else -> r.fold(
                        onSuccess = { pdf ->
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                                LazyColumn(
                                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(pdf.pageCount) { index -> PdfPage(pdf, index, widthPx) }
                                }
                            }
                        },
                        onFailure = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("PDF не открылся: ${it.message ?: it.javaClass.simpleName}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDocument, index: Int, widthPx: Int) {
    val aspect = remember(index) { runCatching { pdf.aspect(index) }.getOrDefault(0.707f) }
    val page by produceState<ImageBitmap?>(null, index, widthPx) { value = runCatching { pdf.render(index, widthPx) }.getOrNull() }
    Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Color.White), contentAlignment = Alignment.Center) {
        val bmp = page
        if (bmp == null) WexSpinner(color = NoctaliaPrimary)
        else Image(bmp, contentDescription = "Страница ${index + 1}", modifier = Modifier.fillMaxSize())
    }
}
