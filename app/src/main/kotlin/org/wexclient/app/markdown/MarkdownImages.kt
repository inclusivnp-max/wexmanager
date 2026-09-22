package org.wexclient.app.markdown

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import org.wexclient.app.util.startHelper

/** Картинка для просмотра: растр и размер «в пикселях CSS» (как на GitHub). */
class MdBitmap(val bitmap: ImageBitmap, val cssWidth: Float, val cssHeight: Float)

/**
 * Адрес картинки ведёт в локальную сеть или на этот компьютер: такие запросы — только по щелчку.
 * Документ из чужих рук иначе мог бы «постучаться» в роутер или локальный сервис.
 */
class PrivateAddressException(val host: String) : java.io.IOException("Адрес в локальной сети: $host")

/** Картинки Markdown: файлы рядом с документом, http(s) и data:, растр и SVG. Кэш с пределом по памяти. */
object MarkdownImages {
    private const val MAX_BYTES = 20L shl 20
    private const val MAX_REDIRECTS = 5

    /**
     * Раскодированные картинки живут вне кучи Java (сборщик мусора их не видит), до 64 МБ каждая:
     * без предела за день просмотра заметок набегали сотни мегабайт. Давно не нужные вытесняются.
     */
    private const val CACHE_BYTES = 160L shl 20
    // Порядок доступа (accessOrder) — вытесняется давно не нужное. Без подкласса: у анонимного
    // наследника с Result (value class) в типе ломаются мостовые методы get/put
    private val cache = LinkedHashMap<String, Result<MdBitmap>>(64, 0.75f, true)
    private var cacheBytes = 0L

    private fun sizeOf(r: Result<MdBitmap>): Long = r.getOrNull()?.bitmap?.let { it.width.toLong() * it.height * 4 } ?: 0L

    private fun fromCache(key: String): Result<MdBitmap>? = synchronized(cache) { cache[key] }

