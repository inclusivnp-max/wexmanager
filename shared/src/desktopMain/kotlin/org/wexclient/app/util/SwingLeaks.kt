package org.wexclient.app.util

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import javax.swing.RepaintManager
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Освобождает ссылки Swing RepaintManager и X11-пира фокуса на закрытые окна.
 * Для доступа к пиру нужен --add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED.
 * На других платформах очистка X11 пропускается.
 */
object SwingLeaks {
    fun install() {
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (event.id == WindowEvent.WINDOW_CLOSED) {
                val closed = (event as WindowEvent).window
                SwingUtilities.invokeLater {
                    repaintAlive()
                    forgetFocus(closed)
                    // Картинки, раскладки и шрифты закрытого окна — нативная память Skia, которую отдаёт только
                    // сборка мусора; куча почти пуста, и сама она не приходит (пауз нет: сборка параллельная)
                    NativeMemory.releaseSoon()
                }
            }
        }, AWTEvent.WINDOW_EVENT_MASK)
    }

    private fun repaintAlive() {
        val alive = Window.getWindows().firstOrNull { it.isDisplayable && it.isShowing && it is RootPaneContainer }
        runCatching {
            if (alive != null) (alive as RootPaneContainer).rootPane?.paintImmediately(0, 0, 1, 1)
            else RepaintManager.setCurrentManager(null)
        }
    }

    internal fun forgetFocus(closed: Window) {
        runCatching {
            val peerClass = Class.forName("sun.awt.X11.XKeyboardFocusManagerPeer")
            val peer = peerClass.getMethod("getInstance").invoke(null)
            if (peerClass.getMethod("getCurrentFocusedWindow").invoke(peer) === closed) {
                peerClass.getMethod("setCurrentFocusedWindow", Window::class.java).invoke(peer, null)
            }
            val owner = peerClass.getMethod("getCurrentFocusOwner").invoke(peer) as? Component
            if (owner != null && (owner === closed || SwingUtilities.getWindowAncestor(owner) === closed)) {
                peerClass.getMethod("setCurrentFocusOwner", Component::class.java).invoke(peer, null)
            }
        }
    }
}
