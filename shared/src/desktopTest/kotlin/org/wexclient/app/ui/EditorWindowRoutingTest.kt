package org.wexclient.app.ui

import org.wexclient.app.editor.EditorLimits
import java.nio.file.Files
import kotlin.test.*

class EditorWindowRoutingTest {
    @Test fun `embedded viewer raises its owner without adding windows or pending opens`() {
        WexWindows.openManager(null)
        val manager = WexWindows.managers.last()
        val count = WexWindows.managers.size
        try {
            WexWindows.raiseManager(manager.id)
            assertEquals(manager.id, WexWindows.raise)
            assertEquals(count, WexWindows.managers.size)
            assertNull(WexWindows.pendingFor(manager.id))
            WexWindows.raised(manager.id)
            WexWindows.raiseManager(-1)
            assertNull(WexWindows.raise)
        } finally { WexWindows.close(manager) }
    }
    @Test fun `equivalent file paths reuse the existing editor window`() {
        val dir = Files.createTempDirectory("wex-editor-route-")
        val file = dir.resolve("sample.txt")
        val before = WexWindows.editors.toList()
        try {
            Files.writeString(file, "alpha beta")
            WexWindows.openEditor(file)
            val opened = WexWindows.editors.last()
            WexWindows.openEditor(dir.resolve("unused/../sample.txt"))
            assertEquals(before.size + 1, WexWindows.editors.size)
            assertEquals(opened.id, WexWindows.raise)
        } finally {
            WexWindows.editors.toList().filter { it !in before }.forEach { WexWindows.close(it) }
            Files.deleteIfExists(file); Files.deleteIfExists(dir)
        }
    }
    @Test fun `text larger than two MiB routes to the same supported editor limit`() {
        val file = Files.createTempFile("wex-editor-limit-", ".txt")
        try {
            Files.writeString(file, "alpha beta\n".repeat(220_000))
            assertTrue(Files.size(file) > 2 * 1024 * 1024)
            assertTrue(Files.size(file) < EditorLimits.MAX_FILE_BYTES)
            assertTrue(WexWindows.opensInEditor(file))
        } finally { Files.deleteIfExists(file) }
    }
}
