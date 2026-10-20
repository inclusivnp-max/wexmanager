package org.wexclient.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileDiffTest {

    @Test
    fun testIdenticalFiles() {
        val lines = listOf("alpha", "beta", "gamma")
        val diff = DiffEngine.computeDiff(lines, lines)

        assertEquals(3, diff.size)
        assertTrue(diff.all { it.type == DiffLineType.SAME })
        assertEquals("alpha", diff[0].textOld)
        assertEquals("alpha", diff[0].textNew)
    }

    @Test
    fun testAdditionsAndDeletions() {
        val linesA = listOf("line 1", "line 2", "line 3")
        val linesB = listOf("line 1", "line 2 modified", "line 3", "line 4")

        val diff = DiffEngine.computeDiff(linesA, linesB)
        val added = diff.filter { it.type == DiffLineType.ADDED }
        val removed = diff.filter { it.type == DiffLineType.REMOVED }
        val same = diff.filter { it.type == DiffLineType.SAME }

        assertEquals(2, same.size) // "line 1", "line 3"
        assertTrue(added.any { it.textNew == "line 4" })
        assertTrue(removed.any { it.textOld == "line 2" })
    }

    @Test
    fun testBuildUnifiedDiff() {
        val linesA = listOf("apple", "banana")
        val linesB = listOf("apple", "orange")

        val diff = DiffEngine.computeDiff(linesA, linesB)
        val patch = DiffEngine.buildUnifiedDiff("fileA.txt", "fileB.txt", diff)

        assertTrue(patch.startsWith("--- fileA.txt\n+++ fileB.txt\n"))
        assertTrue(patch.contains(" apple\n"))
        assertTrue(patch.contains("-banana\n"))
        assertTrue(patch.contains("+orange\n"))
    }

    /** Результат сравнения должен восстанавливать оба файла в исходном порядке. */
    private fun checkConsistent(a: List<String>, b: List<String>, diff: List<DiffRow>) {
        assertEquals(a, diff.filter { it.type != DiffLineType.ADDED }.map { it.textOld })
        assertEquals(b, diff.filter { it.type != DiffLineType.REMOVED }.map { it.textNew })
        assertEquals((1..a.size).toList(), diff.mapNotNull { it.lineNumOld })
        assertEquals((1..b.size).toList(), diff.mapNotNull { it.lineNumNew })
    }

    @Test
    fun testMinimalOnSmallInputs() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val a = List(rnd.nextInt(12)) { "x" + rnd.nextInt(4) }
            val b = List(rnd.nextInt(12)) { "x" + rnd.nextInt(4) }
            val diff = DiffEngine.computeDiff(a, b)
            checkConsistent(a, b, diff)
            // Длина общей подпоследовательности — наибольшая возможная
            val t = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.indices.reversed()) for (j in b.indices.reversed())
                t[i][j] = if (a[i] == b[j]) t[i + 1][j + 1] + 1 else maxOf(t[i + 1][j], t[i][j + 1])
            assertEquals(t[0][0], diff.count { it.type == DiffLineType.SAME })
        }
    }

    @Test
    fun testLargeFilesStayCheap() {
        // 60 тысяч строк с правками: полная таблица заняла бы 3,6 млрд клеток
        val a = List(60_000) { "line $it" }
        val b = a.toMutableList()
        for (k in 0 until 200) b[k * 300 + 5] = "changed $k"
        b.add(1000, "inserted")
        b.removeAt(40_000)
        val started = System.nanoTime()
        val diff = DiffEngine.computeDiff(a, b)
        val ms = (System.nanoTime() - started) / 1_000_000
        checkConsistent(a, b, diff)
        assertEquals(201, diff.count { it.type == DiffLineType.ADDED })
        assertEquals(201, diff.count { it.type == DiffLineType.REMOVED })
        assertTrue(ms < 5000, "сравнение заняло $ms мс")

        // Совсем разные большие файлы без уникальных общих строк — блоками, без таблицы
        val x = List(30_000) { "a" }
        val y = List(30_000) { "b" }
        val blocks = DiffEngine.computeDiff(x, y)
        checkConsistent(x, y, blocks)
    }

    @Test
    fun testRepeatedLinesWithoutAnchors() {
        // Нет ни одной уникальной строки, участок больше таблицы — Хиршберг, результат всё равно наименьший
        val a = List(2500) { "r${(it * 7) % 3}" }
        val b = List(2500) { "r${(it * 5) % 3}" }
        val diff = DiffEngine.computeDiff(a, b)
        checkConsistent(a, b, diff)
        val same = diff.count { it.type == DiffLineType.SAME }
        var prev = IntArray(b.size + 1)
        var cur = IntArray(b.size + 1)
        for (x in a) {
            for (j in 1..b.size) cur[j] = if (x == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            val t = prev; prev = cur; cur = t
        }
        assertEquals(prev[b.size], same)
    }

    @Test
    fun testCancellation() {
        val a = List(3000) { "a${it % 7}" }
        val b = List(3000) { "a${it % 5}" }
        var calls = 0
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            DiffEngine.computeDiff(a, b) { ++calls > 3 }
        }
    }
}
