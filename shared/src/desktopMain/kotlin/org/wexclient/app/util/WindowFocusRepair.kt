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
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.util.Collections
import java.util.WeakHashMap

/**
 * Wayland (niri, sway, Hyprland) через Xwayland: если вернуться в окно с другого рабочего стола
 * или из соседнего окна, AWT не получает событий фокуса — ни «активировано», ни «фокус получен».
 * Нажатия клавиш при этом X-сервер окну шлёт, но AWT их выбрасывает, считая окно неактивным:
 * не работают F-клавиши, Tab, Delete, консоль — пока не щёлкнешь мышью.
 *
 * События мыши приходят и без фокуса. Движение мыши над окном — повод проверить, не наше ли окно
 * снова: если X-сервер говорит, что фокус у нас ([XFocus]), приводим учёт фокуса внутри AWT в порядок.
 * У X-сервера при этом ничего не просим, так что чужой фокус ввода не отбираем.
 *
 * Обратное тоже чиним: пока AWT считает окно активным, раз в секунду сверяемся с X-сервером и, если
 * фокус ушёл, говорим AWT об этом. Раньше мышь, проведённая над окном при работе в соседней программе,
 * делала окно «активным» навсегда: живой фон рисовал кадры без конца, а закрытое окно оставалось
 * в учёте фокуса AWT и не освобождалось из памяти.
 */
object WindowFocusRepair {

    /** Только под Wayland: на обычном X11 события фокуса приходят как положено. */
    val needed: Boolean by lazy {
        System.getenv("WAYLAND_DISPLAY") != null || System.getenv("XDG_SESSION_TYPE") == "wayland"
    }

    private val attached: MutableSet<Window> = Collections.newSetFromMap(WeakHashMap())

    /** Выключатель в настройках: читается при каждом событии, так что действует сразу. */
    private var enabled: () -> Boolean = { true }

    /** Следит за всеми окнами программы, включая отдельные окна просмотра и правки. */
    fun install(enabled: () -> Boolean = { true }) {
        if (!needed) return
        this.enabled = enabled
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            val window = event.source as? Window ?: return@addAWTEventListener
            if (event.id == WindowEvent.WINDOW_OPENED) attach(window)
        }, AWTEvent.WINDOW_EVENT_MASK)
    }

    private fun attach(window: Window) {
        if (!attached.add(window)) return

        // Сторож работает только пока AWT считает окно активным: в простое без фокуса пробуждений нет.
        // Нужен и без наших подсказок: уход в окно Wayland Xwayland не сообщает — AWT так и считает
        // окно активным
        val watchdog = javax.swing.Timer(WATCH_MS) { e ->
            if (!enabled() || !window.isShowing || !window.isFocused) {
                (e.source as javax.swing.Timer).stop()
            } else if (XFocus.owns(window) == false) {
                release(window)
            }
        }
        fun watch() {
            if (enabled() && window.isFocused && !watchdog.isRunning) watchdog.start()
        }

        val watcher = object : MouseAdapter() {
            private var lastRepair = 0L

            private fun repair() {
                if (!enabled() || !window.isShowing) return
                if (window.isFocused) {
                    watch()
                    return
                }
                val now = System.nanoTime()
                if (now - lastRepair < THROTTLE_NANOS) return
                lastRepair = now
                // Мышь просто прошла над окном, а клавиатура у соседней программы — окно не наше
                if (XFocus.owns(window) == false) return
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

        window.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) = watch()
            override fun windowLostFocus(e: WindowEvent?) = watchdog.stop()
        })
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                watchdog.stop()
                // Учёт фокуса AWT держит окно сильными ссылками: закрытое окно со всем содержимым
                // иначе живёт в памяти, пока фокус не получит другое
                val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                if (kfm.focusedWindow === window || kfm.activeWindow === window) release(window)
            }
        })
        // Первый фокус нового окна приходит раньше WINDOW_OPENED — слушатель выше его уже не услышит
        watch()
    }

    /** Говорим AWT то, что уже сделал оконный менеджер: фокус ушёл в другую программу. */
    private fun release(window: Window) {
        runCatching {
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_LOST_FOCUS))
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_DEACTIVATED))
        }
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
    private const val WATCH_MS = 1000
}
