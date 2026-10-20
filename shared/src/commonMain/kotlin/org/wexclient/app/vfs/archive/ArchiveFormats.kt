package org.wexclient.app.vfs.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import org.wexclient.app.util.ProcessRunner
import org.wexclient.app.util.startHelper

/** Сжатие поверх tar или одиночного файла. Всё, кроме gzip, — через системные утилиты. */
enum class Compression(val label: String, private val command: List<String>?, val packageName: String?) {
    NONE("TAR", null, null),
    GZIP("GZ", null, null),
    BZIP2("BZ2", listOf("bzip2"), "bzip2"),
    XZ("XZ", listOf("xz", "-T0"), "xz"),
    ZSTD("ZST", listOf("zstd", "-q", "-T0"), "zstd"),
    LZ4("LZ4", listOf("lz4", "-q"), "lz4"),
    LZMA("LZMA", listOf("xz", "--format=lzma"), "xz");

    val tool: String? get() = command?.first()

    internal fun decompressCommand(): List<String> = command!! + "-dc"

    internal fun compressCommand(): List<String> = command!! + "-c"

    companion object {
        /** По сигнатуре; у lzma её нет — только по имени. */
        fun detect(head: ByteArray): Compression? {
            fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { (head[it].toInt() and 0xff) == b[it] }
            return when {
                starts(0x1f, 0x8b) -> GZIP
                starts(0x42, 0x5a, 0x68) -> BZIP2
                starts(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00) -> XZ
                starts(0x28, 0xb5, 0x2f, 0xfd) -> ZSTD
                starts(0x04, 0x22, 0x4d, 0x18) -> LZ4
                else -> null
            }
        }
    }
}

/** Архивы семейства tar и одиночные сжатые файлы, которые открываются как папка. */
object ArchiveFormats {
    /** [tar] = false — одиночный сжатый файл (dump.sql.gz). */
    data class Kind(val compression: Compression, val tar: Boolean, val suffix: String) {
        val label: String
            get() = when {
                tar && compression == Compression.NONE -> "TAR"
                tar -> "TAR." + compression.label
                else -> compression.label
            }
    }

    // Длинные суффиксы раньше коротких: .tar.gz важнее .gz
    private val suffixes: List<Kind> = listOf(
        Kind(Compression.GZIP, true, ".tar.gz"),
        Kind(Compression.BZIP2, true, ".tar.bz2"),
        Kind(Compression.XZ, true, ".tar.xz"),
        Kind(Compression.ZSTD, true, ".tar.zstd"),
        Kind(Compression.ZSTD, true, ".tar.zst"),
        Kind(Compression.LZ4, true, ".tar.lz4"),
        Kind(Compression.LZMA, true, ".tar.lzma"),
        Kind(Compression.GZIP, true, ".tgz"),
        Kind(Compression.GZIP, true, ".taz"),
        Kind(Compression.BZIP2, true, ".tbz2"),
        Kind(Compression.BZIP2, true, ".tbz"),
        Kind(Compression.BZIP2, true, ".tb2"),
        Kind(Compression.XZ, true, ".txz"),
        Kind(Compression.ZSTD, true, ".tzst"),
        Kind(Compression.LZMA, true, ".tlz"),
        Kind(Compression.NONE, true, ".tar"),
        Kind(Compression.GZIP, false, ".gz"),
        Kind(Compression.BZIP2, false, ".bz2"),
        Kind(Compression.XZ, false, ".xz"),
        Kind(Compression.ZSTD, false, ".zstd"),
        Kind(Compression.ZSTD, false, ".zst"),
        Kind(Compression.LZ4, false, ".lz4"),
        Kind(Compression.LZMA, false, ".lzma")
    )

    fun kindOf(fileName: String): Kind? {
        val lower = fileName.lowercase()
        return suffixes.firstOrNull { lower.length > it.suffix.length && lower.endsWith(it.suffix) }
    }

    fun isTarFamily(fileName: String): Boolean = kindOf(fileName) != null

    private val zipLike = setOf("zip", "apk", "jar", "xapk", "apks", "aab", "aar", "apkm")

