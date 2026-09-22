package org.wexclient.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.isActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.max
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.wexclient.app.util.startHelper

data class VideoMetadata(
    val durationSec: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val fps: String = "",
    val bitrateKbps: Long = 0L,
    /** Соотношение сторон пикселя (sample aspect ratio), 1 — квадратные */
    val sar: Double = 1.0,
    /** Поворот из метаданных телефона: 0, ±90, 180 */
    val rotation: Int = 0
) {
    /** Размер кадра на экране: с учётом неквадратных пикселей и поворота (ffmpeg поворачивает сам). */
    val displayWidth: Int
        get() {
            val w = if (sar > 0.0 && sar != 1.0) (width * sar).roundToInt() else width
            return if (abs(rotation) % 180 == 90) height else w
        }
    val displayHeight: Int
        get() {
            val w = if (sar > 0.0 && sar != 1.0) (width * sar).roundToInt() else width
            return if (abs(rotation) % 180 == 90) w else height
        }
}

/**
 * Размер декодирования: кадр вписывается в область показа (в физических пикселях),
 * но не больше исходника и ~1080p по площади. Стороны чётные.
 */
internal fun videoDecodeSize(meta: VideoMetadata?, area: IntSize): IntSize {
    val srcW = meta?.displayWidth?.takeIf { it > 0 } ?: 1280
    val srcH = meta?.displayHeight?.takeIf { it > 0 } ?: 720
    val boxW = if (area.width > 0) area.width else 1280
    val boxH = if (area.height > 0) area.height else 720
    var scale = minOf(1.0, boxW.toDouble() / srcW, boxH.toDouble() / srcH)
    val maxPixels = 1920.0 * 1080.0
    if (srcW * srcH * scale * scale > maxPixels) scale = sqrt(maxPixels / (srcW.toDouble() * srcH))
    fun even(v: Double) = ((v.roundToInt() / 2) * 2).coerceAtLeast(2)
    return IntSize(even(srcW * scale), even(srcH * scale))
}

/** Фильтр ffmpeg под [size]: lanczos для резкого текста; размеры неизвестны — вписываем с полями. */
internal fun videoFilter(meta: VideoMetadata?, size: IntSize, playback: Boolean): String {
    val w = size.width
    val h = size.height
    val scale = if (meta != null && meta.width > 0 && meta.height > 0) {
        "scale=$w:$h:flags=lanczos,setsar=1"
    } else {
        "scale=$w:$h:force_original_aspect_ratio=decrease:flags=lanczos," +
            "pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=0x0D0D0D,setsar=1"
    }
    val fps = meta?.fps?.toDoubleOrNull() ?: 0.0
    return if (playback && fps > 60.0) "fps=60,$scale" else scale
}

/** Кадр BGRA от ffmpeg (-pix_fmt bgra -f rawvideo) → картинка для Compose. */
private fun bgraFrame(pixels: ByteArray, size: IntSize): ImageBitmap = bgraSkia(pixels, size).asComposeImageBitmap()

