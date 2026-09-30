package org.wexclient.app.dex

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmaliAssemblerTest {

    private val helloSmali = """
        .class public Ltest/Hello;
        .super Ljava/lang/Object;

        .method public static greet()Ljava/lang/String;
            .registers 1
            const-string v0, "HelloOriginal"
            return-object v0
        .end method
    """.trimIndent()

    private val otherSmali = """
        .class public Ltest/Other;
        .super Ljava/lang/Object;

        .method public static answer()I
            .registers 1
            const/16 v0, 0x2a
            return v0
        .end method
    """.trimIndent()

    private fun assembleDex(vararg smali: String): ByteArray {
        val tmp = Files.createTempDirectory("smali_asm_test_").toFile()
        try {
            val files = smali.mapIndexed { i, text -> File(tmp, "C$i.smali").apply { writeText(text) } }
            val dexFile = File(tmp, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dexFile.absolutePath }
            assertTrue(Smali.assemble(options, files.map { it.absolutePath }), "smali assembly failed")
            return dexFile.readBytes()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `edited class is compiled into rebuilt dex`() {
        val dex = assembleDex(helloSmali, otherSmali)
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", dex)))
        val hello = engine.classes.first { it.fullName == "test.Hello" }

        val original = engine.disassembleToSmali(hello)
        assertTrue(original.contains("HelloOriginal"))
        assertFalse(engine.hasUnsavedChanges)

        engine.compileSmali(hello, original.replace("HelloOriginal", "HelloPatched"))
        assertTrue(engine.hasUnsavedChanges)
        assertTrue(engine.isEdited(hello))
        // Смали показывает уже новую версию, даже до пересборки DEX
        assertTrue(engine.disassembleToSmali(hello).contains("HelloPatched"))

        val modified = engine.modifiedDex()
        assertEquals(1, modified.size)
        val rebuilt = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(modified[0].bytes))
        val types = rebuilt.classes.map { it.type }.toSet()
        assertEquals(setOf("Ltest/Hello;", "Ltest/Other;"), types)
        val strings = (0 until rebuilt.stringSection.size).map { rebuilt.stringSection[it] }
        assertTrue("HelloPatched" in strings)
        assertFalse("HelloOriginal" in strings)

        // Второй класс не тронут
        val other = DexEditorEngine(listOf(DexSource("classes.dex", modified[0].bytes)))
            .classes.first { it.fullName == "test.Other" }
        assertTrue(DexEditorEngine(listOf(DexSource("classes.dex", modified[0].bytes))).disassembleToSmali(other).contains("0x2a"))

        engine.markSaved()
        assertFalse(engine.hasUnsavedChanges)
        engine.close()
    }

    /** Класс собрали, пока DEX писался: запись не должна пометить его сохранённым (иначе он теряется при закрытии). */
    @Test
    fun `class compiled during a write stays unsaved`() {
        val dex = assembleDex(helloSmali, otherSmali)
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", dex)))
        val hello = engine.classes.first { it.fullName == "test.Hello" }
        val source = engine.disassembleToSmali(hello)
        engine.compileSmali(hello, source.replace("HelloOriginal", "HelloFirst"))
        // Запись началась с этим номером…
        val version = engine.currentEditVersion
        engine.modifiedDex()
        // …а пока она шла, класс собрали ещё раз
        engine.compileSmali(hello, source.replace("HelloOriginal", "HelloSecond"))
        engine.markSaved(version)
        assertTrue(engine.hasUnsavedChanges)
        // Следующая запись без новых сборок — всё сохранено
        val next = engine.currentEditVersion
        engine.modifiedDex()
        engine.markSaved(next)
        assertFalse(engine.hasUnsavedChanges)
        engine.close()
    }

    @Test
    fun `syntax errors are reported with line numbers`() {
        val dex = assembleDex(helloSmali)
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", dex)))
        val hello = engine.classes.first()
        val broken = helloSmali.replace("return-object v0", "return-objekt v0")
        val e = assertFailsWith<SmaliSyntaxException> { engine.compileSmali(hello, broken) }
        assertTrue(e.errors.isNotEmpty())
        assertTrue(e.message!!.contains("7"), "ожидался номер строки в: ${e.message}")
        assertFalse(engine.hasUnsavedChanges)
        engine.close()
    }

    @Test
    fun `renaming class via smali is rejected`() {
        val dex = assembleDex(helloSmali)
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", dex)))
        val hello = engine.classes.first()
        assertFailsWith<SmaliSyntaxException> {
            engine.compileSmali(hello, helloSmali.replace("Ltest/Hello;", "Ltest/Renamed;"))
        }
        engine.close()
    }

    @Test
    fun `build errors point at the instruction line`() {
        // v5 при .registers 1 — ошибка сборки, а не разбора: строка всё равно та самая
        val broken = helloSmali.replace("return-object v0", "return-object v5")
        val e = assertFailsWith<SmaliSyntaxException> { SmaliAssembler.assemble(broken) }
        assertEquals(7, e.line)
        assertTrue(e.message!!.startsWith("строка 7: "), e.message)
    }

    @Test
    fun `smali of untouched class survives a rebuild byte for byte`() {
        val dex = assembleDex(helloSmali, otherSmali)
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", dex)))
        val hello = engine.classes.first { it.fullName == "test.Hello" }
        val other = engine.classes.first { it.fullName == "test.Other" }
        val otherBefore = engine.disassembleToSmali(other)
        engine.compileSmali(hello, engine.disassembleToSmali(hello).replace("HelloOriginal", "Patched"))
        val rebuilt = DexEditorEngine(listOf(DexSource("classes.dex", engine.modifiedDex()[0].bytes)))
        assertEquals(otherBefore, rebuilt.disassembleToSmali(rebuilt.classes.first { it.fullName == "test.Other" }))
        engine.close()
        rebuilt.close()
    }
}
