package org.wexclient.app.history

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationHistoryManagerTest {

    @Test
    fun testRecordAndBeforeClose() {
        NavigationHistoryManager.clear()
        assertTrue(NavigationHistoryManager.items.isEmpty())

        val p1 = Paths.get("/home/user/downloads")
        val p2 = Paths.get("/home/user/documents")

        NavigationHistoryManager.record(p1, fileName = "file1.txt", paneSide = "Левая")
        assertEquals(1, NavigationHistoryManager.items.size)
        assertEquals("/home/user/downloads", NavigationHistoryManager.items[0].pathStr)
        assertEquals("file1.txt", NavigationHistoryManager.items[0].fileName)
        assertEquals("Левая", NavigationHistoryManager.items[0].paneSide)
        assertFalse(NavigationHistoryManager.items[0].isBeforeClose)

        NavigationHistoryManager.recordBeforeClose(
            leftPath = p1,
            leftFile = "file1.txt",
            rightPath = p2,
            rightFile = "document.pdf"
        )

        val beforeCloseItems = NavigationHistoryManager.items.filter { it.isBeforeClose }
        assertEquals(2, beforeCloseItems.size)
        val leftItem = beforeCloseItems.first { it.paneSide == "Левая" }
        assertEquals("/home/user/downloads", leftItem.pathStr)
        assertEquals("file1.txt", leftItem.fileName)

        val rightItem = beforeCloseItems.first { it.paneSide == "Правая" }
        assertEquals("/home/user/documents", rightItem.pathStr)
        assertEquals("document.pdf", rightItem.fileName)

        val justNow = NavigationHistoryManager.formatRelativeTime(System.currentTimeMillis())
        assertEquals("Только что", justNow)

        NavigationHistoryManager.clear()
        assertTrue(NavigationHistoryManager.items.isEmpty())
    }
}