    /** Архив, в который можно зайти как в папку. */
    fun isBrowsable(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in zipLike || isTarFamily(fileName)

    /** Имя файла внутри одиночного сжатого: dump.sql.gz → dump.sql, a.tgz → a.tar. */
    fun innerName(fileName: String): String {
        val kind = kindOf(fileName) ?: return fileName
        val base = fileName.dropLast(kind.suffix.length)
        return when (kind.suffix) {
            ".tgz", ".taz", ".tbz2", ".tbz", ".tb2", ".txz", ".tzst", ".tlz" -> "$base.tar"
            else -> base
        }
    }

    /** Подпись архива для панели: TAR.GZ, ZIP, APK… */
    fun label(fileName: String): String =
        kindOf(fileName)?.label ?: fileName.substringAfterLast('.', "").uppercase().ifEmpty { "ZIP" }

    /** Есть ли утилита в PATH; на Windows — с .exe/.bat/.cmd (см. [ProcessRunner.which]). */
    fun toolAvailable(tool: String): Boolean = ProcessRunner.isAvailable(tool)

    internal fun requireTool(c: Compression) {
        val tool = c.tool ?: return
        if (!toolAvailable(tool)) {
            throw IOException("Для ${c.label} нужна утилита «$tool» (пакет ${c.packageName})")
        }
    }

    /**
     * Распаковка [source] (любая ФС) в [target] на диске; [progress] — прочитано сжатых байт.
     * [cancelled] прерывает распаковку; диск целиком она не займёт (см. [DiskGuard]).
     */
    internal fun decompress(
        c: Compression,
        source: Path,
        target: Path,
        progress: ((Long) -> Unit)?,
        cancelled: (() -> Boolean)? = null
    ) {
        val guard = DiskGuard(target, cancelled)
        when (c) {
            Compression.NONE -> Files.newInputStream(source).use { input ->
                Files.newOutputStream(target).use { copy(input, it, progress, guard) }
            }
            Compression.GZIP -> {
                val counting = CountingInput(Files.newInputStream(source), progress)
                // GZIPInputStream читает и склеенные gzip-потоки (pigz, cat a.gz b.gz)
                GZIPInputStream(counting.buffered(1 shl 16), 1 shl 16).use { input ->
                    Files.newOutputStream(target).use { copy(input, it, null, guard) }
                }
                progress?.invoke(counting.count)
            }
            else -> {
                requireTool(c)
                runTool(c.decompressCommand(), source, target, progress, guard)
            }
        }
    }

    /** Порог свободного места для [DiskGuard] вместо вычисленного (только для тестов). */
    @Volatile
    internal var floorForTests: Long? = null

    /** Распаковка прервана: отменили или кончается место. Остальные ошибки утилиты — обычные [IOException]. */
    internal class Aborted(message: String) : java.io.InterruptedIOException(message)

    /**
     * «Архивная бомба» в пару килобайт разворачивается в терабайты: распаковку останавливаем,
     * когда свободного места остаётся меньше min(2 ГБ, четверть свободного на старте).
     */
    internal class DiskGuard(target: Path, private val cancelled: (() -> Boolean)?) {
        private val store = runCatching { Files.getFileStore(target.toAbsolutePath().parent) }.getOrNull()
        private val floor: Long = floorForTests
            ?: store?.let { s -> runCatching { minOf(2L shl 30, s.usableSpace / 4) }.getOrNull() } ?: 0L
        private var lastSpaceCheck = 0L

        fun check() {
            if (cancelled?.invoke() == true) throw Aborted("Открытие отменено")
            val s = store ?: return
            val now = System.nanoTime()
            if (now - lastSpaceCheck < 100_000_000L) return
            lastSpaceCheck = now
            val free = runCatching { s.usableSpace }.getOrNull() ?: return
            if (free < floor) {
                throw Aborted("Недостаточно места на диске: содержимое архива не помещается (осталось ${free shr 20} МБ)")
            }
        }
    }

    internal fun compress(c: Compression, source: Path, target: Path) {
        when (c) {
            Compression.NONE -> Files.newInputStream(source).use { input ->
                Files.newOutputStream(target).use { copy(input, it, null) }
            }
            Compression.GZIP -> Files.newInputStream(source).use { input ->
                GZIPOutputStream(Files.newOutputStream(target).buffered(1 shl 16), 1 shl 16).use { copy(input, it, null) }
            }
            else -> {
                requireTool(c)
                runTool(c.compressCommand(), source, target, null)
            }
        }
    }

    private fun runTool(cmd: List<String>, source: Path, target: Path, progress: ((Long) -> Unit)?, guard: DiskGuard? = null) {
        val process = try {
            ProcessBuilder(cmd).redirectOutput(target.toFile()).startHelper()
        } catch (e: IOException) {
            throw IOException("Не удалось запустить ${cmd.first()}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        var stderr = ""
        val errReader = thread(isDaemon = true, name = "wex-${cmd.first()}-stderr") {
            stderr = runCatching { process.errorStream.readBytes().decodeToString() }.getOrDefault("")
        }
        var pumpError: IOException? = null
        try {
            process.outputStream.use { stdin ->
                Files.newInputStream(source).use { input -> copy(input, stdin, progress, guard) }
            }
        } catch (e: Aborted) {
            process.destroyForcibly()
            throw e
        } catch (e: IOException) {
            // Утилита завершилась раньше (битые данные) — причина будет в коде выхода
            pumpError = e
        }
        // Вход передан, а утилита ещё пишет: маленький архив может разворачиваться долго и много
        val deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(6)
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            try {
                guard?.check()
                if (System.nanoTime() > deadline) throw IOException("${cmd.first()}: не завершился")
            } catch (e: IOException) {
                process.destroyForcibly()
                throw e
            }
        }
        errReader.join(2000)
        val code = process.exitValue()
        if (code != 0) {
            val reason = stderr.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "код выхода $code"
            throw IOException("${cmd.first()}: $reason")
        }
        pumpError?.let { throw it }
    }

    internal fun copy(input: InputStream, output: OutputStream, progress: ((Long) -> Unit)?, guard: DiskGuard? = null): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        var reported = 0L
        var checked = 0L
        guard?.check()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            if (progress != null && total - reported >= (1 shl 20)) {
                reported = total
                progress(total)
            }
            if (guard != null && total - checked >= (4 shl 20)) {
                checked = total
                guard.check()
            }
        }
        progress?.invoke(total)
        return total
    }

