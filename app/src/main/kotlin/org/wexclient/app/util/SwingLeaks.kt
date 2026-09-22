package org.wexclient.app.util

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Swing (BufferStrategyPaintManager.rootJ) держит корень последнего окна, которое рисовал сам. Закрытое окно
 * просмотра оставалось так в памяти целиком — со всей композицией и раскладкой текста (на 60 000 строк —
 * сотни МБ), пока не закроют следующее. После закрытия окна перерисовываем точку живого: ссылка переходит к нему.
 */
object SwingLeaks {
    fun install() {
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (event.id == WindowEvent.WINDOW_CLOSED) SwingUtilities.invokeLater(::repaintAlive)
        }, AWTEvent.WINDOW_EVENT_MASK)
    }

    private fun repaintAlive() {
        val alive = Window.getWindows().firstOrNull { it.isDisplayable && it.isShowing && it is RootPaneContainer }
        runCatching { (alive as? RootPaneContainer)?.rootPane?.paintImmediately(0, 0, 1, 1) }
    }
}
