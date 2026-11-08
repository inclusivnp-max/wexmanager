package org.wexclient.app.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.*
import org.wexclient.app.util.AppDirs
import java.util.Properties
import kotlin.test.*

class CompletionPopupSizingTest {
    @Test fun `preferred size clamps extreme values and replaces nonfinite input`() {
        assertEquals(DpSize(720.dp, 420.dp), CompletionPopupSizing.preferred(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(DpSize(280.dp, 180.dp), CompletionPopupSizing.preferred(-10f, -20f))
        assertEquals(DpSize(2400.dp, 1600.dp), CompletionPopupSizing.preferred(99999f, 99999f))
    }

    @Test fun `small windows override preferred minimum without overflowing`() {
        assertEquals(DpSize(212.dp, 132.dp), CompletionPopupSizing.fit(DpSize(720.dp, 420.dp), DpSize(220.dp, 140.dp)))
        assertEquals(DpSize(1.dp, 1.dp), CompletionPopupSizing.fit(DpSize(720.dp, 420.dp), DpSize.Zero))
    }

    @Test fun `fitting a smaller viewport never changes the remembered preference`() {
        val requested = DpSize(1000.dp, 600.dp)
        assertEquals(DpSize(492.dp, 292.dp), CompletionPopupSizing.fit(requested, DpSize(500.dp, 300.dp)))
        assertEquals(requested, CompletionPopupSizing.fit(requested, DpSize(1400.dp, 900.dp)))
    }

    @Test fun `popup position keeps a resized popup inside every window edge`() {
        for (caret in listOf(Rect(0f, 0f, 2f, 20f), Rect(1090f, 680f, 1092f, 700f))) {
            val position = CaretPopupPosition(caret, 2).calculatePosition(IntRect(0, 0, 1100, 700), IntSize(1100, 700), LayoutDirection.Ltr, IntSize(1092, 692))
            assertTrue(position.x >= 0 && position.y >= 0)
            assertTrue(position.x + 1092 <= 1100 && position.y + 692 <= 700)
        }
    }

    @Test fun `size preferences are written together without losing other editor settings`() {
        val oldWidth = EditorPreferences.completionWidthDp; val oldHeight = EditorPreferences.completionHeightDp
        try {
            EditorPreferences.updateCompletionSize(940f, 510f)
            assertEquals(940f, EditorPreferences.completionWidthDp);assertEquals(510f, EditorPreferences.completionHeightDp)
            val properties = Properties()
            AppDirs.config.resolve("editor.properties").toFile().inputStream().use { properties.load(it) }
            assertEquals("940.0", properties.getProperty("completionWidthDp"))
            assertEquals("510.0", properties.getProperty("completionHeightDp"))
            assertNotNull(properties.getProperty("fontSizeSp")); assertNotNull(properties.getProperty("autoPopup"))
        } finally { EditorPreferences.updateCompletionSize(oldWidth, oldHeight) }
    }
}
