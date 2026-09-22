package org.wexclient.app.vfs

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.stream.ImageInputStream
import org.wexclient.app.util.startHelper

/**
 * Миниатюры картинок и видео 64×64. Ключ — путь и время изменения из списка (без обращения
 * к диску в потоке окна), не больше трёх декодирований одновременно, одна загрузка на файл,
 * картинки декодируются сразу уменьшенными.
 */
object MediaThumbnailLoader {
    private const val SIZE = 64
    private const val MEMORY_ENTRIES = 600
    private const val DISK_FILES = 5000
    private const val MAX_IMAGE_BYTES = 64L shl 20

    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MEMORY_ENTRIES
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    // Уменьшенные копии личных фото — только для владельца (как требует спецификация freedesktop)
    private val diskCacheDir: File by lazy {
        File(System.getProperty("user.home"), ".cache/wexdesktop/thumbnails").also { dir ->
            dir.mkdirs()
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    private val diskCacheReal: Path? by lazy { runCatching { diskCacheDir.toPath().toRealPath() }.getOrNull() }

    /**
     * Миниатюры самих миниатюр на диск не пишем: папка кэша, открытая в панели, иначе росла бы
     * без конца (новый файл → обновление списка → миниатюра нового файла → новый файл…).
     */
    internal fun insideDiskCache(path: Path): Boolean {
        if (path.fileSystem !== FileSystems.getDefault()) return false
        val cache = diskCacheReal ?: return false
        return runCatching { path.toRealPath().startsWith(cache) }.getOrDefault(false)
    }

    /** Файлов в кэше на диске (для тестов). */
    internal fun diskCacheFiles(): Int = diskCacheDir.list()?.size ?: 0

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)

    @Volatile
    private var pruned = false

    private fun key(path: Path, lastModified: Long): String = path.toAbsolutePath().toString() + ":" + lastModified

    fun getCached(path: Path, lastModified: Long): ImageBitmap? {
        val k = key(path, lastModified)
        return synchronized(memoryCache) { memoryCache[k] }
    }

    suspend fun loadThumbnail(
        path: Path,
        isImage: Boolean,
        isVideo: Boolean,
        lastModified: Long,
        vfsManager: VfsManager? = null
    ): ImageBitmap? {
        val k = key(path, lastModified)
        synchronized(memoryCache) { memoryCache[k] }?.let { return it }
        if (k in failed) return null
        // Одна загрузка на файл, даже если строка перерисовалась несколько раз
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = inFlight.putIfAbsent(k, mine)
        if (running != null) return running.await()
        try {
            val result = withContext(decodeDispatcher) { produce(path, isImage, isVideo, k, vfsManager) }
            if (result != null) synchronized(memoryCache) { memoryCache[k] = result }
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(k, mine)
        }
    }

    private fun produce(path: Path, isImage: Boolean, isVideo: Boolean, k: String, vfsManager: VfsManager?): ImageBitmap? {
        pruneDiskCacheOnce()
        val useDisk = !insideDiskCache(path)
        val diskFile = diskFile(k)
        if (useDisk && diskFile.length() > 0) {
            runCatching { ImageIO.read(diskFile) }.getOrNull()?.let { return it.toComposeImageBitmap() }
            diskFile.delete()
        }
        val thumb = try {
            when {
                isImage -> imageThumbnail(path, vfsManager)
                isVideo -> videoThumbnail(path)
                else -> null
            }
        } catch (_: OutOfMemoryError) {
            // Не помечаем навсегда: в другой раз памяти может хватить
            return null
        } catch (_: Exception) {
            null
        }
        if (thumb == null) {
            failed.add(k)
            return null
        }
        if (useDisk) runCatching {
            diskFile.parentFile?.mkdirs()
            ImageIO.write(thumb, "png", diskFile)
        }
        return thumb.toComposeImageBitmap()
    }

    private fun imageThumbnail(path: Path, vfsManager: VfsManager?): BufferedImage? {
        val local = path.fileSystem === FileSystems.getDefault()
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size > MAX_IMAGE_BYTES) return null
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
        val image = org.jetbrains.skia.Image.makeFromBitmap(scaled.asSkiaBitmap())
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val out = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        val info = org.jetbrains.skia.ImageInfo(bitmap.width, bitmap.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
        val pixels = bitmap.readPixels(info, bitmap.width * 4, 0, 0) ?: return null
        val ints = IntArray(bitmap.width * bitmap.height)
        java.nio.ByteBuffer.wrap(pixels).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
        out.setRGB(0, 0, bitmap.width, bitmap.height, ints, 0, bitmap.width)
        bitmap.close()
        image.close()
        out
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

    private fun diskFile(key: String): File {
        val md5 = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return File(diskCacheDir, md5.joinToString("") { "%02x".format(it) } + ".png")
    }

    /** Кэш на диске не растёт бесконечно: раз за запуск оставляем самые свежие файлы. */
    private fun pruneDiskCacheOnce() {
        if (pruned) return
        pruned = true
        runCatching {
            val files = diskCacheDir.listFiles() ?: return
            if (files.size <= DISK_FILES) return
            files.sortedByDescending { it.lastModified() }.drop(DISK_FILES).forEach { it.delete() }
        }
    }
}
