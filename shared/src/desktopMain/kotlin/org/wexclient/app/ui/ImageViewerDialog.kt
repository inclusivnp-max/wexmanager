package org.wexclient.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.wexclient.app.util.frozen
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.roundToInt

enum class ImageBackgroundMode(val label: String) {
    CHECKERBOARD("Шахматка"),
    DARK("Тёмный"),
    BLACK("Глубокий чёрный"),
    WHITE("Светлый")
}

@Composable
actual fun ImageViewerDialog(
    entry: VfsEntry,
    siblingImages: List<VfsEntry>,
    vfsManager: VfsManager,
    onDismiss: () -> Unit
) {
    val imagesList = remember(siblingImages, entry) {
        if (siblingImages.isNotEmpty()) siblingImages else listOf(entry)
    }

    var currentIndex by remember {
        mutableStateOf(imagesList.indexOfFirst { it.path == entry.path }.coerceAtLeast(0))
    }

    val currentEntry = imagesList.getOrElse(currentIndex) { entry }

    var loadedBitmap by remember(currentEntry.path) { mutableStateOf<ImageBitmap?>(null) }
    // Размер оригинала (картинку больше предела показываем уменьшенной)
    var imageSize by remember(currentEntry.path) { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    var reducedForView by remember(currentEntry.path) { mutableStateOf(false) }
    var isLoading by remember(currentEntry.path) { mutableStateOf(true) }
    var loadError by remember(currentEntry.path) { mutableStateOf<String?>(null) }

    var zoomScale by remember(currentEntry.path) { mutableStateOf(1.0f) }
    var panOffset by remember(currentEntry.path) { mutableStateOf(Offset.Zero) }
    var rotationAngle by remember(currentEntry.path) { mutableStateOf(0f) }
    var isFlippedH by remember(currentEntry.path) { mutableStateOf(false) }
    var isFlippedV by remember(currentEntry.path) { mutableStateOf(false) }

    var bgMode by remember { mutableStateOf(ImageBackgroundMode.CHECKERBOARD) }
    var filterQuality by remember { mutableStateOf(FilterQuality.Medium) }
    var showInfoPanel by remember { mutableStateOf(false) }
    var isSlideshow by remember { mutableStateOf(false) }
    var showBgMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentEntry.path) {
        isLoading = true
        loadError = null
        try {
            val size = withContext(Dispatchers.IO) { runCatching { vfsManager.size(currentEntry.path) }.getOrDefault(0L) }
            if (size > MAX_IMAGE_FILE_BYTES) {
                loadError = "Файл слишком большой для просмотра (${size / (1024 * 1024)} МБ)"
                isLoading = false
                return@LaunchedEffect
            }
            val bytes = withContext(Dispatchers.IO) {
                vfsManager.readBytes(currentEntry.path)
            }
            if (bytes.isEmpty()) {
                loadError = "Файл пуст"
                isLoading = false
                return@LaunchedEffect
            }

            val view = withContext(Dispatchers.IO) {
                // Ушли к другой картинке, пока эта декодировалась, — её пиксели (до 256 МБ вне кучи) сразу отдаём
                decodeForView(bytes)?.also { v -> if (!isActive) v.bitmap.asSkiaBitmap().close() }
            }
            if (view == null) {
                loadError = "Не удалось загрузить изображение: формат не распознан"
            } else {
                loadedBitmap = view.bitmap
                imageSize = androidx.compose.ui.unit.IntSize(view.width, view.height)
                reducedForView = view.reduced
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            loadError = "Изображение слишком большое для просмотра"
        } catch (e: Exception) {
            loadError = "Не удалось загрузить изображение: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            isLoading = false
        }
    }

    // Показанную картинку закрываем, как только её сменили или окно закрыли: до 64 Мп — это до 256 МБ
    // нативной памяти, а сборщик мусора о ней не знает. Уже записанный кадр не пострадает — SkImage
    // держит пиксели своей ссылкой
    DisposableEffect(loadedBitmap) {
        val shown = loadedBitmap
        onDispose { shown?.asSkiaBitmap()?.close() }
    }

    LaunchedEffect(isSlideshow, currentIndex) {
        if (isSlideshow && imagesList.size > 1) {
            delay(4000)
            currentIndex = (currentIndex + 1) % imagesList.size
        }
    }

    WexWindow(
        onCloseRequest = onDismiss,
        size = DpSize(1080.dp, 750.dp),
        title = "Просмотр изображений — ${currentEntry.name}",
        // ←/→ — соседние картинки, +/−/0 — масштаб, Esc — закрыть
        onKeyEvent = latestKeyHandler { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@latestKeyHandler false
            val n = imagesList.size
            when (e.key) {
                androidx.compose.ui.input.key.Key.Escape -> { onDismiss(); true }
                androidx.compose.ui.input.key.Key.DirectionRight, androidx.compose.ui.input.key.Key.PageDown ->
                    { if (n > 1) currentIndex = (currentIndex + 1) % n; true }
                androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.PageUp ->
                    { if (n > 1) currentIndex = (currentIndex - 1 + n) % n; true }
                androidx.compose.ui.input.key.Key.MoveHome -> { currentIndex = 0; true }
                androidx.compose.ui.input.key.Key.MoveEnd -> { currentIndex = (n - 1).coerceAtLeast(0); true }
                androidx.compose.ui.input.key.Key.Plus, androidx.compose.ui.input.key.Key.NumPadAdd, androidx.compose.ui.input.key.Key.Equals ->
                    { zoomScale = (zoomScale * 1.15f).coerceIn(0.1f, 15f); true }
                androidx.compose.ui.input.key.Key.Minus, androidx.compose.ui.input.key.Key.NumPadSubtract ->
                    { zoomScale = (zoomScale * 0.85f).coerceIn(0.1f, 15f); true }
                androidx.compose.ui.input.key.Key.Zero, androidx.compose.ui.input.key.Key.NumPad0 -> {
                    zoomScale = 1.0f
                    panOffset = Offset.Zero
                    true
                }
                else -> false
            }
        }
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = currentEntry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val dimenStr = imageSize?.let {
                                        "${it.width} × ${it.height} px" + (if (reducedForView) " (показано уменьшенным)" else "") + " • "
                                    } ?: ""
                                    Text(
                                        text = "$dimenStr${currentEntry.formattedSize}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (imagesList.size > 1) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        Text(
                                            text = "${currentIndex + 1} / ${imagesList.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { showInfoPanel = !showInfoPanel },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = if (showInfoPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = "Свойства")
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(0.dp))
                        ) {
                            when (bgMode) {
                                ImageBackgroundMode.CHECKERBOARD -> {
                                    CheckerboardCanvas(modifier = Modifier.fillMaxSize())
                                }
                                ImageBackgroundMode.DARK -> {
                                    // Тёмный фон — из текущей темы, а не фиксированный тёплый
                                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest))
                                }
                                ImageBackgroundMode.BLACK -> {
                                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                                }
                                ImageBackgroundMode.WHITE -> {
                                    Box(modifier = Modifier.fillMaxSize().background(Color.White))
                                }
                            }

                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(currentEntry.path) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                if (zoomScale > 1.2f) {
                                                    zoomScale = 1.0f
                                                    panOffset = Offset.Zero
                                                } else {
                                                    zoomScale = 2.0f
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(currentEntry.path) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            panOffset += dragAmount
                                        }
                                    }
                                    .pointerInput(currentEntry.path) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Scroll) {
                                                    val delta = event.changes.first().scrollDelta.y
                                                    val factor = if (delta < 0) 1.15f else 0.85f
                                                    zoomScale = (zoomScale * factor).coerceIn(0.1f, 15.0f)
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    isLoading -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            WexSpinner(color = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("Загрузка...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                    loadError != null -> {
                                        Text(
                                            text = loadError!!,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    loadedBitmap != null -> {
                                        Image(
                                            bitmap = loadedBitmap!!,
                                            contentDescription = currentEntry.name,
                                            filterQuality = filterQuality,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize(0.92f)
                                                .graphicsLayer {
                                                    translationX = panOffset.x
                                                    translationY = panOffset.y
                                                    scaleX = zoomScale * (if (isFlippedH) -1f else 1f)
                                                    scaleY = zoomScale * (if (isFlippedV) -1f else 1f)
                                                    rotationZ = rotationAngle
                                                }
                                        )
                                    }
                                }
                            }

                            if (imagesList.size > 1) {
                                FilledIconButton(
                                    onClick = {
                                        currentIndex = (currentIndex - 1 + imagesList.size) % imagesList.size
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 12.dp)
                                        .size(44.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущее")
                                }

                                FilledIconButton(
                                    onClick = {
                                        currentIndex = (currentIndex + 1) % imagesList.size
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 12.dp)
                                        .size(44.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующее")
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showInfoPanel,
                            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })
                        ) {
                            Surface(
                                modifier = Modifier
                                    .width(280.dp)
                                    .fillMaxHeight(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                tonalElevation = 6.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Информация",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    InfoRow("Имя файла", currentEntry.name)
                                    imageSize?.let { b ->
                                        InfoRow("Разрешение", "${b.width} × ${b.height} px")
                                        val gcdVal = gcd(b.width, b.height)
                                        val aspect = "${b.width / gcdVal}:${b.height / gcdVal}"
                                        InfoRow("Соотношение", aspect)
                                    }
                                    InfoRow("Размер", currentEntry.formattedSize)
                                    InfoRow("Формат", currentEntry.extension.uppercase())
                                    InfoRow("Дата изменения", currentEntry.formattedDate)

                                    Spacer(modifier = Modifier.weight(1f))

                                    loadedBitmap?.let { shown ->
                                        Surface(
                                            onClick = {
                                                try {
                                                    val transferable = ImageTransferable(shown.toAwtImage())
                                                    Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                                                } catch (_: Throwable) {}
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Копировать в буфер", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }

                                    Surface(
                                        onClick = {
                                            try {
                                                if (currentEntry.path.fileSystem == FileSystems.getDefault()) {
                                                    Desktop.getDesktop().open(currentEntry.path.toFile())
                                                }
                                            } catch (_: Throwable) {}
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Открыть во внешней программе", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { zoomScale = (zoomScale * 0.85f).coerceIn(0.1f, 15f) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.ZoomOut, contentDescription = "Уменьшить", modifier = Modifier.size(18.dp))
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.clickable {
                                        zoomScale = 1.0f
                                        panOffset = Offset.Zero
                                    }
                                ) {
                                    Text(
                                        text = "${(zoomScale * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                IconButton(
                                    onClick = { zoomScale = (zoomScale * 1.15f).coerceIn(0.1f, 15f) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.ZoomIn, contentDescription = "Увеличить", modifier = Modifier.size(18.dp))
                                }

                                IconButton(
                                    onClick = {
                                        zoomScale = 1.0f
                                        panOffset = Offset.Zero
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.FitScreen, contentDescription = "Вписать", modifier = Modifier.size(18.dp))
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { rotationAngle = (rotationAngle - 90f) % 360f },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Повернуть влево", modifier = Modifier.size(18.dp))
                                }

                                IconButton(
                                    onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Повернуть вправо", modifier = Modifier.size(18.dp))
                                }

                                IconButton(
                                    onClick = { isFlippedH = !isFlippedH },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Flip, contentDescription = "Отразить по горизонтали", modifier = Modifier.size(18.dp))
                                }

                                if (imagesList.size > 1) {
                                    IconButton(
                                        onClick = { isSlideshow = !isSlideshow },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            contentColor = if (isSlideshow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isSlideshow) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Слайд-шоу",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    IconButton(
                                        onClick = { showBgMenu = true },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = "Фон", modifier = Modifier.size(18.dp))
                                    }

                                    WexDropdownMenu(
                                        expanded = showBgMenu,
                                        onDismissRequest = { showBgMenu = false }
                                    ) {
                                        ImageBackgroundMode.values().forEach { mode ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = mode.label,
                                                        fontWeight = if (bgMode == mode) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (bgMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                },
                                                onClick = {
                                                    bgMode = mode
                                                    showBgMenu = false
                                                }
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (filterQuality == FilterQuality.None) "Сглаживание: Выкл (Пиксели)" else "Сглаживание: Вкл (Bilinear)",
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                filterQuality = if (filterQuality == FilterQuality.None) FilterQuality.Medium else FilterQuality.None
                                                showBgMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckerboardCanvas(modifier: Modifier = Modifier) {
    // Клетки — оттенки поверхности текущей темы
    val colorA = MaterialTheme.colorScheme.surfaceContainerLow
    val colorB = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier = modifier) {
        val squareSize = 14.dp.toPx()
        val cols = (size.width / squareSize).toInt() + 1
        val rows = (size.height / squareSize).toInt() + 1

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = if ((r + c) % 2 == 0) colorA else colorB
                drawRect(
                    color = color,
                    topLeft = Offset(c * squareSize, r * squareSize),
                    size = Size(squareSize, squareSize)
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return if (x > 0) x else 1
}

private const val MAX_VIEW_PIXELS = 64_000_000L
private const val MAX_IMAGE_FILE_BYTES = 512L * 1024 * 1024

internal class ViewImage(val bitmap: ImageBitmap, val width: Int, val height: Int, val reduced: Boolean)

/**
 * Картинка для просмотра. Размер читается из заголовка: снимок больше 64 Мп декодируется сразу
 * уменьшенным (иначе PNG «60000×60000» из пары килобайт занимал гигабайты памяти).
 */
internal fun decodeForView(bytes: ByteArray): ViewImage? {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (readers.hasNext()) {
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                val pixels = w.toLong() * h
                var step = 1
                while (pixels / (step.toLong() * step) > MAX_VIEW_PIXELS) step++
                val param = reader.defaultReadParam
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                val image = reader.read(0, param)
                return ViewImage(image.toComposeImageBitmap().frozen(), w, h, step > 1)
            } catch (_: javax.imageio.IIOException) {
                // Формат узнан, но прочитать не вышло — попробует Skia
            } finally {
                reader.dispose()
            }
        }
    }
    // WebP, AVIF и прочее — через Skia; размер известен до декодирования
    val image = try {
        org.jetbrains.skia.Image.makeFromEncoded(bytes)
    } catch (_: Exception) {
        return null
    }
    image.use {
        val w = it.width
        val h = it.height
        if (w <= 0 || h <= 0) return null
        val pixels = w.toLong() * h
        // Skia уменьшает, только расшифровав картинку целиком: больше 16384² не берёмся
        if (pixels > 16384L * 16384L) return null
        if (pixels <= MAX_VIEW_PIXELS) return ViewImage(it.toComposeImageBitmap().frozen(), w, h, false)
        val maxPx = (maxOf(w, h) * kotlin.math.sqrt(MAX_VIEW_PIXELS.toDouble() / pixels)).toInt()
        val scaled = org.wexclient.app.util.ImageScale.decodeScaled(bytes, maxPx, maxSourcePx = Int.MAX_VALUE) ?: return null
        return ViewImage(scaled, w, h, true)
    }
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor == DataFlavor.imageFlavor) return image
        throw UnsupportedFlavorException(flavor)
    }
}
