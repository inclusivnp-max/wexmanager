package org.wexclient.app.util

import java.awt.GraphicsEnvironment
import java.awt.Window
import javax.swing.JWindow
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SwingLeaksTest {
    @Test
    fun `closed window is dropped from the X11 focus peer`() {
        if (GraphicsEnvironment.isHeadless()) return
        // Не X11 (Windows, macOS) — пира нет, и сбрасывать нечего
        val peerClass = runCatching { Class.forName("sun.awt.X11.XKeyboardFocusManagerPeer") }.getOrNull() ?: return
        val closed = JWindow()
        val other = JWindow()
        val peer = peerClass.getMethod("getInstance").invoke(null)
        val get = peerClass.getMethod("getCurrentFocusedWindow")
        val set = peerClass.getMethod("setCurrentFocusedWindow", Window::class.java)
        val saved = get.invoke(peer)
        try {
            set.invoke(peer, closed)
            // Закрыли другое окно — фокус, который помнит пир, не трогаем
            SwingLeaks.forgetFocus(other)
            assertSame(closed, get.invoke(peer))
            SwingLeaks.forgetFocus(closed)
            assertNull(get.invoke(peer))
        } finally {
            set.invoke(peer, saved)
            closed.dispose()
            other.dispose()
        }
    }
}
