package org.wexclient.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.wexclient.app.vfs.VfsEntry
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class EditorNativeWindowTest {
    @Test fun `text documents are independent native windows while prompts remain modal`() {
        if (GraphicsEnvironment.isHeadless()) return
        val folder = Files.createTempDirectory("wex-native-editor-")
        val files = (1..3).map { folder.resolve("document-$it.txt").also { f -> Files.writeString(f, "alpha beta\n") } }
        val failure = AtomicReference<Throwable?>()
        try {
            application(exitProcessOnExit = false) {
                var showPrompt by remember { mutableStateOf(false) }
                Window(onCloseRequest = {}, title = "WEX test manager", state = rememberWindowState(width = 400.dp, height = 300.dp)) { }
                for (file in files) {
                    FileViewerDialog(entry = VfsEntry.of(file), content = "alpha beta\n", onSave = {}, onDismiss = {}, onReveal = {})
                }
                if (showPrompt) WexWindow(onCloseRequest = {}, title = "WEX test prompt", size = androidx.compose.ui.unit.DpSize(200.dp, 100.dp)) { }
                LaunchedEffect(Unit) {
                    try {
                        delay(1500)
                        val documents = AwtWindow.getWindows().filter { it.isShowing && (it as? java.awt.Frame)?.title in files.map { f -> f.fileName.toString() } }
                        // The old document-modal DialogWindow gives zero independent Frames.
                        assertEquals(3, documents.size, "Every text document must be an independent window")
                        assertTrue(documents.all { it !is Dialog })
                        assertTrue(AwtWindow.getWindows().any { it.isShowing && (it as? java.awt.Frame)?.title == "WEX test manager" })
                        showPrompt = true
                        delay(500)
                        assertTrue(AwtWindow.getWindows().any { it is Dialog && it.isShowing && it.title == "WEX test prompt" && it.isModal })
                    } catch (t: Throwable) { failure.set(t) }
                    finally { exitApplication() }
                }
            }
            failure.get()?.let { throw it }
        } finally { folder.toFile().deleteRecursively() }
    }
}
