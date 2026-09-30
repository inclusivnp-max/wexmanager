package org.wexclient.app.vfs

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.util.ImageScale
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Иконки APK для списка файлов и окна APK. Кэш ограничен, одновременно разбирается не больше
 * двух архивов, картинки уменьшаются до [MAX_PX] и не декодируются, если заявлены огромными.
 */
object ApkIconLoader {
    // Почти каждая иконка — 192×192 (144 КБ): 400 штук держали ~59 МБ. Уменьшать нельзя — тот же
    // кэш рисует окно APK (72 dp, на HiDPI до 216 px); 150 — с запасом на видимые строки двух панелей
    private const val CACHE_ENTRIES = 150
    internal const val FAILED_ENTRIES = 5000
    private const val MAX_PX = 192
    private const val MAX_SOURCE_PX = 4096
    private const val MAX_IMAGE_BYTES = 8 shl 20
    private const val MAX_MANIFEST_BYTES = 16 shl 20
    private const val MAX_ARSC_BYTES = 128 shl 20

    private val iconCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = (size > CACHE_ENTRIES).also {
            if (it && eldest != null) org.wexclient.app.util.NativeMemory.evicted(eldest.value.width.toLong() * eldest.value.height * 4)
        }
    }
    // С пределом: каждый битый APK и каждая смена mtime оставляли ключ навсегда
    private val failedCache = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > FAILED_ENTRIES
    }
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    // Папка с сотней APK: не больше двух разборов resources.arsc одновременно
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun key(path: Path, lastModified: Long): String =
        (try { path.toAbsolutePath().toString() } catch (_: Exception) { path.toString() }) + ":" + lastModified

    /** Запомненных неудач (для тестов). */
    internal fun failedCount(): Int = synchronized(failedCache) { failedCache.size }

    fun getCachedIcon(path: Path, lastModified: Long): ImageBitmap? {
        val k = key(path, lastModified)
        return synchronized(iconCache) { iconCache[k] }
    }

    suspend fun loadIcon(path: Path, lastModified: Long): ImageBitmap? {
        val k = key(path, lastModified)
        synchronized(iconCache) { iconCache[k] }?.let { return it }
        if (synchronized(failedCache) { failedCache[k] != null }) return null
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = inFlight.putIfAbsent(k, mine)
        if (running != null) return running.await()
        try {
            return withContext(decodeDispatcher) {
                val result = try {
                    when {
                        path.fileSystem != FileSystems.getDefault() || !Files.isRegularFile(path) -> null
                        org.wexclient.app.apk.SplitBundle.isBundle(path.fileName.toString()) -> extractBundleIcon(path.toFile())
                        else -> extractApkIcon(path.toFile())
                    }
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: Exception) {
                    null
                }
                // В кэш — здесь же, а не после: строка могла уйти с экрана, пока шёл разбор (у набора —
                // с копированием основного APK), и тогда при возврате всё делалось бы заново
                if (result != null) synchronized(iconCache) { iconCache[k] = result } else synchronized(failedCache) { failedCache[k] = Unit }
                mine.complete(result)
                result
            }
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(k, mine)
        }
    }

    /** Содержимое записи, если оно не больше [limit]: размер в заголовке zip может врать. */
    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Int): ByteArray? {
        if (entry.size > limit) return null
        zip.getInputStream(entry).use { stream ->
            val bytes = stream.readNBytes(limit + 1)
            return if (bytes.size > limit) null else bytes
        }
    }

    private fun decodeIcon(bytes: ByteArray): ImageBitmap? = ImageScale.decodeScaled(bytes, MAX_PX, MAX_SOURCE_PX)

    private fun extractApkIcon(apkFile: File): ImageBitmap? =
        ZipFile(apkFile).use { zip -> iconFromZip(zip, resolveIconsFromManifestAndArsc(zip)) }

    /** Самые большие основные APK, которые распаковываем ради иконки. */
    private const val MAX_BASE_BYTES = 256L shl 20

    /**
     * Иконка набора split-APK. SAI, APKPure и APKMirror кладут icon.png рядом с частями; иначе —
     * из основного APK: пути берём из его манифеста и таблицы ресурсов, а сама картинка у набора
     * из Google Play часто лежит в части под плотность экрана (config.xxhdpi.apk).
     */
    private fun extractBundleIcon(file: File): ImageBitmap? {
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            entries
                .filter { e ->
                    val n = e.name.lowercase()
                    '/' !in n && n.startsWith("icon") && (n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpg"))
                }
                .sortedByDescending { it.size }
                .forEach { e -> runCatching { readEntry(zip, e, MAX_IMAGE_BYTES)?.let(::decodeIcon) }.getOrNull()?.let { return it } }

            val parts = entries
                .filter { it.name.lowercase().endsWith(".apk") && !it.name.startsWith("standalones/") }
                .map { it to org.wexclient.app.apk.SplitBundle.part(it.name, it.size) }
            val base = parts.firstOrNull { it.second.kind == org.wexclient.app.apk.BundlePartKind.BASE }?.first
                ?: parts.filter { it.second.kind == org.wexclient.app.apk.BundlePartKind.FEATURE }.singleOrNull()?.first
                ?: return null
            if (base.size > MAX_BASE_BYTES) return null
            val temp = org.wexclient.app.util.ProcessScratch.work.newFile("icon-", ".apk")
            try {
                zip.getInputStream(base).use { Files.copy(it, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                val paths = ZipFile(temp.toFile()).use { baseZip ->
                    val resolved = resolveIconsFromManifestAndArsc(baseZip)
                    iconFromZip(baseZip, resolved)?.let { return it }
                    resolved
                }
                // Картинки под плотность экрана — сначала самые чёткие
                val rank = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "tvdpi", "mdpi", "ldpi")
                val densities = parts
                    .filter { it.second.kind == org.wexclient.app.apk.BundlePartKind.DENSITY && it.first.size <= MAX_BASE_BYTES }
                    .sortedBy { rank.indexOf(it.second.label).let { i -> if (i < 0) rank.size else i } }
                for ((entry, _) in densities) {
                    zip.getInputStream(entry).use { Files.copy(it, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    ZipFile(temp.toFile()).use { part -> iconFromZip(part, paths)?.let { return it } }
                }
                return null
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    /** Картинка иконки в APK: по путям из манифеста, по привычным именам, по ключевым словам. */
    private fun iconFromZip(zip: ZipFile, resolved: List<String>): ImageBitmap? {
        run {
            fun tryEntry(entry: ZipEntry?): ImageBitmap? {
                if (entry == null || entry.isDirectory) return null
                return runCatching { readEntry(zip, entry, MAX_IMAGE_BYTES)?.let(::decodeIcon) }.getOrNull()
            }

            // 1. Точные пути из AndroidManifest.xml и resources.arsc, крупные сначала
            val iconPaths = resolved
                .mapNotNull { zip.getEntry(it) }
                .sortedByDescending { it.size }
            for (entry in iconPaths) tryEntry(entry)?.let { return it }

            // 2. Привычные имена
            val candidates = listOf(
                "res/mipmap-xxxhdpi/ic_launcher.png",
                "res/mipmap-xxhdpi/ic_launcher.png",
                "res/mipmap-xhdpi/ic_launcher.png",
                "res/mipmap-hdpi/ic_launcher.png",
                "res/drawable-xxhdpi/ic_launcher.png",
                "res/drawable-xhdpi/ic_launcher.png",
                "res/drawable/ic_launcher.png",
                "res/mipmap-xxxhdpi/ic_launcher.webp",
                "res/mipmap-xxhdpi/ic_launcher.webp",
                "res/mipmap-xhdpi/ic_launcher.webp",
                "res/mipmap-hdpi/ic_launcher.webp"
            )
            for (target in candidates) tryEntry(zip.getEntry(target))?.let { return it }

            // 3. Поиск по ключевым словам в именах
            val keywordEntry = zip.entries().asSequence().firstOrNull {
                val name = it.name.lowercase()
                (name.contains("wex_icon") || name.contains("ic_launcher") || name.contains("app_icon") || name.endsWith("/icon.png")) &&
                    (name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".jpg"))
            }
            return tryEntry(keywordEntry)
        }
    }

    private fun resolveIconsFromManifestAndArsc(zip: ZipFile): List<String> {
        return try {
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
            val manifestBytes = readEntry(zip, manifestEntry, MAX_MANIFEST_BYTES) ?: return emptyList()

            // Extract icon resource ID (e.g. 0x7f100000) from Manifest binary XML
            val iconResId = getIconResIdFromManifest(manifestBytes) ?: return emptyList()

            val arscEntry = zip.getEntry("resources.arsc") ?: return emptyList()
            val arscBytes = readEntry(zip, arscEntry, MAX_ARSC_BYTES) ?: return emptyList()

            // Lookup path in ARSC for this resource ID
            resolvePathsFromArsc(arscBytes, iconResId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Строки пула по смещениям. Длина проверяется до выделения памяти, одно смещение
     * читается один раз: файл из APK может заявлять строки в гигабайты.
     */
    private fun readStrings(buf: ByteBuffer, base: Int, offsets: IntArray, isUtf8: Boolean): List<String> =
        LazyPool(buf.duplicate().order(ByteOrder.LITTLE_ENDIAN), base, offsets, isUtf8)

    /**
     * Строки — по запросу: из глобального пула resources.arsc (100 000+ строк) значку нужны две-три,
     * а разбор всего пула стоил на APK до 300 мс и десятков мегабайт мусора. Своя копия буфера:
     * позиция разбора таблицы у вызывающего не сдвигается.
     */
    private class LazyPool(
        private val buf: ByteBuffer,
        private val base: Int,
        private val offsets: IntArray,
        private val isUtf8: Boolean
    ) : AbstractList<String>() {
        private val cache = HashMap<Int, String>()

        override val size: Int get() = offsets.size

        override fun get(index: Int): String {
            val off = offsets[index]
            return cache.getOrPut(off) { decode(off) }
        }

        private fun decode(off: Int): String = try {
            val at = base.toLong() + off
            if (at < 0 || at >= buf.limit()) ""
            else {
                buf.position(at.toInt())
                if (isUtf8) {
                    val l1 = buf.get().toInt() and 0xFF
                    if ((l1 and 0x80) != 0) buf.get()
                    val l2 = buf.get().toInt() and 0xFF
                    val byteLen = if ((l2 and 0x80) != 0) ((l2 and 0x7F) shl 8) or (buf.get().toInt() and 0xFF) else l2
                    if (byteLen > buf.remaining()) ""
                    else ByteArray(byteLen).also { buf.get(it) }.toString(Charsets.UTF_8)
                } else {
                    var charLen = buf.short.toInt() and 0xFFFF
                    if ((charLen and 0x8000) != 0) charLen = ((charLen and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
                    if (charLen.toLong() * 2 > buf.remaining()) ""
                    else String(CharArray(charLen) { buf.short.toInt().toChar() })
                }
            }
        } catch (_: RuntimeException) {
            ""
        }
    }

    internal fun getIconResIdFromManifest(bytes: ByteArray): Int? {
        if (bytes.size < 36) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Read string pool
        buf.position(8)
        var stringTable: List<String> = emptyList()
        var resIds: IntArray = intArrayOf()

        while (buf.hasRemaining()) {
            val start = buf.position()
            if (buf.remaining() < 8) break
            val chunkType = buf.int
            val chunkSize = buf.int
            if (chunkSize < 8 || start.toLong() + chunkSize > bytes.size) break

            if (chunkType == 0x001C0001) { // string pool
                val stringCount = buf.int
                val styleCount = buf.int
                val flags = buf.int
                val stringsStart = buf.int
                val stylesStart = buf.int
                // Счётчик из файла: не больше, чем смещений помещается в сам пул
                if (stringCount < 0 || stringCount > chunkSize / 4) break
                val isUtf8 = (flags and (1 shl 8)) != 0
                val offsets = IntArray(stringCount)
                for (i in 0 until stringCount) offsets[i] = buf.int
                val absStart = start + stringsStart
                stringTable = readStrings(buf, absStart, offsets, isUtf8)
            } else if (chunkType == 0x00080180) { // res map
                val count = minOf((chunkSize - 8) / 4, buf.remaining() / 4)
                resIds = IntArray(count)
                for (i in 0 until count) resIds[i] = buf.int
            } else if (chunkType == 0x00100102) { // start tag
                buf.position(start + 16)
                val nsIdx = buf.int
                val nameIdx = buf.int
                val attrStart = buf.short.toInt() and 0xFFFF
                val attrSize = buf.short.toInt() and 0xFFFF
                val attrCount = buf.short.toInt() and 0xFFFF

                val tagName = if (nameIdx in stringTable.indices) stringTable[nameIdx] else ""
                if (tagName == "application") {
                    buf.position(start + 16 + 20)
                    for (i in 0 until attrCount) {
                        val aNs = buf.int
                        val aName = buf.int
                        val aRaw = buf.int
                        val aValSize = buf.short.toInt() and 0xFFFF
                        val aValRes0 = buf.get()
                        val aValType = buf.get().toInt() and 0xFF
                        val aValData = buf.int

                        val attrName = if (aName in stringTable.indices) stringTable[aName] else ""
                        val rId = if (aName in resIds.indices) resIds[aName] else 0

                        if (attrName == "icon" || rId == 0x01010002) {
                            return aValData
                        }
                    }
                }
            }
            buf.position(start + chunkSize)
        }
        return null
    }

    internal fun resolvePathsFromArsc(arsc: ByteArray, resId: Int): List<String> {
        val targetTypeId = (resId shr 16) and 0xFF
        val targetEntryId = resId and 0xFFFF

        val buf = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
        val tableType = buf.short.toInt() and 0xFFFF
        val headerSize = buf.short.toInt() and 0xFFFF
        val tableSize = buf.int

        // Read global string pool
        buf.position(headerSize)
        val spType = buf.short.toInt() and 0xFFFF
        val spHeaderSize = buf.short.toInt() and 0xFFFF
        val spSize = buf.int
        val stringCount = buf.int
        buf.position(headerSize + 16)
        val flags = buf.int
        val stringsStart = buf.int
        val isUtf8 = (flags and (1 shl 8)) != 0

        buf.position(headerSize + 28)
        if (stringCount < 0 || stringCount > spSize / 4 || stringCount > buf.remaining() / 4) return emptyList()
        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) offsets[i] = buf.int

        val absStringsStart = headerSize + stringsStart
        val globalStrings = readStrings(buf, absStringsStart, offsets, isUtf8)

        // Search Package chunks
        var pos = headerSize + spSize
        val candidates = mutableListOf<String>()

        while (pos < arsc.size - 8) {
            buf.position(pos)
            val cType = buf.short.toInt() and 0xFFFF
            val hSize = buf.short.toInt() and 0xFFFF
            val cSize = buf.int
            if (cSize <= 0) break

            if (cType == 0x0200) { // Package
                var innerPos = pos + hSize
                while (innerPos < pos + cSize) {
                    buf.position(innerPos)
                    val it = buf.short.toInt() and 0xFFFF
                    val ih = buf.short.toInt() and 0xFFFF
                    val ic = buf.int
                    if (ic <= 0) break

                    if (it == 0x0201) { // Type Table
                        val typeId = buf.get().toInt() and 0xFF
                        buf.position(innerPos + 12)
                        val entryCount = buf.int
                        val entriesStart = buf.int
                        val configSize = buf.int

                        if (typeId == targetTypeId && targetEntryId < entryCount) {
                            val offPos = innerPos + 20 + configSize + targetEntryId * 4
                            if (offPos + 4 <= arsc.size) {
                                buf.position(offPos)
                                val entryOffset = buf.int
                                if (entryOffset != -1) {
                                    val entryValPos = innerPos + entriesStart + entryOffset
                                    buf.position(entryValPos)
                                    val eSize = buf.short.toInt() and 0xFFFF
                                    val eFlags = buf.short.toInt() and 0xFFFF
                                    buf.position(entryValPos + eSize)
                                    val vSize = buf.short.toInt() and 0xFFFF
                                    val vRes0 = buf.get()
                                    val vType = buf.get().toInt() and 0xFF
                                    val vData = buf.int

                                    if (vType == 0x03 && vData in globalStrings.indices) {
                                        val path = globalStrings[vData]
                                        candidates.add(path)
                                    }
                                }
                            }
                        }
                    }
                    innerPos += ic
                }
            }
            pos += cSize
        }

        return candidates
    }
}
