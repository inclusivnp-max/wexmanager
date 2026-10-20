package org.wexclient.app.vfs

import org.wexclient.app.util.PlatformImages

import androidx.compose.ui.graphics.ImageBitmap
import org.wexclient.app.util.frozen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Миниатюры картинок и видео 64×64. Ключ — путь и время изменения из списка (без обращения
 * к диску в потоке окна), не больше трёх декодирований одновременно, одна загрузка на файл,
 * картинки декодируются сразу уменьшенными.
 */
object MediaThumbnailLoader {
    private const val MEMORY_ENTRIES = 600
    private const val DISK_FILES = 5000
    private const val MAX_IMAGE_BYTES = 64L shl 20
    internal const val FAILED_ENTRIES = 5000
    // Процесс живёт днями: кэш на диске подрезается не только при запуске
    private const val PRUNE_EVERY = 500

    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = (size > MEMORY_ENTRIES).also {
            if (it && eldest != null) org.wexclient.app.util.NativeMemory.evicted(eldest.value.width.toLong() * eldest.value.height * 4)
        }
    }
    // С пределом: каждая битая картинка, каждое видео без ffmpeg и каждая смена mtime оставляли ключ навсегда
    private val failed = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > FAILED_ENTRIES
    }
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    // Уменьшенные копии личных фото — только для владельца (как требует спецификация freedesktop)
    private val diskCacheDir: File by lazy {
        org.wexclient.app.util.AppDirs.cache.resolve("thumbnails").toFile().also { dir ->
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

    /** Запомненных неудач (для тестов). */
    internal fun failedCount(): Int = synchronized(failed) { failed.size }

    /**
     * Окон не осталось — миниатюры в памяти держать незачем (на диске они остаются). Пиксели лежат вне кучи;
     * закрыть их самим нельзя (строка, ещё не отпустившая картинку, нарисовала бы закрытую), поэтому ссылки
     * убираем и просим сборку мусора. Неудачи тоже забываем: за это время мог появиться ffmpeg.
     */
    fun clear() {
        val had = synchronized(memoryCache) { memoryCache.isNotEmpty().also { memoryCache.clear() } }
        synchronized(failed) { failed.clear() }
        if (had) org.wexclient.app.util.NativeMemory.releaseSoon()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)

    @Volatile
    private var pruned = false
    private val pruning = java.util.concurrent.atomic.AtomicBoolean()
    private val written = java.util.concurrent.atomic.AtomicInteger()

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
        if (synchronized(failed) { failed[k] != null }) return null
        // Одна загрузка на файл, даже если строка перерисовалась несколько раз
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = inFlight.putIfAbsent(k, mine)
        if (running != null) return running.await()
        try {
            // В кэш — прямо в фоне: строка ушла с экрана, пока декодировали, — результат не пропадает,
            // и возврат к ней не декодирует картинку заново
            val result = withContext(decodeDispatcher) {
                produce(path, isImage, isVideo, k, vfsManager)?.also { r -> synchronized(memoryCache) { memoryCache[k] = r } }
            }
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
            runCatching { PlatformImages.decode(diskFile.readBytes()) }.getOrNull()?.let { return it.frozen() }
            diskFile.delete()
        }
        val thumb = try {
            when {
                isImage -> ThumbnailCodec.image(path, vfsManager, MAX_IMAGE_BYTES)
                isVideo -> ThumbnailCodec.video(path)
                else -> null
            }
        } catch (_: OutOfMemoryError) {
            // Не помечаем навсегда: в другой раз памяти может хватить
            return null
        } catch (_: Exception) {
            null
        }
        if (thumb == null) {
            synchronized(failed) { failed[k] = Unit }
            return null
        }
        if (useDisk) runCatching {
            diskFile.parentFile?.mkdirs()
            PlatformImages.encodePng(thumb)?.let { diskFile.writeBytes(it) }
            // Здесь же, в потоке декодирования: окно не ждёт
            if (written.incrementAndGet() % PRUNE_EVERY == 0) pruneDiskCache()
        }
        return thumb.frozen()
    }

    private fun diskFile(key: String): File {
        val md5 = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return File(diskCacheDir, md5.joinToString("") { "%02x".format(it) } + ".png")
    }

    /** Кэш на диске не растёт бесконечно: при запуске и каждые [PRUNE_EVERY] новых файлов оставляем самые свежие. */
    private fun pruneDiskCacheOnce() {
        if (pruned) return
        pruned = true
        pruneDiskCache()
    }

    private fun pruneDiskCache() {
        // Уборка уже идёт в другом потоке декодирования — второй проход не нужен
        if (!pruning.compareAndSet(false, true)) return
        try {
            runCatching {
                val files = diskCacheDir.listFiles() ?: return
                if (files.size <= DISK_FILES) return
                // Время — один раз на файл, а не при каждом сравнении сортировки
                files.map { it to it.lastModified() }.sortedByDescending { it.second }
                    .drop(DISK_FILES).forEach { it.first.delete() }
            }
        } finally {
            pruning.set(false)
        }
    }
}

