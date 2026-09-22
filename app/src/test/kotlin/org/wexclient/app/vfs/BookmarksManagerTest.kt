package org.wexclient.app.vfs

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookmarksManagerTest {

    @Test
    fun testBookmarksAddRemove() {
        val testPath = Paths.get("/tmp/test_wex_folder_123").toAbsolutePath().normalize()

        // Ensure not bookmarked initially
        if (BookmarksManager.isBookmarked(testPath)) {
            BookmarksManager.toggleBookmark(testPath)
        }
        assertFalse(BookmarksManager.isBookmarked(testPath))

        // Toggle to add
        BookmarksManager.toggleBookmark(testPath, "Test Bookmark")
        assertTrue(BookmarksManager.isBookmarked(testPath))

        val found = BookmarksManager.userBookmarks.firstOrNull { it.pathStr == testPath.toString() }
        assertEquals("Test Bookmark", found?.title)

        // Toggle to remove
        BookmarksManager.toggleBookmark(testPath)
        assertFalse(BookmarksManager.isBookmarked(testPath))
    }

    @Test
    fun testQuickLocationsIncludeSystem() {
        val quick = BookmarksManager.getQuickLocations()
        val root = quick.firstOrNull { it.pathStr == "/" }
        assertTrue(root != null, "Root filesystem should be in quick locations")
        assertTrue(root.isSystem, "Root filesystem should be marked as system")
    }
}
