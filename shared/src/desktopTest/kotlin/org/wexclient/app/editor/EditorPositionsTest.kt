package org.wexclient.app.editor

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorPositionsTest {
    @Test
    fun `caret place is remembered per file and the file start is not stored`() {
        val a = Path.of(System.getProperty("user.home"), "positions-test", "a.kt")
        val b = Path.of(System.getProperty("user.home"), "positions-test", "../positions-test/b.kt")
        EditorPositions.put(a, 41, 7)
        EditorPositions.put(b, 3, 0)
        assertEquals(TextTarget(42, 8), EditorPositions.get(a))
        // Путь сводится к одному виду: «..» не делает из файла другой
        assertEquals(TextTarget(4, 1), EditorPositions.get(b.normalize()))
        EditorPositions.put(a, 0, 0)
        assertNull(EditorPositions.get(a))
    }
}
