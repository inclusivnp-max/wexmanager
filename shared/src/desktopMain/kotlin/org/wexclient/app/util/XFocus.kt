package org.wexclient.app.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/**
 * Чей фокус ввода на самом деле — по мнению X-сервера (под Wayland это Xwayland, и он честно
 * переносит фокус вслед за композитором: наше окно в фокусе — фокус у него, окно Wayland — «ни у кого»).
 *
 * Соединение с дисплеем своё: соединение AWT не трогаем, блокировка AWT не нужна. Запросы дерева окон
 * идут только к своим окнам — ошибка X (BadWindow) у Xlib по умолчанию завершает процесс, а свои окна,
 * пока они на экране, посреди запроса не исчезнут. Вызывать из потока окон.
 */
internal object XFocus {
    @Suppress("FunctionName")
    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XGetInputFocus(display: Pointer, focus: NativeLongByReference, revertTo: IntByReference): Int
        fun XQueryTree(
            display: Pointer,
            w: NativeLong,
            root: NativeLongByReference,
            parent: NativeLongByReference,
            children: PointerByReference,
            count: IntByReference
        ): Int
        fun XFree(data: Pointer): Int
    }

    private var lib: Xlib? = null
    private var display: Pointer? = null
    private var failed = false

    private fun connect(): Pair<Xlib, Pointer>? {
        if (failed) return null
        val l = lib
        val d = display
        if (l != null && d != null) return l to d
        return runCatching {
            val x = Native.load("X11", Xlib::class.java)
            val dpy = x.XOpenDisplay(null) ?: error("нет X-дисплея")
            lib = x
            display = dpy
            x to dpy
        }.getOrElse {
            failed = true
            null
        }
    }

    /**
     * Фокус ввода X-сервера — в [window] (в нём самом или в его дочерних окнах, например у прокси фокуса AWT).
     * null — узнать нельзя (нет X11 или JNA, у окна ещё нет X-окна): тогда решают по-старому.
     */
    @Synchronized
    fun owns(window: Window): Boolean? {
        if (!window.isShowing) return false
        val handle = handleOf(window) ?: return null
        val (x, dpy) = connect() ?: return null
        return runCatching {
            val focus = NativeLongByReference()
            x.XGetInputFocus(dpy, focus, IntByReference())
            when (val f = focus.value.toLong()) {
                NONE -> false            // фокус ни у одного X-окна — он у окна Wayland
                POINTER_ROOT -> null     // фокус «за мышью»: судить не по чему
                else -> {
                    val top = topLevel(x, dpy, handle) ?: return@runCatching null
                    f == top || f in descendants(x, dpy, top)
                }
            }
        }.getOrNull()
    }

    private fun handleOf(window: Window): Long? {
        val h = when (window) {
            is androidx.compose.ui.awt.ComposeWindow -> window.windowHandle
            is androidx.compose.ui.awt.ComposeDialog -> window.windowHandle
            else -> runCatching { Native.getWindowID(window) }.getOrNull()
        }
        return h?.takeIf { it != 0L }
    }

    /** Окно верхнего уровня (дочернее для корня), в котором лежит [start]. */
    private fun topLevel(x: Xlib, dpy: Pointer, start: Long): Long? {
        var w = start
        repeat(MAX_DEPTH) {
            val (root, parent, _) = query(x, dpy, w) ?: return null
            if (parent == 0L || parent == root) return w
            w = parent
        }
        return null
    }

    /** Все окна под [top]: у AWT их немного (содержимое, холст, прокси фокуса). */
    private fun descendants(x: Xlib, dpy: Pointer, top: Long): Set<Long> {
        val out = HashSet<Long>()
        val queue = ArrayDeque<Long>().apply { add(top) }
        while (queue.isNotEmpty() && out.size < MAX_WINDOWS) {
            val (_, _, kids) = query(x, dpy, queue.removeFirst()) ?: continue
            for (k in kids) if (out.add(k)) queue.add(k)
        }
        return out
    }

    private fun query(x: Xlib, dpy: Pointer, w: Long): Triple<Long, Long, LongArray>? {
        val root = NativeLongByReference()
        val parent = NativeLongByReference()
        val children = PointerByReference()
        val count = IntByReference()
        if (x.XQueryTree(dpy, NativeLong(w), root, parent, children, count) == 0) return null
        val list = children.value
        val kids = if (list == null || count.value <= 0) LongArray(0) else LongArray(count.value) {
            list.getNativeLong(it.toLong() * NativeLong.SIZE).toLong()
        }
        list?.let { x.XFree(it) }
        return Triple(root.value.toLong(), parent.value.toLong(), kids)
    }

    private const val NONE = 0L
    private const val POINTER_ROOT = 1L
    private const val MAX_DEPTH = 32
    private const val MAX_WINDOWS = 256
}
