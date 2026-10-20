package org.wexclient.app.util

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Подготовка и кэширование значков окна в фоновом потоке. */
object WindowIcon {

    @Volatile
    private var images: List<Image>? = null

    private val lock = Any()
    private var started = false
    private val waiting = mutableListOf<java.awt.Window>()

    /** Начать готовить значок. Вызывается при запуске, до создания окон. */
    fun prepare() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Thread({
            val ready = runCatching { build() }.getOrNull() ?: emptyList()
            images = ready
            if (ready.isEmpty()) return@Thread
            val pending = synchronized(lock) { waiting.toList().also { waiting.clear() } }
            if (pending.isEmpty()) return@Thread
            javax.swing.SwingUtilities.invokeLater {
                pending.forEach { window -> runCatching { window.iconImages = ready } }
            }
        }, "wex-icon").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Поставить значок окну. Готов — сразу, иначе окно получит его чуть позже само.
     * Вызывать из потока окна.
     */
    fun applyTo(window: java.awt.Window) {
        images?.let {
            if (it.isNotEmpty()) runCatching { window.iconImages = it }
            return
        }
        synchronized(lock) {
            val ready = images
            if (ready != null) {
                if (ready.isNotEmpty()) runCatching { window.iconImages = ready }
                return
            }
            waiting.add(window)
        }
        prepare()
    }

    private fun build(): List<Image> {
        // Читаем целиком в память: распаковка прямо из jar идёт мелкими кусками и заметно медленнее
        val bytes = WindowIcon::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return emptyList()
        val base = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return emptyList()
        return SIZES.map { size ->
            if (size == base.width && size == base.height) return@map base
            val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(base, 0, 0, size, size, null)
            g.dispose()
            scaled
        }
    }

    private const val RESOURCE = "/icons/wexdesktop.png"

    /** Размеры, которые спрашивают панель задач, переключатель окон и заголовок. */
    private val SIZES = listOf(16, 24, 32, 48, 64, 128, 256)
}
