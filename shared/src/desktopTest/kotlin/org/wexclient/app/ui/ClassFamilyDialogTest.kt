package org.wexclient.app.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import org.wexclient.app.theme.NoctaliaTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ClassFamilyDialogTest {
    private val d = '$'
    private val parts = listOf(
        ClassFamilyItem("test.MainActivity", "MainActivity", "classes.dex"),
        ClassFamilyItem("test.MainActivity${d}Inner", "MainActivity${d}Inner", "classes2.dex"),
        ClassFamilyItem("test.MainActivity${d}Companion", "MainActivity${d}Companion", "classes.dex")
    )
    @Test fun `whole class opens in one action without selecting every part`() = runComposeUiTest {
        var whole = 0;var selected: String? = null
        setContent { NoctaliaTheme(backdrop = false) { ClassFamilyDialog("test.MainActivity", parts, "Java целиком, Smali по частям.", { selected = it }, { whole++ }, {}) } }
        onNodeWithTag("class-family-whole").performClick()
        assertEquals(1, whole);assertNull(selected)
    }
    @Test fun `choosing an inner class sends its exact identity`() = runComposeUiTest {
        var selected: String? = null;var whole = false
        setContent { NoctaliaTheme(backdrop = false) { ClassFamilyDialog("test.MainActivity", parts, "Java целиком, Smali по частям.", { selected = it }, { whole = true }, {}) } }
        onNodeWithTag("class-family-part:${parts[1].id}").performClick()
        assertEquals(parts[1].id, selected);assertFalse(whole)
    }
    @Test fun `cancel does not open any class`() = runComposeUiTest {
        var opened = false;var canceled = false
        setContent { NoctaliaTheme(backdrop = false) { ClassFamilyDialog("test.MainActivity", parts, "", { opened = true }, { opened = true }, { canceled = true }) } }
        onNodeWithText("Отмена").performClick()
        assertTrue(canceled);assertFalse(opened)
    }
    @Test fun `large families can be searched without narrowing the whole class action`() = runComposeUiTest {
        val many = (0..100).map { ClassFamilyItem("p.MainActivity${d}Inner$it", "MainActivity${d}Inner$it", "classes${it % 3 + 1}.dex") }
        var whole = 0
        setContent { NoctaliaTheme(backdrop = false) { ClassFamilyDialog("p.MainActivity", many, "", {}, { whole++ }, {}) } }
        onNode(hasSetTextAction()).performTextInput("Inner80")
        onNodeWithTag("class-family-part:${many[80].id}").assertIsDisplayed()
        onNodeWithTag("class-family-whole").performClick()
        assertEquals(1, whole)
    }
    @Test fun `long generated names wrap and remain readable`() = runComposeUiTest {
        val long = "MainActivity" + "${d}GeneratedInnerPart".repeat(9)
        val options = parts.take(1) + ClassFamilyItem("test.$long", long, "classes2.dex · test.MainActivity")
        setContent { NoctaliaTheme(backdrop = false) { ClassFamilyDialog("test.MainActivity", options, "Java целиком, Smali по частям.", {}, {}, {}) } }
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(long, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1);assertFalse(layouts.single().hasVisualOverflow)
        val path = Path.of("build/reports/class-families/chooser.png");Files.createDirectories(path.parent)
        org.jetbrains.skia.Image.makeFromBitmap(onNodeWithTag("class-family-dialog").captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) }
        }
    }
}
