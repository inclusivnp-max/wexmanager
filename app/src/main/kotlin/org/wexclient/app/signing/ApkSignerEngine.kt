package org.wexclient.app.signing

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.wexclient.app.apk.ApkAnalyzer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class SignResult(
    val output: File,
    val key: SigningKey,
    val schemes: SignSchemes,
    val arscRepacked: Boolean,
    val v4File: File?,
    /** null — подпись проверена после записи, иначе что не так. */
    val verifyProblem: String?,
    /** Набор split-APK: сколько частей подписано (0 — обычный APK). */
    val bundleParts: Int = 0
) {
    val message: String
        get() = buildString {
            if (bundleParts > 0) {
                append("Набор подписан ключом «${key.title}» (${schemes.label}): ${output.name} — частей: $bundleParts")
            } else {
                append("APK подписан ключом «${key.title}» (${schemes.label}): ${output.name} (${output.length() / 1024} KB)")
            }
            if (arscRepacked) append(" · resources.arsc без сжатия")
            if (v4File != null) append(" · ${v4File.name}")
            append(if (verifyProblem == null) " · подпись проверена" else " · проверка: $verifyProblem")
        }
}

object ApkSignerEngine {

    /** Подпись в новый файл тестовым ключом Android debug (старый API). */
    fun signApk(
        inputFile: File,
        outputFile: File,
        v1Signing: Boolean = true,
        v2Signing: Boolean = true,
        v3Signing: Boolean = true
    ): String = sign(inputFile, outputFile, SigningKeys.debugKey, SignSchemes(v1Signing, v2Signing, v3Signing)).message

    /** Подпись [input] в [output] ключом [key]; V4 пишется в «output.idsig». */
    fun sign(input: File, output: File, key: SigningKey, schemes: SignSchemes): SignResult {
        // apksig открывает выход на запись и обрезает его до нуля — при совпадении путей исходник теряется
        if (output.exists() && input.exists() && Files.isSameFile(input.toPath(), output.toPath())) {
            throw IllegalArgumentException("Выходной файл совпадает с исходным APK — укажите другое имя")
        }
        if (input.absoluteFile.normalize() == output.absoluteFile.normalize()) {
            throw IllegalArgumentException("Выходной файл совпадает с исходным APK — укажите другое имя")
        }
        require(schemes.isValid) { "Выберите схему подписи (V4 работает только вместе с V2 или V3)" }

        val signerConfig = SigningKeys.load(key).signerConfig()
        val v4File = if (schemes.v4) File(output.path + ".idsig") else null
        // apksig пишет прямо в выходной файл: при ошибке прежний файл с этим именем был бы испорчен.
        // Пишем во временный рядом и переносим на место только готовый.
        val dir = output.absoluteFile.parentFile ?: throw java.io.IOException("Нет папки для ${output.path}")
        val tmp = File.createTempFile(".wex-sign-", ".apk", dir)
        val tmpV4 = if (schemes.v4) File(tmp.path + ".idsig") else null

        fun signer(minSdk: Int?) = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(tmp)
            .setV1SigningEnabled(schemes.v1)
            .setV2SigningEnabled(schemes.v2)
            .setV3SigningEnabled(schemes.v3)
            .setV4SigningEnabled(schemes.v4)
            .apply {
                if (tmpV4 != null) setV4SignatureOutputFile(tmpV4)
                if (minSdk != null) setMinSdkVersion(minSdk)
            }
            .build()

        try {
            try {
                signer(null).sign()
            } catch (_: com.android.apksig.apk.MinSdkVersionException) {
                // Манифест не читается (защита/обфускация) — подписываем с максимальной совместимостью v1 (SHA-1)
                signer(1).sign()
            }
            // createTempFile даёт права 600 — у подписанного APK права исходника
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), Files.getPosixFilePermissions(input.toPath())) }
            move(tmp, output)
            if (tmpV4 != null && v4File != null) move(tmpV4, v4File)
        } finally {
            tmp.delete()
            tmpV4?.delete()
        }
        return SignResult(output, key, schemes, false, v4File, verify(output, v4File))
    }

    private fun verify(apk: File, v4File: File?): String? = try {
        val builder = ApkVerifier.Builder(apk)
        if (v4File != null && v4File.isFile) builder.setV4SignatureFile(v4File)
        val result = try {
            builder.build().verify()
        } catch (_: com.android.apksig.apk.MinSdkVersionException) {
            ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(1).build().verify()
        }
        if (result.isVerified) null else ApkAnalyzer.verifySignature(apk) ?: "подпись не прошла проверку"
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /**
     * Подпись «на месте», как в MT Manager: подписанная копия пишется рядом во временный
     * файл и атомарно заменяет исходный APK. Если resources.arsc сжат (так бывает после
     * правок), APK сначала переупаковывается с несжатым resources.arsc — иначе Android 11+
     * откажется его ставить (-124).
     */
    fun signInPlace(
        apk: File,
        key: SigningKey = SigningPreferences.defaultKey,
        schemes: SignSchemes = SigningPreferences.schemes
    ): SignResult {
        val target = apk.absoluteFile
        require(target.isFile) { "Файл не найден: ${target.path}" }
        val dir = target.parentFile
        val prepared = File.createTempFile(".wex-prepare-", ".apk", dir)
        val signed = File.createTempFile(".wex-signed-", ".apk", dir)
        try {
            val repacked = repackWithStoredArsc(target, prepared)
            val result = sign(if (repacked) prepared else target, signed, key, schemes)
            // createTempFile даёт права 600 — возвращаем права исходника
            runCatching {
                Files.setPosixFilePermissions(signed.toPath(), Files.getPosixFilePermissions(target.toPath()))
            }
            move(signed, target)
            val v4 = result.v4File?.let { tmp -> File(target.path + ".idsig").also { move(tmp, it) } }
            return result.copy(output = target, arscRepacked = repacked, v4File = v4)
        } finally {
            prepared.delete()
            signed.delete()
            File(signed.path + ".idsig").delete()
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** true — resources.arsc был сжат и APK переписан в [dst] (остальные записи с исходным методом). */
    internal fun repackWithStoredArsc(src: File, dst: File): Boolean {
        ZipFile(src).use { zip ->
            val arsc = zip.getEntry("resources.arsc")
            if (arsc == null || arsc.method == ZipEntry.STORED) return false
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dst), 1 shl 16)).use { out ->
                val seen = HashSet<String>()
                for (e in zip.entries()) {
                    if (!seen.add(e.name)) continue // дубликаты имён ZipOutputStream не примет
                    val ne = ZipEntry(e.name)
                    ne.time = e.time
                    e.comment?.let { ne.comment = it }
                    if (e.name == "resources.arsc" || e.method == ZipEntry.STORED) {
                        // STORED требует CRC и размер заранее — считаем отдельным проходом
                        val crc = CRC32()
                        var size = 0L
                        zip.getInputStream(e).use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                crc.update(buf, 0, n)
                                size += n
                            }
                        }
                        ne.method = ZipEntry.STORED
                        ne.size = size
                        ne.compressedSize = size
                        ne.crc = crc.value
                    } else {
                        ne.method = ZipEntry.DEFLATED
                    }
                    out.putNextEntry(ne)
                    if (!e.isDirectory) zip.getInputStream(e).use { it.copyTo(out, 1 shl 16) }
                    out.closeEntry()
                }
            }
            return true
        }
    }
}