    private class CountingInput(private val inner: InputStream, private val progress: ((Long) -> Unit)?) : InputStream() {
        var count = 0L
            private set
        private var reported = 0L
        private fun add(n: Int) {
            if (n <= 0) return
            count += n
            if (progress != null && count - reported >= (1 shl 20)) {
                reported = count
                progress(count)
            }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { add(it) }
        override fun close() = inner.close()
    }
}

/**
 * Временные файлы распакованных архивов: ~/.cache/wexdesktop/archives/<процесс> (только для владельца).
 * Остатки после аварийного выхода убираются при следующем запуске — но не файлы другой работающей копии.
 */
internal object ArchiveCache {
    private val scratch = org.wexclient.app.util.ProcessScratch {
        org.wexclient.app.util.AppDirs.cache.resolve("archives")
    }

    internal val dir: Path by lazy {
        val d = scratch.dir
        // Прежние версии клали файлы прямо в archives/ — такие старше суток
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        runCatching {
            Files.newDirectoryStream(d.parent, "wex-*").use { stream ->
                for (f in stream) {
                    runCatching {
                        if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                            Files.getLastModifiedTime(f).toMillis() < cutoff
                        ) Files.deleteIfExists(f)
                    }
                }
            }
        }
        d
    }

    fun newTempFile(suffix: String): Path = Files.createTempFile(dir, "wex-", suffix)

    /**
     * Убирает временные файлы архивов, брошенных незакрытыми: их ФС уже забрал сборщик мусора
     * (см. VfsManager.releaseIdle). Закрытые обычным путём убирают за собой сами.
     */
    internal val cleaner: org.wexclient.app.util.RefCleaner by lazy { org.wexclient.app.util.RefCleaner("wex-archive-cleaner") }
}
