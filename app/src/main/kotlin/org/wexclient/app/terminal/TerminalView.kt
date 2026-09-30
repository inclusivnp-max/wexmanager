package org.wexclient.app.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import org.wexclient.app.ui.Motion
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.Icons
import org.wexclient.app.ui.glass
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.core.compatibility.Point
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.StyledTextConsumer
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.mouse.MouseButtonCodes
import com.jediterm.terminal.emulator.mouse.MouseButtonModifierFlags
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.SelectionUtil
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.util.CharUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.util.Keymap
import org.wexclient.app.util.awtEvent
import org.wexclient.app.util.physicalLatinLetter
import org.wexclient.app.util.withUserEnv
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.awt.event.KeyEvent as AwtKeyEvent
import org.jetbrains.skia.Canvas as SkCanvas

/** Шрифты терминала: JetBrains Mono из ресурсов + системные запасные для иероглифов и эмодзи. */
object TerminalFonts {
    val regular: Typeface? by lazy { load("fonts/JetBrainsMono-Regular.ttf") }
    val bold: Typeface? by lazy { load("fonts/JetBrainsMono-Bold.ttf") ?: regular }

    private val fallback = ConcurrentHashMap<Int, Any>()
    private val NONE = Any()

    private fun load(path: String): Typeface? = runCatching {
        val bytes = TerminalFonts::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes), 0)
    }.getOrNull()

    fun fallbackFor(cp: Int): Typeface? {
        val v = fallback.getOrPut(cp) {
            runCatching { FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, cp) }.getOrNull() ?: NONE
        }
        return v as? Typeface
    }
}

/** Размер ячейки и шрифты для текущего кегля. */
class CellMetrics(sizePx: Float) {
    val font: Font = makeFont(TerminalFonts.regular, sizePx)
    val bold: Font = makeFont(TerminalFonts.bold, sizePx)
    val italic: Font = makeFont(TerminalFonts.regular, sizePx).apply { skewX = -0.2f }
    val size = sizePx
    val cellW: Float
    val cellH: Float
    val baseline: Float
    private val supported = java.util.BitSet(0x10000)
    private val checked = java.util.BitSet(0x10000)
    private val fallbackFonts = HashMap<Typeface, Font>()

    init {
        val m = font.metrics
        cellW = font.measureTextWidth("M").takeIf { it > 0f } ?: (sizePx * 0.6f)
        val textH = -m.ascent + m.descent
        cellH = ceil(textH * 1.16f)
        baseline = ((cellH - textH) / 2f - m.ascent).roundToInt().toFloat()
    }

    private fun makeFont(tf: Typeface?, size: Float) = Font(tf, size).apply {
        edging = FontEdging.ANTI_ALIAS
        hinting = FontHinting.SLIGHT
        isSubpixel = true
    }

    fun hasGlyph(c: Char): Boolean {
        val i = c.code
        if (!checked.get(i)) {
            checked.set(i)
            if (font.getUTF32Glyph(i) != 0.toShort()) supported.set(i)
        }
        return supported.get(i)
    }

    fun fallbackFont(cp: Int): Font? {
        val tf = TerminalFonts.fallbackFor(cp) ?: return null
        return fallbackFonts.getOrPut(tf) { makeFont(tf, size) }
    }
}

/** Цвета терминала из темы. */
class TerminalTheme(val background: Color, val foreground: Color, val cursor: Color, val selection: Color) {
    val bgArgb = background.toArgb()
    val fgArgb = foreground.toArgb()
}

private fun jColorArgb(c: com.jediterm.core.Color): Int = (0xFF shl 24) or (c.red shl 16) or (c.green shl 8) or c.blue

private fun TerminalColor.argb(): Int = if (isIndexed) jColorArgb(WexPalette.INSTANCE.getForeground(this)) else jColorArgb(toColor())

private fun withAlpha(argb: Int, alpha: Float): Int = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)

