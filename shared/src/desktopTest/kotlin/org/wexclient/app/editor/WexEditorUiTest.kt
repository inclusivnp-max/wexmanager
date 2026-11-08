package org.wexclient.app.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.NoctaliaTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import java.awt.event.KeyEvent as AwtKey

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class WexEditorUiTest {
    @Test fun `real editor opens member popup accepts arguments and moves between tab stops`() = runBlocking {
        val source = "import gui;\n\nconst TITLE = \"WEX demo\";\nvar ratio = 0.5;\n\nfn render() {\n    gui.text(TITLE);\n}\n\n"
        val ctrl = CodeEditorController(source)
        val component = object : java.awt.Component() {}
        val scene = ImageComposeScene(1100, 700, Density(1f), coroutineContext) {
            NoctaliaTheme(backdrop = false) {
                CodeEditorView(source, Languages.WEX, fileName = "demo.wex", controller = ctrl, onSave = {})
            }
        }
        var time = 0L
        suspend fun frames(n: Int = 6) { repeat(n) { time += 16_000_000; scene.render(time).close(); delay(8) } }
        fun typed(c: Char) = KeyEvent(Key.Unknown, KeyEventType.Unknown, c.code,
            nativeEvent = AwtKey(component, AwtKey.KEY_TYPED, 0, 0, AwtKey.VK_UNDEFINED, c))
        fun tab() = KeyEvent(Key.Tab, KeyEventType.KeyDown, nativeEvent = AwtKey(component, AwtKey.KEY_PRESSED, 0, 0, AwtKey.VK_TAB, AwtKey.CHAR_UNDEFINED))
        try {
            frames(12); ctrl.select(source.length); frames()
            for (c in "gui.") { scene.sendKeyEvent(typed(c)); frames(2) }
            repeat(50) { if (ctrl.completion == null) frames(2) }
            val completion = assertNotNull(ctrl.completion)
            assertEquals(11, completion.result.items.size)
            assertTrue(completion.result.items.any { it.label == "progress" && it.detail.contains("fraction") })
            val folder = Path.of("build/reports/wex-language"); Files.createDirectories(folder)
            time += 16_000_000
            scene.render(time).use { frame -> frame.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(folder.resolve("wex-completion.png"), it.bytes) } }
            ctrl.moveCompletion(completion.result.items.indexOfFirst { it.label == "progress" }); frames(2)
            scene.sendKeyEvent(tab()); frames()
            assertTrue(ctrl.text.endsWith("gui.progress(fraction, text)"), ctrl.text)
            assertEquals("fraction", ctrl.text.substring(ctrl.value.selection.min, ctrl.value.selection.max))
            for (c in "ratio") scene.sendKeyEvent(typed(c))
            frames(); scene.sendKeyEvent(tab()); frames()
            assertEquals("text", ctrl.text.substring(ctrl.value.selection.min, ctrl.value.selection.max))
            assertTrue(ctrl.text.endsWith("gui.progress(ratio, text)"))
        } finally { scene.close() }
    }
}
