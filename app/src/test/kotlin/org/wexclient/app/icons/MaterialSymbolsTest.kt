package org.wexclient.app.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaterialSymbolsTest {

    @Test
    fun `index is bundled and resolves names`() {
        assertTrue(MaterialSymbols.all.size > 4000, "в индексе ${MaterialSymbols.all.size} иконок")
        assertEquals(0xe2c7, MaterialSymbols.codepointOf("folder"))
        assertEquals(0xe88a, MaterialSymbols.codepointOf("home"))
        // Подборка для папок целиком есть в наборе
        MaterialSymbols.folderPresets.forEach { assertNotNull(MaterialSymbols.info(it), "нет иконки $it") }
        // Синонимы одного глифа в подборке сломали бы ключи сетки
        val cps = MaterialSymbols.folderPresets.map { MaterialSymbols.codepointOf(it) }
        assertEquals(cps.size, cps.toSet().size, "в подборке есть повторяющиеся глифы")
    }

    @Test
    fun `refs encode and parse including legacy values`() {
        val ref = SymbolRef("sports_esports", SymbolStyle.OUTLINED)
        val parsed = SymbolRef.parse(ref.encode())
        assertEquals("sports_esports", parsed.name)
        assertEquals(SymbolStyle.OUTLINED, parsed.style)
        assertEquals(MaterialSymbols.codepointOf("sports_esports"), parsed.codepoint)

        assertEquals("home", SymbolRef.parse("HOME").name)
        assertEquals("download", SymbolRef.parse("DOWNLOADS").name)
        assertEquals(MaterialSymbols.codepointOf("download"), SymbolRef.parse("DOWNLOADS").codepoint)
        assertEquals(MaterialSymbols.codepointOf("desktop_windows"), SymbolRef.parse("DESKTOP").codepoint)
        assertEquals(MaterialSymbols.codepointOf("hard_drive"), SymbolRef.parse("ROOT").codepoint)
        assertEquals("folder", SymbolRef.parse(null).name)
        assertEquals("folder", SymbolRef.parse("filled:folder").name)
    }

    @Test
    fun `search understands english and russian`() {
        assertEquals("folder", MaterialSymbols.search("folder").first().name)
        assertTrue(MaterialSymbols.search("папка").any { it.name == "folder" })
        assertTrue(MaterialSymbols.search("игры").any { it.name == "sports_esports" })
        assertTrue(MaterialSymbols.search("загрузки").any { it.name == "download" })
        val all = MaterialSymbols.search("")
        assertEquals(all.size, all.map { it.codepoint }.toSet().size, "повторы одного глифа")
        val android = MaterialSymbols.search("", category = MaterialSymbols.categories.indexOf("Android"))
        assertTrue(android.isNotEmpty())
    }

    @Test
    fun `fonts load and cover every indexed glyph`() {
        for (style in SymbolStyle.entries) {
            val tf = assertNotNull(MaterialSymbols.typeface(style), "шрифт ${style.key} не загрузился")
            val font = org.jetbrains.skia.Font(tf, 24f)
            val sample = MaterialSymbols.all.map { it.codepoint }.distinct()
            val glyphs = font.getUTF32Glyphs(sample.toIntArray())
            val missing = sample.filterIndexed { i, _ -> glyphs[i] == 0.toShort() }
            assertTrue(missing.isEmpty(), "${style.key}: нет глифов для ${missing.take(5).map { it.toString(16) }}")
        }
    }
}