/** Рисование ячеек терминала поверх Skia-холста. */
private class TerminalPainter(val m: CellMetrics, val theme: TerminalTheme, val padX: Float) {
    val fill = Paint().apply { isAntiAlias = false }
    val text = Paint().apply { isAntiAlias = true }
    val line = Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE }

    // Контур для скруглённых уголков и символов Powerline — один на все: новый Path на каждый символ
    // каждого кадра копил нативную память, пока сборщик мусора не доберётся до обёрток
    private val shape = org.jetbrains.skia.Path()

    // Цвета текущего отрезка и буфер текста — поля, а не новые объекты на каждый отрезок каждого кадра
    private var runFg = 0
    private var runBg = 0
    private var runHasBg = false
    private val sb = StringBuilder(256)

    private fun colors(style: TextStyle) {
        var fg = style.foreground?.argb() ?: theme.fgArgb
        val styleBg = style.background
        var hasBg = styleBg != null
        var bg = styleBg?.argb() ?: 0
        if (style.hasOption(TextStyle.Option.INVERSE)) {
            val newFg = if (hasBg) bg else theme.bgArgb
            bg = fg
            hasBg = true
            fg = newFg
        }
        if (style.hasOption(TextStyle.Option.DIM)) fg = withAlpha(fg, 0.62f)
        runFg = fg
        runBg = bg
        runHasBg = hasBg
    }

    private fun flush(c: SkCanvas, col: Int, segStart: Int, baseY: Float, font: Font) {
        if (segStart >= 0 && sb.isNotEmpty()) c.drawString(sb.toString(), padX + (col + segStart) * m.cellW, baseY, font, text)
        sb.setLength(0)
    }

    fun run(c: SkCanvas, col: Int, row: Int, style: TextStyle, chars: CharBuffer, textOnly: Boolean = false) {
        val len = chars.length
        if (len == 0) return
        colors(style)
        val fg = runFg
        val x0 = padX + col * m.cellW
        val y0 = row * m.cellH
        if (runHasBg && !textOnly) {
            fill.color = runBg
            c.drawRect(Rect.makeXYWH(x0, y0, len * m.cellW + 0.5f, m.cellH), fill)
        }
        if (style.hasOption(TextStyle.Option.HIDDEN)) return
        text.color = fg
        val font = when {
            style.hasOption(TextStyle.Option.BOLD) -> m.bold
            style.hasOption(TextStyle.Option.ITALIC) -> m.italic
            else -> m.font
        }
        val buf = chars.buf
        val start = chars.start
        val baseY = y0 + m.baseline
        var segStart = -1
        sb.setLength(0)
        var i = 0
        while (i < len) {
            val ch = buf[start + i]
            when {
                ch == CharUtils.DWC || ch == ' ' || ch == CharUtils.NUL_CHAR -> {
                    flush(c, col, segStart, baseY, font)
                    segStart = -1
                }
                ch.code in 0x2500..0x259F || ch.code in 0xE0B0..0xE0B7 -> {
                    flush(c, col, segStart, baseY, font)
                    segStart = -1
                    drawSpecial(c, ch.code, padX + (col + i) * m.cellW, y0, fg)
                }
                Character.isHighSurrogate(ch) && i + 1 < len -> {
                    flush(c, col, segStart, baseY, font)
                    segStart = -1
                    val cp = Character.toCodePoint(ch, buf[start + i + 1])
                    val wide = i + 2 < len && buf[start + i + 2] == CharUtils.DWC
                    drawFallback(c, cp, padX + (col + i) * m.cellW, baseY, if (wide) 2 else 1)
                    i++
                }
                m.hasGlyph(ch) -> {
                    if (segStart < 0) segStart = i
                    // Сегмент должен идти подряд по ячейкам
                    if (segStart + sb.length != i) {
                        flush(c, col, segStart, baseY, font)
                        segStart = i
                    }
                    sb.append(ch)
                }
                else -> {
                    flush(c, col, segStart, baseY, font)
                    segStart = -1
                    val wide = i + 1 < len && buf[start + i + 1] == CharUtils.DWC
                    drawFallback(c, ch.code, padX + (col + i) * m.cellW, baseY, if (wide) 2 else 1)
                }
            }
            i++
        }
        flush(c, col, segStart, baseY, font)
        if (style.hasOption(TextStyle.Option.UNDERLINED)) {
            fill.color = fg
            c.drawRect(Rect.makeXYWH(x0, y0 + m.baseline + max(1f, m.size / 12f), len * m.cellW, max(1f, m.size / 14f)), fill)
        }
    }

    private fun drawFallback(c: SkCanvas, cp: Int, x: Float, baseY: Float, cells: Int) {
        val f = m.fallbackFont(cp) ?: return
        val s = String(Character.toChars(cp))
        val w = f.measureTextWidth(s)
        val avail = cells * m.cellW
        if (w > avail * 1.05f && w > 0f) {
            // Широкий глиф ужимаем в свои ячейки
            c.save()
            c.translate(x, baseY)
            c.scale(avail / w, avail / w)
            c.drawString(s, 0f, 0f, f, text)
            c.restore()
        } else {
            c.drawString(s, x + (avail - w) / 2f, baseY, f, text)
        }
    }

    // Рамки (U+2500–257F), блоки (U+2580–259F) и символы Powerline рисуем сами — без щелей между строками
    private fun drawSpecial(c: SkCanvas, cp: Int, x: Float, y: Float, color: Int) {
        val w = m.cellW
        val h = m.cellH
        fill.color = color
        if (cp in 0x2580..0x259F) {
            drawBlock(c, cp, x, y, w, h, color)
            return
        }
        if (cp in 0xE0B0..0xE0B7) {
            drawPowerline(c, cp, x, y, w, h, color)
            return
        }
        val arms = BOX[cp - 0x2500]
        if (arms == null) {
            // Диагонали
            line.color = color
            line.strokeWidth = max(1f, w / 8f)
            if (cp == 0x2571 || cp == 0x2573) c.drawLine(x, y + h, x + w, y, line)
            if (cp == 0x2572 || cp == 0x2573) c.drawLine(x, y, x + w, y + h, line)
            return
        }
        val light = max(1f, (w / 8f).roundToInt().toFloat())
        val heavy = light * 2
        val gap = max(light, (w / 6f).roundToInt().toFloat())
        val cx = (x + w / 2f).roundToInt().toFloat()
        val cy = (y + h / 2f).roundToInt().toFloat()
        val l = arms[0].digitToInt()
        val r = arms[1].digitToInt()
        val u = arms[2].digitToInt()
        val d = arms[3].digitToInt()
        fun t(kind: Int) = if (kind == 2) heavy else light
        // Линии толщиной t занимают целые пиксели: [c - floor(t/2), c - floor(t/2) + t)
        fun lo(c: Float, th: Float) = c - floor(th / 2f)
        fun hBar(x0: Float, x1: Float, yc: Float, th: Float) = c.drawRect(Rect.makeLTRB(x0, lo(yc, th), x1, lo(yc, th) + th), fill)
        fun vBar(y0: Float, y1: Float, xc: Float, th: Float) = c.drawRect(Rect.makeLTRB(lo(xc, th), y0, lo(xc, th) + th, y1), fill)
        if (cp in 0x256D..0x2570) {
            line.color = color
            line.strokeWidth = light
            val ax = lo(cx, light) + light / 2f
            val ay = lo(cy, light) + light / 2f
            val rad = min(w, h) / 2f
            val path = shape.rewind()
            when (cp) {
                0x256D -> { path.moveTo(ax, y + h); path.lineTo(ax, ay + rad); path.quadTo(ax, ay, ax + rad, ay); path.lineTo(x + w, ay) }
                0x256E -> { path.moveTo(ax, y + h); path.lineTo(ax, ay + rad); path.quadTo(ax, ay, ax - rad, ay); path.lineTo(x, ay) }
                0x256F -> { path.moveTo(ax, y); path.lineTo(ax, ay - rad); path.quadTo(ax, ay, ax - rad, ay); path.lineTo(x, ay) }
                else -> { path.moveTo(ax, y); path.lineTo(ax, ay - rad); path.quadTo(ax, ay, ax + rad, ay); path.lineTo(x + w, ay) }
            }
            c.drawPath(path, line)
            return
        }
        val vt = max(t(u), t(d))
        val ht = max(t(l), t(r))
        // Одинарные/жирные линии: от центра до края, с перекрытием на толщину поперечной
        if (l in 1..2) hBar(x, lo(cx, vt) + vt, cy, t(l))
        if (r in 1..2) hBar(lo(cx, vt), x + w, cy, t(r))
        if (u in 1..2) vBar(y, lo(cy, ht) + ht, cx, t(u))
        if (d in 1..2) vBar(lo(cy, ht), y + h, cx, t(d))
        // Двойные линии: две параллельные на расстоянии gap от центра, стыки внутрь/наружу
        val hasU = u > 0
        val hasD = d > 0
        val hasL = l > 0
        val hasR = r > 0
        val near = gap
        if (l == 3) {
            hBar(x, (if (hasU) cx - near else cx + near) + light, cy - gap, light)
            hBar(x, (if (hasD) cx - near else cx + near) + light, cy + gap, light)
        }
        if (r == 3) {
            hBar(if (hasU) cx + near else cx - near, x + w, cy - gap, light)
            hBar(if (hasD) cx + near else cx - near, x + w, cy + gap, light)
        }
        if (u == 3) {
            vBar(y, (if (hasL) cy - near else cy + near) + light, cx - gap, light)
            vBar(y, (if (hasR) cy - near else cy + near) + light, cx + gap, light)
        }
        if (d == 3) {
            vBar(if (hasL) cy + near else cy - near, y + h, cx - gap, light)
            vBar(if (hasR) cy + near else cy - near, y + h, cx + gap, light)
        }
    }

    private fun drawBlock(c: SkCanvas, cp: Int, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fun r(l: Float, t: Float, rr: Float, b: Float) = c.drawRect(Rect.makeLTRB(x + l * w, y + t * h, x + rr * w, y + b * h), fill)
        when (cp) {
            0x2580 -> r(0f, 0f, 1f, 0.5f)
            in 0x2581..0x2587 -> r(0f, 1f - (cp - 0x2580) / 8f, 1f, 1f)
            0x2588 -> r(0f, 0f, 1f, 1f)
            in 0x2589..0x258F -> r(0f, 0f, (0x2590 - cp) / 8f, 1f)
            0x2590 -> r(0.5f, 0f, 1f, 1f)
            0x2591, 0x2592, 0x2593 -> {
                fill.color = withAlpha(color, (cp - 0x2590) * 0.25f)
                r(0f, 0f, 1f, 1f)
            }
            0x2594 -> r(0f, 0f, 1f, 0.125f)
            0x2595 -> r(0.875f, 0f, 1f, 1f)
            else -> {
                // Четверти: биты UL, UR, LL, LR
                val q = when (cp) {
                    0x2596 -> 0b0010; 0x2597 -> 0b0001; 0x2598 -> 0b1000; 0x2599 -> 0b1011
                    0x259A -> 0b1001; 0x259B -> 0b1110; 0x259C -> 0b1101; 0x259D -> 0b0100
                    0x259E -> 0b0110; else -> 0b0111
                }
                if (q and 0b1000 != 0) r(0f, 0f, 0.5f, 0.5f)
                if (q and 0b0100 != 0) r(0.5f, 0f, 1f, 0.5f)
                if (q and 0b0010 != 0) r(0f, 0.5f, 0.5f, 1f)
                if (q and 0b0001 != 0) r(0.5f, 0.5f, 1f, 1f)
            }
        }
    }

    private fun drawPowerline(c: SkCanvas, cp: Int, x: Float, y: Float, w: Float, h: Float, color: Int) {
        val p = shape.rewind()
        line.color = color
        line.strokeWidth = max(1f, w / 10f)
        when (cp) {
            0xE0B0 -> { p.moveTo(x, y); p.lineTo(x + w, y + h / 2); p.lineTo(x, y + h); p.closePath(); c.drawPath(p, fill) }
            0xE0B2 -> { p.moveTo(x + w, y); p.lineTo(x, y + h / 2); p.lineTo(x + w, y + h); p.closePath(); c.drawPath(p, fill) }
            0xE0B1 -> { p.moveTo(x, y); p.lineTo(x + w, y + h / 2); p.lineTo(x, y + h); c.drawPath(p, line) }
            0xE0B3 -> { p.moveTo(x + w, y); p.lineTo(x, y + h / 2); p.lineTo(x + w, y + h); c.drawPath(p, line) }
            0xE0B4 -> { p.moveTo(x, y); p.quadTo(x + w * 1.3f, y + h / 2, x, y + h); p.closePath(); c.drawPath(p, fill) }
            0xE0B6 -> { p.moveTo(x + w, y); p.quadTo(x - w * 0.3f, y + h / 2, x + w, y + h); p.closePath(); c.drawPath(p, fill) }
            0xE0B5 -> { p.moveTo(x, y); p.quadTo(x + w * 1.3f, y + h / 2, x, y + h); c.drawPath(p, line) }
            else -> { p.moveTo(x + w, y); p.quadTo(x - w * 0.3f, y + h / 2, x + w, y + h); c.drawPath(p, line) }
        }
    }

    companion object {
        // Плечи линий «лево, право, верх, низ»: 0 — нет, 1 — тонкая, 2 — жирная, 3 — двойная
        private val BOX: Array<String?> = arrayOf(
            "1100", "2200", "0011", "0022", "1100", "2200", "0011", "0022", "1100", "2200", "0011", "0022",
            "0101", "0201", "0102", "0202", "1001", "2001", "1002", "2002",
            "0110", "0210", "0120", "0220", "1010", "2010", "1020", "2020",
            "0111", "0211", "0121", "0112", "0122", "0221", "0212", "0222",
            "1011", "2011", "1021", "1012", "1022", "2021", "2012", "2022",
            "1101", "2101", "1201", "2201", "1102", "2102", "1202", "2202",
            "1110", "2110", "1210", "2210", "1120", "2120", "1220", "2220",
            "1111", "2111", "1211", "2211", "1121", "1112", "1122", "2121",
            "1221", "2112", "1212", "2221", "2212", "2122", "1222", "2222",
            "1100", "2200", "0011", "0022",
            "3300", "0033", "0301", "0103", "0303", "3001", "1003", "3003",
            "0310", "0130", "0330", "3010", "1030", "3030",
            "0311", "0133", "0333", "3011", "1033", "3033",
            "3301", "1103", "3303", "3310", "1130", "3330",
            "3311", "1133", "3333",
            "0101", "1001", "1010", "0110",
            null, null, null,
            "1000", "0010", "0100", "0001", "2000", "0020", "0200", "0002", "1200", "0012", "2100", "0021"
        )
    }
}

