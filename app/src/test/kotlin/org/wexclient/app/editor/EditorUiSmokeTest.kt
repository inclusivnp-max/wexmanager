package org.wexclient.app.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.wexclient.app.editor.lang.Languages
import java.awt.event.InputEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKey

/**
 * Редактор целиком в невидимой сцене Compose: ввод, мышь, несколько кареток и поиск в выделении
 * проходят через настоящие обработчики поверхности и рисуются без ошибок.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class EditorUiSmokeTest {
    private val source = object : java.awt.Component() {}

    private fun key(key: Key, code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): KeyEvent {
        var mods = 0
        if (ctrl) mods = mods or InputEvent.CTRL_DOWN_MASK
        if (shift) mods = mods or InputEvent.SHIFT_DOWN_MASK
        if (alt) mods = mods or InputEvent.ALT_DOWN_MASK
        val awt = AwtKey(source, AwtKey.KEY_PRESSED, 0L, mods, code, AwtKey.CHAR_UNDEFINED)
        return KeyEvent(key, KeyEventType.KeyDown, 0, ctrl, false, alt, shift, awt)
    }

    private fun typed(ch: Char): KeyEvent {
        val awt = AwtKey(source, AwtKey.KEY_TYPED, 0L, 0, AwtKey.VK_UNDEFINED, ch)
        return KeyEvent(Key.Unknown, KeyEventType.Unknown, ch.code, nativeEvent = awt)
    }

    private class Harness(val scene: ImageComposeScene) {
        var time = 0L

        suspend fun frames(n: Int = 4) {
            repeat(n) {
                time += 16_000_000L
                scene.render(time)
                delay(5)
            }
        }
    }

    private fun withEditor(text: String, block: suspend Harness.(CodeEditorController) -> Unit) = runBlocking {
        val ctrl = CodeEditorController(text)
        val scene = ImageComposeScene(1100, 700, Density(1f), coroutineContext) {
            MaterialTheme {
                CodeEditorView(initialText = text, language = Languages.KOTLIN, controller = ctrl, onSave = {})
            }
        }
        try {
            val h = Harness(scene)
            h.frames(8)
            h.block(ctrl)
            h.frames(2)
        } finally {
            scene.close()
        }
    }

    private val sample = (1..40).joinToString("\n") { "val item$it = compute($it) // строка $it" } + "\n"

    @Test
    fun `typing and editor shortcuts go through the surface`() = withEditor(sample) { ctrl ->
        ctrl.select(0)
        frames()
        scene.sendKeyEvent(typed('x'))
        frames()
        assertTrue(ctrl.text.startsWith("xval item1"), ctrl.text.take(20))
        // Ctrl+Z — отмена через бинды редактора
        scene.sendKeyEvent(key(Key.Z, AwtKey.VK_Z, ctrl = true))
        frames()
        assertTrue(ctrl.text.startsWith("val item1"), ctrl.text.take(20))
    }

    @Test
    fun `alt j and typing edit every occurrence and escape leaves one caret`() = withEditor(sample) { ctrl ->
        ctrl.select(4)
        frames()
        repeat(3) {
            scene.sendKeyEvent(key(Key.J, AwtKey.VK_J, alt = true))
            frames(1)
        }
        // «item1» — само слово; «item10»… целиком не совпадают
        assertEquals(1, ctrl.carets().size, "item1 встречается как слово один раз")
        ctrl.setCarets(listOf(TextRange(0, 3), TextRange(ctrl.text.indexOf("val", 5), ctrl.text.indexOf("val", 5) + 3)), TextRange(0, 3))
        frames()
        scene.sendKeyEvent(typed('v'))
        scene.sendKeyEvent(typed('a'))
        scene.sendKeyEvent(typed('r'))
        frames()
        assertTrue(ctrl.text.startsWith("var item1 = compute(1) // строка 1\nvar item2"), ctrl.text.take(60))
        assertEquals(2, ctrl.carets().size)
        scene.sendKeyEvent(key(Key.Escape, AwtKey.VK_ESCAPE))
        frames()
        assertFalse(ctrl.hasMultipleCarets)
    }

    @Test
    fun `alt click adds a caret and middle drag makes a column of carets`() = withEditor(sample) { ctrl ->
        ctrl.select(0)
        frames()
        val at = Offset(420f, 260f)
        scene.sendPointerEvent(
            PointerEventType.Press, at,
            buttons = PointerButtons(isPrimaryPressed = true),
            keyboardModifiers = PointerKeyboardModifiers(isAltPressed = true),
            button = PointerButton.Primary
        )
        scene.sendPointerEvent(
            PointerEventType.Release, at,
            buttons = PointerButtons(),
            keyboardModifiers = PointerKeyboardModifiers(isAltPressed = true),
            button = PointerButton.Primary
        )
        frames()
        assertEquals(2, ctrl.carets().size, "Alt+щелчок добавил каретку")

        // Средняя кнопка с протяжкой — прямоугольное выделение по нескольким строкам
        val from = Offset(200f, 180f)
        val to = Offset(260f, 300f)
        scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isTertiaryPressed = true), button = PointerButton.Tertiary)
        frames(1)
        scene.sendPointerEvent(PointerEventType.Move, Offset(230f, 240f), buttons = PointerButtons(isTertiaryPressed = true))
        frames(1)
        scene.sendPointerEvent(PointerEventType.Move, to, buttons = PointerButtons(isTertiaryPressed = true))
        frames(1)
        scene.sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Tertiary)
        frames()
        val carets = ctrl.carets()
        assertTrue(carets.size >= 4, "каретки: $carets")
        assertTrue(carets.all { !it.collapsed }, "у каждой строки — кусок между колонками")
        val snap = ctrl.snapshot
        assertEquals(carets.size, carets.map { snap.lineOf(it.min) }.distinct().size, "по одной на строку")
    }

    @Test
    fun `find in selection limits matches and alt enter puts carets on them`() = withEditor(sample) { ctrl ->
        // Выделены строки 3–5: Ctrl+F включает поиск в них
        val from = ctrl.snapshot.lineStart(2)
        val to = ctrl.snapshot.lineStart(5)
        ctrl.select(from, to)
        frames()
        scene.sendKeyEvent(key(Key.F, AwtKey.VK_F, ctrl = true))
        frames(6)
        assertEquals(TextRange(from, to), ctrl.searchScope)
        for (ch in "compute") scene.sendKeyEvent(typed(ch))
        frames(12)
        // Alt+Enter в строке поиска — каретки на все совпадения, но только в области
        scene.sendKeyEvent(key(Key.Enter, AwtKey.VK_ENTER, alt = true))
        frames(6)
        val carets = ctrl.carets()
        assertEquals(3, carets.size, "каретки: $carets")
        assertTrue(carets.all { it.min >= from && it.max <= to })
        for (ch in "calc") scene.sendKeyEvent(typed(ch))
        frames()
        assertEquals(3, Regex("calc\\(").findAll(ctrl.text).count())
        assertEquals(37, Regex("compute\\(").findAll(ctrl.text).count())
    }
}
