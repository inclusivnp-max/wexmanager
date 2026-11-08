package org.wexclient.app.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.wexclient.app.editor.lang.Languages
import java.awt.event.InputEvent
import kotlin.test.*
import java.awt.event.KeyEvent as AwtKey

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class WordWrapUiTest {
    private val source = object : java.awt.Component() {}
    private class Harness(val ctrl: CodeEditorController, val state: FastSurfaceState, val scene: ImageComposeScene) {
        var active by mutableStateOf(true)
        var width by mutableStateOf(360.dp)
        var time = 0L
        suspend fun frames(count: Int = 4) { repeat(count) { scene.render(time); time += 16_000_000L; delay(8) } }
    }
    private fun withSurface(block: suspend Harness.() -> Unit) = runBlocking {
        val oldWrap = EditorPreferences.softWrap
        val ctrl = CodeEditorController((1..20).joinToString("\n") { "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu" })
        ctrl.applyLanguage(Languages.PLAIN)
        ctrl.updateSoftWrap(true)
        val state = FastSurfaceState(ctrl)
        val requester = FocusRequester()
        val scene = ImageComposeScene(600, 350, Density(1f), coroutineContext)
        val h = Harness(ctrl, state, scene)
        scene.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    FastEditorSurface(ctrl, state, false, 14.sp, 21.sp,
                        SearchHighlight(SearchMatches(IntArray(0), 0, null, false), -1), requester,
                        onKey = { false }, onChange = { ctrl.applyEdit(it) }, onPaste = {}, onTypeCarets = {},
                        contextItems = { emptyList() }, onZoom = {},
                        modifier = Modifier.width(h.width).fillMaxSize(), active = h.active)
                }
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
        try { h.frames(8); h.block() } finally { scene.close(); EditorPreferences.updateSoftWrap(oldWrap) }
    }
    private fun Harness.press(key: Key, code: Int, shift: Boolean = false) {
        val mods = if (shift) InputEvent.SHIFT_DOWN_MASK else 0
        val event = AwtKey(source, AwtKey.KEY_PRESSED, 0, mods, code, AwtKey.CHAR_UNDEFINED)
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, 0, false, false, false, shift, event))
    }
    @Test fun `every wrapped continuation is painted at the left text margin`() = withSurface {
        frames()
        scene.render(time).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data ->
                val folder = java.nio.file.Path.of("build/reports/wordwrap")
                java.nio.file.Files.createDirectories(folder)
                java.nio.file.Files.write(folder.resolve("wrapped-surface.png"), data.bytes)
                val pixels = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data.bytes))
                val left = state.textLeft.toInt() + 4
                for (row in 0..2) {
                    val top = (row * state.metrics.lineHeight).toInt()
                    val colors = HashSet<Int>()
                    for (x in left until left + 120) for (y in top until top + state.metrics.lineHeight.toInt()) colors.add(pixels.getRGB(x, y))
                    assertTrue(colors.size > 8, "visual row $row contains no glyphs at left margin: colors=${colors.size}")
                }
            }
        }
    }
    @Test fun `Home End and vertical arrows use visual rows`() = withSurface {
        val layout = state.layout!!
        assertTrue(layout.rowsOf(0) >= 2)
        ctrl.select(layout.offsetAt(0, 2)); frames()
        press(Key.MoveEnd, AwtKey.VK_END); frames()
        assertEquals(layout.offsetAt(0, Int.MAX_VALUE), ctrl.value.selection.end)
        assertEquals(0, state.caretPosition(state.layout!!, ctrl.value.selection.end).first)
        press(Key.MoveHome, AwtKey.VK_HOME); frames()
        assertEquals(0, ctrl.value.selection.end)
        press(Key.DirectionDown, AwtKey.VK_DOWN); frames()
        assertEquals(layout.offsetAt(1, 0), ctrl.value.selection.end)
        press(Key.MoveEnd, AwtKey.VK_END); frames()
        assertEquals(layout.offsetAt(1, Int.MAX_VALUE), ctrl.value.selection.end)
        assertEquals(1, state.caretPosition(state.layout!!, ctrl.value.selection.end).first)
    }
    @Test fun `Shift End selects only the current visual row`() = withSurface {
        ctrl.select(3); frames()
        press(Key.MoveEnd, AwtKey.VK_END, shift = true); frames()
        assertEquals(3, ctrl.value.selection.start)
        assertEquals(state.layout!!.offsetAt(0, Int.MAX_VALUE), ctrl.value.selection.end)
        assertEquals(0, state.caretPosition(state.layout!!, ctrl.value.selection.end).first)
    }
    @Test fun `clicking blank space beside wrapped text stays on that row`() = withSurface {
        val point = Offset(state.viewport.width - 18f, state.metrics.lineHeight / 2)
        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
        frames()
        assertEquals(state.layout!!.offsetAt(0, Int.MAX_VALUE), ctrl.value.selection.end)
        assertEquals(0, state.caretPosition(state.layout!!, ctrl.value.selection.end).first)
    }
    @Test fun `enabling wrap clears horizontal scrolling and resizing keeps logical anchor`() = withSurface {
        ctrl.updateSoftWrap(false); frames()
        state.scrollX = 200f; frames()
        ctrl.updateSoftWrap(true); frames()
        assertEquals(0f, state.scrollX)
        assertEquals(0f, state.maxScrollX())
        state.scrollY = state.metrics.lineHeight * 12; frames()
        val old = state.layout!!
        val anchor = old.offsetAt((state.scrollY / state.metrics.lineHeight).toInt(), 0)
        width = 500.dp; frames(8)
        val next = state.layout!!
        assertNotEquals(old.columns, next.columns)
        val top = (state.scrollY / state.metrics.lineHeight).toInt()
        assertEquals(next.rowAndColumn(anchor).first, top)
    }
    @Test fun `inactive window stops caret updates and resumes on focus`() = withSurface {
        assertTrue(ctrl.hasFocus)
        active = false; frames()
        assertFalse(state.caretVisible)
        delay(550); frames()
        assertFalse(state.caretVisible)
        active = true; frames()
        assertTrue(state.caretVisible)
    }
}
