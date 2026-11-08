package org.wexclient.app.editor

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.NoctaliaTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import java.awt.event.KeyEvent as AwtKey

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class CompletionPopupTest {
    private val longDetail = "(x, y, width, height, color) → bool · Нарисовать прямоугольник указанного размера и цвета. " +
        "Описание сохраняется целиком, переносится по ширине окна и доступно при прокрутке. ".repeat(5) + "КОНЕЦ ОПИСАНИЯ"
    private val items get() = listOf(
        CompletionItem("gui.draw_rect", ItemKind.FUNCTION, longDetail),
        CompletionItem("gui.begin", ItemKind.FUNCTION, "(title, width, height) → bool · Открыть окно; размеры можно опустить"),
        CompletionItem("gui.progress", ItemKind.FUNCTION, "(fraction, text) → bool · Индикатор выполнения")
    ) + (3..19).map { CompletionItem("other_$it", ItemKind.FUNCTION, "Описание функции $it") }

    private fun withPreferences(block: () -> Unit) {
        val width = EditorPreferences.completionWidthDp;val height = EditorPreferences.completionHeightDp
        try { EditorPreferences.updateCompletionSize(720f, 420f); block() }
        finally { EditorPreferences.updateCompletionSize(width, height) }
    }

    private fun popup(width: Int = 1100, height: Int = 700,
        block: ComposeUiTest.(MutableIntState, MutableState<Boolean>, MutableList<String>) -> Unit
    ) = withPreferences {
        runDesktopComposeUiTest(width = width, height = height) {
            val selected = mutableIntStateOf(0);val shown = mutableStateOf(true);val accepts = mutableListOf<String>()
            val candidates = items
            setContent { NoctaliaTheme(backdrop = false) {
                if (shown.value) CompletionPopup(
                    CompletionUi(CompletionResult(candidates, 0, 0, 0, ""), selected.intValue, false),
                    rememberEditorColors(), 13.sp, DpSize(width.dp, height.dp),
                    onSelect = { selected.intValue = it }, onAccept = { item, _ -> accepts.add(item.label) }
                )
            } }
            block(selected, shown, accepts)
        }
    }

    private fun ComposeUiTest.assertNoEllipsis() {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("completion-detail-text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        assertEquals(Int.MAX_VALUE, layouts.single().layoutInput.maxLines)
    }

    @Test fun `full selected description wraps instead of ellipsizing`() = popup { _, _, _ ->
        onNodeWithTag("completion-detail-text").assertTextEquals(longDetail)
        assertNoEllipsis()
        capture("default.png")
    }

    @Test fun `hover updates documentation and partially visible selection scrolls fully into view`() = popup(height = 210) { selected, _, accepts ->
        onNodeWithTag("completion-item-1").performMouseInput { moveTo(center); moveBy(Offset(1f, 0f)) }
        waitUntil { selected.intValue == 1 }
        onNodeWithTag("completion-detail-name").assertTextEquals("gui.begin")
        onNodeWithTag("completion-detail-text").assertTextContains("размеры можно опустить", substring = true)
        assertTrue(accepts.isEmpty())
        onNodeWithTag("completion-item-1").performClick()
        assertEquals(listOf("gui.begin"), accepts)
        runOnIdle { selected.intValue = 4 }
        val row = onNodeWithTag("completion-item-4").getUnclippedBoundsInRoot()
        val list = onNodeWithTag("completion-list").getUnclippedBoundsInRoot()
        assertTrue(row.top >= list.top && row.bottom <= list.bottom + 1.dp, "$row / $list")
        assertEquals(4, selected.intValue, "Прокрутка под неподвижной мышью сохраняет выбор клавиатуры")
        assertEquals(listOf("gui.begin"), accepts)
    }

    @Test fun `drag grows both dimensions and size survives closing and reopening`() = popup { _, shown, accepts ->
        val before = onNodeWithTag("completion-popup").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("completion-resize-handle").performMouseInput { moveTo(center); press(); moveBy(Offset(130f, 80f)); release() }
        val after = onNodeWithTag("completion-popup").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width + 70f, "$before -> $after")
        assertTrue(after.height > before.height + 30f, "$before -> $after")
        assertEquals(after.width, EditorPreferences.completionWidthDp, 2f)
        assertEquals(after.height, EditorPreferences.completionHeightDp, 2f)
        assertTrue(accepts.isEmpty())
        runOnIdle { shown.value = false }; onNodeWithTag("completion-popup").assertDoesNotExist()
        runOnIdle { shown.value = true }
        val reopened = onNodeWithTag("completion-popup").fetchSemanticsNode().boundsInRoot
        assertEquals(after.width, reopened.width, 1f);assertEquals(after.height, reopened.height, 1f)
        capture("resized.png")
    }

    @Test fun `narrow window keeps the popup bounded and long detail scrollable`() = popup(width = 320, height = 300) { _, _, _ ->
        val bounds = onNodeWithTag("completion-popup").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width <= 312f && bounds.height <= 292f, bounds.toString())
        onNodeWithTag("completion-resize-handle").assertIsDisplayed()
        assertNoEllipsis()
        val scroll = onNodeWithTag("completion-details").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.maxValue() > 0f)
        onNodeWithTag("completion-details").performTouchInput { swipeUp() }
        assertTrue(scroll.value() > 0f)
        onNodeWithTag("completion-detail-text").assertTextContains("КОНЕЦ ОПИСАНИЯ", substring = true)
        capture("compact.png")
    }

    @Test fun `real editor keeps keyboard completion and argument fields after resizing`() = withPreferences {
        runDesktopComposeUiTest(width = 1100, height = 700) {
            val source = "import gui;\n\ngui."
            val ctrl = CodeEditorController(source)
            setContent { NoctaliaTheme(backdrop = false) {
                CodeEditorView(source, Languages.WEX, controller = ctrl, fileName = "demo.wex", modifier = Modifier.testTag("completion-editor"))
            } }
            runOnIdle { ctrl.select(source.length);ctrl.requestCompletion(true) }
            waitUntil(timeoutMillis = 5_000) { ctrl.completion != null }
            onNodeWithTag("completion-resize-handle").performMouseInput { moveTo(center); press(); moveBy(Offset(100f, 50f)); release() }
            val index = ctrl.completion!!.result.items.indexOfFirst { it.label == "draw_rect" };assertTrue(index >= 0)
            onNodeWithTag("completion-item-$index").performMouseInput { moveTo(center); moveBy(Offset(1f, 0f)) }
            waitUntil { ctrl.completion?.selected == index }
            onNodeWithTag("completion-detail-text").assertTextContains("width", substring = true)
            capture("editor.png")
            val component = object : java.awt.Component() {}
            val awt = AwtKey(component, AwtKey.KEY_PRESSED, 0, 0, AwtKey.VK_TAB, AwtKey.CHAR_UNDEFINED)
            onNode(isFocused()).performKeyPress(KeyEvent(Key.Tab, KeyEventType.KeyDown, nativeEvent = awt))
            waitUntil { ctrl.completion == null }
            assertTrue(ctrl.text.endsWith("gui.draw_rect(x, y, width, height, color)"), ctrl.text)
            assertEquals("x", ctrl.text.substring(ctrl.value.selection.min, ctrl.value.selection.max))
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        val folder = Path.of("build/reports/completion-popup");Files.createDirectories(folder)
        org.jetbrains.skia.Image.makeFromBitmap(onNodeWithTag("completion-popup").captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(folder.resolve(name), it.bytes) }
        }
    }
}
