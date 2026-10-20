package org.wexclient.app.icons

import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import org.wexclient.app.util.AppDirs
import java.io.File
import java.util.concurrent.ConcurrentHashMap

actual fun symbolPainter(codepoint: Int, style: SymbolStyle): Painter =
    AndroidSymbolPainter(codepoint, SymbolTypefaces.of(style))

/** Шрифты Material Symbols: из ресурсов в кэш приложения один раз, дальше Typeface отображает файл. */
private object SymbolTypefaces {
    private val loaded = ConcurrentHashMap<SymbolStyle, Typeface>()
    private val failed = ConcurrentHashMap.newKeySet<SymbolStyle>()

    fun of(style: SymbolStyle): Typeface? {
        loaded[style]?.let { return it }
        if (style in failed) return null
        return synchronized(this) {
            loaded[style] ?: load(style).also { if (it != null) loaded[style] = it else failed.add(style) }
        }
    }

    private fun load(style: SymbolStyle): Typeface? = runCatching {
        val resPath = "symbols/${style.resource}"
        val loader = MaterialSymbols::class.java.classLoader!!
        val dir = AppDirs.cache.resolve("symbols").toFile().apply { mkdirs() }
        val target = File(dir, style.resource)
        if (!target.isFile || target.length() == 0L) {
            val tmp = File(dir, style.resource + ".tmp")
            loader.getResourceAsStream(resPath)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) tmp.delete()
        }
        Typeface.createFromFile(target)
    }.onFailure { it.printStackTrace() }.getOrNull()
}

/** Один глиф шрифта; цвет — tint'ом Icon (через ColorFilter), как у Painter на десктопе. */
private class AndroidSymbolPainter(codepoint: Int, typeface: Typeface?) : Painter() {
    private val text = String(Character.toChars(codepoint))
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        color = android.graphics.Color.BLACK
    }
    private val hasTypeface = typeface != null

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun applyAlpha(alpha: Float): Boolean {
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        paint.colorFilter = colorFilter?.asAndroidColorFilter()
        return true
    }

    override fun DrawScope.onDraw() {
        if (!hasTypeface) return
        val s = minOf(size.width, size.height)
        if (s <= 0f) return
        paint.textSize = s
        // Глифы Material Symbols занимают em-квадрат над базовой линией (0..960 при upm 960)
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        drawIntoCanvas { it.nativeCanvas.drawText(text, left, top + s, paint) }
    }
}
