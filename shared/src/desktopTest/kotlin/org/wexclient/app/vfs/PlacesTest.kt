package org.wexclient.app.vfs

import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.icons.SymbolStyle
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** user.home в тестах указывает на build/test-home (см. app/build.gradle.kts). */
class PlacesTest {
    private val home = File(System.getProperty("user.home"))
    private val config = File(home, ".config/wexdesktop")

    @BeforeTest
    fun setUp() {
        File(home, "Загрузки").mkdirs()
        File(home, "Рабочий стол").mkdirs()
        File(home, "IdeaProjects").mkdirs()
        File(home, ".config").mkdirs()
        File(home, ".config/user-dirs.dirs").writeText(
            """
            # тестовые XDG-папки
            XDG_DESKTOP_DIR="${'$'}HOME/Рабочий стол"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Загрузки"
            XDG_DOCUMENTS_DIR="${'$'}HOME/"
            """.trimIndent()
        )
        File(config, "places.tsv").delete()
        File(config, "bookmarks.tsv").delete()
    }

    @Test
    fun `first start seeds default chips from xdg dirs`() {
        BookmarksManager.reload()
        val items = BookmarksManager.userBookmarks.toList()
        assertEquals(listOf("Домой", "Корень", "Загрузки", "Стол", "Проекты"), items.map { it.title })
        assertEquals(File(home, "Загрузки").absolutePath, items[2].pathStr)
        assertEquals(File(home, "Рабочий стол").absolutePath, items[3].pathStr)
        assertEquals("download", items[2].icon.name)
        assertTrue(File(config, "places.tsv").isFile, "список сохраняется сразу")
        // Документы указывают на сам HOME — такую XDG-папку не показываем
        assertEquals(null, BookmarksManager.xdgDir("DOCUMENTS"))
    }

    @Test
    fun `legacy bookmarks are migrated once`() {
        config.mkdirs()
        File(config, "bookmarks.tsv").writeText("Старая\t/tmp\tDOWNLOADS\n")
        BookmarksManager.reload()
        val legacy = BookmarksManager.userBookmarks.last()
        assertEquals("Старая", legacy.title)
        assertEquals("download", legacy.icon.name)
        BookmarksManager.remove(legacy)
        BookmarksManager.reload()
        assertTrue(BookmarksManager.userBookmarks.none { it.title == "Старая" }, "повторно не импортируется")
    }

    @Test
    fun `places are edited, reordered and persisted`() {
        BookmarksManager.reload()
        val games = File(home, "Games").apply { mkdirs() }
        BookmarksManager.add("Игры", "~/Games", SymbolRef("sports_esports", SymbolStyle.OUTLINED))
        val added = BookmarksManager.userBookmarks.last()
        assertEquals(games.absolutePath, added.pathStr)

        BookmarksManager.move(added, -10)
        assertEquals("Игры", BookmarksManager.userBookmarks.first().title)

        BookmarksManager.update(added, "Игры\tи\nфильмы", games.absolutePath, SymbolRef("movie"))
        BookmarksManager.reload()
        val reloaded = BookmarksManager.userBookmarks.first()
        assertEquals("Игры и фильмы", reloaded.title)
        assertEquals("movie", reloaded.icon.name)
        assertEquals(SymbolStyle.FILLED, reloaded.icon.style)

        assertTrue(BookmarksManager.isBookmarked(games.toPath()))
        BookmarksManager.toggleBookmark(games.toPath())
        assertTrue(!BookmarksManager.isBookmarked(games.toPath()))

        // Папка с переводом строки и табуляцией в имени переживает перезапуск
        val odd = File(home, "odd\tname\nhere\\x").apply { mkdirs() }
        BookmarksManager.add("Странная", odd.absolutePath, SymbolRef("folder"))
        BookmarksManager.reload()
        assertEquals(odd.absolutePath, BookmarksManager.userBookmarks.last().pathStr)
        assertTrue(BookmarksManager.isBookmarked(odd.toPath()))

        BookmarksManager.resetToDefaults()
        assertEquals(5, BookmarksManager.userBookmarks.size)
    }

    @Test
    fun `quick locations do not duplicate system paths`() {
        BookmarksManager.reload()
        val quick = BookmarksManager.getQuickLocations()
        assertEquals(quick.size, quick.map { it.pathStr }.toSet().size)
        assertTrue(quick.first { it.pathStr == "/" }.isSystem)
    }
}
