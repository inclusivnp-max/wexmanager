package org.wexclient.app.appimage

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.wexclient.app.util.withUserEnv
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.wexclient.app.util.startHelper

/** Файл внутри образа: путь от корня, признак папки и цель ссылки. */
class ImageFileEntry(val path: String, val isDir: Boolean, val link: String?)

/** Что известно об AppImage без запуска: заголовок ELF, squashfs и встроенный .desktop. */
class AppImageInfo(
    val path: Path,
    /** 2 — ELF + squashfs, 1 — старый формат (ISO 9660), 0 — не распознан */
    val type: Int,
    val offset: Long,
    val arch: String?,
    val compression: String?,
    val desktopFile: String?,
    val desktop: DesktopEntry?,
    val icon: ByteArray?,
    /** «.png», «.svg» или пусто */
    val iconExt: String,
    val fileCount: Int
) {
    val name: String
        get() = desktop?.localized("Name") ?: AppImages.nameFromFile(path)
    val version: String?
        get() = desktop?.get("X-AppImage-Version")?.takeIf { it.isNotBlank() } ?: AppImages.versionFromFile(path)
    val comment: String?
        get() = desktop?.localized("Comment")
    val categories: List<String>
        get() = desktop?.get("Categories")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val terminal: Boolean
        get() = desktop?.get("Terminal")?.trim().equals("true", ignoreCase = true)
    /** Собран не под этот процессор */
    val foreignArch: Boolean
        get() = arch != null && arch != AppImages.systemArch()
}

/** Установленное приложение: ярлык в меню и файл, на который он указывает. */
class InstalledApp(
    val desktopFile: Path,
    val appImage: Path,
    val icon: Path?,
    val version: String?,
    /** Ярлык указывает именно на этот файл */
    val sameFile: Boolean
)

/**
 * AppImage: сведения, установка в меню (как у Gear Lever: ~/AppImages, иконки в .icons,
 * ярлык в ~/.local/share/applications), запуск и распаковка. Образ не запускается —
 * содержимое читает unsquashfs.
 */
object AppImages {
    private const val SQUASHFS_MAGIC = 0x73717368 // «hsqs»

    fun isAppImageName(name: String) = name.endsWith(".appimage", ignoreCase = true)

    private val home: Path get() = Paths.get(System.getProperty("user.home"))
    val applicationsDir: Path get() = home.resolve(".local/share/applications")

    /** Папка приложений: из настроек Gear Lever, иначе ~/AppImages. */
    fun appImagesDir(): Path {
        val configured = gsetting("appimages-default-folder")?.trim('\'', '"')?.takeIf { it.isNotBlank() }
        return when {
            configured == null -> home.resolve("AppImages")
            configured == "~" -> home
            configured.startsWith("~/") -> home.resolve(configured.substring(2))
            else -> Paths.get(configured)
        }
    }

    /** Gear Lever по умолчанию перемещает файл при установке. */
    fun moveByDefault(): Boolean = gsetting("move-appimage-on-integration") == "true"

    private val gsettings = ConcurrentHashMap<String, String>()

    private fun gsetting(key: String): String? {
        // В тестах — без настоящих настроек пользователя (папка приложений могла бы оказаться настоящей)
        if (System.getProperty("wex.isolated") == "true") return null
        gsettings[key]?.let { return it.ifEmpty { null } }
        val value = runCatching {
            val (code, out) = runTool(listOf("gsettings", "get", "it.mijorus.gearlever", key), timeoutSec = 5)
            if (code == 0) out.decodeToString().trim() else ""
        }.getOrDefault("")
        gsettings[key] = value
        return value.ifEmpty { null }
    }

