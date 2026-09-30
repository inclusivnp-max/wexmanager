package org.wexclient.app.html

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.wexclient.app.util.frozen
import org.wexclient.app.util.startHelper

/**
 * Настоящий предпросмотр страницы: рядом запускается установленный в системе Chromium
 * без окна, а его картинка приходит сюда потоком кадров по протоколу DevTools. Стили,
 * шрифты и скрипты работают как в браузере, мышь и клавиатура передаются туда же.
 *
 * Свой движок в программу не встраивается: это сотни мегабайт и чужой код в одном процессе
 * с файловым менеджером. Здесь браузер — отдельный процесс, который можно закрыть в любой миг.
 */
class BrowserSession private constructor(
    private val process: Process,
    private val profile: Path,
    private val socket: WebSocket,
    private val onFrame: (ByteArray) -> Unit,
    private val onEvent: (String, JsonObject) -> Unit
) : AutoCloseable {

    private val nextId = AtomicInteger(1)
    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wex-browser-send").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    /** Команда браузеру; ответ не ждём — кадры приходят событиями. */
    fun send(method: String, params: JsonObject = JsonObject()) {
        if (closed) return
        val message = JsonObject().apply {
            addProperty("id", nextId.getAndIncrement())
            addProperty("method", method)
            add("params", params)
        }
        val text = message.toString()
        // Отправка строго по одной: WebSocket не принимает второй кадр, пока не ушёл первый
        sender.execute {
            runCatching { socket.sendText(text, true).get(5, TimeUnit.SECONDS) }
        }
    }

    fun navigate(url: String) = send("Page.navigate", JsonObject().apply { addProperty("url", url) })

    fun reload() = send("Page.reload", JsonObject().apply { addProperty("ignoreCache", true) })

    /** Размер страницы в точках вместе с плотностью экрана. */
    fun resize(width: Int, height: Int, scale: Float) {
        if (width <= 0 || height <= 0) return
        send(
            "Emulation.setDeviceMetricsOverride",
            JsonObject().apply {
                addProperty("width", width)
                addProperty("height", height)
                addProperty("deviceScaleFactor", scale)
                addProperty("mobile", false)
            }
        )
        startScreencast(width, height, scale)
    }

    fun startScreencast(width: Int, height: Int, scale: Float) {
        send(
            "Page.startScreencast",
            JsonObject().apply {
                addProperty("format", "jpeg")
                addProperty("quality", 80)
                addProperty("maxWidth", (width * scale).toInt().coerceAtLeast(1))
                addProperty("maxHeight", (height * scale).toInt().coerceAtLeast(1))
                addProperty("everyNthFrame", 1)
            }
        )
    }

    fun mouse(type: String, x: Float, y: Float, button: String, clickCount: Int, modifiers: Int) {
        send(
            "Input.dispatchMouseEvent",
            JsonObject().apply {
                addProperty("type", type)
                addProperty("x", x)
                addProperty("y", y)
                addProperty("button", button)
                addProperty("clickCount", clickCount)
                addProperty("modifiers", modifiers)
            }
        )
    }

    fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float, modifiers: Int) {
        send(
            "Input.dispatchMouseEvent",
            JsonObject().apply {
                addProperty("type", "mouseWheel")
                addProperty("x", x)
                addProperty("y", y)
                addProperty("deltaX", deltaX)
                addProperty("deltaY", deltaY)
                addProperty("modifiers", modifiers)
                addProperty("button", "none")
            }
        )
    }

    fun key(type: String, keyCode: Int, key: String, text: String?, modifiers: Int) {
        send(
            "Input.dispatchKeyEvent",
            JsonObject().apply {
                addProperty("type", type)
                addProperty("windowsVirtualKeyCode", keyCode)
                addProperty("nativeVirtualKeyCode", keyCode)
                addProperty("key", key)
                addProperty("modifiers", modifiers)
                if (text != null) {
                    addProperty("text", text)
                    addProperty("unmodifiedText", text)
                }
            }
        )
    }

    private fun handle(text: String) {
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val method = json.get("method")?.asString ?: return
        val params = json.getAsJsonObject("params") ?: JsonObject()
        if (method == "Page.screencastFrame") {
            val data = params.get("data")?.asString
            val sessionId = params.get("sessionId")?.asInt
            if (sessionId != null) {
                send("Page.screencastFrameAck", JsonObject().apply { addProperty("sessionId", sessionId) })
            }
            if (data != null) {
                runCatching { onFrame(Base64.getDecoder().decode(data)) }
            }
            return
        }
        onEvent(method, params)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { send("Page.stopScreencast") }
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "закрыто") }
        sender.shutdownNow()
        runCatching { process.destroy() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) runCatching { process.destroyForcibly() }
        runCatching { profile.toFile().deleteRecursively() }
    }

    companion object {
        private val BROWSERS = listOf(
            "chromium", "chromium-browser", "google-chrome-stable", "google-chrome",
            "brave", "brave-browser", "microsoft-edge", "vivaldi-stable", "thorium-browser"
        )

        /**
         * Браузер, который умеет отдавать картинку страницы (Chromium и родня). Ищется по PATH без
         * запуска процессов (раньше — до девяти запусков which в потоке окна) и запоминается.
         */
        fun findBrowser(): String? = foundBrowser

        private val foundBrowser: String? by lazy {
            if (java.io.File.separatorChar == '\\') return@lazy windowsBrowser()
            val dirs = (System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin").split(':').filter { it.isNotBlank() }
            BROWSERS.firstOrNull { name -> dirs.any { dir -> java.io.File(dir, name).let { it.isFile && it.canExecute() } } }
        }

        /** Windows: Chromium-браузеры не в PATH, а в Program Files / AppData; Edge есть всегда. */
        private fun windowsBrowser(): String? {
            val roots = listOfNotNull(
                System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")
            )
            val rel = listOf(
                "Google\\Chrome\\Application\\chrome.exe",
                "Chromium\\Application\\chrome.exe",
                "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                "Microsoft\\Edge\\Application\\msedge.exe"
            )
            return rel.asSequence().flatMap { r -> roots.asSequence().map { java.io.File(it, r) } }
                .firstOrNull { it.isFile }?.absolutePath
        }

        /**
         * Запускает браузер и подключается к нему. [onFrame] получает кадры (JPEG),
         * [onEvent] — события страницы (загрузка, ошибки).
         */
        fun start(
            url: String,
            width: Int,
            height: Int,
            scale: Float,
            onFrame: (ByteArray) -> Unit,
            onEvent: (String, JsonObject) -> Unit
        ): BrowserSession {
            val browser = findBrowser() ?: throw java.io.IOException("Не найден Chromium — установите его или откройте страницу в браузере")
            sweepOldProfiles()
            val profile = Files.createTempDirectory("wex-preview")
            val process = ProcessBuilder(
                browser,
                "--headless=new",
                "--remote-debugging-port=0",
                "--user-data-dir=$profile",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-gpu",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--mute-audio",
                "--window-size=${(width * scale).toInt()},${(height * scale).toInt()}",
                "about:blank"
            ).redirectErrorStream(true).startHelper()

            try {
                val port = awaitPort(profile, process)
                val wsUrl = pageSocket(port)
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                lateinit var session: BrowserSession
                val listener = object : WebSocket.Listener {
                    private val buffer = StringBuilder()

                    override fun onOpen(webSocket: WebSocket) = webSocket.request(1)

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        // Большие кадры приходят кусками — собираем до последнего
                        buffer.append(data)
                        if (last) {
                            val text = buffer.toString()
                            buffer.setLength(0)
                            runCatching { session.handle(text) }
                        }
                        webSocket.request(1)
                        return null
                    }

                    override fun onError(webSocket: WebSocket, error: Throwable) {
                        onEvent("wex.error", JsonObject().apply { addProperty("message", error.message ?: "сбой связи") })
                    }
                }
                val socket = client.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(15, TimeUnit.SECONDS)
                session = BrowserSession(process, profile, socket, onFrame, onEvent)
                session.send("Page.enable")
                session.send("Runtime.enable")
                session.resize(width, height, scale)
                session.navigate(url)
                return session
            } catch (e: Throwable) {
                runCatching { process.destroyForcibly() }
                runCatching { profile.toFile().deleteRecursively() }
                throw e
            }
        }

        /** Профили от прошлых запусков (например, после падения) — чтобы не копились во временной папке. */
        private fun sweepOldProfiles() {
            runCatching {
                val temp = java.io.File(System.getProperty("java.io.tmpdir"))
                val old = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                temp.listFiles { f -> f.isDirectory && f.name.startsWith("wex-preview") && f.lastModified() < old }
                    ?.forEach { it.deleteRecursively() }
            }
        }

        /** Браузер пишет выбранный порт в файл профиля. */
        private fun awaitPort(profile: Path, process: Process): Int {
            val file = profile.resolve("DevToolsActivePort")
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) throw java.io.IOException("Браузер не запустился")
                if (Files.isRegularFile(file)) {
                    val port = runCatching { Files.readAllLines(file).firstOrNull()?.trim()?.toInt() }.getOrNull()
                    if (port != null && port > 0) return port
                }
                Thread.sleep(100)
            }
            throw java.io.IOException("Браузер не ответил за 20 секунд")
        }

        /** Адрес страницы в браузере, к которому подключаемся. */
        private fun pageSocket(port: Int): String {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val deadline = System.currentTimeMillis() + 10_000
            var last: String? = null
            while (System.currentTimeMillis() < deadline) {
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val body = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull()
                if (body != null) {
                    last = body
                    val targets = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull()
                    val page = targets?.firstOrNull { it.asJsonObject.get("type")?.asString == "page" }
                    val ws = page?.asJsonObject?.get("webSocketDebuggerUrl")?.asString
                    if (ws != null) return ws
                }
                Thread.sleep(150)
            }
            throw java.io.IOException("Страница в браузере не открылась (${last?.take(120)})")
        }

        /**
         * Разделяемый разбор кадров: JPEG из браузера в картинку Compose. Картинка — нативная память,
         * закрывает её показавший (BrowserView через FrameHandoff), а не сборщик мусора.
         */
        fun decodeFrame(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
            val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
            try {
                image.toComposeImageBitmap().frozen()
            } finally {
                image.close()
            }
        }.getOrNull()

        /** Сессии, о которых надо не забыть при выходе. */
        internal val live = ConcurrentHashMap.newKeySet<BrowserSession>()

        fun closeAll() {
            live.forEach { runCatching { it.close() } }
            live.clear()
        }
    }
}