    private fun toCache(key: String, value: Result<MdBitmap>) = synchronized(cache) {
        cache.put(key, value)?.let { cacheBytes -= sizeOf(it) }
        cacheBytes += sizeOf(value)
        val it = cache.entries.iterator()
        while (cacheBytes > CACHE_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            cacheBytes -= sizeOf(e.value)
            it.remove()
        }
    }
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            // Переходы — вручную: каждый адрес проверяется
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build()
    }

    /** Loopback, частные, link-local, CGNAT и ULA-адреса. */
    internal fun isPrivate(addr: java.net.InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress) return true
        val b = addr.address
        return when (addr) {
            is java.net.Inet4Address -> {
                val first = b[0].toInt() and 0xFF
                val second = b[1].toInt() and 0xFF
                first == 0 || (first == 100 && second in 64..127) || (first == 198 && second in 18..19) || first >= 240
            }
            is java.net.Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
            else -> true
        }
    }

    private fun checkPublic(uri: URI) {
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Неподдерживаемый адрес" }
        val host = uri.host ?: throw java.io.IOException("Нет адреса сервера")
        val addresses = java.net.InetAddress.getAllByName(host)
        if (addresses.isEmpty() || addresses.any(::isPrivate)) throw PrivateAddressException(host)
    }

    fun key(src: String, baseDir: Path?): String =
        if (isRemote(src) || src.startsWith("data:")) src else (resolve(src, baseDir)?.toString() ?: src)

    fun cached(src: String, baseDir: Path?): MdBitmap? = fromCache(key(src, baseDir))?.getOrNull()

    fun isRemote(src: String) = src.startsWith("http://", true) || src.startsWith("https://", true)

    fun resolve(src: String, baseDir: Path?): Path? {
        val clean = src.substringBefore('#').substringBefore('?').let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrDefault(it) }
        if (clean.isBlank()) return null
        return runCatching {
            when {
                clean.startsWith("file://") -> Path.of(URI(clean))
                clean.startsWith("/") && baseDir != null -> baseDir.fileSystem.getPath(clean)
                baseDir != null -> baseDir.resolve(clean).normalize()
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Блокирующая загрузка — вызывать не из потока окна.
     * [allowPrivate] — пользователь сам попросил загрузить картинку из локальной сети.
     */
    fun load(src: String, baseDir: Path?, allowPrivate: Boolean = false): Result<MdBitmap> {
        val key = key(src, baseDir)
        fromCache(key)?.let { cached ->
            if (!(allowPrivate && cached.exceptionOrNull() is PrivateAddressException)) return cached
        }
        val result = runCatching {
            val bytes = when {
                src.startsWith("data:") -> {
                    val comma = src.indexOf(',')
                    require(comma > 0 && src.substring(0, comma).endsWith(";base64")) { "Неподдерживаемый data-адрес" }
                    Base64.getDecoder().decode(src.substring(comma + 1))
                }
                isRemote(src) -> fetchRemote(src, allowPrivate)
                else -> {
                    val path = resolve(src, baseDir) ?: error("Путь не найден")
                    require(Files.isRegularFile(path)) { "Нет файла ${path.fileName}" }
                    require(Files.size(path) <= MAX_BYTES) { "Слишком большой файл" }
                    Files.readAllBytes(path)
                }
            }
            decode(bytes)
        }
        toCache(key, result)
        return result
    }

    private fun fetchRemote(src: String, allowPrivate: Boolean): ByteArray {
        var uri = URI(src)
        var hops = 0
        while (true) {
            if (!allowPrivate) checkPublic(uri)
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "WEXDesktop")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val code = response.statusCode()
            if (code in 300..399) {
                response.body().close()
                val location = response.headers().firstValue("Location").orElse(null)
                    ?: throw java.io.IOException("HTTP $code без адреса")
                if (++hops > MAX_REDIRECTS) throw java.io.IOException("Слишком много перенаправлений")
                val next = uri.resolve(location)
                // С https на http не переходим
                if (uri.scheme.equals("https", true) && !next.scheme.equals("https", true)) {
                    throw java.io.IOException("Перенаправление на незащищённый адрес")
                }
                uri = next
                continue
            }
            response.body().use { body ->
                require(code in 200..299) { "HTTP $code" }
                return body.readNBytes(MAX_BYTES.toInt())
            }
        }
    }

    private fun isSvg(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.UTF_8).trimStart()
        return head.startsWith("<") && head.contains("<svg")
    }

    private val rsvg: Boolean by lazy { org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("rsvg-convert") }

    /** librsvg рисует и текст (значки shields.io), SVG-движок Skia без шрифтов текст пропускает. */
    private fun rasterizeWithRsvg(bytes: ByteArray): MdBitmap? {
        if (!rsvg) return null
        return runCatching {
            val process = ProcessBuilder("rsvg-convert", "-z", "2", "-f", "png").startHelper()
            val writer = Thread {
                runCatching { process.outputStream.use { it.write(bytes) } }
            }.apply {
                isDaemon = true
                start()
            }
            val png = process.inputStream.use { it.readNBytes(MAX_BYTES.toInt()) }
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            writer.join(1000)
            if (process.exitValue() != 0 || png.isEmpty()) return null
            raster(png)?.let { (bitmap, w, h) -> MdBitmap(bitmap, w / 2f, h / 2f) }
        }.getOrNull()
    }

    fun decode(bytes: ByteArray): MdBitmap {
        if (isSvg(bytes)) {
            rasterizeWithRsvg(bytes)?.let { return it }
            val painter = loadSvgPainter(bytes.inputStream(), Density(1f))
            val intrinsic = painter.intrinsicSize
            val w = if (intrinsic.width.isFinite() && intrinsic.width > 0) intrinsic.width else 256f
            val h = if (intrinsic.height.isFinite() && intrinsic.height > 0) intrinsic.height else 256f
            // С запасом для чёткости на HiDPI
            val scale = 2f
            val bitmap = ImageBitmap((w * scale).toInt().coerceIn(1, 4096), (h * scale).toInt().coerceIn(1, 4096))
            val size = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(painter) { draw(size) }
            }
            return MdBitmap(bitmap, w, h)
        }
        val (bitmap, w, h) = raster(bytes) ?: throw java.io.IOException("Картинка слишком большая или повреждена")
        return MdBitmap(bitmap, w.toFloat(), h.toFloat())
    }

    /**
     * Растровая картинка с проверкой размеров до декодирования (не больше 64 Мп) и уменьшенная для показа;
     * возвращает и исходные размеры — они задают размер на странице.
     */
    private fun raster(bytes: ByteArray): Triple<androidx.compose.ui.graphics.ImageBitmap, Int, Int>? {
        val (w, h) = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.width to it.height } }.getOrNull() ?: return null
        val bitmap = org.wexclient.app.util.ImageScale.decodeScaled(bytes, maxPx = 4096, maxSourcePx = 16384, maxSourcePixels = 64_000_000)
            ?: return null
        return Triple(bitmap, w, h)
    }
}
