package com.tim.dsmali.disasm

import com.tim.dsmali.io.SmaliFiles
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliField
import com.tim.dsmali.model.SmaliTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmaliFilesTest {
    private fun cls(descriptor: String) = SmaliClass(descriptor, mutableListOf("public")).apply {
        fields.add(SmaliField(mutableListOf("public"), "f", "I"))
    }

    private fun relFiles(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `case-insensitive collisions get unique names`() {
        val planner = SmaliFiles.NamePlanner(false)
        assertEquals("a.smali", planner.fileFor("La;"))
        assertEquals("A.1.smali", planner.fileFor("LA;"))
        assertEquals("p/B.smali", planner.fileFor("Lp/B;"))
        assertEquals("P.1/b.smali", planner.fileFor("LP/b;"))
        assertEquals("p/b.1.smali", planner.fileFor("Lp/b;"))
        assertEquals("a.smali.1/x.smali", planner.fileFor("La.smali/x;"))
        assertEquals("É.smali", planner.fileFor("LÉ;"))
        assertEquals("é.1.smali", planner.fileFor("Lé;"))
        assertEquals(listOf("p", "P.1", "a.smali.1"), planner.directories)
    }

    @Test
    fun `characters a filesystem cannot keep are escaped`() {
        val planner = SmaliFiles.NamePlanner(false)
        assertEquals("a/#002e#002e/b.smali", planner.fileFor("La/../b;"))
        assertEquals("#002e/c.smali", planner.fileFor("L./c;"))
        // непарные суррогаты: без замены оба имени превратились бы в «?.smali»
        assertEquals("x#d800.smali", planner.fileFor("Lx\uD800;"))
        assertEquals("x#dc00.smali", planner.fileFor("Lx\uDC00;"))
        assertEquals("x\uD83D\uDE00.smali", planner.fileFor("Lx\uD83D\uDE00;"))
        assertEquals("#003a#002a#0001#007f.smali", planner.fileFor("L:*\u0001\u007f;"))
    }

    @Test
    fun `overlong path components are shortened`() {
        val planner = SmaliFiles.NamePlanner(false)
        val long = "N".repeat(300)
        val cyr = "Ж".repeat(200) // 2 байта UTF-8 на символ
        val paths = listOf("L$long;", "L${long}x;", "L$cyr;", "L$long/$cyr;").map { planner.fileFor(it) }
        for (path in paths) {
            for (part in path.split('/')) assertTrue(part.toByteArray(Charsets.UTF_8).size <= 255, "$part: ${part.length}")
            assertTrue(path.endsWith(".smali"), path)
        }
        assertEquals(4, paths.toSet().size)
        // один и тот же пакет — один и тот же каталог
        assertEquals(paths[3].substringBefore('/'), planner.fileFor("L$long/y;").substringBefore('/'))
    }

    @Test
    fun `write and read back without losing classes`(@TempDir dir: File) {
        val tree = SmaliTree()
        val long = "L" + "q".repeat(400) + ";"
        for (d in listOf("La;", "LA;", "Lp/B;", "LP/b;", long)) tree.classes.add(cls(d))
        SmaliFiles.write(tree, dir)
        assertEquals(5, relFiles(dir).size)
        // Суффиксы — по порядку дескрипторов, а не по порядку классов: после сборки DEX порядок другой.
        assertTrue(File(dir, "A.smali").isFile)
        assertTrue(File(dir, "a.1.smali").isFile)
        val reversed = File(dir.parentFile, dir.name + "-rev")
        SmaliFiles.write(SmaliTree(tree.classes.reversed().toMutableList()), reversed)
        assertEquals(relFiles(dir).sorted(), relFiles(reversed).sorted())
        reversed.deleteRecursively()
        val loaded = SmaliFiles.read(dir)
        assertEquals(tree.classes.map { it.descriptor }.toSet(), loaded.classes.map { it.descriptor }.toSet())
        // порядок чтения детерминирован (по пути)
        assertEquals(loaded.classes.map { it.descriptor }, SmaliFiles.read(dir).classes.map { it.descriptor })
    }

    @Test
    fun `read errors name the file`(@TempDir dir: File) {
        File(dir, "x").mkdirs()
        File(dir, "x/Bad.smali").writeText("# нет директивы .class\n")
        val e = assertFailsWith<IllegalStateException> { SmaliFiles.read(dir) }
        assertTrue("Bad.smali" in e.message!!, e.message)
    }

    @Test
    fun `duplicate classes and bad descriptors are rejected`(@TempDir dir: File) {
        SmaliFiles.write(SmaliTree(mutableListOf(cls("La;"), cls("Lb;"))), dir)
        File(dir, "a.smali").copyTo(File(dir, "copy.smali"))
        assertFailsWith<IllegalStateException> { SmaliFiles.read(dir) }
        assertFailsWith<IllegalArgumentException> { SmaliFiles.write(SmaliTree(mutableListOf(cls("Lc;"), cls("Lc;"))), File(dir, "dup")) }
        assertFailsWith<IllegalArgumentException> { SmaliFiles.NamePlanner(false).fileFor("[I") }
        assertFailsWith<IllegalArgumentException> { SmaliFiles.NamePlanner(false).fileFor("La//b;") }
        assertFailsWith<java.io.IOException> { SmaliFiles.read(File(dir, "missing")) }
    }
}
