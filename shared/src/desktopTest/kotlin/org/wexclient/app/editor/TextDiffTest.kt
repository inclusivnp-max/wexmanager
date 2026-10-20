package org.wexclient.app.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class TextDiffTest {
    private fun naive(old: String, new: String): Triple<Int, String, String> {
        val min = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < min && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < min - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return Triple(prefix, old.substring(prefix, old.length - suffix), new.substring(prefix, new.length - suffix))
    }

    @Test
    fun `block comparison finds the same minimal edit`() {
        val rnd = java.util.Random(3)
        val alphabet = "ab\nя😀"
        repeat(3000) {
            val len = when (rnd.nextInt(4)) {
                0 -> rnd.nextInt(10)
                1 -> rnd.nextInt(300)
                2 -> rnd.nextInt(5000)
                else -> 20_000 + rnd.nextInt(80_000)
            }
            val base = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val pos = if (base.isEmpty()) 0 else rnd.nextInt(base.length + 1)
            val cut = if (base.isEmpty()) 0 else rnd.nextInt(minOf(50, base.length - pos) + 1)
            val insert = buildString { repeat(rnd.nextInt(5)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val changed = base.substring(0, pos) + insert + base.substring(pos + cut)
            val e = TextEdit.diff(base, changed)
            val expected = naive(base, changed)
            assertEquals(expected, Triple(e.start, e.removed, e.inserted), "len=$len pos=$pos cut=$cut ins=$insert")
        }
        // Латиница и не-латиница в одной паре (разное внутреннее представление строк)
        val latin = "x".repeat(10_000)
        val mixed = latin.substring(0, 5000) + "ж" + latin.substring(5000)
        assertEquals(Triple(5000, "", "ж"), TextEdit.diff(latin, mixed).let { Triple(it.start, it.removed, it.inserted) })
        assertEquals(Triple(5000, "ж", ""), TextEdit.diff(mixed, latin).let { Triple(it.start, it.removed, it.inserted) })
        assertEquals(Triple(10_000, "", ""), TextEdit.diff(latin, latin).let { Triple(it.start, it.removed, it.inserted) })
    }
}
