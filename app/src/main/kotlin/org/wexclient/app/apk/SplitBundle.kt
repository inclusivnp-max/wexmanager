package org.wexclient.app.apk

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import org.wexclient.app.signing.ApkSignerEngine
import org.wexclient.app.signing.SignResult
import org.wexclient.app.signing.SignSchemes
import org.wexclient.app.signing.SigningKey
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Что за часть набора: основной APK, библиотеки под процессор, картинки под плотность экрана… */
enum class BundlePartKind(val title: String) {
    BASE("основной"),
    ABI("процессор"),
    DENSITY("плотность экрана"),
    LANGUAGE("язык"),
    ASSET_PACK("пакет ресурсов"),
    FEATURE("модуль")
}

/** Часть набора: запись в архиве, её размер и вид. [label] — коротко для людей: «arm64-v8a», «xxhdpi», «ru». */
data class BundlePart(val entryName: String, val size: Long, val kind: BundlePartKind, val label: String)

data class BundleInfo(
    val parts: List<BundlePart>,
    /** OBB-файлы XAPK: в один APK они не входят. */
    val obbFiles: Int,
    val packageName: String?
) {
    val totalApkBytes: Long get() = parts.sumOf { it.size }
    val base: BundlePart? get() = parts.firstOrNull { it.kind == BundlePartKind.BASE }

    /** «основной + 5: arm64-v8a, xxhdpi, en, ru, пакет ресурсов». */
    fun summary(max: Int = 6): String {
        val splits = parts.filter { it.kind != BundlePartKind.BASE }
        if (splits.isEmpty()) return "только основной APK"
        val names = splits.take(max).joinToString(", ") { it.label }
        val more = if (splits.size > max) ", ещё ${splits.size - max}" else ""
        return (if (base != null) "основной + " else "") + "${splits.size}: $names$more"
    }
}

/** Ход работы: этап и, если известно, сколько сделано из скольких. */
data class BundleProgress(val stage: String, val done: Long = 0, val total: Long = 0)

data class BundleConvertResult(
    val output: Path,
    val parts: Int,
    val obbSkipped: Int,
    val size: Long,
    val sign: SignResult
)

/**
 * Наборы split-APK: .apks (SAI, bundletool), .xapk (APKPure, APKCombo), .apkm (APKMirror).
 * Подпись — каждой части одним ключом (иначе Android не поставит набор), конвертация —
 * слияние частей в один APK через ARSCLib, как в APKEditor: таблицы ресурсов объединяются,
 * из манифеста уходят признаки split, результат подписывается заново.
 */
object SplitBundle {

    private val EXTENSIONS = listOf(".apks", ".xapk", ".apkm")
    private val ABIS = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val DENSITIES = setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi")

    fun isBundle(name: String): Boolean {
        val lower = name.lowercase()
        return EXTENSIONS.any { lower.endsWith(it) }
    }

