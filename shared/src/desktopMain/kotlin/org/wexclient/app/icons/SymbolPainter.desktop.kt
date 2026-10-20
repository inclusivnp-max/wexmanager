package org.wexclient.app.icons

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaPaint
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

actual fun symbolPainter(codepoint: Int, style: SymbolStyle): Painter =
    SymbolPainter(codepoint, MaterialSymbols.typeface(style))

private val typefaces = ConcurrentHashMap<SymbolStyle, Typeface>()
private val failed = ConcurrentHashMap.newKeySet<SymbolStyle>()

/** Шрифт начертания (Skia), загруженный один раз; null — не загрузился. */
fun MaterialSymbols.typeface(style: SymbolStyle): Typeface? {
    typefaces[style]?.let { return it }
    if (style in failed) return null
    return synchronized(typefaces) {
        typefaces[style] ?: loadTypeface(style).also {
            if (it != null) typefaces[style] = it else failed.add(style)
        }
    }
}

private fun loadTypeface(style: SymbolStyle): Typeface? {
    return try {
        // Шрифт распаковывается в кэш один раз: makeFromFile отображает файл в память
        // и не читает 1–2 МБ из jar на каждом старте
        val cacheDir = File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.cache"), "wexdesktop/symbols")
        val resPath = "symbols/${style.resource}"
        val loader = MaterialSymbols::class.java.classLoader
        val size = loader.getResource(resPath)?.openConnection()?.contentLengthLong ?: -1L
        val target = File(cacheDir, style.resource.removeSuffix(".ttf") + "-$size.ttf")
        if (!target.isFile || target.length() != size) {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, target.name + ".tmp")
            loader.getResourceAsStream(resPath)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target)) tmp.delete()
        }
        if (target.isFile) {
            FontMgr.default.makeFromFile(target.absolutePath, 0)
        } else {
            val bytes = loader.getResourceAsStream(resPath)!!.use { it.readBytes() }
            FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes), 0)
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }
}

/** Рисует один глиф шрифта Material Symbols; цвет задаётся tint'ом Icon. */
private class SymbolPainter(codepoint: Int, private val typeface: Typeface?) : Painter() {
    private val text = String(Character.toChars(codepoint))
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    // Один Paint на значок: новый на каждую отрисовку — нативный объект Skia, который ждал сборщика мусора
    // (при прокрутке списка это сотни в секунду)
    private val paint = Paint().apply {
        color = Color.Black
        isAntiAlias = true
    }

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val tf = typeface ?: return
        val s = minOf(size.width, size.height)
        if (s <= 0f) return
        val f = fontFor(tf, s)
        paint.alpha = alpha
        paint.colorFilter = colorFilter
        // Глифы Material Symbols занимают em-квадрат над базовой линией (0..960 при upm 960)
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawString(text, left, top + s, f, paint.skiaPaint)
        }
    }

    private companion object {
        /** Шрифт на (начертание, размер) — общий для всех значков: размеров в интерфейсе с десяток. */
        private val fonts = object : LinkedHashMap<Pair<Typeface, Float>, Font>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Typeface, Float>, Font>?) = size > 64
        }

        fun fontFor(tf: Typeface, size: Float): Font = synchronized(fonts) {
            fonts.getOrPut(tf to size) {
                Font(tf, size).apply {
                    edging = FontEdging.ANTI_ALIAS
                    isSubpixel = true
                }
            }
        }
    }
}
