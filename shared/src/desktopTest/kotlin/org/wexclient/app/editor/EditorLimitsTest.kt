package org.wexclient.app.editor

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorLimitsTest {
    @Test
    fun `too many lines or too long a line are refused`() {
        assertNull(EditorLimits.problem(""))
        assertNull(EditorLimits.problem("a\nb\n"))
        assertNull(EditorLimits.problem("\n".repeat(EditorLimits.MAX_LINES - 1)))
        val tall = assertNotNull(EditorLimits.problem("\n".repeat(EditorLimits.MAX_LINES)))
        assertTrue(tall.contains("строк"), tall)

        assertNull(EditorLimits.problem("x".repeat(EditorLimits.MAX_LINE_CHARS)))
        val wide = assertNotNull(EditorLimits.problem("short\n" + "x".repeat(EditorLimits.MAX_LINE_CHARS + 1) + "\nend"))
        assertTrue(wide.contains("длиной"), wide)
        // Последняя строка без перевода — тоже считается
        assertNotNull(EditorLimits.problem("a\n" + "y".repeat(EditorLimits.MAX_LINE_CHARS + 1)))
    }
}
