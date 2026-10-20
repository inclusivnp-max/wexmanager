package org.wexclient.app.vfs

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.wexclient.app.util.frozen
import org.wexclient.app.util.startHelper
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.stream.ImageInputStream

/** Десктоп: ImageIO (с прореживанием и миниатюрой EXIF), Skia для WebP/AVIF, ffmpeg для кадра видео. */
internal actual object ThumbnailCodec {
    private const val SIZE = THUMBNAIL_SIZE

    actual fun image(path: Path, vfsManager: VfsManager?, maxBytes: Long): ImageBitmap? =
        imageThumbnail(path, vfsManager, maxBytes)?.toComposeImageBitmap()?.frozen()

    actual fun video(path: Path): ImageBitmap? = videoThumbnail(path)?.toComposeImageBitmap()?.frozen()

    private fun imageThumbnail(path: Path, vfsManager: VfsManager?, maxBytes: Long): BufferedImage? {
        val local = path.fileSystem === FileSystems.getDefault()
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size > maxBytes) return null
        if (local && isJpegName(path)) exifThumbnailOf(path)?.let { return scaleToThumbnail(it) }
        val bytes: ByteArray? = if (local) null else vfsManager?.readBytes(path)
        val decoded = if (local) {
            ImageIO.createImageInputStream(path.toFile())?.use { readSampled(it) }
        } else {
            bytes?.let { b -> ImageIO.createImageInputStream(ByteArrayInputStream(b))?.use { readSampled(it) } }
        }
        val src = decoded ?: skiaDecode(bytes ?: if (local) Files.readAllBytes(path) else return null) ?: return null
        return scaleToThumbnail(src)
    }

    /** Сразу уменьшенная картинка: 24-мегапиксельный JPEG не занимает 72 МБ ради значка 64×64. */
    private fun readSampled(input: ImageInputStream): BufferedImage? {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.setInput(input, true, true)
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            val step = maxOf(1, minOf(w, h) / (SIZE * 2))
            val param: ImageReadParam = reader.defaultReadParam
            if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
            return reader.read(0, param)
        } finally {
            reader.dispose()
        }
    }

    /** WebP, AVIF и прочее, чего нет в ImageIO, — через Skia, сразу в размер миниатюры. */
    private fun skiaDecode(bytes: ByteArray): BufferedImage? = runCatching {
        // Заявленные размеры проверяются до декодирования: WebP 16 000 × 16 000 — это гигабайт
        val scaled = org.wexclient.app.util.ImageScale.decodeScaled(bytes, maxPx = SIZE * 2, maxSourcePx = 16384, maxSourcePixels = 64_000_000)
            ?: return null
        // Промежуточное Skia — нативная память: закрываем сами и на каждом выходе, а не ждём сборщика
        val skia = scaled.asSkiaBitmap()
        try {
            val w = skia.width
            val h = skia.height
            val info = org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
            val pixels = skia.readPixels(info, w * 4, 0, 0) ?: return null
            val ints = IntArray(w * h)
            java.nio.ByteBuffer.wrap(pixels).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, w, h, ints, 0, w) }
        } finally {
            skia.close()
        }
    }.getOrNull()

    /**
     * Готовая миниатюра из EXIF (APP1) фотографии: камеры и телефоны кладут туда 160×120. ImageIO даже
     * с прореживанием декодирует все блоки JPEG — 150–400 мс на снимок в 12–24 Мп; эта — единицы мс.
     * Меньше [SIZE] или битая — null, тогда обычный путь.
     */
    private fun isJpegName(path: Path): Boolean {
        val n = path.fileName?.toString() ?: return false
        return n.endsWith(".jpg", true) || n.endsWith(".jpeg", true) || n.endsWith(".jfif", true) || n.endsWith(".jpe", true)
    }

    private fun exifThumbnailOf(path: Path): BufferedImage? = runCatching {
        val head = Files.newInputStream(path).use { it.readNBytes(EXIF_HEAD_BYTES) }
        val jpeg = exifThumbnail(head) ?: return null
        // Пропорции — как у самого снимка, иначе мимо: камеры кладут 160×120 с чёрными полями для 3:2,
        // а обрезка без обновления EXIF оставляет миниатюру прежнего кадра
        val (w, h) = jpegSize(head) ?: return null
        val thumb = ImageIO.read(ByteArrayInputStream(jpeg))?.takeIf { it.width >= SIZE && it.height >= SIZE / 2 } ?: return null
        val want = w.toDouble() / h
        val got = thumb.width.toDouble() / thumb.height
        thumb.takeIf { kotlin.math.abs(got - want) / want <= 0.02 }
    }.getOrNull()

    private fun videoThumbnail(path: Path): BufferedImage? {
        if (path.fileSystem !== FileSystems.getDefault() || !Files.exists(path)) return null
        val process = ProcessBuilder(
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-ss", "00:00:01",
            "-protocol_whitelist", "file",
            "-i", path.toAbsolutePath().toString(),
            "-frames:v", "1",
            "-vf", "scale=${SIZE + 8}:${SIZE + 8}:force_original_aspect_ratio=increase,crop=${SIZE + 8}:${SIZE + 8}",
            "-f", "image2pipe",
            "-vcodec", "png",
            "-"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).startHelper()
        var bytes = ByteArray(0)
        val reader = Thread { bytes = runCatching { process.inputStream.readAllBytes() }.getOrDefault(ByteArray(0)) }
            .apply {
                isDaemon = true
                start()
            }
        // Битый или сетевой файл не должен держать поток миниатюр вечно
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(2000)
        if (bytes.isEmpty()) return null
        // ffmpeg режет кадр с запасом — к общему размеру миниатюр
        return ImageIO.read(ByteArrayInputStream(bytes))?.let(::scaleToThumbnail)
    }

    private fun scaleToThumbnail(src: BufferedImage): BufferedImage {
        val sw = src.width
        val sh = src.height
        if (sw <= SIZE && sh <= SIZE && src.type == BufferedImage.TYPE_INT_ARGB) return src
        // Обрезка по центру с сохранением пропорций
        val scale = maxOf(SIZE.toDouble() / sw, SIZE.toDouble() / sh)
        val nw = (sw * scale).toInt().coerceAtLeast(1)
        val nh = (sh * scale).toInt().coerceAtLeast(1)
        val dst = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, (SIZE - nw) / 2, (SIZE - nh) / 2, nw, nh, null)
        g.dispose()
        return dst
    }
}
