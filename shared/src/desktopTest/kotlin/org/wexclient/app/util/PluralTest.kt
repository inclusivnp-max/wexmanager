package org.wexclient.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PluralTest {
    @Test
    fun `russian plural forms`() {
        val expected = mapOf(
            0 to "0 элементов", 1 to "1 элемент", 2 to "2 элемента", 4 to "4 элемента", 5 to "5 элементов",
            11 to "11 элементов", 12 to "12 элементов", 14 to "14 элементов", 21 to "21 элемент",
            22 to "22 элемента", 25 to "25 элементов", 101 to "101 элемент", 111 to "111 элементов", 112 to "112 элементов"
        )
        for ((n, text) in expected) assertEquals(text, Plural.items(n))
        assertEquals("3 файла", Plural.files(3))
        assertEquals("файл", Plural.ru(-1, "файл", "файла", "файлов"))
    }
}
