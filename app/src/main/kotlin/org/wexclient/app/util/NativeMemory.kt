package org.wexclient.app.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Раскладка большого текста живёт в памяти Skia, вне кучи Java, и освобождается только сборкой мусора.
 * Куча при этом почти пустая, сборка сама не приходит: после нескольких открытий файла в 60 000 строк
 * процесс занимал на 800 МБ больше. После закрытия такого окна сборку просим сами.
 */
object NativeMemory {
    private val pending = AtomicBoolean(false)

    /**
     * Кэш раскладок Skia (ParagraphCache, свой у каждого окна) хранит до 128 последних абзацев целиком.
     * В редакторе каждое нажатие — новый абзац: в файле на 60 000 строк десять символов занимали +2,9 ГБ.
     * Новому тексту кэш всё равно не помогает — в окнах с редактором он выключен.
     */
    fun disableParagraphCache(resolver: androidx.compose.ui.text.font.FontFamily.Resolver) {
        runCatching {
            // Внутренние классы Compose (SkiaFontLoader): доступ только отражением
            val loader = resolver.javaClass.getMethod("getPlatformFontLoader\$ui_text").invoke(resolver) ?: return
            val fonts = loader.javaClass.getMethod("getFontCollection").invoke(loader)
            val cache = (fonts as? org.jetbrains.skia.paragraph.FontCollection)?.paragraphCache ?: return
            cache.setEnabled(false)
            cache.reset()
        }
    }

    private val evictedBytes = java.util.concurrent.atomic.AtomicLong()

    /**
     * Кэш картинок вытеснил запись: её пиксели — вне кучи, и освободит их только сборка мусора, а записи
     * успевают состариться и ждут редкой полной сборки. Набралось 32 МБ таких — просим сборку сами.
     */
    fun evicted(bytes: Long) {
        if (evictedBytes.addAndGet(bytes) < EVICTED_LIMIT) return
        evictedBytes.set(0)
        releaseSoon(delayMs = 500)
    }

    private const val EVICTED_LIMIT = 32L shl 20

    fun releaseSoon(delayMs: Long = 1500) {
        if (!pending.compareAndSet(false, true)) return
        Thread({
            // Окно успевает закрыться и отпустить раскладку
            Thread.sleep(delayMs)
            pending.set(false)
            System.gc()
            rssAtGc = rssKb()
        }, "wex-release-native").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    @Volatile
    private var lastCheck = 0L

    @Volatile
    private var rssAtGc = 0L

    /**
     * После раскладки большого текста: прежние раскладки (у 60 000 строк — по 100+ МБ вне кучи) ждут сборки
     * мусора, и при вводе процесс рос на сотни мегабайт за символ. Не чаще раза в 2 с и только при росте на 300 МБ.
     */
    fun afterBigLayout() {
        val now = System.nanoTime()
        if (now - lastCheck < 2_000_000_000L) return
        lastCheck = now
        val rss = rssKb()
        if (rss <= 0 || rss - rssAtGc < 300_000) return
        releaseSoon(delayMs = 200)
    }

    private fun rssKb(): Long = runCatching {
        java.io.File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull()
        }
    }.getOrNull() ?: -1L
}
