package org.wexclient.app.util

/**
 * Кэш раскладок Skia (ParagraphCache, свой у каждого окна) хранит до 128 последних абзацев целиком.
 * В редакторе каждое нажатие — новый абзац: в файле на 60 000 строк десять символов занимали +2,9 ГБ.
 * Новому тексту кэш всё равно не помогает — в окнах с редактором он выключен.
 */
actual fun NativeMemory.disableParagraphCache(resolver: androidx.compose.ui.text.font.FontFamily.Resolver) {
    runCatching {
        // Внутренние классы Compose (SkiaFontLoader): доступ только отражением
        val loader = resolver.javaClass.getMethod("getPlatformFontLoader\$ui_text").invoke(resolver) ?: return
        val fonts = loader.javaClass.getMethod("getFontCollection").invoke(loader)
        val cache = (fonts as? org.jetbrains.skia.paragraph.FontCollection)?.paragraphCache ?: return
        cache.setEnabled(false)
        cache.reset()
    }
}
