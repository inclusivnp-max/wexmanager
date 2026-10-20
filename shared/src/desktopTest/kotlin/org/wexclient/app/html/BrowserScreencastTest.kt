package org.wexclient.app.html

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Настоящий Chromium: кадры трансляции подтверждаются только после показа, и браузер без подтверждения
 * новых не шлёт; на паузе не шлёт вовсе, а после неё присылает свежий кадр даже неподвижной страницы.
 */
class BrowserScreencastTest {
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun page(html: String) = "data:text/html," + URLEncoder.encode(html, Charsets.UTF_8).replace("+", "%20")

    private class Frames {
        val received = AtomicInteger()
        val acks = ConcurrentLinkedQueue<() -> Unit>()
        fun onFrame(@Suppress("UNUSED_PARAMETER") bytes: ByteArray, ack: () -> Unit) {
            received.incrementAndGet()
            acks.add(ack)
        }
        /** «Показать» всё пришедшее: подтвердить. */
        fun ackAll() {
            while (true) (acks.poll() ?: return).invoke()
        }
    }

    @Test
    fun `unacknowledged frames stop the stream and pause stops it too`() {
        assumeTrue(BrowserSession.findBrowser() != null, "нет Chromium")
        // Страница меняется каждый кадр — браузеру всегда есть что прислать
        val html = "<body style='margin:0'><div id=c style='font:40px sans-serif'>0</div><script>" +
            "let n=0;(function f(){document.getElementById('c').textContent=++n;requestAnimationFrame(f)})()</script></body>"
        val frames = Frames()
        val session = BrowserSession.start(page(html), 320, 240, 1f, frames::onFrame) { _, _ -> }
        try {
            assertTrue(waitFor(15_000) { frames.received.get() >= 2 }, "кадры не пришли")
            // Никто ничего не показал — браузер новых кадров не снимает: в полёте не больше двух на трансляцию
            // (переход на страницу сбрасывает счёт один раз — отсюда запас), и дальше поток стоит
            Thread.sleep(1000)
            val unacked = frames.received.get()
            assertTrue(unacked <= 4, "без подтверждений пришло $unacked кадров")
            Thread.sleep(1000)
            assertTrue(frames.received.get() == unacked, "без подтверждений поток не встал: ${frames.received.get()} после $unacked")

            // Показываем — поток идёт
            val shownFrom = frames.received.get()
            val end = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < end) {
                frames.ackAll()
                Thread.sleep(15)
            }
            assertTrue(frames.received.get() - shownFrom >= 10, "после подтверждений пришло ${frames.received.get() - shownFrom}")

            // Пауза: ни одного нового кадра, даже с подтверждениями
            session.pauseScreencast(true)
            Thread.sleep(300)
            frames.ackAll()
            val paused = frames.received.get()
            Thread.sleep(1000)
            frames.ackAll()
            assertTrue(frames.received.get() - paused <= 1, "на паузе пришло ${frames.received.get() - paused}")

            session.pauseScreencast(false)
            assertTrue(waitFor(5000) { frames.ackAll(); frames.received.get() > paused + 3 }, "после паузы кадры не пошли")
        } finally {
            session.close()
        }
    }

    @Test
    fun `static page gets a fresh frame after pause`() {
        assumeTrue(BrowserSession.findBrowser() != null, "нет Chromium")
        val frames = Frames()
        val session = BrowserSession.start(page("<body><h1>static</h1></body>"), 320, 240, 1f, frames::onFrame) { _, _ -> }
        try {
            assertTrue(waitFor(15_000) { frames.ackAll(); frames.received.get() >= 1 }, "кадр не пришёл")
            Thread.sleep(500)
            frames.ackAll()
            session.pauseScreencast(true)
            Thread.sleep(300)
            val before = frames.received.get()
            session.pauseScreencast(false)
            assertTrue(waitFor(5000) { frames.ackAll(); frames.received.get() > before }, "после паузы неподвижная страница не прислала кадр")
        } finally {
            session.close()
        }
    }
}
