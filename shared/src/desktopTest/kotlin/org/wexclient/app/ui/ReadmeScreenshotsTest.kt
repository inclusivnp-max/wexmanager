package org.wexclient.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.wexclient.app.doc.*
import org.wexclient.app.editor.*
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.*

/** Actual application views, rendered with public demo data for the README. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReadmeScreenshotsTest {
    private val output = Path.of("build/reports/readme")
    private val source = """
        .class public Ldemo/MainActivity;
        .super Landroid/app/Activity;
        .source "MainActivity.java"

        .field private static final TITLE:Ljava/lang/String; = "Demo application"

        .method public constructor <init>()V
            .locals 0
            invoke-direct {p0}, Landroid/app/Activity;-><init>()V
            return-void
        .end method

        .method protected onCreate(Landroid/os/Bundle;)V
            .locals 2
            invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

            const-string v0, "Hello, Android!"
            new-instance v1, Landroid/widget/TextView;
            invoke-direct {v1, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V
            invoke-virtual {v1, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
            invoke-virtual {p0, v1}, Ldemo/MainActivity;->setContentView(Landroid/view/View;)V

            return-void
        .end method

        .method public static getTitle()Ljava/lang/String;
            .locals 1
            sget-object v0, Ldemo/MainActivity;->TITLE:Ljava/lang/String;
            return-object v0
        .end method
    """.trimIndent() + "\n"

    private fun capture(node: SemanticsNodeInteraction, name: String) {
        Files.createDirectories(output)
        org.jetbrains.skia.Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve(name), it.bytes) }
        }
    }

    @Test fun `file manager screenshot uses only demo folders and files`() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val demo = Path.of(System.getProperty("java.io.tmpdir")).resolve("WEX-demo")
        demo.toFile().deleteRecursively()
        Files.createDirectories(demo)
        val project = Files.createDirectory(demo.resolve("Demo project"))
        val documents = Files.createDirectory(demo.resolve("Документы"))
        for (name in listOf("src", "assets", "docs")) Files.createDirectory(project.resolve(name))
        Files.writeString(project.resolve("MainActivity.smali"), source)
        Files.writeString(project.resolve("README.md"), "# Demo project\n\nИсходный код и заметки проекта.\n")
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") }\n")
        Files.writeString(project.resolve("settings.json"), "{\"theme\": \"dark\", \"fontSize\": 14}\n")
        Files.writeString(project.resolve("LICENSE"), "MIT License\n")
        Files.writeString(documents.resolve("Заметки.md"), "# Заметки\n\nСписок задач на неделю.\n")
        Files.writeString(documents.resolve("План.txt"), "Собрать проект\nПроверить интерфейс\nОпубликовать выпуск\n")
        Files.writeString(documents.resolve("Таблица.csv"), "name,status\neditor,ready\nreader,ready\n")
        Files.copy(pdfReaderFixture(demo), documents.resolve("Книга.pdf"))
        Files.createDirectory(documents.resolve("Архив"))
        Files.walk(demo).use { stream -> stream.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(1794225600000)) } }
        val vfs = VfsManager()
        val panes = DualPaneTabs(PaneTabs(vfs, "", project), PaneTabs(vfs, "", documents))
        try {
            setContent { NoctaliaTheme(backdrop = false) { DualPaneScreen(vfs, panes = panes, persistTabs = false) } }
            waitUntil(timeoutMillis = 5000) { panes.left.active.entries.size == 8 && panes.right.active.entries.size == 5 }
            runOnIdle { panes.left.active.revealFile(project.resolve("MainActivity.smali")) }
            waitForIdle()
            onNodeWithText("MainActivity.smali").assertIsDisplayed()
            capture(onRoot(), "file-manager.png")
        } finally {
            panes.left.tabs.forEach { it.dispose() }; panes.right.tabs.forEach { it.dispose() }
            vfs.closeAll(); demo.toFile().deleteRecursively()
        }
    }

    @Test fun `editor screenshot shows Smali syntax and editing tools`() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val ctrl = CodeEditorController(source)
        setContent {
            NoctaliaTheme(backdrop = false) {
                CodeEditorView(source, Languages.SMALI, fileName = "MainActivity.smali", controller = ctrl, onSave = {})
            }
        }
        waitForIdle()
        capture(onRoot(), "smali-editor.png")
    }

    @Test fun `PDF screenshot includes rendered pages and reader controls`() = runDesktopComposeUiTest(width = 1280, height = 800) {
        assertTrue(PdfTools.available && PdfTools.textAvailable, "Install Poppler to regenerate README screenshots")
        val demo = Files.createTempDirectory("wex-readme-pdf-")
        val pdf = pdfReaderFixture(demo)
        val book = PdfBookSession(pdf, demo.resolve("cache"))
        try {
            val info = assertNotNull(PdfTools.info(pdf))
            setContent {
                NoctaliaTheme(backdrop = false) {
                    PdfReaderContent(book, info.copy(title = "A quiet page", author = ""),
                        PdfReadingState(mode = PdfReadingMode.PAGE, fit = PdfFit.PAGE, bookmarks = setOf(1, 3)),
                        PdfReadingStore(demo.resolve("settings")), "Книга.pdf")
                }
            }
            waitUntil(timeoutMillis = 15000) { onAllNodesWithContentDescription("Страница 1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Страницы и закладки").performClick()
            onNodeWithTag("pdf-reader").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(1000f, 500f)) }
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            capture(onNodeWithTag("pdf-reader"), "pdf-reader.png")
        } finally { book.close(); demo.toFile().deleteRecursively() }
    }

    @Test fun `class family screenshot shows whole class and individual parts`() = runDesktopComposeUiTest(width = 1000, height = 680) {
        val d = '$'
        val parts = listOf(
            ClassFamilyItem("demo.MainActivity", "MainActivity", "classes.dex · основной класс"),
            ClassFamilyItem("demo.MainActivity${d}1", "MainActivity${d}1", "classes.dex · анонимный класс"),
            ClassFamilyItem("demo.MainActivity${d}Inner", "MainActivity${d}Inner", "classes2.dex · вложенный класс"),
            ClassFamilyItem("demo.MainActivity${d}Companion", "MainActivity${d}Companion", "classes.dex · Kotlin companion")
        )
        setContent {
            NoctaliaTheme(backdrop = false) {
                Surface(Modifier.fillMaxSize()) { }
                ClassFamilyDialog("demo.MainActivity", parts, "Java целиком, Smali по частям.", {}, {}, {})
            }
        }
        onNodeWithTag("class-family-whole").assertIsDisplayed()
        capture(onNodeWithTag("class-family-dialog"), "class-family.png")
    }
}
