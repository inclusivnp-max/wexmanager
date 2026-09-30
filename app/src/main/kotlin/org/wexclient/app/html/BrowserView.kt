package org.wexclient.app.html

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaPrimary

/**
 * Страница как в браузере: рядом работает Chromium без окна, сюда приходит его картинка,
 * а мышь и клавиатура уходят обратно. Скрипты, стили и шрифты — настоящие.
 */
@Composable
fun BrowserView(
    url: String,
    modifier: Modifier = Modifier,
    /** Меняется — страница перезагружается. */
    reloadKey: Int = 0,
    onStatus: (String) -> Unit = {}
) {
    // По адресу: сменился адрес — прежний кадр уходит с экрана в той же композиции, где его закрывает FrameHandoff
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<BrowserSession?>(null) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current.density

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color(0xFF101010))) {
        val cssWidth = maxWidth.value.toInt().coerceAtLeast(320)
        val cssHeight = maxHeight.value.toInt().coerceAtLeast(240)

        // Здесь же, где рисуется кадр: закрытие кадров и смена картинки попадают в одну композицию
        val handoff = remember(url) { FrameHandoff() }
        DisposableEffect(handoff) {
            onDispose { handoff.releaseAll() }
        }

        LaunchedEffect(url) {
            frame = null
            error = null
            ready = false
            val frames = Channel<ByteArray>(Channel.CONFLATED)
            // Кадры разбираются в фоне: раскодировать JPEG в потоке окна — это заметные рывки
            val decoder = launch(Dispatchers.Default) {
                for (bytes in frames) {
                    // Окно не успевает показывать (свёрнуто, подвисло) — ждём, а не копим картинки:
                    // канал хранит только последний кадр, он и покажется
                    while (handoff.pending >= 2) delay(16)
                    val bitmap = BrowserSession.decodeFrame(bytes)
                    if (bitmap != null) {
                        handoff.publish(bitmap) { frame = it }
                        ready = true
                    }
                }
            }
            // Запуск не прерывается на полпути (NonCancellable): иначе при закрытии окна во время запуска
            // (до ~45 с) готовая сессия терялась, и Chromium жил до выхода из программы
            val started = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    BrowserSession.start(
                        url = url,
                        width = cssWidth,
                        height = cssHeight,
                        scale = density,
                        onFrame = { frames.trySend(it) },
                        onEvent = { method, params ->
                            when (method) {
                                "Page.loadEventFired" -> onStatus("страница загружена")
                                "wex.error" -> onStatus(params.get("message")?.asString ?: "сбой связи")
                            }
                        }
                    )
                }
            }
            if (!isActive) {
                started.getOrNull()?.let { s -> Thread { runCatching { s.close() } }.apply { isDaemon = true }.start() }
                decoder.cancel()
                return@LaunchedEffect
            }
            started.onSuccess {
                BrowserSession.live.add(it)
                session = it
            }
            started.onFailure {
                decoder.cancel()
                error = it.message ?: "не удалось запустить браузер"
            }
        }

        // Размер окна поменялся — страница пересчитывается под него
        LaunchedEffect(session, cssWidth, cssHeight) {
            session?.resize(cssWidth, cssHeight, density)
        }

        // «Обновить» перечитывает страницу, браузер при этом остаётся запущенным
        LaunchedEffect(reloadKey) {
            if (reloadKey > 0) session?.reload()
        }

        // Закрываем именно ту сессию, ради которой эффект создан: если читать состояние,
        // при появлении новой сессии закроется она сама
        val started = session
        DisposableEffect(started) {
            onDispose {
                started?.let {
                    BrowserSession.live.remove(it)
                    Thread { runCatching { it.close() } }.apply { isDaemon = true }.start()
                }
            }
        }

        val current = frame
        // Композиция с этим кадром применена — Image рисует уже его, прежние можно закрывать
        DisposableEffect(handoff, current) {
            current?.let(handoff::shown)
            onDispose { }
        }
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { event -> sendKey(session, event) }
                    .pointerInput(session) {
                        val browser = session ?: return@pointerInput
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val x = change.position.x / density
                                val y = change.position.y / density
                                val button = when {
                                    event.buttons.isSecondaryPressed -> "right"
                                    else -> "left"
                                }
                                when (event.type) {
                                    PointerEventType.Move -> browser.mouse("mouseMoved", x, y, "none", 0, 0)
                                    PointerEventType.Press -> {
                                        runCatching { focus.requestFocus() }
                                        browser.mouse("mousePressed", x, y, button, 1, 0)
                                    }
                                    PointerEventType.Release -> browser.mouse("mouseReleased", x, y, button, 1, 0)
                                    PointerEventType.Scroll -> {
                                        val delta = change.scrollDelta
                                        browser.wheel(x, y, -delta.x * 60f, delta.y * 60f, 0)
                                        change.consume()
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
            )
        }

        if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Не удалось показать страницу",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else if (!ready) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                org.wexclient.app.ui.WexSpinner(color = NoctaliaPrimary, modifier = Modifier.size(28.dp))
                Text(
                    text = "Запуск браузера…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Кадры потока (страница, видео) из фонового потока в окно. Сборщик мусора не видит нативную память
 * картинок: при 30–60 кадрах в секунду между сборками копились сотни мегабайт, поэтому кадры закрываем сами.
 * Закрывает их не поток, а окно ([shown]): сменённый в состоянии кадр рисуется до следующей композиции,
 * и закрыть его раньше — значит нарисовать закрытую картинку.
 */
internal class FrameHandoff {
    private val frames = ArrayDeque<ImageBitmap>()
    private var headShown = false
    private var released = false

    /** Сколько кадров ещё не дошло до экрана: окно не успевает — новые не копим. */
    val pending: Int
        get() = synchronized(this) { frames.size - if (headShown) 1 else 0 }

    /**
     * Кадр в очередь и в состояние ([show]) одним шагом: порядок очереди совпадает с порядком записей,
     * поэтому кадр старше показанного в состояние уже не вернётся. Все картинки состояния — только через сюда.
     */
    fun publish(frame: ImageBitmap, show: (ImageBitmap) -> Unit) {
        synchronized(this) {
            if (!released) {
                frames.addLast(frame)
                show(frame)
                return
            }
        }
        // Окно уже закрыто — кадр никто не покажет
        close(frame)
    }

    /** Композиция с [frame] применена: всё, что опубликовано раньше, уже не рисуется. Только из потока окна. */
    fun shown(frame: ImageBitmap) {
        val old = synchronized(this) {
            val index = frames.indexOfFirst { it === frame }
            if (index < 0) return
            headShown = true
            List(index) { frames.removeFirst() }
        }
        old.forEach(::close)
    }

    /** Картинки больше не рисуются (окно закрыто, сменился источник): закрываем все, запоздавшие — по приходу. */
    fun releaseAll() {
        val all = synchronized(this) {
            released = true
            frames.toList().also { frames.clear() }
        }
        all.forEach(::close)
    }

    companion object {
        /** Кадр, который так и не попал в состояние, закрывается сразу. */
        fun close(frame: ImageBitmap) {
            runCatching { frame.asSkiaBitmap().close() }
        }
    }
}

/** Клавиши в браузер: печатные символы и навигация. */
private fun sendKey(session: BrowserSession?, event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val browser = session ?: return false
    val type = when (event.type) {
        KeyEventType.KeyDown -> "keyDown"
        KeyEventType.KeyUp -> "keyUp"
        else -> return false
    }
    val modifiers = (if (event.isAltPressed) 1 else 0) or
        (if (event.isCtrlPressed) 2 else 0) or
        (if (event.isMetaPressed) 4 else 0) or
        (if (event.isShiftPressed) 8 else 0)
    val code = virtualKey(event.key)
    val char = event.utf16CodePoint
    val text = if (char in 32..0x10FFFF && !event.isCtrlPressed && !event.isMetaPressed) {
        String(Character.toChars(char))
    } else null
    // Порядок как в браузере: нажатие без текста, затем отдельное событие символа.
    // Если текст приложить и к нажатию, буква попадёт в поле дважды.
    if (type == "keyDown" && text != null) {
        browser.key("rawKeyDown", code, text, null, modifiers)
        browser.key("char", code, text, text, modifiers)
        return true
    }
    browser.key(type, code, keyName(event.key), null, modifiers)
    return code != 0
}

private fun virtualKey(key: Key): Int = when (key) {
    Key.Backspace -> 8
    Key.Tab -> 9
    Key.Enter -> 13
    Key.Escape -> 27
    Key.Spacebar -> 32
    Key.PageUp -> 33
    Key.PageDown -> 34
    Key.MoveEnd -> 35
    Key.MoveHome -> 36
    Key.DirectionLeft -> 37
    Key.DirectionUp -> 38
    Key.DirectionRight -> 39
    Key.DirectionDown -> 40
    Key.Delete -> 46
    else -> 0
}

private fun keyName(key: Key): String = when (key) {
    Key.Backspace -> "Backspace"
    Key.Tab -> "Tab"
    Key.Enter -> "Enter"
    Key.Escape -> "Escape"
    Key.Spacebar -> " "
    Key.PageUp -> "PageUp"
    Key.PageDown -> "PageDown"
    Key.MoveEnd -> "End"
    Key.MoveHome -> "Home"
    Key.DirectionLeft -> "ArrowLeft"
    Key.DirectionUp -> "ArrowUp"
    Key.DirectionRight -> "ArrowRight"
    Key.DirectionDown -> "ArrowDown"
    Key.Delete -> "Delete"
    else -> ""
}