/** Сколько читать с начала JPEG, чтобы захватить APP1 (он не длиннее 64 КБ и идёт в начале). */
internal const val EXIF_HEAD_BYTES = 128 * 1024

/**
 * Встроенная миниатюра EXIF: JPEG из IFD1 (теги 0x0201 — смещение, 0x0202 — длина) в сегменте APP1.
 * [head] — начало файла. null — не JPEG, нет EXIF или миниатюры, данные битые.
 */
internal fun exifThumbnail(head: ByteArray): ByteArray? {
    fun u8(i: Int) = head[i].toInt() and 0xFF
    if (head.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
    var p = 2
    while (p + 4 <= head.size) {
        if (u8(p) != 0xFF) return null
        val marker = u8(p + 1)
        // Заполнители и маркеры без длины
        if (marker == 0xFF) { p++; continue }
        if (marker == 0x01 || marker in 0xD0..0xD7) { p += 2; continue }
        // Начались данные изображения — метаданных дальше нет
        if (marker == 0xDA || marker == 0xD9) return null
        val len = (u8(p + 2) shl 8) or u8(p + 3)
        if (len < 2) return null
        val seg = p + 4
        val end = p + 2 + len
        if (end > head.size) return null
        if (marker == 0xE1 && len >= 16 &&
            head[seg] == 'E'.code.toByte() && head[seg + 1] == 'x'.code.toByte() &&
            head[seg + 2] == 'i'.code.toByte() && head[seg + 3] == 'f'.code.toByte() &&
            head[seg + 4] == 0.toByte() && head[seg + 5] == 0.toByte()
        ) {
            return tiffThumbnail(head, seg + 6, end)
        }
        p = end
    }
    return null
}

/** Ширина и высота JPEG из маркера SOF в [head]; null — не дошли до него (или не JPEG). */
internal fun jpegSize(head: ByteArray): Pair<Int, Int>? {
    fun u8(i: Int) = head[i].toInt() and 0xFF
    if (head.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
    var p = 2
    while (p + 4 <= head.size) {
        if (u8(p) != 0xFF) return null
        val marker = u8(p + 1)
        if (marker == 0xFF) { p++; continue }
        if (marker == 0x01 || marker in 0xD0..0xD7) { p += 2; continue }
        if (marker == 0xDA || marker == 0xD9) return null
        val len = (u8(p + 2) shl 8) or u8(p + 3)
        if (len < 2) return null
        // SOF0…SOF15, кроме DHT (C4), JPG (C8) и DAC (CC)
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            if (p + 9 > head.size) return null
            val h = (u8(p + 5) shl 8) or u8(p + 6)
            val w = (u8(p + 7) shl 8) or u8(p + 8)
            return if (w > 0 && h > 0) w to h else null
        }
        p += 2 + len
    }
    return null
}

/** Разбор TIFF внутри APP1: [base] — начало TIFF-заголовка, [limit] — конец сегмента. */
private fun tiffThumbnail(b: ByteArray, base: Int, limit: Int): ByteArray? {
    if (base + 8 > limit) return null
    val little = when {
        b[base] == 'I'.code.toByte() && b[base + 1] == 'I'.code.toByte() -> true
        b[base] == 'M'.code.toByte() && b[base + 1] == 'M'.code.toByte() -> false
        else -> return null
    }
    fun u16(at: Int): Int {
        if (at < base || at + 2 > limit) throw IndexOutOfBoundsException()
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        return if (little) a or (c shl 8) else (a shl 8) or c
    }
    fun u32(at: Int): Long {
        val lo = u16(if (little) at else at + 2).toLong()
        val hi = u16(if (little) at + 2 else at).toLong()
        return (hi shl 16) or lo
    }
    return try {
        if (u16(base + 2) != 42) return null
        val ifd0 = u32(base + 4)
        if (ifd0 < 8 || base + ifd0 + 2 > limit) return null
        val n0 = u16(base + ifd0.toInt())
        val next = base + ifd0.toInt() + 2 + n0 * 12
        val ifd1 = u32(next)
        if (ifd1 < 8 || base + ifd1 + 2 > limit) return null
        val at1 = base + ifd1.toInt()
        val n1 = u16(at1)
        var offset = -1L
        var length = -1L
        for (i in 0 until n1) {
            val e = at1 + 2 + i * 12
            when (u16(e)) {
                0x0201 -> offset = u32(e + 8)
                0x0202 -> length = u32(e + 8)
            }
        }
        if (offset <= 0 || length <= 0 || length > 64 * 1024) return null
        val from = base + offset
        val to = from + length
        if (to > limit) return null
        val thumb = b.copyOfRange(from.toInt(), to.toInt())
        // Сама миниатюра — тоже JPEG
        if (thumb.size < 4 || (thumb[0].toInt() and 0xFF) != 0xFF || (thumb[1].toInt() and 0xFF) != 0xD8) null else thumb
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}
