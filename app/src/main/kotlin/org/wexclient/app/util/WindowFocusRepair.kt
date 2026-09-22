package org.wexclient.app.util

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowEvent
import java.util.Collections
import java.util.WeakHashMap

/**
 * Wayland (niri, sway, Hyprland) через Xwayland: если вернуться в окно с другого рабочего стола
 * или из соседнего окна, AWT не получает событий фокуса — ни «активировано», ни «фокус получен».
 * Нажатия клавиш при этом X-сервер окну шлёт, но AWT их выбрасывает, считая окно неактивным:
 * не работают F-клавиши, Tab, Delete, консоль — пока не щёлкнешь мышью.
 *
 * События мыши приходят и без фокуса. Движение мыши над окном — верный признак, что окно снова
 * наше: по нему приводим учёт фокуса внутри AWT в порядок. У X-сервера при этом ничего не просим,
 * так что чужой фокус ввода не отбираем — чиним только своё представление о нём.
 */
object WindowFocusRepair {

    /** Только под Wayland: на обычном X11 события фокуса приходят как положено. */
    val needed: Boolean by lazy {
        System.getenv("WAYLAND_DISPLAY") != null || System.getenv("XDG_SESSION_TYPE") == "wayland"
    }

    private val attached: MutableSet<Window> = Collections.newSetFromMap(WeakHashMap())

    /** Следит за всеми окнами программы, включая отдельные окна просмотра и правки. */
    fun install() {
        if (!needed) return
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            val window = event.source as? Window ?: return@addAWTEventListener
            if (event.id == WindowEvent.WINDOW_OPENED) attach(window)
        }, AWTEvent.WINDOW_EVENT_MASK)
    }

    private fun attach(window: Window) {
        if (!attached.add(window)) return
        val watcher = object : MouseAdapter() {
            private var lastRepair = 0L

            private fun repair() {
                if (window.isFocused || !window.isShowing) return
                val now = System.nanoTime()
                if (now - lastRepair < THROTTLE_NANOS) return
                lastRepair = now
                restore(window)
            }

            override fun mouseMoved(e: MouseEvent?) = repair()
            override fun mouseDragged(e: MouseEvent?) = repair()
            override fun mouseEntered(e: MouseEvent?) = repair()
            override fun mousePressed(e: MouseEvent?) = repair()
            override fun mouseWheelMoved(e: MouseWheelEvent?) = repair()
        }
        window.addMouseListener(watcher)
        window.addMouseMotionListener(watcher)
        window.addMouseWheelListener(watcher)
    }

    /**
     * Говорим AWT то, что уже сделал оконный менеджер: окно активно, клавиатура — у него.
     * Отдельно возвращаем фокус холсту Compose, иначе нажатия дойдут до окна и там и останутся.
     */
    private fun restore(window: Window) {
        runCatching {
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_ACTIVATED))
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS))
            val target = window.focusTraversalPolicy?.getDefaultComponent(window) ?: deepestFocusable(window)
            if (target != null) {
                target.requestFocusInWindow()
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner == null) {
                    Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
                        FocusEvent(target, FocusEvent.FOCUS_GAINED, false, null)
                    )
                }
            }
        }
    }

    /** Самый глубокий видимый компонент, который умеет принимать фокус (холст Compose). */
    private fun deepestFocusable(component: Component): Component? {
        if (component is Container) {
            for (child in component.components.reversed()) deepestFocusable(child)?.let { return it }
        }
        val itself = component.isFocusable && component.isShowing &&
            component !is Window && component !is javax.swing.JRootPane
        return if (itself) component else null
    }

    private const val THROTTLE_NANOS = 300_000_000L
}
