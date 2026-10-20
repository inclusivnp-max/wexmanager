package org.wexclient.app.apk

import org.wexclient.app.util.readUpTo

import com.android.apksig.ApkVerifier
import org.wexclient.app.axml.AxmlDecoder
import org.wexclient.app.axml.ManifestInfo
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

data class ApkDetails(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val minSdk: String,
    val minSdkName: String,
    val targetSdk: String,
    val targetSdkName: String,
    val fileSizeFormatted: String,
    val fileSizeBytes: Long,
    val lastModifiedFormatted: String,
    val protection: String,
    val isProtected: Boolean,
    val signatureScheme: String,
    val certIssuer: String,
    val certSha256: String,
    val certMd5: String,
    val isSignatureValid: Boolean,
    /** Почему подпись не прошла проверку (null — подпись верна). */
    val signatureProblem: String?,
    val launcherActivity: String?,
    val permissionsCount: Int,
    val activitiesCount: Int
)

object ApkAnalyzer {

    fun analyze(apkFile: File): ApkDetails {
        var appName = apkFile.nameWithoutExtension
        var pkgName = ""
        var vName = ""
        var vCode = ""
        var minSdk = ""
        var targetSdk = ""
        var launcher: String? = null
        var permsCount = 0
        var actsCount = 0

        var protection = "Не удалось проверить"
        var isProtected = false

        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                val (detectedProt, protFound) = detectProtector(entries)
                protection = detectedProt
                isProtected = protFound

                val mfEntry = zip.getEntry("AndroidManifest.xml")
                val mfBytes = mfEntry?.let { e -> zip.getInputStream(e).use { readLimited(it, MAX_MANIFEST_BYTES) } }
                if (mfBytes != null) {
                    val xml = AxmlDecoder().decode(mfBytes)
                    val info = ManifestInfo.parse(xml)
                    pkgName = info.packageName
                    vName = info.versionName
                    vCode = info.versionCode
                    minSdk = info.minSdkVersion
                    targetSdk = info.targetSdkVersion
                    launcher = info.launcherActivity
                    permsCount = info.permissions.size
                    actsCount = info.activities.size

                    // Имя приложения: сначала label тега <application>, иначе первый попавшийся
                    val appLabelMatch = Regex("<application[^>]+android:label=\"([^\"]+)\"")
                        .find(xml)
                        ?: Regex("android:label=\"([^\"]+)\"").find(xml)
                    if (appLabelMatch != null) {
                        val rawLabel = appLabelMatch.groupValues[1]
                        if (!rawLabel.startsWith("@")) {
                            appName = rawLabel
                        }
                    }
                }
            }
        } catch (e: Exception) {
            org.wexclient.app.util.ErrorLog.record("Не удалось прочитать манифест «${apkFile.name}»", e)
        }

        if (pkgName.isEmpty()) {
            pkgName = "Не определен"
        }
        var sigScheme = "Не удалось проверить"
        var certIssuer = "—"
        var certSha256 = "—"
        var certMd5 = "—"
        var isSigValid = false
        var sigProblem: String? = "Не удалось проверить подпись"

        try {
            val result = verify(apkFile)
            isSigValid = result.isVerified
            sigProblem = if (result.isVerified) null else describeSignatureErrors(result)

            val schemes = mutableListOf<String>()
            if (result.isVerifiedUsingV1Scheme) schemes.add("v1 (JAR)")
            if (result.isVerifiedUsingV2Scheme) schemes.add("v2 (Full APK)")
            if (result.isVerifiedUsingV3Scheme) schemes.add("v3")
            if (result.isVerifiedUsingV4Scheme) schemes.add("v4")

            sigScheme = if (schemes.isNotEmpty()) schemes.joinToString(", ") else if (result.isVerified) "Подписан" else "Не подписан / Ошибка подписи"

            val signers = result.signerCertificates
            if (signers.isNotEmpty()) {
                val cert = signers[0]
                certIssuer = cert.issuerX500Principal.name.substringBefore(",")
                certSha256 = formatFingerprint(cert, "SHA-256")
                certMd5 = formatFingerprint(cert, "MD5")
            }
        } catch (e: Exception) {
            sigProblem = "Не удалось проверить подпись: ${e.message ?: e.javaClass.simpleName}"
            org.wexclient.app.util.ErrorLog.record("Не удалось проверить подпись «${apkFile.name}»", e)
        }

        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val lastModStr = sdf.format(Date(apkFile.lastModified()))

        val sizeBytes = apkFile.length()
        val sizeFormatted = when {
            sizeBytes < 1024 -> "$sizeBytes Б"
            sizeBytes < 1024 * 1024 -> "%.1f КБ".format(sizeBytes / 1024.0)
            else -> "%.2f МБ".format(sizeBytes / (1024.0 * 1024.0))
        }

        return ApkDetails(
            appName = appName,
            packageName = pkgName,
            versionName = vName.ifEmpty { "—" },
            versionCode = vCode.ifEmpty { "—" },
            minSdk = minSdk.ifEmpty { "—" },
            minSdkName = minSdk.toIntOrNull()?.let(::sdkToVersionName) ?: "—",
            targetSdk = targetSdk.ifEmpty { "—" },
            targetSdkName = targetSdk.toIntOrNull()?.let(::sdkToVersionName) ?: "—",
            fileSizeFormatted = sizeFormatted,
            fileSizeBytes = sizeBytes,
            lastModifiedFormatted = lastModStr,
            protection = protection,
            isProtected = isProtected,
            signatureScheme = sigScheme,
            certIssuer = certIssuer,
            certSha256 = certSha256,
            certMd5 = certMd5,
            isSignatureValid = isSigValid,
            signatureProblem = sigProblem,
            launcherActivity = launcher,
            permissionsCount = permsCount,
            activitiesCount = actsCount
        )
    }

    private const val MAX_MANIFEST_BYTES = 16 shl 20

    /** Содержимое потока, если оно не длиннее [limit] (zip-бомба не должна занять всю память). */
    internal fun readLimited(input: java.io.InputStream, limit: Int): ByteArray? {
        val bytes = input.readUpTo(limit + 1)
        return if (bytes.size > limit) null else bytes
    }

    /** Разбор APK по Path: файл внутри архива копируется во временный (в кэш на диске, не в /tmp в памяти). */
    fun analyzePath(path: java.nio.file.Path): ApkDetails {
        if (path.fileSystem === java.nio.file.FileSystems.getDefault()) return analyze(path.toFile())
        val cacheDir = org.wexclient.app.util.AppDirs.cache.toFile().also { it.mkdirs() }
        val size = java.nio.file.Files.size(path)
        if (size > cacheDir.usableSpace - (512L shl 20)) throw java.io.IOException("Недостаточно места, чтобы разобрать APK из архива")
        val temp = File.createTempFile("apk_detail_", ".apk", cacheDir)
        try {
            java.nio.file.Files.copy(path, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return analyze(temp)
        } finally {
            temp.delete()
        }
    }

    /** .xapk/.apks/.apkm — набор split-APK, у самого архива подписи нет. */
    fun isBundle(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm")
    }

    /** Пакет из manifest.json XAPK или из манифеста первой части набора — у всех частей он один. */
    fun bundlePackageName(path: java.nio.file.Path): String? = try {
        java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(path)).use { zin ->
            var result: String? = null
            while (result == null) {
                val e = zin.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (name == "manifest.json") {
                    val text = readLimited(zin, 1 shl 20)?.toString(Charsets.UTF_8) ?: continue
                    result = Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                } else if (name.lowercase().endsWith(".apk")) {
                    // Манифест части — потоком, без копии всего APK во временный файл.
                    // Основной APK зовут по-разному (base.apk, base-master.apk, по имени пакета у APKCombo),
                    // но пакет записан в манифесте любой части
                    result = runCatching { manifestPackageFromStream(zin) }.getOrNull()
                }
            }
            result
        }
    } catch (_: Exception) {
        null
    }

    private fun manifestPackageFromStream(apkStream: java.io.InputStream): String? {
        // Внешний поток не закрываем: ZipInputStream.close() закрыл бы и его
        val inner = java.util.zip.ZipInputStream(object : java.io.FilterInputStream(apkStream) {
            override fun close() {}
        })
        while (true) {
            val e = inner.nextEntry ?: return null
            if (e.name == "AndroidManifest.xml") {
                val bytes = readLimited(inner, MAX_MANIFEST_BYTES) ?: return null
                return ManifestInfo.parse(AxmlDecoder().decode(bytes)).packageName.takeIf { it.isNotBlank() }
            }
        }
    }

    /** Проверка подписи без разбора манифеста (после правок APK). */
    fun verifySignature(apkFile: File): String? = try {
        val result = verify(apkFile)
        if (result.isVerified) null else describeSignatureErrors(result)
    } catch (e: Exception) {
        "Не удалось проверить подпись: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun verify(apkFile: File): ApkVerifier.Result = try {
        ApkVerifier.Builder(apkFile).build().verify()
    } catch (_: com.android.apksig.apk.MinSdkVersionException) {
        // minSdk из манифеста не прочитать — проверяем для всех версий Android
        ApkVerifier.Builder(apkFile).setMinCheckedPlatformVersion(1).build().verify()
    }

    private fun describeSignatureErrors(result: ApkVerifier.Result): String {
        val all = result.allErrors
        val issues = all.map { it.issue }.toSet()
        fun param(issue: ApkVerifier.Issue): String? =
            all.firstOrNull { it.issue == issue }?.params?.firstOrNull()?.toString()

        val noV1 = ApkVerifier.Issue.JAR_SIG_NO_SIGNATURES in issues || ApkVerifier.Issue.JAR_SIG_NO_SIGNERS in issues
        val noSchemes = result.v1SchemeSigners.isEmpty() && result.v2SchemeSigners.isEmpty() && result.v3SchemeSigners.isEmpty()
        return when {
            noSchemes && (noV1 || all.isEmpty()) -> "APK не подписан"
            ApkVerifier.Issue.JAR_SIG_ZIP_ENTRY_DIGEST_DID_NOT_VERIFY in issues ->
                "Файл «${param(ApkVerifier.Issue.JAR_SIG_ZIP_ENTRY_DIGEST_DID_NOT_VERIFY) ?: "?"}» изменён после подписи"
            ApkVerifier.Issue.JAR_SIG_NO_ZIP_ENTRY_DIGEST_IN_MANIFEST in issues ->
                "Файл «${param(ApkVerifier.Issue.JAR_SIG_NO_ZIP_ENTRY_DIGEST_IN_MANIFEST) ?: "?"}» добавлен после подписи"
            ApkVerifier.Issue.JAR_SIG_MISSING_ZIP_ENTRY_REFERENCED_IN_MANIFEST in issues ->
                "Файл «${param(ApkVerifier.Issue.JAR_SIG_MISSING_ZIP_ENTRY_REFERENCED_IN_MANIFEST) ?: "?"}» удалён после подписи"
            ApkVerifier.Issue.V2_SIG_APK_DIGEST_DID_NOT_VERIFY in issues ||
                ApkVerifier.Issue.V3_SIG_APK_DIGEST_DID_NOT_VERIFY in issues ->
                "Содержимое APK изменено после подписи (контрольная сумма v2/v3 не совпадает)"
            ApkVerifier.Issue.JAR_SIG_MISSING_APK_SIG_REFERENCED in issues ||
                ApkVerifier.Issue.V2_SIG_MISSING_APK_SIG_REFERENCED in issues ->
                "APK изменён после подписи — блок подписи v2/v3 удалён при перезаписи архива"
            all.isNotEmpty() -> "Подпись повреждена: " + all.first().toString().take(200)
            else -> "Подпись не прошла проверку"
        }
    }

    private fun detectProtector(entries: List<String>): Pair<String, Boolean> {
        for (e in entries) {
            val lower = e.lowercase()
            when {
                lower.contains("libjiagu") -> return "Qihoo 360 (Jiagu / 360加固)" to true
                lower.contains("libtcloud_shell") || lower.contains("libtx_id.so") -> return "Tencent Legu (腾讯乐固)" to true
                lower.contains("libsecmain") || lower.contains("libsecshell") -> return "Bangcle (梆梆加固)" to true
                lower.contains("libdexhelper") -> return "SecNeo / DexGuard" to true
                lower.contains("libexec.so") || lower.contains("libijiami") -> return "Ijiami (爱加密)" to true
                lower.contains("libbaiduprotect") -> return "Baidu Protect (百度加固)" to true
                lower.contains("libmobisec") -> return "Alibaba Security (阿里聚安全)" to true
                lower.contains("libkwsgmain") -> return "Kuaishou Guard" to true
                lower.contains("libprotector") -> return "App Protector (libprotector.so)" to true
                lower.contains("libcrashanalitycs") -> return "App Protector (libcrashanalitycs.so)" to true
            }
        }
        return "Известные протекторы не обнаружены" to false
    }

    private fun formatFingerprint(cert: X509Certificate, algorithm: String): String {
        return try {
            val md = MessageDigest.getInstance(algorithm)
            val digest = md.digest(cert.encoded)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Throwable) {
            "—"
        }
    }

    fun sdkToVersionName(api: Int): String {
        return when (api) {
            36 -> "Android 16 (Baklava)"
            35 -> "Android 15 (Vanilla Ice Cream)"
            34 -> "Android 14 (Upside Down Cake)"
            33 -> "Android 13 (Tiramisu)"
            32 -> "Android 12L (Sv2)"
            31 -> "Android 12 (Snow Cone)"
            30 -> "Android 11 (Red Velvet Cake)"
            29 -> "Android 10 (Quince Tart)"
            28 -> "Android 9.0 (Pie)"
            27 -> "Android 8.1 (Oreo MR1)"
            26 -> "Android 8.0 (Oreo)"
            25 -> "Android 7.1 (Nougat MR1)"
            24 -> "Android 7.0 (Nougat)"
            23 -> "Android 6.0 (Marshmallow)"
            22 -> "Android 5.1 (Lollipop MR1)"
            21 -> "Android 5.0 (Lollipop)"
            else -> "API $api"
        }
    }
}
