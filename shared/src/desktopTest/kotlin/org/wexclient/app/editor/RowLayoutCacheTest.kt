package org.wexclient.app.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.Tok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Кэш раскладок строк: прокрутка с дрожанием вбок и правка одной строки не раскладывают экран заново.
 * Раньше любое из этого сбрасывало весь кэш, и при прокрутке тачпадом поток окна был занят целиком.
 */
class RowLayoutCacheTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)
    private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    private val styles = Array<SpanStyle?>(Tok.values().size) { SpanStyle(color = Color(0xFF000000 + it * 0x010203)) }
    private val text = (0 until 400).joinToString("\n") { "val value$it = call(\"строка $it\") // комментарий ${"x".repeat(it % 50)}" }

    private fun layoutOf(t: String): FastLayout {
        val model = HighlightModel(Languages.detect("a.kt"))
        model.reset(t)
        return FastLayout(model.snapshot, tabSize = 4, whitespace = false, columns = 0)
    }

    /** Как кадр поверхности без переноса: строки экрана, срез — кусок вокруг первой видимой колонки. */
    private fun frame(cache: RowLayoutCache, lay: FastLayout, firstRow: Int, rows: Int, firstColumn: Int, visibleColumns: Int) {
        val chunk = RowLayoutCache.chunkColumns(visibleColumns)
        val chunkStart = firstColumn / chunk * chunk
        for (row in firstRow until firstRow + rows) {
            cache.row(lay, row, row, chunkStart..(chunkStart + 2 * chunk), styles, Color.Black)
        }
    }

    @Test
    fun `дрожание вбок при прокрутке не раскладывает строки заново`() {
        val cache = RowLayoutCache(measurer, style)
        val lay = layoutOf(text)
        frame(cache, lay, firstRow = 0, rows = 40, firstColumn = 0, visibleColumns = 120)
        assertEquals(40, cache.measured)
        // Прокрутка вниз на строку за кадр, по горизонтали — тачпадное дрожание на пару колонок
        for (step in 1..60) frame(cache, lay, firstRow = step, rows = 40, firstColumn = step % 3, visibleColumns = 120)
        assertEquals(100, cache.measured, "новые строки — по одной на кадр, старые берутся из кэша")
    }

    @Test
    fun `правка одной строки раскладывает только её`() {
        val cache = RowLayoutCache(measurer, style)
        frame(cache, layoutOf(text), firstRow = 0, rows = 40, firstColumn = 0, visibleColumns = 120)
        val before = cache.measured
        val edited = text.replaceFirst("val value5 ", "val value5x ")
        frame(cache, layoutOf(edited), firstRow = 0, rows = 40, firstColumn = 0, visibleColumns = 120)
        assertEquals(before + 1, cache.measured)
    }

    @Test
    fun `расширенное окно получает срез шире`() {
        val cache = RowLayoutCache(measurer, style)
        val long = "x".repeat(1000)
        val lay = layoutOf(long)
        val narrow = RowLayoutCache.chunkColumns(80)
        val wide = RowLayoutCache.chunkColumns(300)
        assertTrue(wide >= 300 && narrow >= 80)
        val a = cache.row(lay, 0, 0, 0..(2 * narrow), styles, Color.Black)
        val b = cache.row(lay, 0, 0, 0..(2 * wide), styles, Color.Black)
        assertTrue(b.size.width > a.size.width, "после расширения строка не остаётся обрезанной по старой ширине")
    }

    @Test
    fun `отрисовка готовой раскладки не пересобирает абзац`() {
        val layout = RowLayoutCache(measurer, style).row(layoutOf(text), 3, 3, 0..256, styles, Color.Black)
        val canvas = Canvas(ImageBitmap(800, 40))
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        // Внутренности Compose 1.7 (MultiParagraph → SkiaParagraph → ParagraphLayouter): другого способа
        // увидеть, пересобран ли абзац Skia, нет
        fun paragraphOf(l: TextLayoutResult): Any? {
            val info = (field(l.multiParagraph, "paragraphInfoList") as List<*>).first()!!
            val skia = field(info, "paragraph")!!
            return field(field(skia, "layouter")!!, "paragraphCache")
        }
        val built = paragraphOf(layout)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(800f, 40f)) {
            drawLaidOut(layout, Offset.Zero)
        }
        assertSame(built, paragraphOf(layout), "drawLaidOut рисует собранный абзац как есть")

        // Для сравнения: в Compose 1.7 обычный drawText на свежей раскладке пересобирал абзац (из-за этого
        // и понадобился drawLaidOut). С Compose 1.12 уже нет — drawLaidOut можно упростить до drawText
        val fresh = RowLayoutCache(measurer, style).row(layoutOf(text), 4, 4, 0..256, styles, Color.Black)
        val freshBuilt = paragraphOf(fresh)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(800f, 40f)) {
            drawText(fresh, topLeft = Offset.Zero)
        }
        assertSame(freshBuilt, paragraphOf(fresh), "Compose снова пересобирает абзац в drawText — drawLaidOut нужен")
    }
}
