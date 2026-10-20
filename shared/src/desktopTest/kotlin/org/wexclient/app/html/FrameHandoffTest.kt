package org.wexclient.app.html

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameHandoffTest {

    private fun frame() = ImageBitmap(4, 4)
    private val ImageBitmap.closed get() = asSkiaBitmap().isClosed

    @Test
    fun `shown frame closes only older ones`() {
        val handoff = FrameHandoff()
        var state: ImageBitmap? = null
        val a = frame()
        val b = frame()
        val c = frame()
        handoff.publish(a) { state = it }
        handoff.shown(a)
        handoff.publish(b) { state = it }
        handoff.publish(c) { state = it }
        assertSame(c, state)
        assertEquals(2, handoff.pending)

        // Окно показало b (c записан позже): закрыт только a, c ещё впереди
        handoff.shown(b)
        assertTrue(a.closed)
        assertFalse(b.closed)
        assertFalse(c.closed)
        assertEquals(1, handoff.pending)

        // Пропущенный и сменённый кадры закрываются, показанный — нет
        handoff.shown(c)
        assertTrue(b.closed)
        assertFalse(c.closed)
        assertEquals(0, handoff.pending)
    }

    @Test
    fun `unknown frame closes nothing`() {
        val handoff = FrameHandoff()
        val a = frame()
        handoff.publish(a) { }
        handoff.shown(frame())
        assertFalse(a.closed)
        assertEquals(1, handoff.pending)
    }

    @Test
    fun `release closes everything and late frames right away`() {
        val handoff = FrameHandoff()
        val a = frame()
        val b = frame()
        handoff.publish(a) { }
        handoff.shown(a)
        handoff.publish(b) { }
        handoff.releaseAll()
        assertTrue(a.closed)
        assertTrue(b.closed)

        var state: ImageBitmap? = null
        val late = frame()
        handoff.publish(late) { state = it }
        assertNull(state)
        assertTrue(late.closed)
    }

    @Test
    fun `onShown runs once and only for the frame that reached the screen`() {
        val handoff = FrameHandoff()
        val calls = ArrayList<String>()
        val a = frame()
        val b = frame()
        val c = frame()
        handoff.publish(a, onShown = { calls += "a" }) { }
        handoff.publish(b, onShown = { calls += "b" }) { }
        handoff.publish(c, onShown = { calls += "c" }) { }
        assertTrue(calls.isEmpty())
        // Окно сразу показало b: a пропущен — его подтверждение закрывает подтверждение b
        handoff.shown(b)
        assertEquals(listOf("b"), calls)
        handoff.shown(b)
        assertEquals(listOf("b"), calls)
        // После закрытия окна подтверждений не бывает
        handoff.releaseAll()
        handoff.shown(c)
        assertEquals(listOf("b"), calls)
    }

    @Test
    fun `awaitRoom sleeps until the window shows a frame`() = kotlinx.coroutines.runBlocking {
        val handoff = FrameHandoff()
        val a = frame()
        val b = frame()
        val c = frame()
        handoff.publish(a) { }
        handoff.shown(a)
        handoff.publish(b) { }
        handoff.publish(c) { }
        assertEquals(2, handoff.pending)
        val waiter = launch(kotlinx.coroutines.Dispatchers.Default) { handoff.awaitRoom(2) }
        kotlinx.coroutines.delay(200)
        assertFalse(waiter.isCompleted, "очередь полна — ждать")
        handoff.shown(c)
        kotlinx.coroutines.withTimeout(2000) { waiter.join() }
        assertEquals(0, handoff.pending)
    }
}
