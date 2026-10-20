package org.wexclient.app.signing

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64

/** Во что можно сохранить ключ подписи. */
enum class KeyExportFormat(
    val title: String,
    /** Первое — по умолчанию, остальные тоже принимаются как есть. */
    val extensions: List<String>,
    val hint: String
) {
    JKS("JKS", listOf("jks", "keystore"), "Хранилище Java: Android Studio, MT Manager, apktool, jarsigner"),
    PKCS12("PKCS12", listOf("p12", "pfx", "keystore"), "Стандартное хранилище: keytool, openssl, Android Studio"),
    PK8_PEM("PK8 + X509.PEM", listOf("pk8"), "Пара как у AOSP: signapk, apksigner --key/--cert, MT Manager"),
    PEM("PEM", listOf("pem", "key"), "Закрытый ключ PKCS#8 и сертификат одним текстовым файлом (openssl)"),
    CERTIFICATE("Сертификат", listOf("x509.pem", "pem", "crt", "cer"), "Только открытая часть — сверить подпись, передать магазину");

    val extension: String get() = extensions.first()

    /** Хранилище с паролем и псевдонимом. */
    val isKeystore: Boolean get() = this == JKS || this == PKCS12

    val containsPrivateKey: Boolean get() = this != CERTIFICATE
}

data class KeyExportRequest(
    val format: KeyExportFormat,
    /** Файл хранилища / .pk8 / .pem; сертификат пары PK8 ляжет рядом (см. [KeyExporter.outputs]). */
    val target: File,
    val alias: String = "",
    val storePassword: String = "",
    /** Только для JKS: PKCS12 хранит ключ под паролем хранилища. */
    val keyPassword: String = storePassword
)

/** Экспорт ключей подписи в JKS, PKCS12, PK8 + X509.PEM, PEM и сертификат. */
object KeyExporter {
    const val MIN_PASSWORD = 6

    /** Имя файла по умолчанию: для встроенных — привычные testkey/debug, для своих — название. */
    fun defaultFileName(key: SigningKey, format: KeyExportFormat): String {
        val base = when (key.kind) {
            KeyKind.AOSP_TESTKEY -> "testkey"
            KeyKind.ANDROID_DEBUG -> "debug"
            else -> sanitize(key.title).ifEmpty { sanitize(key.alias ?: "") }.ifEmpty { "key" }
        }
        return "$base.${format.extension}"
    }

    private fun sanitize(name: String): String =
        name.map { c -> if (c.isISOControl() || c in "/\\:*?\"<>|") '_' else c }.joinToString("").trim().trim('.')

    /** Расширение формата дописывается, если его нет (например, «release» → «release.jks»). */
    fun normalizeTarget(format: KeyExportFormat, file: File): File {
        val name = file.name
        if (format.extensions.any { name.length > it.length + 1 && name.endsWith(".$it", ignoreCase = true) }) return file
        return File(file.parentFile, "$name.${format.extension}")
    }

    /** Все файлы, которые запишет экспорт: для PK8 — ключ и сертификат рядом (name.pk8 + name.x509.pem). */
    fun outputs(format: KeyExportFormat, target: File): List<File> {
        val main = normalizeTarget(format, target)
        if (format != KeyExportFormat.PK8_PEM) return listOf(main)
        return listOf(main, File(main.parentFile, main.name.dropLast(".pk8".length) + ".x509.pem"))
    }

    /** Что не так с параметрами (null — можно экспортировать). */
    fun validate(req: KeyExportRequest): String? {
        if (req.format.isKeystore) {
            if (req.alias.isBlank()) return "Укажите псевдоним ключа"
            if (req.storePassword.length < MIN_PASSWORD) return "Пароль хранилища — не короче $MIN_PASSWORD символов"
            if (req.format == KeyExportFormat.JKS && req.keyPassword.length < MIN_PASSWORD) {
                return "Пароль ключа — не короче $MIN_PASSWORD символов"
            }
        }
        val dir = normalizeTarget(req.format, req.target).absoluteFile.parentFile
        if (dir == null || !dir.isDirectory) return "Папка не существует: ${dir?.path ?: req.target.path}"
        return null
    }

    /** Записывает ключ и возвращает созданные файлы. Закрытые ключи — с правами только для владельца. */
    fun export(key: SigningKey, req: KeyExportRequest): List<File> {
        validate(req)?.let { throw IllegalArgumentException(it) }
        val loaded = SigningKeys.load(key)
        val files = outputs(req.format, req.target).map { it.absoluteFile }
        when (req.format) {
            KeyExportFormat.JKS, KeyExportFormat.PKCS12 -> {
                val ks = KeyStore.getInstance(if (req.format == KeyExportFormat.JKS) "JKS" else "PKCS12")
                ks.load(null, null)
                // У PKCS12 отдельный пароль ключа большинство инструментов не понимает (keytool его тоже игнорирует)
                val keyPassword = if (req.format == KeyExportFormat.JKS) req.keyPassword else req.storePassword
                ks.setKeyEntry(req.alias.trim(), loaded.privateKey, keyPassword.toCharArray(), loaded.certificates.toTypedArray())
                write(files[0], private = true) { ks.store(it, req.storePassword.toCharArray()) }
            }
            KeyExportFormat.PK8_PEM -> {
                write(files[0], private = true) { it.write(pkcs8(loaded.privateKey)) }
                write(files[1], private = false) { it.write(certificatesPem(loaded.certificates).toByteArray()) }
            }
            KeyExportFormat.PEM -> write(files[0], private = true) {
                it.write((privateKeyPem(loaded.privateKey) + certificatesPem(loaded.certificates)).toByteArray())
            }
            KeyExportFormat.CERTIFICATE -> write(files[0], private = false) {
                it.write(certificatesPem(loaded.certificates).toByteArray())
            }
        }
        return files
    }

    private fun pkcs8(key: PrivateKey): ByteArray =
        key.encoded ?: throw IOException("Закрытый ключ нельзя извлечь (${key.algorithm})")

    fun privateKeyPem(key: PrivateKey): String = pem("PRIVATE KEY", pkcs8(key))

    fun certificatesPem(certificates: List<X509Certificate>): String =
        certificates.joinToString("") { pem("CERTIFICATE", it.encoded) }

    private fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    /**
     * Блок signingConfigs для build.gradle.kts с путём к экспортированному хранилищу.
     * Пароли попадают в текст — вызывается только по явному действию пользователя.
     */
    fun gradleSigningConfig(req: KeyExportRequest, name: String = "release"): String {
        fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = normalizeTarget(req.format, req.target).absolutePath
        val keyPassword = if (req.format == KeyExportFormat.JKS) req.keyPassword else req.storePassword
        return buildString {
            appendLine("signingConfigs {")
            appendLine("    create(${lit(name)}) {")
            appendLine("        storeFile = file(${lit(file)})")
            if (req.format == KeyExportFormat.JKS) appendLine("        storeType = \"jks\"")
            appendLine("        storePassword = ${lit(req.storePassword)}")
            appendLine("        keyAlias = ${lit(req.alias.trim())}")
            appendLine("        keyPassword = ${lit(keyPassword)}")
            appendLine("    }")
            append("}")
        }
    }

    // Запись во временный файл рядом и атомарная замена: недописанный ключ не останется на месте старого
    private fun write(target: File, private: Boolean, body: (OutputStream) -> Unit) {
        val dir = target.parentFile ?: throw IOException("Нет папки для ${target.path}")
        val tmp = Files.createTempFile(dir.toPath(), ".wex-export-", ".tmp") // права 600
        try {
            Files.newOutputStream(tmp).use(body)
            if (!private) runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--")) }
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