    /** Состав набора по оглавлению архива: сами APK не распаковываются. */
    fun inspect(path: Path): BundleInfo {
        ZipFile(path.toFile()).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val apks = names.filter { it.name.lowercase().endsWith(".apk") }
            val parts = apks.map { e -> part(e.name, e.size.coerceAtLeast(0)) }
            // Основного по имени не нашли (XAPK называет его по пакету) — это единственная часть не из config.*
            val fixed = if (parts.none { it.kind == BundlePartKind.BASE }) {
                val candidates = parts.filter { it.kind == BundlePartKind.FEATURE }
                if (candidates.size == 1) parts.map { if (it === candidates[0]) it.copy(kind = BundlePartKind.BASE, label = BundlePartKind.BASE.title) else it }
                else parts
            } else parts
            val obb = names.count { it.name.lowercase().endsWith(".obb") }
            val pkg = runCatching { ApkAnalyzer.bundlePackageName(path) }.getOrNull()
            return BundleInfo(fixed.sortedWith(compareBy({ it.kind.ordinal }, { it.label })), obb, pkg)
        }
    }

    /** Вид части по имени: config.arm64_v8a-69252.apk, split_config.xxhdpi.apk, splits/base-ru.apk… */
    internal fun part(entryName: String, size: Long): BundlePart {
        val file = entryName.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")
        val lower = file.lowercase()
        // Версия в хвосте имени (APKCombo): «config.ru-69252» → «config.ru»
        val clean = lower.replace(Regex("-\\d+$"), "")
        val tail = when {
            clean.startsWith("split_config.") -> clean.removePrefix("split_config.")
            clean.startsWith("config.") -> clean.removePrefix("config.")
            clean.startsWith("base-") -> clean.removePrefix("base-")
            else -> ""
        }
        val kind = when {
            clean == "base" || clean == "base-master" || clean == "master" -> BundlePartKind.BASE
            "asset_pack" in clean || "assetpack" in clean || entryName.startsWith("asset-slices/") -> BundlePartKind.ASSET_PACK
            tail in ABIS -> BundlePartKind.ABI
            tail in DENSITIES -> BundlePartKind.DENSITY
            tail.matches(Regex("[a-z]{2,3}([_-][a-z0-9]{2,8})?")) -> BundlePartKind.LANGUAGE
            else -> BundlePartKind.FEATURE
        }
        val label = when (kind) {
            BundlePartKind.BASE -> kind.title
            BundlePartKind.ABI -> tail.replace('_', '-')
            BundlePartKind.DENSITY, BundlePartKind.LANGUAGE -> tail
            BundlePartKind.ASSET_PACK -> kind.title
            BundlePartKind.FEATURE -> file
        }
        return BundlePart(entryName, size, kind, label)
    }

    /**
     * Какие части сливать. У bundletool рядом со split лежат «standalones/» — это целые APK
     * для старых Android, их с частями смешивать нельзя.
     */
    internal fun mergeable(info: BundleInfo): List<BundlePart> {
        val splitsDir = info.parts.filter { it.entryName.startsWith("splits/") }
        return if (splitsDir.isNotEmpty()) splitsDir + info.parts.filter { it.entryName.startsWith("asset-slices/") }
        else info.parts.filter { !it.entryName.startsWith("standalones/") && !it.entryName.startsWith("instant/") }
    }

    /**
     * Подписать все APK набора ключом [key]. [output] может совпадать с [input]: новый архив пишется
     * рядом и заменяет исходный одним переименованием. Остальное (manifest.json, icon.png, OBB) — как было.
     */
    fun sign(
        input: Path,
        output: Path,
        key: SigningKey,
        schemes: SignSchemes,
        cancelled: () -> Boolean = { false },
        onProgress: (BundleProgress) -> Unit = {}
    ): SignResult {
        // V4 — отдельный файл .idsig рядом с APK: у частей внутри архива ему негде лежать
        val partSchemes = schemes.copy(v4 = false)
        require(partSchemes.isValid) { "Выберите схему подписи V1, V2 или V3" }
        val target = output.toAbsolutePath()
        val dir = target.parent ?: throw IOException("Нет папки для ${target.fileName}")
        val work = org.wexclient.app.util.ProcessScratch.work.newDir("apks-sign-")
        val tmp = Files.createTempFile(dir, ".wex-bundle-", ".tmp")
        try {
            val problems = ArrayList<String>()
            var signedParts = 0
            ZipFile(input.toFile()).use { zip ->
                val entries = zip.entries().toList()
                val total = entries.sumOf { it.size.coerceAtLeast(0) }
                var done = 0L
                ZipOutputStream(BufferedOutputStream(Files.newOutputStream(tmp), 1 shl 16)).use { out ->
                    val seen = HashSet<String>()
                    for (e in entries) {
                        if (cancelled()) throw CancellationException("Остановлено")
                        if (!seen.add(e.name)) continue // дубликаты имён ZipOutputStream не примет
                        if (!e.isDirectory && e.name.lowercase().endsWith(".apk")) {
                            onProgress(BundleProgress("Подпись: ${e.name.substringAfterLast('/')}", done, total))
                            val part = work.resolve("part.apk")
                            val signed = work.resolve("signed.apk")
                            zip.getInputStream(e).use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
                            val r = ApkSignerEngine.sign(part.toFile(), signed.toFile(), key, partSchemes)
                            r.verifyProblem?.let { problems.add("${e.name}: $it") }
                            putFile(out, e, signed)
                            signedParts++
                        } else {
                            copyEntry(zip, e, out)
                        }
                        done += e.size.coerceAtLeast(0)
                    }
                }
            }
            if (signedParts == 0) throw IOException("В наборе нет APK")
            runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(input)) }
            move(tmp, target)
            return SignResult(
                output = target.toFile(),
                key = key,
                schemes = partSchemes,
                arscRepacked = false,
                v4File = null,
                verifyProblem = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
                bundleParts = signedParts
            )
        } finally {
            Files.deleteIfExists(tmp)
            org.wexclient.app.util.FileTree.delete(work)
        }
    }

    /** Сведения об основной части (пакет, версия, подпись) — ею подписан и весь набор. */
    fun baseDetails(path: Path): ApkDetails? {
        val base = inspect(path).base ?: return null
        val temp = org.wexclient.app.util.ProcessScratch.work.newFile("base-", ".apk")
        return try {
            ZipFile(path.toFile()).use { zip ->
                val entry = zip.getEntry(base.entryName) ?: return null
                zip.getInputStream(entry).use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            }
            ApkAnalyzer.analyzePath(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Имя APK рядом с набором: «app.apks» → «app.apk», занято — «app (2).apk». */
    fun defaultApkTarget(input: Path): Path {
        val name = input.fileName.toString()
        val base = name.substringBeforeLast('.').ifEmpty { name }
        return org.wexclient.app.vfs.FileTransfers.freeName(input.toAbsolutePath().parent, "$base.apk")
    }

    /**
     * Собрать части набора в один APK и подписать ключом [key]. Части распаковываются во временную
     * папку программы, после работы она удаляется. OBB в APK не входят — их число в результате.
     */
    fun convertToApk(
        input: Path,
        output: Path,
        key: SigningKey,
        schemes: SignSchemes,
        cancelled: () -> Boolean = { false },
        onProgress: (BundleProgress) -> Unit = {}
    ): BundleConvertResult {
        val info = inspect(input)
        val parts = mergeable(info)
        if (parts.isEmpty()) throw IOException("В наборе нет APK")
        val need = parts.sumOf { it.size }
        val work = org.wexclient.app.util.ProcessScratch.work.newDir("apks-merge-")
        try {
            // Распакованные части и готовый APK — примерно по размеру частей каждый
            checkSpace(work, need * 2 + (64L shl 20), "во временной папке")
            checkSpace(output.toAbsolutePath().parent, need, "рядом с набором")

            ZipFile(input.toFile()).use { zip ->
                var done = 0L
                parts.forEachIndexed { i, part ->
                    if (cancelled()) throw CancellationException("Остановлено")
                    val entry = zip.getEntry(part.entryName) ?: throw IOException("Нет части ${part.entryName}")
                    val file = work.resolve("%03d_%s".format(i, part.entryName.replace('/', '_')))
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(file).use { out ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(BundleProgress("Распаковка частей", done, need))
                                if (cancelled()) throw CancellationException("Остановлено")
                            }
                        }
                    }
                }
            }

            onProgress(BundleProgress("Слияние частей"))
            val unsigned = work.resolve("merged-unsigned.apk")
            ApkBundle().use { bundle ->
                bundle.setAPKLogger(object : APKLogger {
                    override fun logMessage(msg: String?) {
                        if (msg != null && msg.startsWith("Merging")) {
                            onProgress(BundleProgress("Слияние: " + msg.substringAfter(':').trim().replace(Regex("^\\d{3}_"), "")))
                        }
                    }
                    override fun logError(msg: String?, tr: Throwable?) = Unit
                    override fun logVerbose(msg: String?) = Unit
                })
                bundle.loadApkDirectory(work.toFile(), false)
                val modules = bundle.apkModuleList
                val bases = modules.filter { m -> runCatching { m.hasAndroidManifest() && !m.androidManifest.isSplit }.getOrDefault(false) }
                if (bases.isEmpty()) throw IOException("В наборе нет основного APK — сливать не с чем")
                if (bundle.baseModule == null) {
                    // ARSCLib считает основным APK с главной активностью; у обоев, циферблатов и клавиатур её нет —
                    // тогда основой станет часть с самой большой таблицей ресурсов. Убеждаемся, что это она и есть
                    val largest = modules.maxByOrNull { m ->
                        runCatching { if (m.hasTableBlock()) m.zipEntryMap.getInputSource("resources.arsc")?.length ?: 0L else 0L }.getOrDefault(0L)
                    }
                    if (largest == null || largest !in bases) throw IOException("Не удалось определить основной APK набора")
                }
                val merged = bundle.mergeModules(false)
                if (cancelled()) throw CancellationException("Остановлено")
                // Подписи частей к общему APK не подходят: снимаем, apksig подпишет заново
                merged.setApkSignatureBlock(null)
                merged.zipEntryMap.removeIf { src -> isOldSignature(src.alias ?: src.name) }
                merged.refreshTable()
                merged.refreshManifest()
                val total = merged.zipEntryMap.size().toLong()
                var written = 0L
                merged.writeApk(unsigned.toFile()) { _, _, _ ->
                    written++
                    onProgress(BundleProgress("Запись APK", written, total))
                }
            }
            if (cancelled()) throw CancellationException("Остановлено")

            onProgress(BundleProgress("Подпись"))
            val sign = ApkSignerEngine.sign(unsigned.toFile(), output.toFile(), key, schemes.copy(v4 = false))
            return BundleConvertResult(output, parts.size, info.obbFiles, Files.size(output), sign)
        } finally {
            org.wexclient.app.util.FileTree.delete(work)
        }
    }

    /** Старые подписи и печать магазина: после слияния они недействительны. */
    internal fun isOldSignature(path: String): Boolean {
        if (path == "stamp-cert-sha256") return true
        if (!path.startsWith("META-INF/") || path.count { it == '/' } != 1) return false
        val name = path.substringAfter('/').uppercase()
        return name == "MANIFEST.MF" || name.endsWith(".SF") || name.endsWith(".RSA") ||
            name.endsWith(".DSA") || name.endsWith(".EC")
    }

    private fun checkSpace(dir: Path, need: Long, where: String) {
        val free = runCatching { Files.getFileStore(dir).usableSpace }.getOrNull() ?: return
        if (free < need) {
            throw IOException("Не хватает места $where: нужно около ${gb(need)}, свободно ${gb(free)}")
        }
    }

    private fun gb(bytes: Long): String = String.format(java.util.Locale.forLanguageTag("ru"), "%.1f ГБ", bytes / 1073741824.0)

    /** Запись файла [file] под именем записи [like] тем же способом сжатия. */
    private fun putFile(out: ZipOutputStream, like: ZipEntry, file: Path) {
        val ne = ZipEntry(like.name)
        ne.time = like.time
        if (like.method == ZipEntry.STORED) {
            // STORED требует CRC и размер заранее
            val crc = CRC32()
            Files.newInputStream(file).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            val size = Files.size(file)
            ne.method = ZipEntry.STORED
            ne.size = size
            ne.compressedSize = size
            ne.crc = crc.value
        }
        out.putNextEntry(ne)
        Files.copy(file, out)
        out.closeEntry()
    }

    private fun copyEntry(zip: ZipFile, e: ZipEntry, out: ZipOutputStream) {
        val ne = ZipEntry(e.name)
        ne.time = e.time
        e.comment?.let { ne.comment = it }
        if (!e.isDirectory && e.method == ZipEntry.STORED) {
            ne.method = ZipEntry.STORED
            ne.size = e.size
            ne.compressedSize = e.size
            ne.crc = e.crc
        }
        out.putNextEntry(ne)
        if (!e.isDirectory) zip.getInputStream(e).use { it.copyTo(out, 1 shl 16) }
        out.closeEntry()
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
