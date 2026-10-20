package org.wexclient.app.signing

import org.wexclient.app.util.readUpTo

import androidx.compose.runtime.mutableStateListOf
import com.android.apksig.ApkSigner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import org.wexclient.app.util.startHelper

enum class KeyKind {
    /** Встроенный AOSP testkey (им же подписывает MT Manager). */
    AOSP_TESTKEY,
    /** ~/.android/debug.keystore (создаётся при необходимости). */
    ANDROID_DEBUG,
    /** Хранилище JKS/PKCS12. */
    KEYSTORE,
    /** Пара PKCS#8 (.pk8) + сертификат X.509 (.pem). */
    PK8
}

data class SigningKey(
    val id: String,
    val title: String,
    val kind: KeyKind,
    val storeFile: File? = null,
    val certFile: File? = null,
    val alias: String? = null,
    val storePassword: String? = null,
    val keyPassword: String? = null
) {
    val builtIn: Boolean get() = kind == KeyKind.AOSP_TESTKEY || kind == KeyKind.ANDROID_DEBUG
}

/** Сведения о сертификате ключа для списка и окна «Сведения». */
data class KeyInfo(
    val subject: String,
    val commonName: String,
    val algorithm: String,
    val notBefore: Date,
    val notAfter: Date,
    val sha256: String,
    val sha1: String,
    val md5: String
) {
    val shortLabel: String
        get() = "$algorithm · до ${SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(notAfter)}"
}

/** Ключ + цепочка сертификатов, готовые для apksig. */
class LoadedKey(val privateKey: PrivateKey, val certificates: List<X509Certificate>, val name: String) {
    fun signerConfig(): ApkSigner.SignerConfig =
        ApkSigner.SignerConfig.Builder(name, privateKey, certificates).build()
}

/**
 * Ключи подписи: встроенные (AOSP testkey, Android debug) и свои —
 * ~/.config/wexdesktop/keys/<id>/{key.properties, хранилище или .pk8+.pem}.
 */
object SigningKeys {
    const val TESTKEY_ID = "aosp-testkey"
    const val DEBUG_ID = "android-debug"

    private val home: String get() = org.wexclient.app.util.AppDirs.userHome.toString()
    private val keysDir: File get() = org.wexclient.app.util.AppDirs.config.resolve("keys").toFile()

    val testKey = SigningKey(TESTKEY_ID, "AOSP testkey", KeyKind.AOSP_TESTKEY, alias = "testkey")
    val debugKey = SigningKey(DEBUG_ID, "Android Debug", KeyKind.ANDROID_DEBUG, alias = "androiddebugkey")

    /** Встроенные + пользовательские, в порядке отображения. */
    val keys = mutableStateListOf<SigningKey>()