    fun systemArch(): String = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "AArch64"
        "arm" -> "ARM"
        "x86", "i386", "i686" -> "x86"
        "riscv64" -> "RISC-V"
        else -> System.getProperty("os.arch")
    }

    // ---------- Разбор файла ----------

    fun nameFromFile(path: Path): String {
        val base = path.fileName.toString().replace(Regex("(?i)\\.appimage$"), "")
        return base.replace(Regex("(?i)[-_ ]?(x86[-_]64|amd64|aarch64|arm64|armhf|i[36]86)"), "")
            .replace(Regex("[-_ ]v?\\d+(\\.\\d+)+.*$"), "")
            .ifBlank { base }
    }

    fun versionFromFile(path: Path): String? =
        Regex("(\\d+(?:\\.\\d+)+)").find(path.fileName.toString())?.groupValues?.get(1)

    private fun machineName(machine: Int): String? = when (machine) {
        3 -> "x86"
        62 -> "x86-64"
        40 -> "ARM"
        183 -> "AArch64"
        243 -> "RISC-V"
        else -> null
    }

    private class Header(val elf64: Boolean, val order: ByteOrder, val type: Int, val machine: Int, val sectionsEnd: Long)

    private fun header(head: ByteArray): Header? {
        if (head.size < 64 || head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return null
        val order = if (head[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val bb = ByteBuffer.wrap(head).order(order)
        val elf64 = head[4].toInt() == 2
        val end = if (elf64) {
            bb.getLong(0x28) + (bb.getShort(0x3A).toInt() and 0xFFFF).toLong() * (bb.getShort(0x3C).toInt() and 0xFFFF)
        } else {
            (bb.getInt(0x20).toLong() and 0xFFFFFFFFL) + (bb.getShort(0x2E).toInt() and 0xFFFF).toLong() * (bb.getShort(0x30).toInt() and 0xFFFF)
        }
        // «AI» + версия — в свободных байтах e_ident
        val type = if (head[8] == 'A'.code.toByte() && head[9] == 'I'.code.toByte()) head[10].toInt() else 0
        return Header(elf64, order, type, bb.getShort(0x12).toInt() and 0xFFFF, end)
    }

    /** Начало squashfs: сразу за таблицей секций ELF, иначе — поиск сигнатуры в первых мегабайтах. */
    private fun squashfsOffset(path: Path, h: Header): Long? {
        fun isSquash(at: Long): Boolean = runCatching {
            Files.newByteChannel(path).use { ch ->
                if (at < 0 || at + 32 > ch.size()) return@use false
                val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                ch.position(at)
                while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                buf.getInt(0) == SQUASHFS_MAGIC && buf.getShort(28).toInt() == 4
            }
        }.getOrDefault(false)
        if (isSquash(h.sectionsEnd)) return h.sectionsEnd
        Files.newInputStream(path).use { input ->
            val window = input.readNBytes(8 shl 20)
            val bb = ByteBuffer.wrap(window).order(ByteOrder.LITTLE_ENDIAN)
            var i = 64
            while (i + 32 <= window.size) {
                if (bb.getInt(i) == SQUASHFS_MAGIC && bb.getShort(i + 28).toInt() == 4) return i.toLong()
                i++
            }
        }
        return null
    }

    private fun compressionAt(path: Path, offset: Long): String? = runCatching {
        Files.newByteChannel(path).use { ch ->
            val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            ch.position(offset)
            while (buf.hasRemaining() && ch.read(buf) > 0) Unit
            when (buf.getShort(20).toInt()) {
                1 -> "gzip"
                2 -> "lzma"
                3 -> "lzo"
                4 -> "xz"
                5 -> "lz4"
                6 -> "zstd"
                else -> null
            }
        }
    }.getOrNull()

    fun inspect(path: Path): AppImageInfo {
        if (path.fileSystem !== FileSystems.getDefault()) throw IOException("AppImage внутри архива: сначала извлеките его")
        if (!Files.isRegularFile(path)) throw IOException("Это не обычный файл")
        val head = Files.newInputStream(path).use { it.readNBytes(64) }
        val h = header(head) ?: throw IOException("Не ELF-файл: это не AppImage")
        val arch = machineName(h.machine)
        if (h.type == 1) {
            return AppImageInfo(path, 1, 0, arch, "ISO 9660", null, null, null, "", 0)
        }
        val offset = squashfsOffset(path, h)
            ?: return AppImageInfo(path, 0, 0, arch, null, null, null, null, "", 0)
        val listing = list(path, offset)
        val desktopFile = listing.filter { !it.isDir && '/' !in it.path && it.path.endsWith(".desktop") }.minByOrNull { it.path }?.path
        val desktop = desktopFile?.let { f -> runCatching { DesktopEntry.parse(cat(path, offset, f).decodeToString()) }.getOrNull() }
        val (icon, ext) = findIcon(path, offset, listing, desktop)
        return AppImageInfo(path, 2, offset, arch, compressionAt(path, offset), desktopFile, desktop, icon, ext, listing.count { !it.isDir })
    }

    private val lsLine = Regex("^\\S{10}\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+squashfs-root(?:/(.*?))?(?: -> (.*))?$")

    fun list(path: Path, offset: Long): List<ImageFileEntry> {
        val (code, out) = runTool(listOf("unsquashfs", "-o", offset.toString(), "-lls", path.toString()))
        if (code == 1) throw IOException("unsquashfs не смог прочитать образ")
        return out.decodeToString().lineSequence().mapNotNull { line ->
            val m = lsLine.find(line) ?: return@mapNotNull null
            val p = m.groupValues[1]
            if (p.isEmpty()) return@mapNotNull null
            ImageFileEntry(p, line.startsWith("d"), m.groups[2]?.value)
        }.toList()
    }

    /** Содержимое файла образа (ссылки unsquashfs разыменовывает сам). */
    fun cat(path: Path, offset: Long, inside: String): ByteArray {
        // Значки и .desktop — килобайты
        val (code, out) = runTool(listOf("unsquashfs", "-o", offset.toString(), "-cat", path.toString(), inside), maxOutput = 16 shl 20)
        if (code == 1 || out.isEmpty()) throw IOException("Нет файла «$inside» в образе")
        return out
    }

    fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()

    fun isSvg(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.UTF_8).trimStart()
        return head.startsWith("<") && head.contains("<svg")
    }

    private fun findIcon(path: Path, offset: Long, listing: List<ImageFileEntry>, desktop: DesktopEntry?): Pair<ByteArray?, String> {
        val present = listing.map { it.path }.toHashSet()
        val iconName = desktop?.get("Icon")?.substringAfterLast('/')?.replace(Regex("\\.(png|svg|svgz|xpm)$"), "")
        val candidates = ArrayList<String>()
        if (!iconName.isNullOrBlank()) candidates += listOf("$iconName.png", "$iconName.svg")
        candidates += ".DirIcon"
        if (!iconName.isNullOrBlank()) {
            fun sizeOf(p: String): Int = Regex("/(\\d+)x\\d+/").find(p)?.groupValues?.get(1)?.toIntOrNull()
                ?: if ("/scalable/" in p) 100_000 else 0
            candidates += listing.map { it.path }
                .filter { it.startsWith("usr/share/icons/") && it.substringAfterLast('/').substringBeforeLast('.') == iconName }
                .sortedByDescending(::sizeOf)
            candidates += listing.map { it.path }
                .filter { it.startsWith("usr/share/pixmaps/") && it.substringAfterLast('/').substringBeforeLast('.') == iconName }
        }
        for (c in candidates.distinct()) {
            if (c !in present) continue
            val bytes = runCatching { cat(path, offset, c) }.getOrNull() ?: continue
            when {
                isPng(bytes) -> return bytes to ".png"
                isSvg(bytes) -> return bytes to ".svg"
            }
        }
        return null to ""
    }

    // ---------- Иконки ----------

    /** PNG или SVG → картинка; SVG растеризуется в [sizePx]. */
    fun iconBitmap(bytes: ByteArray, sizePx: Int): ImageBitmap? = try {
        if (isSvg(bytes)) {
            val painter = loadSvgPainter(bytes.inputStream(), Density(1f))
            val bitmap = ImageBitmap(sizePx, sizePx)
            val size = Size(sizePx.toFloat(), sizePx.toFloat())
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(painter) { draw(size) }
            }
            bitmap
        } else {
            org.wexclient.app.util.ImageScale.decodeScaled(bytes, sizePx)
        }
    } catch (_: Throwable) {
        null
    }

    private val iconCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }
    private val noIcon = ConcurrentHashMap.newKeySet<String>()
    // Папка с десятками AppImage — не больше двух unsquashfs одновременно
    private val iconPermits = Semaphore(2)

    private fun iconKey(path: Path, lastModified: Long) = "$path:$lastModified"

    fun cachedIcon(path: Path, lastModified: Long): ImageBitmap? = synchronized(iconCache) { iconCache[iconKey(path, lastModified)] }

    /** Иконка для списка файлов: сначала .DirIcon (один вызов), иначе полный разбор. */
    suspend fun loadIcon(path: Path, lastModified: Long): ImageBitmap? {
        val key = iconKey(path, lastModified)
        cachedIcon(path, lastModified)?.let { return it }
        if (key in noIcon) return null
        return iconPermits.withPermit {
            // Пока ждали очереди, иконку могла достать другая строка
            cachedIcon(path, lastModified)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                val bitmap = try {
                    loadIconBlocking(path)
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                if (bitmap != null) synchronized(iconCache) { iconCache[key] = bitmap } else noIcon.add(key)
                bitmap
            }
        }
    }

    private fun loadIconBlocking(path: Path): ImageBitmap? {
        if (path.fileSystem !== FileSystems.getDefault() || !Files.isRegularFile(path)) return null
        val head = Files.newInputStream(path).use { it.readNBytes(64) }
        val h = header(head) ?: return null
        val offset = squashfsOffset(path, h) ?: return null
        val quick = runCatching { cat(path, offset, ".DirIcon") }.getOrNull()
            ?.takeIf { isPng(it) || isSvg(it) }
        val bytes = quick ?: inspect(path).icon
        return bytes?.let { iconBitmap(it, 64) }
    }

    // ---------- Установка ----------

    /** Ярлык для этого файла или для другой версии того же приложения. */
    fun installedFor(info: AppImageInfo): InstalledApp? {
        val target = info.path.toAbsolutePath().normalize()
        val entries = desktopEntries()
        entries.firstOrNull { it.second.tryExec() == target }?.let { (file, entry) -> return toInstalled(file, entry, true) }
        // Gear Lever пишет в X-AppImage-Name локализованное имя — сравниваем с обоими
        val names = setOfNotNull(info.desktop?.get("Name"), info.name)
        return entries.firstOrNull { (_, entry) ->
            val exec = entry.tryExec() ?: return@firstOrNull false
            val appName = entry.get("X-AppImage-Name") ?: entry.get("Name")
            appName in names && exec.fileName.toString().endsWith(".appimage", ignoreCase = true) && Files.isRegularFile(exec)
        }?.let { (file, entry) -> toInstalled(file, entry, false) }
    }

    private fun DesktopEntry.tryExec(): Path? =
        get("TryExec")?.takeIf { it.startsWith("/") }?.let { runCatching { Paths.get(it).normalize() }.getOrNull() }

    private fun toInstalled(file: Path, entry: DesktopEntry, same: Boolean) = InstalledApp(
        desktopFile = file,
        appImage = entry.tryExec()!!,
        icon = entry.get("Icon")?.takeIf { it.startsWith("/") }?.let { Paths.get(it) },
        version = entry.get("X-AppImage-Version"),
        sameFile = same
    )

    private fun desktopEntries(): List<Pair<Path, DesktopEntry>> {
        val dir = applicationsDir
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { it.fileName.toString().endsWith(".desktop") && Files.isRegularFile(it) }.toList()
        }.mapNotNull { f ->
            runCatching { f to DesktopEntry.parse(Files.readString(f)) }.getOrNull()
        }
    }

    /** Имя файла, как у Gear Lever: имя приложения, пробелы → «_», без спецсимволов, строчными. */
    fun baseName(appName: String): String {
        val cleaned = Regex("(?U)[^\\w._]+").replace(appName.lowercase().replace(' ', '_'), "").trim('.', '_')
        return cleaned.ifEmpty { "appimage" }
    }

    private fun uniqueBase(dir: Path, base: String, version: String?): String {
        fun free(b: String) = !Files.exists(dir.resolve("$b.appimage")) && !Files.exists(applicationsDir.resolve("$b.desktop"))
        if (free(base)) return base
        if (!version.isNullOrBlank()) {
            val withVersion = baseName("${base}_${version.replace('.', '_')}")
            if (free(withVersion)) return withVersion
        }
        var i = 1
        while (!free("${base}_$i")) i++
        return "${base}_$i"
    }

    /**
     * Установка: файл в папку приложений (копия или перенос), иконка, ярлык в меню.
     * [replace] — обновление уже установленной версии (тот же ярлык и имя файла).
     */
    fun install(info: AppImageInfo, move: Boolean, replace: InstalledApp? = null): InstalledApp {
        val folder = appImagesDir()
        Files.createDirectories(folder)
        Files.createDirectories(applicationsDir)
        val source = info.path.toAbsolutePath().normalize()

        val base: String
        val target: Path
        val desktopFile: Path
        if (replace != null) {
            desktopFile = replace.desktopFile
            base = desktopFile.fileName.toString().removeSuffix(".desktop")
            target = replace.appImage
        } else {
            base = uniqueBase(folder, baseName(info.name), info.version)
            target = folder.resolve("$base.appimage")
            desktopFile = applicationsDir.resolve("$base.desktop")
        }

        if (target != source) {
            // Через временный файл: прерванное копирование не оставит полуфайл под рабочим именем
            val part = target.resolveSibling(".${target.fileName}.part")
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(part)
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } else {
            makeExecutable(target)
        }

        replace?.icon?.let { old -> if (old.startsWith(folder.resolve(".icons"))) Files.deleteIfExists(old) }
        val icon = info.icon?.let { bytes ->
            val icons = folder.resolve(".icons")
            Files.createDirectories(icons)
            icons.resolve(base + info.iconExt).also { Files.write(it, bytes) }
        }

        val entry = info.desktop?.let { DesktopEntry.parse(it.text()) } ?: DesktopEntry.create(info.name)
        val exec = DesktopEntry.escape(DesktopEntry.quoteExecArg(target.toString()))
        val args = entry.raw("Exec")?.let(DesktopEntry::execArguments).orEmpty()
        entry.setRaw("Exec", "env DESKTOPINTEGRATION=1 $exec" + if (args.isNotEmpty()) " $args" else "")
        entry.set("TryExec", target.toString())
        entry.set("Icon", icon?.toString() ?: "application-x-executable")
        for (group in entry.groups.filter { it.startsWith("Desktop Action ") }) {
            val actionArgs = entry.raw("Exec", group)?.let(DesktopEntry::execArguments).orEmpty()
            entry.setRaw("Exec", exec + if (actionArgs.isNotEmpty()) " $actionArgs" else "", group)
            entry.set("Icon", icon?.toString() ?: "application-x-executable", group)
        }
        info.version?.let { entry.set("X-AppImage-Version", it) }
        entry.set("X-AppImage-Name", info.desktop?.get("Name") ?: info.name)

        val part = desktopFile.resolveSibling(".${desktopFile.fileName}.part")
        Files.writeString(part, entry.text())
        Files.move(part, desktopFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        if (move && source != target) Files.deleteIfExists(source)
        refreshMenus()
        synchronized(iconCache) { iconCache.keys.removeIf { it.startsWith("$target:") } }
        return InstalledApp(desktopFile, target, icon, info.version, sameFile = true)
    }

    /** Убрать из меню; файл удаляется, только если он лежит в папке приложений. */
    fun uninstall(app: InstalledApp): Boolean {
        val folder = appImagesDir()
        Files.deleteIfExists(app.desktopFile)
        app.icon?.let { if (it.startsWith(folder.resolve(".icons"))) Files.deleteIfExists(it) }
        val deleteFile = app.appImage.startsWith(folder)
        if (deleteFile) Files.deleteIfExists(app.appImage)
        refreshMenus()
        return deleteFile
    }

    private fun refreshMenus() {
        runCatching { runTool(listOf("update-desktop-database", "-q", applicationsDir.toString()), timeoutSec = 15) }
    }

    fun makeExecutable(path: Path) {
        val perms = Files.getPosixFilePermissions(path)
        val add = buildSet {
            add(PosixFilePermission.OWNER_EXECUTE)
            if (PosixFilePermission.GROUP_READ in perms) add(PosixFilePermission.GROUP_EXECUTE)
            if (PosixFilePermission.OTHERS_READ in perms) add(PosixFilePermission.OTHERS_EXECUTE)
        }
        if (!perms.containsAll(add)) Files.setPosixFilePermissions(path, perms + add)
    }

    // ---------- Запуск и распаковка ----------

    private fun fuse2Available(): Boolean = listOf(
        "/usr/lib/libfuse.so.2", "/usr/lib64/libfuse.so.2", "/lib/x86_64-linux-gnu/libfuse.so.2",
        "/usr/lib/x86_64-linux-gnu/libfuse.so.2", "/lib/aarch64-linux-gnu/libfuse.so.2"
    ).any { File(it).exists() }

    /** Отдельный процесс с окружением пользователя; без libfuse2 — распаковка во время запуска. */
    fun launch(path: Path) {
        makeExecutable(path)
        val pb = ProcessBuilder(path.toString())
            .directory(path.parent?.toFile())
            .withUserEnv()
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        if (!fuse2Available()) pb.environment()["APPIMAGE_EXTRACT_AND_RUN"] = "1"
        pb.start()
    }

    /** Содержимое образа — в новую папку рядом; возвращает её имя. */
    fun extract(info: AppImageInfo, parent: Path): Path {
        val base = info.path.fileName.toString().replace(Regex("(?i)\\.appimage$"), "")
        var dest = parent.resolve(base)
        var i = 2
        while (Files.exists(dest)) dest = parent.resolve("$base ($i)").also { i++ }
        val cmd = when (info.type) {
            2 -> listOf("unsquashfs", "-o", info.offset.toString(), "-d", dest.toString(), "-no-progress", "-quiet", info.path.toString())
            1 -> {
                Files.createDirectories(dest)
                listOf("bsdtar", "-xf", info.path.toString(), "-C", dest.toString())
            }
            else -> throw IOException("Формат образа не распознан")
        }
        val (code, out) = runTool(cmd, timeoutSec = 600)
        // 2 — некритичные ошибки (xattr, права): содержимое распаковано
        if (code != 0 && code != 2) {
            throw IOException("Не удалось распаковать: ${out.decodeToString().lineSequence().lastOrNull { it.isNotBlank() } ?: "код $code"}")
        }
        return dest
    }

    // ---------- Вспомогательное ----------

    /**
     * Команда с таймаутом: stdout целиком (не больше [maxOutput] — образ чужой: «.DirIcon» может оказаться
     * ссылкой на гигабайтный файл или сжатой «бомбой», а значки читаются уже при просмотре папки), stderr — туда же.
     */
    internal fun runTool(cmd: List<String>, timeoutSec: Long = 60, maxOutput: Int = 64 shl 20): Pair<Int, ByteArray> {
        val process = ProcessBuilder(cmd).redirectErrorStream(cmd.first() != "unsquashfs").startHelper()
        if (cmd.first() == "unsquashfs") {
            // Ошибки unsquashfs не должны попадать в -cat
            Thread { runCatching { process.errorStream.readAllBytes() } }.apply { isDaemon = true }.start()
        }
        var out = ByteArray(0)
        val tooBig = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            out = runCatching {
                val bytes = process.inputStream.readNBytes(maxOutput + 1)
                if (bytes.size > maxOutput) {
                    tooBig.set(true)
                    process.destroyForcibly()
                    ByteArray(0)
                } else bytes
            }.getOrDefault(ByteArray(0))
        }.apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("${cmd.first()}: нет ответа за $timeoutSec с")
        }
        reader.join(10_000)
        if (tooBig.get()) throw IOException("${cmd.first()}: вывод больше ${maxOutput shr 20} МБ")
        return process.exitValue() to out
    }
}