private fun bgraSkia(pixels: ByteArray, size: IntSize): SkiaBitmap {
    val info = ImageInfo(size.width, size.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    val bitmap = SkiaBitmap()
    bitmap.installPixels(info, pixels, size.width * 4)
    bitmap.setImmutable()
    return bitmap
}

/**
 * ffmpeg для одного кадра: без вопросов в stdin («Overwrite? [y/N]» вешал захват навсегда)
 * и со сроком; stdout целиком.
 */
private fun runFfmpeg(args: List<String>, timeoutSec: Long = 20): ByteArray? {
    val process = ProcessBuilder(listOf("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error") + args)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .startHelper()
    var out = ByteArray(0)
    val reader = Thread { out = runCatching { process.inputStream.readAllBytes() }.getOrDefault(ByteArray(0)) }
        .apply {
            isDaemon = true
            start()
        }
    if (!process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    reader.join(5000)
    return if (process.exitValue() == 0) out else null
}

@Composable
fun VideoPlayerDialog(
    entry: VfsEntry,
    siblingVideos: List<VfsEntry> = emptyList(),
    onDismiss: () -> Unit
) {
    val videosList = remember(siblingVideos, entry) {
        if (siblingVideos.isNotEmpty()) siblingVideos else listOf(entry)
    }

    var currentIndex by remember {
        mutableStateOf(videosList.indexOfFirst { it.path == entry.path }.coerceAtLeast(0))
    }

    val currentEntry = videosList.getOrElse(currentIndex) { entry }
    val scope = rememberCoroutineScope()

    var metadata by remember(currentEntry.path) { mutableStateOf<VideoMetadata?>(null) }
    var currentSeekSec by remember(currentEntry.path) { mutableStateOf(0.0) }
    var currentFrameBitmap by remember(currentEntry.path) { mutableStateOf<ImageBitmap?>(null) }
    var isExtractingFrame by remember(currentEntry.path) { mutableStateOf(false) }
    var showInfoPanel by remember { mutableStateOf(false) }
    var captureNotification by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember(currentEntry.path) { mutableStateOf(false) }
    var seekTrigger by remember { mutableStateOf(0) }

    /** Пуск/пауза; в самом конце «пуск» начинает сначала (иначе ffmpeg сразу упирается в конец файла). */
    fun togglePlay() {
        val total = metadata?.durationSec ?: 0.0
        if (!isPlaying && total > 0 && currentSeekSec >= total - 0.25) currentSeekSec = 0.0
        isPlaying = !isPlaying
    }

    // Область кадра в пикселях; при перетаскивании края окна — с задержкой, чтобы не перезапускать поток на каждый пиксель
    var frameArea by remember { mutableStateOf(IntSize.Zero) }
    var settledArea by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(frameArea) {
        if (settledArea != IntSize.Zero) delay(350)
        settledArea = frameArea
    }
    val decodeSize = remember(metadata, settledArea) { videoDecodeSize(metadata, settledArea) }

    // Probe metadata on video load
    LaunchedEffect(currentEntry.path) {
        isPlaying = false
        withContext(Dispatchers.IO) {
            val meta = probeVideoMetadata(currentEntry)
            metadata = meta
            currentSeekSec = if (meta.durationSec > 1.0) 1.0 else 0.0
        }
    }

    // Стоп-кадр в размер области: после загрузки и когда окно поменяло размер
    LaunchedEffect(metadata, decodeSize) {
        val meta = metadata ?: return@LaunchedEffect
        if (isPlaying || settledArea == IntSize.Zero) return@LaunchedEffect
        val frame = withContext(Dispatchers.IO) { extractFrameAt(currentEntry, currentSeekSec, meta, decodeSize) }
        if (frame != null && !isPlaying) currentFrameBitmap = frame
    }

    // Real-time high-performance in-dialog playback streaming engine
    LaunchedEffect(isPlaying, currentEntry.path, seekTrigger, decodeSize) {
        if (!isPlaying) return@LaunchedEffect
        val size = decodeSize
        val meta = metadata
        withContext(Dispatchers.IO) {
            var videoProcess: Process? = null
            var audioProcess: Process? = null
            try {
                val startSecStr = String.format(Locale.US, "%.2f", currentSeekSec)
                val filePath = currentEntry.path.toAbsolutePath().toString()

                val videoCmd = mutableListOf(
                    "ffmpeg",
                    "-nostdin",
                    "-hwaccel", "auto",
                    "-threads", "0",
                    "-re",
                    "-ss", startSecStr,
                    "-protocol_whitelist", "file",
                    "-i", filePath,
                    "-an",
                    "-vf", videoFilter(meta, size, playback = true),
                    // Без JPEG: кадры в размер области, без потерь (раньше 854 px и q=5 — мыло)
                    "-pix_fmt", "bgra",
                    "-f", "rawvideo",
                    "-"
                )
                videoProcess = ProcessBuilder(videoCmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .startHelper()

                // Decoupled background audio player (native Pulse/PipeWire audio clock, zero pipe blocking)
                if (metadata?.audioCodec?.isNotEmpty() == true) {
                    val audioCmd = listOf(
                        "ffplay",
                        "-nodisp",
                        "-autoexit",
                        "-ss", startSecStr,
                        // Файл может оказаться плейлистом со ссылками в сеть
                        "-protocol_whitelist", "file",
                        filePath
                    )
                    try {
                        audioProcess = ProcessBuilder(audioCmd)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .startHelper()
                    } catch (_: Throwable) {}
                }

                val frameLen = size.width * size.height * 4
                val retiredFrames = ArrayDeque<SkiaBitmap>()
                val input = java.io.DataInputStream(java.io.BufferedInputStream(videoProcess.inputStream, minOf(frameLen, 8 shl 20)))
                val pixels = ByteArray(frameLen)
                val frameMs = 1000.0 / (meta?.fps?.toDoubleOrNull()?.takeIf { it > 0.0 }?.coerceAtMost(60.0) ?: 30.0)
                val baseSeekSec = currentSeekSec
                var clockStart = 0L
                var lastUiUpdate = 0L
                var frameIndex = 0
                var ended = false

                while (isActive) {
                    try {
                        input.readFully(pixels)
                    } catch (_: java.io.EOFException) {
                        ended = true
                        break
                    }
                    try {
                        val arrived = System.currentTimeMillis()
                        // Часы — от первого кадра: запуск ffmpeg (декодер видеокарты — до полусекунды)
                        // иначе делал «опоздавшими» все кадры, и картинка стояла на первом
                        if (frameIndex == 0) clockStart = arrived
                        val late = arrived - clockStart - frameIndex * frameMs
                        val framePos = baseSeekSec + frameIndex * frameMs / 1000.0
                        frameIndex++
                        // Не успеваем показывать — пропускаем кадр, чтобы видео не отставало от звука
                        if (late > 2 * frameMs) continue
                        // Нативная память кадра освобождается сразу, а не когда соберёт GC:
                        // закрываем кадр, отставший на два — он уже точно не рисуется
                        val skia = bgraSkia(pixels, size)
                        currentFrameBitmap = skia.asComposeImageBitmap()
                        retiredFrames.addLast(skia)
                        while (retiredFrames.size > 3) runCatching { retiredFrames.removeFirst().close() }

                        val totalSec = metadata?.durationSec ?: 0.0
                        if (totalSec > 0 && framePos >= totalSec) {
                            currentSeekSec = totalSec
                            isPlaying = false
                            break
                        } else if (arrived - lastUiUpdate >= 200) {
                            currentSeekSec = framePos
                            lastUiUpdate = arrived
                        }
                    } catch (_: Throwable) {}
                }
                // Файл кончился (или ffmpeg не смог его читать) — кнопка не должна показывать «Пауза»
                if (ended && isActive) {
                    metadata?.durationSec?.takeIf { it > 0 && frameIndex > 0 }?.let { currentSeekSec = it }
                    isPlaying = false
                }
            } catch (_: Throwable) {
            } finally {
                try { videoProcess?.destroyForcibly() } catch (_: Throwable) {}
                try { audioProcess?.destroyForcibly() } catch (_: Throwable) {}
            }
        }
    }

    DialogWindow(
        onCloseRequest = {
            isPlaying = false
            onDismiss()
        },
        state = rememberDialogState(size = DpSize(1020.dp, 720.dp)),
        title = "Видеоплеер — ${currentEntry.name}",
        onPreviewKeyEvent = latestKeyHandler { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.key) {
                    Key.Spacebar -> {
                        togglePlay()
                        true
                    }
                    Key.DirectionLeft -> {
                        val target = (currentSeekSec - 5.0).coerceAtLeast(0.0)
                        currentSeekSec = target
                        seekTrigger++
                        true
                    }
                    Key.DirectionRight -> {
                        val totalSec = metadata?.durationSec ?: 0.0
                        val target = (currentSeekSec + 5.0).coerceAtMost(totalSec)
                        currentSeekSec = target
                        seekTrigger++
                        true
                    }
                    Key.Escape -> {
                        isPlaying = false
                        onDismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top Bar
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
                                    Icons.Default.Movie,
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
                                    val meta = metadata
                                    val subtitle = if (meta != null && meta.width > 0) {
                                        "${meta.width} × ${meta.height} • ${meta.videoCodec.uppercase()} • ${currentEntry.formattedSize}"
                                    } else {
                                        currentEntry.formattedSize
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (videosList.size > 1) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        Text(
                                            text = "${currentIndex + 1} / ${videosList.size}",
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
                                    Icon(Icons.Default.Info, contentDescription = "Характеристики видео")
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

                    // Main Preview Canvas + Inspector
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Video Frame Preview Canvas (isolated composable to prevent dialog-level recomposition)
                        VideoPreviewCanvas(
                            frameBitmap = currentFrameBitmap,
                            onFrameAreaSize = { frameArea = it },
                            isPlaying = isPlaying,
                            isExtractingFrame = isExtractingFrame,
                            captureNotification = captureNotification,
                            hasMultipleVideos = videosList.size > 1,
                            onTogglePlay = { togglePlay() },
                            onPrevVideo = { currentIndex = (currentIndex - 1 + videosList.size) % videosList.size },
                            onNextVideo = { currentIndex = (currentIndex + 1) % videosList.size },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        // Collapsible Video Info Inspector
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
                                        text = "Параметры видео",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    val meta = metadata
                                    VideoInfoRow("Файл", currentEntry.name)
                                    if (meta != null) {
                                        VideoInfoRow("Разрешение", "${meta.width} × ${meta.height}")
                                        VideoInfoRow("Длительность", formatDuration(meta.durationSec))
                                        VideoInfoRow("Видеокодек", meta.videoCodec.uppercase())
                                        if (meta.fps.isNotEmpty()) VideoInfoRow("Частота кадров", "${meta.fps} fps")
                                        if (meta.audioCodec.isNotEmpty()) VideoInfoRow("Аудиокодек", meta.audioCodec.uppercase())
                                        if (meta.bitrateKbps > 0) VideoInfoRow("Битрейт", "${meta.bitrateKbps} kbps")
                                    }
                                    VideoInfoRow("Размер", currentEntry.formattedSize)
                                    VideoInfoRow("Дата", currentEntry.formattedDate)

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Open in OS Default Player / ffplay
                                    Button(
                                        onClick = { launchFfplay(currentEntry, currentSeekSec, metadata?.videoCodec ?: "") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Окно ffplay (NVDEC)", style = MaterialTheme.typography.labelSmall)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                if (currentEntry.path.fileSystem == FileSystems.getDefault()) {
                                                    Desktop.getDesktop().open(currentEntry.path.toFile())
                                                }
                                            } catch (_: Throwable) {}
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Системный плеер", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Scrubber and Action Dock
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Timeline Scrubber
                            val totalSec = metadata?.durationSec ?: 1.0
                            val progressRatio = if (totalSec > 0) (currentSeekSec / totalSec).toFloat().coerceIn(0f, 1f) else 0f

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatDuration(currentSeekSec),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(50.dp)
                                )

                                Slider(
                                    value = progressRatio,
                                    onValueChange = { ratio ->
                                        val targetSec = (ratio * totalSec).coerceIn(0.0, totalSec)
                                        currentSeekSec = targetSec
                                    },
                                    onValueChangeFinished = {
                                        seekTrigger++
                                        if (!isPlaying) {
                                            scope.launch {
                                                isExtractingFrame = true
                                                val bmp = withContext(Dispatchers.IO) {
                                                    extractFrameAt(currentEntry, currentSeekSec, metadata, decodeSize)
                                                }
                                                if (bmp != null) currentFrameBitmap = bmp
                                                isExtractingFrame = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(24.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                )

                                Text(
                                    text = formatDuration(totalSec),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(50.dp),
                                    textAlign = TextAlign.End
                                )
                            }

                            // Bottom Action Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Seek controls (-5s, +5s)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val target = (currentSeekSec - 5.0).coerceAtLeast(0.0)
                                            currentSeekSec = target
                                            seekTrigger++
                                            if (!isPlaying) {
                                                scope.launch {
                                                    val bmp = withContext(Dispatchers.IO) { extractFrameAt(currentEntry, target, metadata, decodeSize) }
                                                    if (bmp != null) currentFrameBitmap = bmp
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.FastRewind, contentDescription = "-5 сек", modifier = Modifier.size(20.dp))
                                    }

                                    IconButton(
                                        onClick = {
                                            val target = (currentSeekSec + 5.0).coerceAtMost(totalSec)
                                            currentSeekSec = target
                                            seekTrigger++
                                            if (!isPlaying) {
                                                scope.launch {
                                                    val bmp = withContext(Dispatchers.IO) { extractFrameAt(currentEntry, target, metadata, decodeSize) }
                                                    if (bmp != null) currentFrameBitmap = bmp
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.FastForward, contentDescription = "+5 сек", modifier = Modifier.size(20.dp))
                                    }
                                }

                                // Center: In-dialog Play/Pause button
                                Button(
                                    onClick = { togglePlay() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                        contentColor = if (isPlaying) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isPlaying) "Пауза (Пробел)" else "Воспроизвести (Пробел)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Right: Screenshot Frame Capture Button + Pop-out external player
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { launchFfplay(currentEntry, currentSeekSec, metadata?.videoCodec ?: "") },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Внешнее окно", style = MaterialTheme.typography.labelSmall)
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val savedPath = withContext(Dispatchers.IO) {
                                                    captureFrameToFile(currentEntry, currentSeekSec)
                                                }
                                                if (savedPath != null) {
                                                    captureNotification = "Снимок сохранён: ${File(savedPath).name}"
                                                    kotlinx.coroutines.delay(3000)
                                                    captureNotification = null
                                                }
                                            }
                                        },
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Icon(Icons.Default.CameraAlt, contentDescription = "Сделать снимок кадра", modifier = Modifier.size(20.dp))
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
private fun VideoPreviewCanvas(
    frameBitmap: ImageBitmap?,
    onFrameAreaSize: (IntSize) -> Unit,
    isPlaying: Boolean,
    isExtractingFrame: Boolean,
    captureNotification: String?,
    hasMultipleVideos: Boolean,
    onTogglePlay: () -> Unit,
    onPrevVideo: () -> Unit,
    onNextVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF0D0D0D))
            // Без подсветки при наведении: иначе кадр затемняется, стоит навести мышь
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onTogglePlay() },
        contentAlignment = Alignment.Center
    ) {
        // Та же область, что у кадра: по ней выбирается размер декодирования
        Box(modifier = Modifier.fillMaxSize(0.95f).onSizeChanged(onFrameAreaSize))
        if (frameBitmap != null) {
            Image(
                bitmap = frameBitmap,
                contentDescription = "Превью кадра",
                modifier = Modifier.fillMaxSize(0.95f),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Извлечение видеопотока...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // Big center play button overlay (toggles in-dialog playback)
        if (!isPlaying) {
            Surface(
                onClick = onTogglePlay,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                shadowElevation = 10.dp,
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Воспроизвести видео",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // Loading spinner when scrubbing frame
        if (isExtractingFrame) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(24.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Capture Toast Pill
        captureNotification?.let { msg ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Next/Prev Buttons
        if (hasMultipleVideos) {
            FilledIconButton(
                onClick = onPrevVideo,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(44.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущее видео")
            }

            FilledIconButton(
                onClick = onNextVideo,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(44.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующее видео")
            }
        }
    }
}

@Composable
private fun VideoInfoRow(label: String, value: String) {
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

private fun launchFfplay(entry: VfsEntry, seekSec: Double, videoCodec: String = "") {
    try {
        val startSecStr = String.format(Locale.US, "%.2f", seekSec)
        val filePath = entry.path.toAbsolutePath().toString()
        val title = "WEX Video Player — ${entry.name}"
        val cmd = mutableListOf(
            "ffplay",
            "-autoexit",
            "-threads", "0",
            "-framedrop",
            "-x", "960",
            "-y", "540",
            "-ss", startSecStr,
            "-window_title", title,
            "-protocol_whitelist", "file"
        )
        val vc = videoCodec.lowercase()
        if (File("/dev/nvidia0").exists()) {
            when {
                vc.contains("hevc") || vc.contains("h265") -> cmd.addAll(listOf("-vcodec", "hevc_cuvid"))
                vc.contains("h264") || vc.contains("avc") -> cmd.addAll(listOf("-vcodec", "h264_cuvid"))
                vc.contains("av1") -> cmd.addAll(listOf("-vcodec", "av1_cuvid"))
                vc.contains("vp9") -> cmd.addAll(listOf("-vcodec", "vp9_cuvid"))
            }
        }
        cmd.add(filePath)
        try {
            val p = ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .startHelper()
            // Watch if hardware decoding fails and fallback to standard multithreaded ffplay
            Thread {
                Thread.sleep(400)
                if (!p.isAlive && p.exitValue() != 0) {
                    val fallbackCmd = listOf(
                        "ffplay",
                        "-autoexit",
                        "-threads", "0",
                        "-framedrop",
                        "-x", "960",
                        "-y", "540",
                        "-ss", startSecStr,
                        "-window_title", title,
                        "-protocol_whitelist", "file",
                        filePath
                    )
                    try { ProcessBuilder(fallbackCmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .startHelper() } catch (_: Throwable) {}
                }
            }.apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            val fallbackCmd = listOf(
                "ffplay",
                "-autoexit",
                "-threads", "0",
                "-framedrop",
                "-x", "960",
                "-y", "540",
                "-ss", startSecStr,
                "-window_title", title,
                "-protocol_whitelist", "file",
                filePath
            )
            ProcessBuilder(fallbackCmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .startHelper()
        }
    } catch (_: Throwable) {}
}

internal fun extractFrameAt(entry: VfsEntry, seekSec: Double, meta: VideoMetadata?, size: IntSize): ImageBitmap? {
    return try {
        val seekStr = String.format(Locale.US, "%.3f", seekSec)
        val filePath = entry.path.toAbsolutePath().toString()
        val bytes = runFfmpeg(
            listOf(
                "-threads", "0",
                "-ss", seekStr,
                "-protocol_whitelist", "file",
                "-i", filePath,
                "-frames:v", "1",
                "-vf", videoFilter(meta, size, playback = false),
                "-pix_fmt", "bgra",
                "-f", "rawvideo",
                "-"
            )
        ) ?: return null
        if (bytes.size >= size.width * size.height * 4) bgraFrame(bytes, size) else null
    } catch (_: Exception) {
        null
    }
}

private fun captureFrameToFile(entry: VfsEntry, seekSec: Double): String? {
    return try {
        val seekStr = String.format(Locale.US, "%.3f", seekSec)
        val parentDir = entry.path.parent?.toFile() ?: File(System.getProperty("user.home"))
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = entry.name.substringBeforeLast('.')
        // Два снимка за одну секунду — не перезаписываем первый
        var outFile = File(parentDir, "${baseName}_frame_${timestamp}.png")
        var n = 2
        while (outFile.exists()) outFile = File(parentDir, "${baseName}_frame_${timestamp}_${n++}.png")

        runFfmpeg(
            listOf(
                "-threads", "0",
                "-ss", seekStr,
                "-protocol_whitelist", "file",
                "-i", entry.path.toAbsolutePath().toString(),
                "-frames:v", "1",
                "-q:v", "2",
                "-n",
                outFile.absolutePath
            )
        )
        if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
    } catch (_: Exception) {
        null
    }
}

internal fun probeVideoMetadata(entry: VfsEntry): VideoMetadata {
    return try {
        val filePath = entry.path.toAbsolutePath().toString()

        // 1. Probe video stream metadata with stream selection v:0
        val jsonStr = org.wexclient.app.util.MediaTools.probe(
            listOf(
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,sample_aspect_ratio,avg_frame_rate,r_frame_rate,duration,bit_rate:stream_side_data=rotation:stream_tags=rotate",
                "-show_entries", "format=duration,bit_rate,tags",
                "-of", "json"
            ),
            filePath
        ).orEmpty()

        var duration = 0.0
        var w = 0
        var h = 0
        var vCodec = ""
        var aCodec = ""
        var fps = ""
        var bitrate = 0L

        val durMatch = Regex("\"duration\"\\s*:\\s*\"([0-9.]+)\"").find(jsonStr)
        if (durMatch != null) {
            duration = durMatch.groupValues[1].toDoubleOrNull() ?: 0.0
        }

        val wMatch = Regex("\"width\"\\s*:\\s*([0-9]+)").find(jsonStr)
        if (wMatch != null) w = wMatch.groupValues[1].toIntOrNull() ?: 0

        val hMatch = Regex("\"height\"\\s*:\\s*([0-9]+)").find(jsonStr)
        if (hMatch != null) h = hMatch.groupValues[1].toIntOrNull() ?: 0

        val vMatch = Regex("\"codec_name\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)
        if (vMatch != null) vCodec = vMatch.groupValues[1]

        fun evalRate(key: String): Double? {
            val m = Regex("\"$key\"\\s*:\\s*\"([0-9]+)/([0-9]+)\"").find(jsonStr) ?: return null
            val num = m.groupValues[1].toDoubleOrNull() ?: return null
            val den = m.groupValues[2].toDoubleOrNull() ?: return null
            return if (den > 0.0) num / den else null
        }
        val avgFps = evalRate("avg_frame_rate")
        val rFps = evalRate("r_frame_rate")
        val captureFps = Regex("\"com.android.capture.fps\"\\s*:\\s*\"([0-9.]+)\"").find(jsonStr)?.groupValues?.get(1)?.toDoubleOrNull()

        val effectiveFps = when {
            captureFps != null && captureFps > 0.0 -> captureFps
            avgFps != null && avgFps > 0.0 && avgFps <= 360.0 -> avgFps
            rFps != null && rFps > 0.0 && rFps <= 360.0 -> rFps
            else -> 0.0
        }
        if (effectiveFps > 0.0) {
            val rounded = Math.round(effectiveFps * 100.0) / 100.0
            fps = if (rounded % 1.0 == 0.0) "${rounded.toLong()}" else String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
        }

        val brMatch = Regex("\"bit_rate\"\\s*:\\s*\"([0-9]+)\"").find(jsonStr)
        if (brMatch != null) {
            val bits = brMatch.groupValues[1].toLongOrNull() ?: 0L
            bitrate = bits / 1000
        }

        // 2. Probe audio stream metadata with stream selection a:0
        try {
            val audioCodecStr = org.wexclient.app.util.MediaTools.probe(
                listOf("-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0"),
                filePath
            ).orEmpty().trim()
            if (audioCodecStr.isNotEmpty()) {
                aCodec = audioCodecStr
            }
        } catch (_: Throwable) {}

        val sar = Regex("\"sample_aspect_ratio\"\\s*:\\s*\"([0-9]+):([0-9]+)\"").find(jsonStr)?.let { m ->
            val num = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val den = m.groupValues[2].toDoubleOrNull() ?: 0.0
            if (num > 0.0 && den > 0.0) num / den else 1.0
        } ?: 1.0
        val rotation = Regex("\"rotation\"\\s*:\\s*(-?[0-9]+)").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\"rotate\"\\s*:\\s*\"(-?[0-9]+)\"").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        VideoMetadata(
            durationSec = duration,
            width = w,
            height = h,
            videoCodec = vCodec,
            audioCodec = aCodec,
            fps = fps,
            bitrateKbps = bitrate,
            sar = sar,
            rotation = rotation
        )
    } catch (_: Throwable) {
        VideoMetadata()
    }
}

private fun formatDuration(sec: Double): String {
    val totalSec = max(0, sec.toInt())
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
