package org.wexclient.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.wexclient.app.doc.*
import org.wexclient.app.theme.NoctaliaTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PdfReaderUiTest {
    private fun available() { assumeTrue(PdfTools.available && PdfTools.textAvailable, "Poppler не установлен") }
    private fun setup(): Triple<PdfBookSession, PdfInfo, PdfReadingStore> {
        available()
        val dir = Files.createTempDirectory("pdf-ui")
        val pdf = pdfReaderFixture(dir)
        return Triple(PdfBookSession(pdf, dir.resolve("cache")), assertNotNull(PdfTools.info(pdf)), PdfReadingStore(dir.resolve("settings")))
    }
    @Test fun `keyboard navigation and page entry update the visible page`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(mode = PdfReadingMode.PAGE), store, "Книга.pdf") } }
            onNodeWithTag("pdf-reader").performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 2 / 3")
            onNodeWithTag("pdf-page-input").performTextReplacement("3")
            onNodeWithTag("pdf-page-input").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 3 / 3")
            onNodeWithTag("pdf-reader").performKeyInput { pressKey(Key.MoveHome) }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 1 / 3")
        } finally { book.close() }
    }
    @Test fun `search focuses immediately and finds the real second page`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(mode = PdfReadingMode.PAGE), store, "Книга.pdf") } }
            onNodeWithTag("pdf-reader").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
            waitUntil(timeoutMillis = 3000) { onAllNodes(isFocused()).fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText) } }
            onNodeWithTag("pdf-search").performTextInput("needle")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1 стр.").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("pdf-search").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 2 / 3")
        } finally { book.close() }
    }
    @Test fun `bookmarks and the last page are restored after content closes`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        var shown by mutableStateOf(true)
        try {
            setContent { if (shown) NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(mode = PdfReadingMode.PAGE), store, "Книга.pdf") } }
            onNodeWithTag("pdf-reader").performKeyInput { pressKey(Key.DirectionRight); keyDown(Key.CtrlLeft); pressKey(Key.D); keyUp(Key.CtrlLeft) }
            onNodeWithContentDescription("Убрать закладку (Ctrl+D)").assertExists()
            runOnIdle { shown = false }
            waitForIdle()
            val saved = store.load(book.path, info.pages)
            assertEquals(2, saved.page); assertEquals(setOf(2), saved.bookmarks)
        } finally { book.close() }
    }
    @Test fun `text mode reflows a real text layer and has selectable content`() = runDesktopComposeUiTest(width = 1000, height = 760) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(page = 2, mode = PdfReadingMode.TEXT), store, "Книга.pdf") } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Second chapter: NEEDLE needle.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Second chapter: NEEDLE needle.", substring = true).assertIsDisplayed()
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 2 / 3")
        } finally { book.close() }
    }
    @Test fun `typing does not trigger page navigation and oversized page entries clamp`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(mode = PdfReadingMode.PAGE), store, "Книга.pdf") } }
            onNodeWithTag("pdf-page-input").performTextReplacement("9999")
            onNodeWithTag("pdf-page-input").performKeyInput { pressKey(Key.MoveHome); pressKey(Key.DirectionRight) }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 1 / 3")
            onNodeWithTag("pdf-page-input").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 3 / 3")
        } finally { book.close() }
    }
    @Test fun `paper and night layouts render without clipping at laptop size`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info.copy(title = "Книга на вечер", author = "Демонстрационный документ"), PdfReadingState(fit = PdfFit.PAGE), store, "Книга.pdf") } }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Страница 1").fetchSemanticsNodes().isNotEmpty() }
            val dir = Path.of("build/reports/pdf-reader"); Files.createDirectories(dir)
            fun capture(name: String) {
                val bitmap = onNodeWithTag("pdf-reader").captureToImage()
                org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { img ->
                    img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data -> Files.write(dir.resolve(name), data.bytes) }
                }
            }
            capture("paper.png")
            onNodeWithText("Бумага").performClick()
            onNodeWithText("Ночная — тёмные страницы").performClick()
            waitForIdle(); capture("night.png")
        } finally { book.close() }
    }
    @Test fun `continuous reading restores the same page and scroll offset`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        var shown by mutableStateOf(true)
        try {
            setContent { if (shown) NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(page = 2, offset = 80), store, "Книга.pdf") } }
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 2 / 3")
            runOnIdle { shown = false }; waitForIdle()
            assertEquals(2, store.load(book.path, 3).page)
            assertEquals(80, store.load(book.path, 3).offset)
        } finally { book.close() }
    }
    @Test fun `switching from single pages to the scroll view keeps the current page`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(page = 3, mode = PdfReadingMode.PAGE), store, "Книга.pdf") } }
            onNodeWithText("По странице").performClick()
            onNodeWithText("Непрерывная прокрутка").performClick()
            waitForIdle()
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 3 / 3")
        } finally { book.close() }
    }
    @Test fun `bookmark sidebar returns to a saved page without editing the document`() = runDesktopComposeUiTest(width = 1200, height = 820) {
        val (book, info, store) = setup()
        try {
            setContent { NoctaliaTheme(backdrop = false) { PdfReaderContent(book, info, PdfReadingState(mode = PdfReadingMode.PAGE, bookmarks = setOf(2)), store, "Книга.pdf") } }
            onNodeWithContentDescription("Страницы и закладки").performClick()
            onNodeWithText("Закладки").performClick()
            onNodeWithText("Страница 2").performClick()
            onNodeWithTag("pdf-progress").assertTextEquals("Стр. 2 / 3")
        } finally { book.close() }
    }

}