    private val infoCache = HashMap<String, KeyInfo>()
    private val loadedCache = HashMap<String, LoadedKey>()

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        infoCache.clear()
        loadedCache.clear()
        keys.clear()
        keys.add(testKey)
        keys.add(debugKey)
        keysDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() }?.forEach { dir ->
            readKey(dir)?.let { keys.add(it) }
        }
    }

    fun byId(id: String?): SigningKey? = keys.firstOrNull { it.id == id }

    private fun readKey(dir: File): SigningKey? = try {
        val p = Properties()
        File(dir, "key.properties").inputStream().use { p.load(it) }
        val kind = KeyKind.valueOf(p.getProperty("kind"))
        SigningKey(
            id = dir.name,
            title = p.getProperty("title") ?: dir.name,
            kind = kind,
            storeFile = p.getProperty("store")?.let { File(dir, it) },
            certFile = p.getProperty("cert")?.let { File(dir, it) },
            alias = p.getProperty("alias"),
            storePassword = p.getProperty("storePassword"),
            keyPassword = p.getProperty("keyPassword")
        )
    } catch (_: Exception) {
        null
    }

    private fun writeKey(key: SigningKey, dir: File) {
        val p = Properties()
        p.setProperty("title", key.title)
        p.setProperty("kind", key.kind.name)
        key.storeFile?.let { p.setProperty("store", it.name) }
        key.certFile?.let { p.setProperty("cert", it.name) }
        key.alias?.let { p.setProperty("alias", it) }
        key.storePassword?.let { p.setProperty("storePassword", it) }
        key.keyPassword?.let { p.setProperty("keyPassword", it) }
        val f = File(dir, "key.properties")
        // Пароли — сразу с правами только для владельца, без мгновения с 644
        val tmp = Files.createTempFile(dir.toPath(), ".key-", ".tmp")
        try {
            Files.newOutputStream(tmp).use { p.store(it, "WEX Desktop signing key") }
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        restrict(f)
    }

    // Пароли и закрытые ключи — только для владельца
    private fun restrict(f: File) {
        runCatching {
            Files.setPosixFilePermissions(
                f.toPath(),
                PosixFilePermissions.fromString(if (f.isDirectory) "rwx------" else "rw-------")
            )
        }
    }

    private fun newKeyDir(): File {
        keysDir.mkdirs()
        restrict(keysDir)
        val dir = File(keysDir, UUID.randomUUID().toString().take(8))
        dir.mkdirs()
        restrict(dir)
        return dir
    }

    fun rename(key: SigningKey, title: String) {
        if (key.builtIn) return
        val dir = key.storeFile?.parentFile ?: return
        writeKey(key.copy(title = title.trim()), dir)
        reload()
    }

    fun delete(key: SigningKey) {
        if (key.builtIn) return
        key.storeFile?.parentFile?.takeIf { it.parentFile == keysDir }?.let { org.wexclient.app.util.FileTree.delete(it) }
        reload()
    }

    @Synchronized
    fun load(key: SigningKey): LoadedKey {
        loadedCache[key.id]?.let { return it }
        val loaded = when (key.kind) {
            KeyKind.AOSP_TESTKEY -> {
                val loader = SigningKeys::class.java.classLoader
                val pk8 = loader.getResourceAsStream("signing/testkey.pk8")!!.use { it.readBytes() }
                val pem = loader.getResourceAsStream("signing/testkey.x509.pem")!!.use { it.readBytes() }
                LoadedKey(privateKeyFromPk8(pk8), certificatesFrom(pem), "CERT")
            }
            KeyKind.ANDROID_DEBUG -> {
                val ks = File(home, ".android/debug.keystore")
                if (!ks.exists()) createDebugKeystore(ks)
                fromKeystore(ks, "android", "androiddebugkey", "android", "CERT")
            }
            KeyKind.KEYSTORE -> fromKeystore(
                requireNotNull(key.storeFile) { "Нет файла хранилища" },
                key.storePassword ?: "",
                requireNotNull(key.alias) { "Не выбран псевдоним ключа" },
                key.keyPassword ?: key.storePassword ?: "",
                signerName(key.alias)
            )
            KeyKind.PK8 -> LoadedKey(
                privateKeyFromPk8(requireNotNull(key.storeFile).readBytes()),
                certificatesFrom(requireNotNull(key.certFile).readBytes()),
                signerName(key.alias ?: "CERT")
            )
        }
        loadedCache[key.id] = loaded
        return loaded
    }

    /** Сведения о сертификате; debug.keystore здесь не создаётся — только при подписи. */
    fun info(key: SigningKey): KeyInfo? {
        synchronized(this) { infoCache[key.id] }?.let { return it }
        if (key.kind == KeyKind.ANDROID_DEBUG && !File(home, ".android/debug.keystore").exists()) return null
        return runCatching { infoOf(load(key).certificates.first()) }.getOrNull()?.also {
            synchronized(this) { infoCache[key.id] = it }
        }
    }

    /** Какой из известных ключей подписал APK (по SHA-256 сертификата в формате AA:BB:…). */
    fun findBySha256(sha256: String?): SigningKey? {
        if (sha256.isNullOrBlank() || sha256 == "—") return null
        return keys.firstOrNull { info(it)?.sha256.equals(sha256, ignoreCase = true) }
    }

    // Имя блока подписи v1 (META-INF/<NAME>.RSA): только латиница и цифры, до 8 символов
    private fun signerName(alias: String): String {
        val cleaned = alias.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
        return cleaned.take(8).ifEmpty { "CERT" }
    }

    fun infoOf(cert: X509Certificate): KeyInfo {
        val pub = cert.publicKey
        val algo = when (pub) {
            is RSAPublicKey -> "RSA ${pub.modulus.bitLength()}"
            is ECPublicKey -> "EC ${pub.params.order.bitLength()}"
            is DSAPublicKey -> "DSA ${pub.params.p.bitLength()}"
            else -> pub.algorithm
        }
        val rfc2253 = cert.subjectX500Principal.name
        val cn = Regex("(?:^|,)CN=([^,]+)").find(rfc2253)?.groupValues?.get(1) ?: rfc2253
        return KeyInfo(
            // toString() знает EMAILADDRESS и др., в RFC 2253 они выглядят как OID=#hex
            subject = cert.subjectX500Principal.toString(),
            commonName = cn,
            algorithm = algo,
            notBefore = cert.notBefore,
            notAfter = cert.notAfter,
            sha256 = fingerprint(cert, "SHA-256"),
            sha1 = fingerprint(cert, "SHA-1"),
            md5 = fingerprint(cert, "MD5")
        )
    }

    private fun fingerprint(cert: X509Certificate, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    private fun privateKeyFromPk8(bytes: ByteArray): PrivateKey {
        val der = if (looksLikePem(bytes)) {
            val (type, data) = pemBlocks(String(bytes, Charsets.ISO_8859_1))
                .firstOrNull { it.first.endsWith("PRIVATE KEY") }
                ?: throw IOException("В файле нет закрытого ключа (ожидается блок PRIVATE KEY)")
            when (type) {
                "PRIVATE KEY" -> data
                "RSA PRIVATE KEY" -> rsaPkcs1ToPkcs8(data)
                "ENCRYPTED PRIVATE KEY" -> throw IOException("Зашифрованный PKCS#8 не поддерживается — сохраните ключ без пароля")
                "EC PRIVATE KEY" -> throw IOException("Ключ EC в формате SEC1 не поддерживается — преобразуйте: openssl pkcs8 -topk8 -nocrypt")
                else -> throw IOException("Формат ключа «$type» не поддерживается")
            }
        } else bytes
        val spec = PKCS8EncodedKeySpec(der)
        for (algo in listOf("RSA", "EC", "DSA")) {
            try {
                return KeyFactory.getInstance(algo).generatePrivate(spec)
            } catch (_: Exception) {
                // следующий алгоритм
            }
        }
        throw IOException("Не удалось прочитать закрытый ключ PKCS#8 (поддерживаются RSA/EC/DSA без пароля)")
    }

    private fun certificatesFrom(bytes: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = if (looksLikePem(bytes)) {
            // В одном PEM может лежать и закрытый ключ — берём только сертификаты (и цепочки PKCS#7)
            pemBlocks(String(bytes, Charsets.ISO_8859_1)).flatMap { (type, data) ->
                when (type) {
                    "CERTIFICATE", "X509 CERTIFICATE" -> listOf(factory.generateCertificate(data.inputStream()))
                    "PKCS7" -> factory.generateCertificates(data.inputStream()).toList()
                    else -> emptyList()
                }
            }
        } else {
            factory.generateCertificates(bytes.inputStream()).toList()
        }.map { it as X509Certificate }
        if (certs.isEmpty()) throw IOException("В файле нет сертификата X.509")
        return certs
    }

    // Перед блоками PEM бывают «Bag Attributes» от openssl — смотрим не только начало
    private fun looksLikePem(bytes: ByteArray): Boolean =
        String(bytes, 0, minOf(bytes.size, 16 * 1024), Charsets.ISO_8859_1).contains("-----BEGIN ")

    /** Блоки PEM: тип (PRIVATE KEY, CERTIFICATE…) и содержимое DER. */
    internal fun pemBlocks(text: String): List<Pair<String, ByteArray>> =
        Regex("-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----").findAll(text).map { m ->
            val body = m.groupValues[2].lines()
            // Старый формат с заголовками: Proc-Type: 4,ENCRYPTED / DEK-Info: …
            if (body.any { it.startsWith("Proc-Type:") && it.contains("ENCRYPTED") }) {
                throw IOException("Ключ зашифрован паролем — сохраните его без пароля")
            }
            val b64 = body.filterNot { it.contains(':') }.joinToString("") { it.trim() }
            m.groupValues[1] to java.util.Base64.getMimeDecoder().decode(b64)
        }.toList()

    /** Есть ли в файле сертификат PEM (например, ключ и сертификат в одном .pem). */
    fun pemHasCertificate(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return false
        String(file.readBytes(), Charsets.ISO_8859_1).contains("-----BEGIN CERTIFICATE-----")
    }.getOrDefault(false)

    // RSAPrivateKey (PKCS#1, «BEGIN RSA PRIVATE KEY») → PrivateKeyInfo (PKCS#8)
    internal fun rsaPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        // SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }
        val algorithm = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        )
        return der(0x30, version + algorithm + der(0x04, pkcs1))
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    /**
     * Хранилище с определением формата по первым байтам — как KeyStore.getInstance(File, …) из Java 9
     * (её нет в Android до 13): JKS — FEEDFEED, JCEKS — CECECECE, иначе PKCS12.
     */
    private fun loadKeystoreAuto(file: File, password: CharArray): KeyStore {
        val head = file.inputStream().use { it.readUpTo(4) }
        fun magic(v: Long) = head.size == 4 && head.foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (24 - 8 * i)) } == v
        val type = when {
            magic(0xFEEDFEEDL) -> "JKS"
            magic(0xCECECECEL) -> "JCEKS"
            else -> "PKCS12"
        }
        return KeyStore.getInstance(type).apply { file.inputStream().use { load(it, password) } }
    }

    private fun openKeystore(file: File, storePassword: String): KeyStore = try {
        loadKeystoreAuto(file, storePassword.toCharArray())
    } catch (e: java.security.KeyStoreException) {
        throw IOException("Формат хранилища не поддерживается (нужен JKS или PKCS12/.p12)", e)
    } catch (e: IOException) {
        if (e.cause is java.security.UnrecoverableKeyException || e.message?.contains("password", true) == true) {
            throw IOException("Неверный пароль хранилища", e)
        }
        throw e
    }

    private fun fromKeystore(file: File, storePassword: String, alias: String, keyPassword: String, name: String): LoadedKey {
        val ks = openKeystore(file, storePassword)
        if (!ks.containsAlias(alias)) throw IOException("В хранилище нет ключа «$alias»")
        val key = try {
            ks.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
        } catch (e: java.security.UnrecoverableKeyException) {
            throw IOException("Неверный пароль ключа «$alias»", e)
        } ?: throw IOException("«$alias» — не закрытый ключ")
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }
            ?: throw IOException("У ключа «$alias» нет сертификата")
        return LoadedKey(key, chain, name)
    }

    /**
     * Проверка ключа в хранилище без импорта: пароли, псевдоним, сертификат.
     * Пустой [keyPassword] — как у хранилища.
     */
    fun inspectKeystore(file: File, storePassword: String, alias: String, keyPassword: String): KeyInfo {
        val loaded = fromKeystore(file, storePassword, alias, keyPassword.ifEmpty { storePassword }, "CERT")
        return infoOf(loaded.certificates.first())
    }

    /** Проверка пары .pk8 + сертификат без импорта (ключ должен подходить к сертификату). */
    fun inspectPk8(pk8: File, cert: File): KeyInfo {
        val key = privateKeyFromPk8(pk8.readBytes())
        val certs = certificatesFrom(cert.readBytes())
        checkPair(key, certs.first())
        return infoOf(certs.first())
    }

    // Подпись случайных данных закрытым ключом и проверка открытым ключом сертификата
    private fun checkPair(key: PrivateKey, cert: X509Certificate) {
        val algorithm = when (key.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> return
        }
        val data = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val ok = runCatching {
            val signature = java.security.Signature.getInstance(algorithm).run {
                initSign(key)
                update(data)
                sign()
            }
            java.security.Signature.getInstance(algorithm).run {
                initVerify(cert.publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!ok) throw IOException("Закрытый ключ не подходит к сертификату")
    }

    /** Псевдонимы закрытых ключей в хранилище (для импорта). */
    fun aliases(file: File, storePassword: String): List<String> {
        val ks = openKeystore(file, storePassword)
        return ks.aliases().toList().filter { ks.isKeyEntry(it) }
    }

    fun importKeystore(file: File, title: String, storePassword: String, alias: String, keyPassword: String): SigningKey {
        // Проверяем до копирования: пароль, псевдоним, сертификат
        fromKeystore(file, storePassword, alias, keyPassword.ifEmpty { storePassword }, "CERT")
        val dir = newKeyDir()
        val ext = file.extension.ifEmpty { "keystore" }
        val target = File(dir, "store.$ext")
        file.copyTo(target, overwrite = true)
        restrict(target)
        val key = SigningKey(
            id = dir.name,
            title = title.trim().ifEmpty { alias },
            kind = KeyKind.KEYSTORE,
            storeFile = target,
            alias = alias,
            storePassword = storePassword,
            keyPassword = keyPassword.ifEmpty { storePassword }
        )
        writeKey(key, dir)
        reload()
        return key
    }

    /** Пара .pk8 + .x509.pem (или один .pem с ключом и сертификатом) — хранится как DER PKCS#8 + PEM. */
    fun importPk8(pk8: File, cert: File, title: String): SigningKey {
        val loaded = LoadedKey(privateKeyFromPk8(pk8.readBytes()), certificatesFrom(cert.readBytes()), "CERT")
        checkPair(loaded.privateKey, loaded.certificates.first())
        val encoded = loaded.privateKey.encoded ?: throw IOException("Закрытый ключ нельзя сохранить")
        val dir = newKeyDir()
        val keyFile = File(dir, "key.pk8").also {
            it.createNewFile()
            restrict(it)
            it.writeBytes(encoded)
        }
        val certFile = File(dir, "cert.pem").also { it.writeText(KeyExporter.certificatesPem(loaded.certificates)) }
        val key = SigningKey(
            id = dir.name,
            title = title.trim().ifEmpty { pk8.nameWithoutExtension.removeSuffix(".x509") },
            kind = KeyKind.PK8,
            storeFile = keyFile,
            certFile = certFile,
            alias = infoOf(loaded.certificates.first()).commonName
        )
        writeKey(key, dir)
        reload()
        return key
    }

    data class NewKeyRequest(
        val title: String,
        val alias: String,
        val password: String,
        val commonName: String,
        val organization: String = "",
        val country: String = "",
        val validityYears: Int = 30,
        val keySize: Int = 2048
    )

    /** Новый ключ RSA в PKCS12 через keytool текущей JVM. */
    fun create(req: NewKeyRequest): SigningKey {
        require(req.alias.isNotBlank()) { "Укажите псевдоним ключа" }
        require(req.password.isEmpty() || req.password.length >= 6) { "Пароль — не короче 6 символов (или оставьте пустым)" }
        // PKCS12 в JDK шифрует ключ только ASCII-паролем
        require(req.password.all { it.code in 0x20..0x7E }) { "Пароль — только латинские буквы, цифры и знаки" }
        // Пустой пароль — генерируем свой: PKCS12 без пароля keytool не создаёт
        val password = req.password.ifEmpty { UUID.randomUUID().toString().replace("-", "") }
        val dir = newKeyDir()
        val store = File(dir, "store.p12")
        try {
            val dname = buildList {
                add("CN=" + escapeDn(req.commonName.ifBlank { req.alias }))
                if (req.organization.isNotBlank()) add("O=" + escapeDn(req.organization))
                if (req.country.isNotBlank()) add("C=" + escapeDn(req.country.uppercase().take(2)))
            }.joinToString(",")
            // Пароль — через окружение: командную строку процесса видят все пользователи машины (ps)
            val out = runKeytool(
                listOf(
                    "-genkeypair", "-noprompt",
                    "-keystore", store.absolutePath, "-storetype", "PKCS12",
                    "-storepass:env", PASSWORD_ENV, "-keypass:env", PASSWORD_ENV,
                    "-alias", req.alias.trim(),
                    "-keyalg", "RSA", "-keysize", req.keySize.toString(),
                    "-validity", (req.validityYears.coerceIn(1, 100) * 365).toString(),
                    "-dname", dname
                ),
                env = mapOf(PASSWORD_ENV to password)
            )
            if (!store.isFile) throw IOException("keytool не создал ключ: $out")
            restrict(store)
            val key = SigningKey(
                id = dir.name,
                title = req.title.trim().ifEmpty { req.alias.trim() },
                kind = KeyKind.KEYSTORE,
                storeFile = store,
                alias = req.alias.trim(),
                storePassword = password,
                keyPassword = password
            )
            writeKey(key, dir)
            reload()
            return key
        } catch (e: Exception) {
            org.wexclient.app.util.FileTree.delete(dir)
            throw e
        }
    }

    // RFC 2253: \ , + " < > ; = экранируются обратным слэшем, # — в начале значения
    private fun escapeDn(value: String): String {
        val sb = StringBuilder()
        value.trim().forEachIndexed { i, c ->
            if (c in "\\,+\"<>;=" || (i == 0 && c == '#')) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun keytool(): String {
        val exe = if (File.separatorChar == '\\') "keytool.exe" else "keytool"
        val bundled = File(System.getProperty("java.home"), "bin/$exe")
        return if (bundled.canExecute()) bundled.absolutePath else "keytool"
    }

    private const val PASSWORD_ENV = "WEX_KEYTOOL_PASSWORD"

    private fun runKeytool(args: List<String>, env: Map<String, String> = emptyMap()): String {
        val process = try {
            ProcessBuilder(listOf(keytool()) + args).redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .startHelper()
        } catch (e: IOException) {
            throw IOException("keytool не найден (нужен JDK)", e)
        }
        // stdin закрываем сразу: иначе keytool ждёт пароль и висит вечно
        runCatching { process.outputStream.close() }
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("keytool не ответил за минуту")
        }
        if (process.exitValue() != 0) throw IOException("keytool: ${out.trim().lines().lastOrNull() ?: "ошибка"}")
        return out
    }

    private fun createDebugKeystore(file: File) {
        file.parentFile.mkdirs()
        runKeytool(
            listOf(
                "-genkeypair", "-noprompt",
                "-alias", "androiddebugkey", "-keypass", "android",
                "-keystore", file.absolutePath, "-storepass", "android",
                "-dname", "CN=Android Debug,O=Android,C=US",
                "-validity", "10000", "-keyalg", "RSA", "-keysize", "2048"
            )
        )
        if (!file.exists()) throw IOException("keytool не смог создать debug.keystore")
    }
}