/** Слова для двойного клика: разделители как в JediTerm, но пути и URL целиком. */
private val WORD_SEPARATORS = listOf(' ', '\t', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>', '|', ';', '`', ',')

private val URL_OR_PATH = Regex("""(?:https?|ftp|file)://[^\s"'<>`]+|(?:~|\.{1,2})?/[^\s"'<>`:]+|[\w.-]+/[\w./-]+""")

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
/**
 * Экран терминала: отрисовка сеанса, клавиатура (xterm), мышь (выделение, прокрутка,
 * отправка событий приложениям), контекстное меню.
 */
@Composable
fun TerminalView(
    session: TerminalSession,
    fontSizeSp: Float,
    focusRequester: FocusRequester,
    onOpenPath: (Path) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    onZoom: (Float) -> Unit,
    onRestart: () -> Unit,
    /** Ctrl+Shift+F — поиск по выводу (строку показывает панель). */
    onFind: () -> Unit = {},
    onClear: () -> Unit = {},
    onReset: () -> Unit = {},
    onCycleTab: (Int) -> Unit = {},
    /** Открыта строка поиска — консоль не перехватывает фокус наведением. */
    findOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val theme = remember(cs.background, cs.onBackground, NoctaliaPrimary) {
        TerminalTheme(cs.background, cs.onBackground, NoctaliaPrimary, NoctaliaPrimary.copy(alpha = 0.35f))
    }
    val metrics = remember(fontSizeSp, density.density) { CellMetrics(with(density) { fontSizeSp.sp.toPx() }) }
    val padX = with(density) { 6.dp.toPx() }
    val painter = remember(metrics, theme, padX) { TerminalPainter(metrics, theme, padX) }
    val clipboard = LocalClipboardManager.current
    var focused by remember { mutableStateOf(false) }
    val findNow by rememberUpdatedState(findOpen)
    var blinkOn by remember { mutableStateOf(true) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var menuPath by remember { mutableStateOf<Path?>(null) }
    var scrollbarAlpha by remember { mutableFloatStateOf(0f) }
    // Полоса прокрутки: в покое — тонкая нить у края, под курсором расширяется и её можно тянуть
    var barHover by remember { mutableStateOf(false) }
    var barDrag by remember { mutableStateOf(false) }
    val barWidth by animateFloatAsState(
        if (barHover || barDrag) 9f else 3f,
        tween(Motion.TINT),
        label = "terminal-scrollbar-width"
    )
    val barAlpha by animateFloatAsState(
        when {
            barDrag -> 1f
            barHover -> 1f
            else -> max(0.45f, scrollbarAlpha)
        },
        tween(Motion.TINT),
        label = "terminal-scrollbar-alpha"
    )

    // Цвета окна — для ответов приложениям на запрос фона/текста
    LaunchedEffect(theme) {
        session.windowBackground = com.jediterm.core.Color(theme.bgArgb and 0xFFFFFF)
        session.windowForeground = com.jediterm.core.Color(theme.fgArgb and 0xFFFFFF)
    }

    // Размер в ячейках; оболочка стартует уже с правильным размером
    LaunchedEffect(viewSize, metrics) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        val cols = floor((viewSize.width - padX * 2) / metrics.cellW).toInt().coerceAtLeast(10)
        val rows = floor(viewSize.height / metrics.cellH).toInt().coerceAtLeast(3)
        // Первый размер — сразу, дальше с задержкой: пока тянут разделитель, каждый шаг
        // переформатировал всю историю в десять тысяч строк
        if (!session.started && session.exitCode == null) {
            // Запуск процесса — не в потоке окна и не в этой корутине: fork/exec подвешивал
            // открытие вкладки, а отмена корутины (переключились на другую вкладку панели)
            // оставляла консоль вовсе без процесса
            session.ensureStarted(cols, rows)
        } else {
            delay(80)
            session.resize(cols, rows)
        }
    }

    // Мигание курсора. Каждое мигание перерисовывает всё окно, поэтому через 15 с без ввода и вывода
    // курсор замирает (как в kitty) — простаивающая консоль больше не грузит процессор.
    // Пока печатают или идёт вывод, курсор виден сплошным
    // Фокус поля остаётся и когда окно уходит на задний план — там мигать незачем, а каждое мигание — кадр
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused && windowFocused, session) {
        blinkOn = true
        if (!focused || !windowFocused) return@LaunchedEffect
        var seen = session.version
        var activeAt = System.nanoTime()
        while (true) {
            delay(560)
            val v = session.version
            if (v != seen) {
                seen = v
                activeAt = System.nanoTime()
                blinkOn = true
                continue
            }
            if (System.nanoTime() - activeAt > BLINK_STOP_NANOS) {
                blinkOn = true
                snapshotFlow { session.version }.first { it != seen }
                seen = session.version
                activeAt = System.nanoTime()
                continue
            }
            blinkOn = !blinkOn
        }
    }

    // Мигание полосы прокрутки при прокрутке истории. Позицию читаем потоком, а не в композиции:
    // иначе каждый шаг колеса перестраивал весь экран консоли
    LaunchedEffect(session) {
        snapshotFlow { session.scrollOrigin }.collectLatest { origin ->
            if (origin != 0) {
                scrollbarAlpha = 1f
                delay(1400)
            }
            scrollbarAlpha = if (session.scrollOrigin != 0) 0.6f else 0f
        }
    }

    fun cellAt(pos: Offset): Pair<Int, Int> {
        val col = floor((pos.x - padX) / metrics.cellW).toInt().coerceIn(0, max(0, session.columns - 1))
        val row = floor(pos.y / metrics.cellH).toInt().coerceIn(0, max(0, session.rows - 1))
        return col to row
    }

    fun selectionText(): String? {
        val sel = session.selection ?: return null
        session.buffer.lock()
        return try {
            SelectionUtil.getSelectionText(sel, session.buffer).takeIf { it.isNotEmpty() }
        } finally {
            session.buffer.unlock()
        }
    }

    fun copySelection(): Boolean {
        val t = selectionText() ?: return false
        clipboard.setText(AnnotatedString(t))
        return true
    }

    fun pasteClipboard() {
        val t = clipboard.getText()?.text ?: return
        session.paste(t)
    }

    /** Первичное выделение X11: то, что выделено мышью в любом окне, вставляется средней кнопкой. */
    fun pastePrimary() {
        val text = runCatching {
            val selection = java.awt.Toolkit.getDefaultToolkit().systemSelection ?: return@runCatching null
            selection.getContents(null)?.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (text.isNullOrEmpty()) pasteClipboard() else session.paste(text)
    }

    fun lineTextAt(row: Int): String {
        session.buffer.lock()
        return try {
            val y = row + session.scrollOrigin
            val line = if (y >= 0) session.buffer.getLine(y) else {
                val hist = session.buffer.historyLinesStorage
                val idx = hist.size + y
                if (idx in 0 until hist.size) hist.get(idx) else null
            }
            line?.text.orEmpty()
        } finally {
            session.buffer.unlock()
        }
    }

    /** Путь или URL под ячейкой. */
    fun linkAt(col: Int, row: Int): String? {
        val text = lineTextAt(row)
        for (m in URL_OR_PATH.findAll(text)) {
            if (col in m.range) return m.value.trimEnd('.', ',', ':', ')')
        }
        return null
    }

    fun openLink(link: String) {
        if (link.contains("://") && !link.startsWith("file://")) {
            runCatching { ProcessBuilder(org.wexclient.app.util.systemOpenCommand(link)).withUserEnv().start() }
            return
        }
        // В ssh-сеансе пути в выводе — на сервере: одноимённый файл на этом компьютере открывать нельзя
        if (session.remote != null) return
        val raw = link.removePrefix("file://")
        val home = System.getProperty("user.home")
        val base = session.currentDirectory() ?: session.startDir
        val p = runCatching {
            when {
                raw.startsWith("~/") -> Paths.get(home, raw.substring(2))
                raw.startsWith("/") -> Paths.get(raw)
                else -> base.resolve(raw)
            }.normalize()
        }.getOrNull() ?: return
        if (Files.exists(p)) onOpenPath(p)
    }

    fun mouseMods(shift: Boolean, ctrl: Boolean, alt: Boolean): Int {
        var m = 0
        if (shift) m = m or MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG
        if (alt) m = m or MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG
        if (ctrl) m = m or MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG
        return m
    }

    fun onKey(e: KeyEvent): Boolean {
        val awt = e.awtEvent() ?: return false
        val mods = awt.modifiersEx
        val ctrl = mods and InputEvent.CTRL_DOWN_MASK != 0
        val shift = mods and InputEvent.SHIFT_DOWN_MASK != 0
        val alt = mods and InputEvent.ALT_DOWN_MASK != 0
        val altGr = mods and InputEvent.ALT_GRAPH_DOWN_MASK != 0
        if (session.exitCode != null) {
            if (e.type == KeyEventType.KeyDown) {
                when (awt.keyCode) {
                    AwtKeyEvent.VK_ENTER -> onRestart()
                    AwtKeyEvent.VK_ESCAPE -> onCloseTab()
                }
            }
            return true
        }
        if (e.type == KeyEventType.KeyDown) {
            val latin = e.physicalLatinLetter()
            // Сочетания самой консоли — из общего списка биндов (настройки → «Бинды»)
            fun history(): Int {
                session.buffer.lock()
                return try { session.buffer.historyLinesCount } finally { session.buffer.unlock() }
            }
            when (Keymap.action(e, Keymap.GROUP_TERMINAL)) {
                "term.copy" -> { copySelection(); return true }
                "term.paste", "term.paste2" -> { pasteClipboard(); return true }
                "term.newTab" -> { onNewTab(); return true }
                "term.closeTab" -> { onCloseTab(); return true }
                "term.find" -> { onFind(); return true }
                "term.clear" -> { onClear(); return true }
                "term.reset" -> { onReset(); return true }
                "term.nextTab" -> { onCycleTab(1); return true }
                "term.prevTab" -> { onCycleTab(-1); return true }
                "term.selectAll" -> {
                    val hist = history()
                    session.selection = TerminalSelection(Point(0, -hist), Point(session.columns, session.rows - 1))
                    session.invalidate()
                    return true
                }
                "term.top" -> { session.scrollOrigin = -history(); return true }
                "term.bottom" -> { session.scrollOrigin = 0; return true }
                "term.pageUp", "term.pageDown" -> {
                    // На дополнительном экране (less, vim) прокрутка — дело самой программы
                    if (!session.alternateScreen) {
                        val hist = history()
                        val page = max(1, session.rows - 1)
                        val delta = if (Keymap.matches("term.pageUp", e)) -page else page
                        session.scrollOrigin = (session.scrollOrigin + delta).coerceIn(-hist, 0)
                        return true
                    }
                }
                "term.zoomIn" -> { onZoom(1f); return true }
                "term.zoomOut" -> { onZoom(-1f); return true }
                "term.zoomReset" -> { onZoom(0f); return true }
            }
            // Цифровая часть клавиатуры на бинды не выносится: там свои коды клавиш
            if (ctrl && !shift && !alt) {
                when (awt.keyCode) {
                    AwtKeyEvent.VK_PLUS, AwtKeyEvent.VK_ADD -> { onZoom(1f); return true }
                    AwtKeyEvent.VK_SUBTRACT -> { onZoom(-1f); return true }
                    AwtKeyEvent.VK_NUMPAD0 -> { onZoom(0f); return true }
                }
            }
            // Ctrl+Space — NUL
            if (ctrl && awt.keyCode == AwtKeyEvent.VK_SPACE) {
                session.sendBytes(byteArrayOf(0)); return true
            }
            // Сами по себе Shift/Ctrl/Alt выделение не снимают: иначе Shift+щелчок его терял
            if (awt.keyCode != AwtKeyEvent.VK_SHIFT && awt.keyCode != AwtKeyEvent.VK_CONTROL &&
                awt.keyCode != AwtKeyEvent.VK_ALT && awt.keyCode != AwtKeyEvent.VK_META &&
                awt.keyCode != AwtKeyEvent.VK_ALT_GRAPH
            ) {
                session.selection = null
            }
            val jMods = awt.modifiers and (InputEvent.SHIFT_MASK or InputEvent.CTRL_MASK or InputEvent.ALT_MASK or InputEvent.META_MASK)
            if (session.sendKey(awt.keyCode, jMods)) return true
            // Ctrl+буква — управляющий символ (и в кириллической раскладке)
            if (ctrl && !alt && latin != null) {
                session.sendBytes(byteArrayOf((latin.code and 0x1F).toByte()))
                return true
            }
            if (ctrl && !alt) {
                val ch = awt.keyChar
                val control = when (ch) {
                    '[', '3' -> 27; '\\', '4' -> 28; ']', '5' -> 29; '^', '6' -> 30; '_', '7', '/' -> 31
                    '@', '2' -> 0; '8', '?' -> 127
                    else -> -1
                }
                if (control >= 0) {
                    session.sendBytes(byteArrayOf(control.toByte())); return true
                }
            }
            // Alt+клавиша — ESC и символ (readline: Alt+B/F/D…)
            if (alt && !ctrl && !altGr) {
                val ch = latin?.let { if (shift) it.uppercaseChar() else it } ?: awt.keyChar.takeIf { it != AwtKeyEvent.CHAR_UNDEFINED && !Character.isISOControl(it) }
                if (ch != null) {
                    session.sendText("$ch")
                    return true
                }
            }
            val ch = awt.keyChar
            if (ch != AwtKeyEvent.CHAR_UNDEFINED && Character.isISOControl(ch)) {
                session.sendText(ch.toString())
                return true
            }
            // Печатные символы придут отдельным KEY_TYPED; остальное не отдаём файловому менеджеру
            return true
        }
        if (awt.id == AwtKeyEvent.KEY_TYPED) {
            val ch = awt.keyChar
            if (ch == AwtKeyEvent.CHAR_UNDEFINED || Character.isISOControl(ch)) return true
            if (alt && !ctrl && !altGr) return true
            if (ctrl && !altGr) return true
            session.selection = null
            session.sendText(ch.toString())
            return true
        }
        return true
    }

    Box(
        modifier
            .background(theme.background)
            .onSizeChanged { viewSize = it }
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                TerminalFocus.active = it.isFocused
            }
            .focusable()
            .onPreviewKeyEvent { onKey(it) }
            .pointerHoverIcon(PointerIcon.Text)
            .pointerInput(session, metrics) {
                // pointerInput не перезапускается при открытии поиска: значение читаем через remember

                var pressCell: Pair<Int, Int>? = null
                var pressButton: PointerButton? = null
                var lastClickTime = 0L
                var clickCount = 0
                var lastClickCell: Pair<Int, Int>? = null
                var selecting = false
                // Доли щелчка колеса: мыши с точной прокруткой и тачпады шлют их пачками
                var wheel = 0f
                awaitPointerEventScope {
                    // Сколько строк истории — столько «хода» у полосы
                    fun history(): Int {
                        session.buffer.lock()
                        return try { session.buffer.historyLinesCount } finally { session.buffer.unlock() }
                    }
                    // Прокрутка по положению курсора на полосе
                    fun scrollTo(y: Float) {
                        val hist = history()
                        if (hist <= 0) return
                        val total = hist + session.rows
                        val h = size.height.toFloat()
                        val thumbH = max(24f, h * session.rows / total)
                        val track = (h - thumbH).coerceAtLeast(1f)
                        val part = ((y - thumbH / 2f) / track).coerceIn(0f, 1f)
                        session.scrollOrigin = (-hist + (hist * part).toInt()).coerceIn(-hist, 0)
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull() ?: continue
                        val pos = change.position
                        val km = ev.keyboardModifiers
                        // Полоса прокрутки живёт у правого края и перехватывает щелчки раньше выделения
                        // Историю спрашиваем только у края: под блокировкой буфера, а событий Move много
                        val onBar = pos.x >= size.width - SCROLLBAR_ZONE && history() > 0
                        if (ev.type == PointerEventType.Exit) barHover = false
                        else if (ev.type != PointerEventType.Scroll) barHover = onBar || barDrag
                        if (barDrag) {
                            when (ev.type) {
                                PointerEventType.Release -> barDrag = false
                                PointerEventType.Move -> scrollTo(pos.y)
                                else -> Unit
                            }
                            change.consume()
                            continue
                        }
                        if (onBar && ev.type == PointerEventType.Press && ev.button == PointerButton.Primary) {
                            barDrag = true
                            scrollTo(pos.y)
                            change.consume()
                            continue
                        }
                        val appMouse = session.mouseMode != MouseMode.MOUSE_REPORTING_NONE && !km.isShiftPressed
                        // Курсор зашёл в консоль — фокус сюда, без щелчка (как «фокус за мышью»).
                        // Просим только когда фокуса нет: иначе запрос шёл бы на каждое движение
                        if (!focused && !findNow && TerminalPreferences.focusOnHover &&
                            (ev.type == PointerEventType.Enter || ev.type == PointerEventType.Move)
                        ) {
                            runCatching { focusRequester.requestFocus() }
                        }
                        when (ev.type) {
                            PointerEventType.Press -> {
                                runCatching { focusRequester.requestFocus() }
                                val cell = cellAt(pos)
                                val button = ev.button
                                pressButton = button
                                if (appMouse) {
                                    val code = when (button) {
                                        PointerButton.Secondary -> MouseButtonCodes.RIGHT
                                        PointerButton.Tertiary -> MouseButtonCodes.MIDDLE
                                        else -> MouseButtonCodes.LEFT
                                    }
                                    session.terminal.mousePressed(cell.first, cell.second, com.jediterm.core.input.MouseEvent(code, mouseMods(km.isShiftPressed, km.isCtrlPressed, km.isAltPressed)))
                                    change.consume()
                                    continue
                                }
                                when (button) {
                                    PointerButton.Secondary -> {
                                        menuPath = linkAt(cell.first, cell.second)?.let { l ->
                                            if (l.contains("://") && !l.startsWith("file://")) null
                                            else if (session.remote != null) null
                                            else runCatching {
                                                val base = session.currentDirectory() ?: session.startDir
                                                val raw = l.removePrefix("file://")
                                                val p = if (raw.startsWith("/")) Paths.get(raw) else base.resolve(raw)
                                                p.normalize().takeIf { Files.exists(it) }
                                            }.getOrNull()
                                        }
                                        menuAt = pos
                                    }
                                    // Средняя кнопка — первичное выделение X11, как в foot и kitty
                                    PointerButton.Tertiary -> pastePrimary()
                                    else -> {
                                        if (km.isCtrlPressed) {
                                            linkAt(cell.first, cell.second)?.let { openLink(it) }
                                            change.consume()
                                            continue
                                        }
                                        val now = System.currentTimeMillis()
                                        clickCount = if (now - lastClickTime < 400 && lastClickCell == cell) clickCount + 1 else 1
                                        lastClickTime = now
                                        lastClickCell = cell
                                        val y = cell.second + session.scrollOrigin
                                        when (clickCount) {
                                            1 -> {
                                                pressCell = cell
                                                selecting = true
                                                session.selection = null
                                            }
                                            2 -> {
                                                session.buffer.lock()
                                                try {
                                                    val p = Point(cell.first, y)
                                                    val s = SelectionUtil.getPreviousSeparator(p, session.buffer, WORD_SEPARATORS)
                                                    val e = SelectionUtil.getNextSeparator(p, session.buffer, WORD_SEPARATORS)
                                                    session.selection = TerminalSelection(s, Point(e.x + 1, e.y))
                                                } finally {
                                                    session.buffer.unlock()
                                                }
                                                selecting = false
                                            }
                                            else -> {
                                                session.selection = TerminalSelection(Point(0, y), Point(session.columns, y))
                                                selecting = false
                                                clickCount = 0
                                            }
                                        }
                                        session.invalidate()
                                    }
                                }
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                val cell = cellAt(pos)
                                if (appMouse && ev.buttons.isPrimaryPressed) {
                                    session.terminal.mouseDragged(cell.first, cell.second, com.jediterm.core.input.MouseEvent(MouseButtonCodes.LEFT, 0))
                                    continue
                                }
                                if (appMouse) {
                                    session.terminal.mouseMoved(cell.first, cell.second, com.jediterm.core.input.MouseEvent(MouseButtonCodes.NONE, 0))
                                    continue
                                }
                                val start = pressCell
                                if (selecting && start != null && ev.buttons.isPrimaryPressed) {
                                    // Выход за край — прокрутка истории
                                    if (pos.y < 0 && !session.alternateScreen) {
                                        session.buffer.lock()
                                        val hist = try { session.buffer.historyLinesCount } finally { session.buffer.unlock() }
                                        session.scrollOrigin = (session.scrollOrigin - 1).coerceIn(-hist, 0)
                                    } else if (pos.y > size.height && session.scrollOrigin < 0) {
                                        session.scrollOrigin = (session.scrollOrigin + 1).coerceAtMost(0)
                                    }
                                    val endCol = if (pos.x > size.width - padX) session.columns else cell.first
                                    val existing = session.selection
                                    val startPoint = existing?.start ?: Point(start.first, start.second + session.scrollOrigin)
                                    val endPoint = Point(endCol, cell.second + session.scrollOrigin)
                                    if (existing == null && abs(endPoint.x - startPoint.x) == 0 && endPoint.y == startPoint.y) continue
                                    session.selection = TerminalSelection(startPoint, endPoint)
                                    session.invalidate()
                                }
                            }
                            PointerEventType.Release -> {
                                val cell = cellAt(pos)
                                if (appMouse) {
                                    val code = when (pressButton) {
                                        PointerButton.Secondary -> MouseButtonCodes.RIGHT
                                        PointerButton.Tertiary -> MouseButtonCodes.MIDDLE
                                        else -> MouseButtonCodes.LEFT
                                    }
                                    session.terminal.mouseReleased(cell.first, cell.second, com.jediterm.core.input.MouseEvent(code, 0))
                                    continue
                                }
                                selecting = false
                                pressCell = null
                                // Выделение сразу в первичный буфер X11 (как в других терминалах)
                                selectionText()?.let { t ->
                                    runCatching {
                                        java.awt.Toolkit.getDefaultToolkit().systemSelection
                                            ?.setContents(java.awt.datatransfer.StringSelection(t), null)
                                    }
                                }
                            }
                            PointerEventType.Scroll -> {
                                val dy = change.scrollDelta.y
                                // Ctrl+колесо уже забрала панель консоли (размер шрифта) — второй раз не меняем
                                if (dy == 0f || change.isConsumed) continue
                                if (km.isCtrlPressed) {
                                    onZoom(if (dy < 0) 1f else -1f)
                                    change.consume()
                                    continue
                                }
                                val cell = cellAt(pos)
                                // Сменилось направление — старый остаток не в счёт: мелкий откат
                                // в конце жеста не должен дёргать вид обратно
                                if (wheel != 0f && (wheel < 0f) != (dy < 0f)) wheel = 0f
                                wheel += dy
                                if (appMouse) {
                                    val notches = wheel.toInt()
                                    if (notches != 0) {
                                        wheel -= notches
                                        // У jediterm имена кодов колеса перепутаны: SCROLLDOWN(4)
                                        // уходит как кнопка 64 (колесо вверх), SCROLLUP(5) — как 65
                                        // (вниз). Поэтому здесь они местами, иначе в программах с
                                        // мышью (Claude CLI и подобных) колесо работало наоборот
                                        val code = if (notches < 0) MouseButtonCodes.SCROLLDOWN else MouseButtonCodes.SCROLLUP
                                        repeat(abs(notches)) {
                                            session.terminal.mouseWheelMoved(cell.first, cell.second, com.jediterm.core.input.MouseWheelEvent(code, 0))
                                        }
                                    }
                                } else if (session.alternateScreen) {
                                    // В полноэкранных программах без мыши колесо — стрелки, по три на щелчок
                                    val steps = (wheel * 3).toInt()
                                    if (steps != 0) {
                                        wheel -= steps / 3f
                                        val key = if (steps < 0) AwtKeyEvent.VK_UP else AwtKeyEvent.VK_DOWN
                                        repeat(abs(steps)) { session.sendKey(key, 0) }
                                    }
                                } else {
                                    // История: три строки на щелчок, доли копятся до целой строки
                                    val lines = (wheel * 3).toInt()
                                    if (lines != 0) {
                                        wheel -= lines / 3f
                                        session.buffer.lock()
                                        val hist = try { session.buffer.historyLinesCount } finally { session.buffer.unlock() }
                                        session.scrollOrigin = (session.scrollOrigin + lines).coerceIn(-hist, 0)
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                }
            }
            // Свой слой: нарисованный экран переиспользуется, пока в консоли ничего не изменилось.
            // Без него всё содержимое консоли рисовалось заново в каждом кадре окна — от снега,
            // анимаций и мигания где угодно (замер: 185 перерисовок на 200 кадров, со слоем — 0),
            // и каждый раз под блокировкой буфера, за которую борется поток эмулятора
            .graphicsLayer()
            .drawBehind {
                // Чтение версии здесь — перерисовка без перекомпоновки
                session.version
                drawTerminal(session, painter, metrics, theme, padX, focused, blinkOn)
                drawScrollbar(session, theme, barAlpha, barWidth)
            }
    ) {
        // Оболочка так и не запустилась: пустой чёрный экран без объяснений — худшее, что можно показать
        var stuck by remember(session) { mutableStateOf(false) }
        LaunchedEffect(session) {
            stuck = false
            delay(2500)
            stuck = !session.started && session.exitCode == null
        }
        if (stuck && session.exitCode == null) {
            val stuckShape = RoundedCornerShape(8.dp)
            Surface(
                color = Color.Transparent,
                shape = stuckShape,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.Center).padding(12.dp)
                    .glass(stuckShape, MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { session.ensureStarted(session.columns, session.rows) }
            ) {
                Text(
                    "Оболочка ${session.shell} не запустилась · щёлкните, чтобы попробовать снова",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Процесс завершился
        val exit = session.exitCode
        if (exit != null) {
            val noticeShape = RoundedCornerShape(8.dp)
            Surface(
                color = Color.Transparent,
                shape = noticeShape,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    .glass(noticeShape, MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Text(
                    (session.startError?.let { "Не удалось запустить ${session.shell}: $it" } ?: "Процесс завершён (код $exit)") +
                        " · Enter — перезапустить · Esc — закрыть вкладку",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        menuAt?.let { at ->
            Box(Modifier.offset(with(density) { at.x.toDp() }, with(density) { at.y.toDp() })) {
                WexDropdownMenu(expanded = true, onDismissRequest = { menuAt = null }) {
                    val hasSelection = session.selection != null
                    TerminalMenuItem("Копировать", Icons.Default.ContentCopy, "Ctrl+Shift+C", enabled = hasSelection) {
                        copySelection(); menuAt = null
                    }
                    TerminalMenuItem("Вставить", Icons.Default.ContentPaste, "Ctrl+Shift+V") {
                        pasteClipboard(); menuAt = null
                    }
                    TerminalMenuItem("Выделить всё", Icons.Default.SelectAll, "Ctrl+Shift+A") {
                        session.buffer.lock()
                        val hist = try { session.buffer.historyLinesCount } finally { session.buffer.unlock() }
                        session.selection = TerminalSelection(Point(0, -hist), Point(session.columns, session.rows - 1))
                        session.invalidate()
                        menuAt = null
                    }
                    TerminalMenuItem("Копировать весь вывод", Icons.Default.Notes) {
                        clipboard.setText(AnnotatedString(session.allText()))
                        menuAt = null
                    }
                    HorizontalDivider()
                    menuPath?.let { p ->
                        TerminalMenuItem("Открыть: ${p.fileName ?: p}", Icons.Default.FolderOpen) {
                            onOpenPath(p); menuAt = null
                        }
                    }
                    session.currentDirectory()?.let { dir ->
                        TerminalMenuItem("Текущую папку — в панель", Icons.Default.DriveFileMove) {
                            onOpenPath(dir); menuAt = null
                        }
                    }
                    TerminalMenuItem("Очистить", Icons.Default.CleaningServices) {
                        session.clearScreenAndHistory(); menuAt = null
                    }
                    TerminalMenuItem("Новая вкладка", Icons.Default.AddBox, "Ctrl+Shift+T") {
                        onNewTab(); menuAt = null
                    }
                    HorizontalDivider()
                    // Размер шрифта: то же, что Ctrl+колесо, но видно, что такая возможность есть
                    TerminalMenuItem("Крупнее", Icons.Default.ZoomIn, "Ctrl+=") { onZoom(1f) }
                    TerminalMenuItem("Мельче", Icons.Default.ZoomOut, "Ctrl+−") { onZoom(-1f) }
                    TerminalMenuItem("Обычный размер", Icons.Default.RestartAlt, "Ctrl+0") {
                        onZoom(0f); menuAt = null
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawTerminal(
    session: TerminalSession,
    painter: TerminalPainter,
    m: CellMetrics,
    theme: TerminalTheme,
    padX: Float,
    focused: Boolean,
    blinkOn: Boolean
) {
    // Видимое копируем под блокировкой буфера, а рисуем уже без неё: поток эмулятора не ждёт,
    // пока Skia рисует экран (при потоке вывода это съедало заметную долю времени)
    val buffer = session.buffer
    val cols = session.columns
    // Размер буфера меняется не тем же кадром, что размер поля: просим не больше, чем в нём есть,
    // иначе jediterm лезет за конец истории и падает на пустой строке
    val rows = min(session.rows, buffer.height)
    val runs = ArrayList<SnapRun>(rows * 4)
    var origin: Int
    var cursorChar = ' '
    buffer.lock()
    try {
        val hist = buffer.historyLinesCount
        origin = session.scrollOrigin.coerceIn(-hist, 0)
        // Кадр с несогласованным буфером рисовать нечем — пропускаем его, а не роняем окно
        runCatching {
        buffer.processHistoryAndScreenLines(origin, rows, object : StyledTextConsumer {
            override fun consume(x: Int, y: Int, style: TextStyle, characters: CharBuffer, startRow: Int) {
                runs.add(SnapRun(x, y - startRow, style, characters.snapshot()))
            }

            override fun consumeNul(x: Int, y: Int, nulIndex: Int, style: TextStyle, characters: CharBuffer, startRow: Int) {
                if (style.background != null || style.hasOption(TextStyle.Option.INVERSE)) {
                    runs.add(SnapRun(x, y - startRow, style, characters.snapshot()))
                }
            }

            override fun consumeQueue(x: Int, y: Int, nulIndex: Int, startRow: Int) = Unit
        })
        }
        val cy = session.cursorY - 1 - origin
        if (cy in 0 until rows) {
            val cx = session.cursorX.coerceIn(0, max(0, cols - 1))
            cursorChar = runCatching { buffer.getStyledCharAt(cx, session.cursorY - 1) }.getOrNull()?.first ?: ' '
        }
    } finally {
        buffer.unlock()
    }

    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        for (r in runs) painter.run(c, r.x, r.row, r.style, r.chars)
        // Выделение
        val sel = session.selection
        if (sel != null) {
            painter.fill.color = theme.selection.toArgb()
            for (row in 0 until rows) {
                val r = sel.intersect(0, row + origin, cols + 1) ?: continue
                val x = padX + r.first * m.cellW
                c.drawRect(Rect.makeXYWH(x, row * m.cellH, r.second * m.cellW, m.cellH), painter.fill)
            }
        }
        // Курсор
        val cy = session.cursorY - 1 - origin
        if (session.cursorVisible && session.exitCode == null && cy in 0 until rows) {
            val cx = session.cursorX.coerceIn(0, max(0, cols - 1))
            val x = padX + cx * m.cellW
            val y = cy * m.cellH
            val shape = session.cursorShape ?: CursorShape.BLINK_BLOCK
            val visible = !focused || !shape.isBlinking || blinkOn
            painter.fill.color = theme.cursor.toArgb()
            if (!focused) {
                painter.line.color = theme.cursor.toArgb()
                painter.line.strokeWidth = 1f
                c.drawRect(Rect.makeXYWH(x + 0.5f, y + 0.5f, m.cellW - 1f, m.cellH - 1f), painter.line)
            } else if (visible) {
                when (shape) {
                    CursorShape.BLINK_UNDERLINE, CursorShape.STEADY_UNDERLINE ->
                        c.drawRect(Rect.makeXYWH(x, y + m.cellH - 2f, m.cellW, 2f), painter.fill)
                    CursorShape.BLINK_VERTICAL_BAR, CursorShape.STEADY_VERTICAL_BAR ->
                        c.drawRect(Rect.makeXYWH(x, y, 2f, m.cellH), painter.fill)
                    else -> {
                        c.drawRect(Rect.makeXYWH(x, y, m.cellW, m.cellH), painter.fill)
                        // Символ под курсором — цветом фона
                        val ch = cursorChar
                        if (ch != ' ' && ch != CharUtils.DWC && ch.code != 0) {
                            val style = TextStyle(TerminalColor.fromColor(com.jediterm.core.Color(theme.bgArgb and 0xFFFFFF)), null)
                            painter.run(c, cx, cy, style, CharBuffer(ch, 1), textOnly = true)
                        }
                    }
                }
            }
        }
    }
}

/** Отрезок строки с одним стилем, скопированный из буфера для рисования. */
private class SnapRun(val x: Int, val row: Int, val style: TextStyle, val chars: CharBuffer)

/** Своя копия символов: массив буфера меняется, пока мы рисуем. */
private fun CharBuffer.snapshot(): CharBuffer = CharBuffer(buf.copyOfRange(start, start + length), 0, length)

private fun DrawScope.drawScrollbar(session: TerminalSession, theme: TerminalTheme, alpha: Float, width: Float) {
    if (alpha <= 0.01f) return
    val hist = session.buffer.historyLinesCount
    if (hist <= 0) return
    val total = hist + session.rows
    val h = size.height
    val thumbH = max(24f, h * session.rows / total)
    val pos = (hist + session.scrollOrigin).toFloat() / hist
    val top = (h - thumbH) * pos
    val left = size.width - width - 2f
    // Под широкой полосой видна дорожка — понятно, куда можно тянуть
    if (width > 4f) {
        drawRoundRect(
            theme.foreground.copy(alpha = 0.08f * alpha),
            topLeft = Offset(left, 0f),
            size = androidx.compose.ui.geometry.Size(width, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
        )
    }
    drawRoundRect(
        theme.foreground.copy(alpha = 0.5f * alpha),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(width, thumbH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
    )
}

/** Ширина полосы, на которой курсор считается «на полосе прокрутки». */
private const val SCROLLBAR_ZONE = 18f

/** Пункт меню терминала: значок, подпись в одну строку, сочетание справа. */
@Composable
private fun TerminalMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    shortcut: String = "",
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        modifier = Modifier.height(32.dp).widthIn(min = 280.dp),
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        },
        text = {
            Text(
                label,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        },
        trailingIcon = if (shortcut.isEmpty()) null else {
            {
                Text(
                    shortcut,
                    fontSize = 9.5.sp,
                    fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
    )
}

/** Через столько без ввода и вывода курсор перестаёт мигать. */
private const val BLINK_STOP_NANOS = 15_000_000_000L
