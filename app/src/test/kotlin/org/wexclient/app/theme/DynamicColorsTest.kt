package org.wexclient.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Цвета системной оболочки: файл пишет не мы, поэтому разбор должен быть терпимым. */
class DynamicColorsTest {

    @Test
    fun `разбирает цвета оболочки`() {
        val json = """
            {
              "mPrimary": "#a1c9ff",
              "mOnPrimary": "#00325a",
              "mSurface": "#111317",
              "mOnSurface": "#e2e2e7",
              "mOutline": "#42474f"
            }
        """.trimIndent()
        val colors = DynamicColors.parse(json)
        assertEquals(Color(0xFFA1C9FF), colors["mPrimary"])
        assertEquals(Color(0xFF111317), colors["mSurface"])
        assertEquals(5, colors.size)
    }

    @Test
    fun `короткая и длинная запись цвета`() {
        assertEquals(Color(0xFFFFFFFF), DynamicColors.color("#fff"))
        assertEquals(Color(0xFF112233), DynamicColors.color("#112233"))
        // #rrggbbaa: альфа в конце, как пишут оболочки
        assertEquals(Color(0x80112233), DynamicColors.color("#11223380"))
        assertNull(DynamicColors.color("не цвет"))
        assertNull(DynamicColors.color("#12"))
    }

    @Test
    fun `мусор в файле не ломает разбор`() {
        val colors = DynamicColors.parse("""{ "mPrimary": "#a1c9ff", "size": 12, "name": "тема", broken }""")
        assertEquals(1, colors.size)
        assertTrue(colors.containsKey("mPrimary"))
        assertTrue(DynamicColors.parse("").isEmpty())
    }

    @Test
    fun `цвета фона и панелей заметно различаются`() {
        // Контейнеры выводятся смешиванием — они должны остаться читаемыми на фоне
        val surface = Color(0xFF111317)
        val primary = Color(0xFFA1C9FF)
        val container = primary.mix(surface, 0.72f)
        assertTrue(container.red > surface.red && container.blue > surface.blue)
        assertTrue(container.blue < primary.blue)
    }
}
