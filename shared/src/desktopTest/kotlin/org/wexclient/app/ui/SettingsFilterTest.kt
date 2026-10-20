package org.wexclient.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Поиск по настройкам: отбор строк и счётчик показанного. */
class SettingsFilterTest {

    @Test
    fun `пустой запрос показывает всё`() {
        val f = SettingsFilter("")
        assertFalse(f.active)
        assertTrue(f.hit("Миникарта", "Уменьшенная копия файла"))
        assertTrue(f.hit("что угодно"))
        assertEquals(2, f.shown)
    }

    @Test
    fun `одна буква ещё не поиск`() {
        // Иначе список прыгал бы уже на первом символе
        val f = SettingsFilter("ш")
        assertFalse(f.active)
        assertTrue(f.hit("Плавные переходы", "Появление окон"))
    }

    @Test
    fun `ищет по части слова без учёта регистра`() {
        val f = SettingsFilter("ШРИФ")
        assertTrue(f.active)
        assertTrue(f.hit("Размер шрифта", "в самом редакторе — Ctrl+колесо"))
        assertFalse(f.hit("Миникарта", "Уменьшенная копия файла справа"))
    }

    @Test
    fun `совпадение в пояснении тоже считается`() {
        val f = SettingsFilter("моноширин")
        assertTrue(f.hit("Лигатуры", "Слитное начертание в моноширинном шрифте"))
    }

    @Test
    fun `пробелы по краям не мешают`() {
        val f = SettingsFilter("  вкладки  ")
        assertEquals("вкладки", f.query)
        assertTrue(f.hit("Вкладки снизу", "Полоса вкладок под списком файлов"))
    }

    @Test
    fun `счётчик показывает только совпавшие`() {
        val f = SettingsFilter("консол")
        f.hit("Размер шрифта консоли", "в самой консоли — Ctrl+колесо")
        f.hit("Номера строк", "Колонка с номерами слева")
        f.hit("Следовать за папкой", "Консоль переходит туда же, куда переходит панель")
        assertEquals(2, f.shown)
    }

    @Test
    fun `ничего не нашлось — счётчик пуст`() {
        val f = SettingsFilter("кракозябра")
        f.hit("Тема", "Цвета оболочки")
        f.hit("Затемнение", "темнее — лучше читается текст")
        assertEquals(0, f.shown)
    }
}
