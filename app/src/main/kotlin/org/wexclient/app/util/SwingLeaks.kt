package org.wexclient.app.util

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Swing (BufferStrategyPaintManager.rootJ) держит корень последнего окна, которое рисовал сам. Закрытое окно
 * просмотра оставалось так в памяти целиком — со всей композицией и раскладкой текста (на 60 000 строк —
 * сотни МБ), пока не закроют следующее. После закрытия окна перерисовываем точку живого: ссылка переходит к нему.
 *
 * То же у X11-пира фокуса (XKeyboardFocusManagerPeer): он помнит последнее окно в фокусе и компонент в нём,
 * а забывает их только по событию X «фокус ушёл». Под Wayland (Xwayland) его не бывает — закрытый диалог
 * (DEX Editor Plus со всеми разобранными классами и редакторами smali) висел в памяти, пока фокус не придёт
 * в другое окно WEX. Сбрасываем эти ссылки сами, как сделал бы AWT; нужен флаг JVM
 * `--add-exports java.desktop/sun.awt.X11=ALL-UNNAMED`, без него и не под X11 — ничего не делаем.
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
        runCatching { (alive as? RootPaneContainer)?.rootPane?.paintImmediately(0, 0, 1, 1) }
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
