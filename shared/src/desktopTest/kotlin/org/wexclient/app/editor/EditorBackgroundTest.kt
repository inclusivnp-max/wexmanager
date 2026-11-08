package org.wexclient.app.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.wexclient.app.editor.lang.Languages
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class EditorBackgroundTest {
    @Test fun `inactive editors defer analysis and reuse it after switching back`() = runBlocking {
        val text = "fun sample() {\n    val alpha = 1\n}\n"
        val ctrl = CodeEditorController(text)
        var active by mutableStateOf(false)
        val scene = ImageComposeScene(900, 650, Density(1f), coroutineContext) {
            MaterialTheme { CodeEditorView(text, Languages.KOTLIN, controller = ctrl, onSave = {}, active = active) }
        }
        var time = 0L
        suspend fun frame() { scene.render(time); time += 16_000_000; delay(10) }
        try {
            repeat(5) { frame() }
            delay(600); repeat(4) { frame() }
            assertNull(ctrl.wordIndex)
            assertTrue(ctrl.folds.isEmpty())
            active = true
            repeat(160) { if (ctrl.wordIndex == null) frame() }
            val index = assertNotNull(ctrl.wordIndex)
            assertTrue(ctrl.folds.isNotEmpty())
            active = false; repeat(4) { frame() }
            active = true; repeat(4) { frame() }
            delay(400); repeat(4) { frame() }
            assertSame(index, ctrl.wordIndex)
        } finally { scene.close() }
    }
}
