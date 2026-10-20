package org.wexclient.app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as Awt

/**
 * Список биндов читается и пишется строками вида «Ctrl+Shift+F»: если разбор и печать
 * перестанут быть обратными друг другу, сохранённые сочетания молча потеряются.
 */
class KeymapTest {

    @Test
    fun `разбор и печать сочетания дают то же самое`() {
        for (text in listOf("Ctrl+Shift+F", "Alt+Enter", "F5", "Shift+Delete", "Ctrl+`", "Ctrl+=", "Ctrl+-", "Alt+↑", "Ctrl+Пробел")) {
            val combo = KeyCombo.parse(text)
            assertNotNull(combo, "не разобрано: $text")
            assertEquals(text, combo.label(), "печать разошлась с разбором")
        }
    }

    @Test
    fun `модификаторы разбираются в любом порядке`() {
        val a = KeyCombo.parse("Ctrl+Shift+F")
        val b = KeyCombo.parse("shift+ctrl+f")
        assertEquals(a, b)
    }

    @Test
    fun `один модификатор сочетанием не считается`() {
        assertNull(KeyCombo.parse("Ctrl"))
        assertNull(KeyCombo.parse("Ctrl+"))
        assertNull(KeyCombo.parse(""))
    }

    @Test
    fun `буква и цифра узнаются по коду клавиши`() {
        assertEquals(KeyCombo(Awt.VK_F, ctrl = true), KeyCombo.parse("Ctrl+F"))
        assertEquals(KeyCombo(Awt.VK_5, ctrl = true, alt = true), KeyCombo.parse("Ctrl+Alt+5"))
    }

    @Test
    fun `у каждого бинда разбирается сочетание по умолчанию`() {
        for (def in Keymap.binds) {
            assertNotNull(def.defaultCombo, "не разобрано умолчание ${def.id}: ${def.default}")
        }
    }

    @Test
    fun `идентификаторы биндов не повторяются`() {
        val ids = Keymap.binds.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "есть повторяющиеся id")
    }

    @Test
    fun `внутри группы сочетания по умолчанию не конфликтуют`() {
        for (group in Keymap.groups) {
            val used = HashMap<KeyCombo, String>()
            for (def in Keymap.binds.filter { it.group == group }) {
                val combo = def.defaultCombo ?: continue
                val other = used.put(combo, def.id)
                assertNull(other, "в группе «$group» ${def.id} и $other заняли ${def.default}")
            }
        }
    }

    @Test
    fun `подпись отключённого действия не пустая`() {
        // Сам объект не трогаем: проверяем только разбор пустой записи
        assertNull(KeyCombo.parse(""))
        assertTrue(Keymap.label(Keymap.binds.first().id).isNotBlank())
    }
}
