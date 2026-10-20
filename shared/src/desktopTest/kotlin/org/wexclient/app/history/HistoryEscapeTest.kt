package org.wexclient.app.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HistoryEscapeTest {
    @Test
    fun `names with tabs, line breaks and backslashes survive a round trip`() {
        for (name in listOf("plain.txt", "a\tb.txt", "line\nbreak", "back\\slash", "\\t literal", "end\\", "\r\n")) {
            val stored = NavigationHistoryManager.escapeField(name)
            assertFalse(stored.any { it == '\t' || it == '\n' || it == '\r' }, stored)
            assertEquals(name, NavigationHistoryManager.unescapeField(stored))
        }
        // Старые записи без экранирования читаются как были
        assertEquals("C:\\x", NavigationHistoryManager.unescapeField("C:\\x"))
    }
}
