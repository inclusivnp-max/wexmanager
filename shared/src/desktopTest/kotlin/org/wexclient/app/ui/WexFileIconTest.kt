package org.wexclient.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsManager
import org.wexclient.app.vfs.VfsItemType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WexFileIconTest {
    @Test fun `dedicated W vector keeps a WEX description with normal and overridden tint`() = runDesktopComposeUiTest(width = 260, height = 100) {
        setContent {
            NoctaliaTheme(backdrop = false) {
                Row(Modifier.testTag("wex-icons").padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FileIconBadge(VfsItemType.CODE_WEX)
                    FileIconBadge(VfsItemType.CODE_WEX, tintOverride = Color.White)
                    Icon(WexFileIcon, null, Modifier.size(64.dp), tint = Color(0xFF5FD4B0))
                }
            }
        }
        assertEquals("WexFile", WexFileIcon.name)
        onAllNodesWithContentDescription("Файл WEX").assertCountEquals(2)
        onAllNodesWithContentDescription("Файл WEX")[0].assertIsDisplayed()
        capture("wex-icons.png", "wex-icons")
    }

    @Test fun `actual file list resolves mixed case WEX extensions to dedicated badges`() {
        val dir = Files.createTempDirectory("wex-icon-test-")
        Files.writeString(dir.resolve("demo.wex"), "var count = 1;")
        Files.writeString(dir.resolve("MAIN.WEX"), "log(1);")
        Files.writeString(dir.resolve("notes.txt"), "notes")
        val vfs = VfsManager(); val state = FilePaneState(vfs, dir)
        try {
            runDesktopComposeUiTest(width = 700, height = 420) {
                setContent { NoctaliaTheme(backdrop = false) {
                    FilePaneView(state = state, isActive = true, onActivate = {}, onOpenFile = {}, onCopyOther = {}, onDelete = {}, onProperties = {}, modifier = Modifier.testTag("wex-file-list"))
                } }
                waitUntil(timeoutMillis = 5_000) { state.entries.size == 3 }
                assertEquals(2, state.entries.count { it.itemType == VfsItemType.CODE_WEX })
                onAllNodesWithContentDescription("Файл WEX", useUnmergedTree = true).assertCountEquals(2)
                onNodeWithText("demo.wex").assertIsDisplayed()
                capture("wex-file-list.png", "wex-file-list")
            }
        } finally {
            state.dispose();vfs.closeAll()
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach { file -> Files.deleteIfExists(file) } }
        }
    }

    private fun ComposeUiTest.capture(name: String, tag: String) {
        val folder = Path.of("build/reports/wex-language");Files.createDirectories(folder)
        org.jetbrains.skia.Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(folder.resolve(name), it.bytes) }
        }
    }
}
