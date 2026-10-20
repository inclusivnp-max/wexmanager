package org.wexclient.app.theme

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Выбор системных обоев. В файле оболочки первым лежит заставка из поставки, а нужны те,
 * что стоят на мониторе — порядок проверяется здесь, чтобы не вернуть чужую картинку.
 */
class BackgroundPreferencesTest {

    private fun file(name: String) = Files.createTempDirectory("wex-wall").resolve(name).also {
        Files.write(it, byteArrayOf(1, 2, 3))
    }.toFile()

    @Test
    fun `обои монитора важнее заставки из поставки`() {
        val stock = file("noctalia.png")
        val mine = file("wallhaven.jpg")
        val json = """
            {
                "defaultWallpaper": "${stock.absolutePath}",
                "usedRandomWallpapers": { "all": ["${stock.absolutePath}"] },
                "wallpapers": {
                    "": { "dark": "${mine.absolutePath}", "light": "${mine.absolutePath}" },
                    "HDMI-A-1": { "dark": "${mine.absolutePath}", "light": "${mine.absolutePath}" }
                }
            }
        """.trimIndent()
        assertEquals(mine.absolutePath, BackgroundPreferences.parseWallpaper(json)?.absolutePath)
    }

    @Test
    fun `если обоев монитора нет, берутся последние случайные`() {
        val stock = file("noctalia.png")
        val random = file("random.jpg")
        val json = """
            {
                "defaultWallpaper": "${stock.absolutePath}",
                "usedRandomWallpapers": { "all": ["${random.absolutePath}"] },
                "wallpapers": {}
            }
        """.trimIndent()
        assertEquals(random.absolutePath, BackgroundPreferences.parseWallpaper(json)?.absolutePath)
    }

    @Test
    fun `несуществующие пути пропускаются`() {
        val real = file("real.jpg")
        val json = """
            {
                "wallpapers": { "eDP-1": { "dark": "/нет/такого/файла.jpg", "light": "${real.absolutePath}" } }
            }
        """.trimIndent()
        assertEquals(real.absolutePath, BackgroundPreferences.parseWallpaper(json)?.absolutePath)
    }

    @Test
    fun `битый файл не роняет разбор`() {
        assertNull(BackgroundPreferences.parseWallpaper(""))
        assertNull(BackgroundPreferences.parseWallpaper("{ не json"))
        assertNull(BackgroundPreferences.parseWallpaper("""{"wallpapers": {"x": {"dark": "/нет.jpg"}}}"""))
    }

    @Test
    fun `запасной разбор без gson-структуры`() {
        val real = file("fallback.jpg")
        // Формат поменялся: объекта wallpapers нет, но пути в файле есть
        val json = """{ "monitors": [ { "dark": "${real.absolutePath}" } ] }"""
        assertEquals(real.absolutePath, BackgroundPreferences.parseWallpaper(json)?.absolutePath)
    }
}
