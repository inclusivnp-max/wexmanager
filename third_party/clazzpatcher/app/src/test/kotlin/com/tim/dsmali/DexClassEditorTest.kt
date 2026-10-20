package com.tim.dsmali

import com.tim.dex.parser.DexReader
import com.tim.dsmali.io.SmaliWriter
import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Правка классов по одному: smali класса, проверка текста, запись DEX с заменёнными классами. */
class DexClassEditorTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `текст класса совпадает с полным разбором`(name: String) {
        val bytes = Fixtures.dex(name)
        val full = Smali.disassemble(DexReader().read(bytes))
        val editor = DexClassEditor(bytes)
        assertEquals(full.classes.map { it.descriptor }, editor.classes, name)
        for (cls in full.classes) assertEquals(SmaliWriter.write(cls), editor.smali(cls.descriptor), "$name: ${cls.descriptor}")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `замена всех классов их же текстом сохраняет смысл DEX`(name: String) {
        val bytes = Fixtures.dex(name)
        val editor = DexClassEditor(bytes)
        val same = editor.classes.map { DexClassEditor.compile(editor.smali(it)!!) }
        val rebuilt = editor.rebuild(same)
        assertNoDiff(DexSemantics.diff(bytes, rebuilt, DexSemantics.Options.SMALI), "$name: замена всех классов")
        if (DexDump.available()) DexDump.assertValid(rebuilt, "$name: замена всех классов")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `без правок — запись как есть`(name: String) {
        val bytes = Fixtures.dex(name)
        assertNoDiff(
            DexSemantics.diff(bytes, DexClassEditor(bytes).rebuild(emptyList()), DexSemantics.Options(version = true, pools = true)),
            name
        )
    }

    @Test
    fun `правка одного класса не трогает остальные`() {
        val bytes = Fixtures.dex("user")
        val editor = DexClassEditor(bytes)
        val target = editor.classes.first { d -> editor.smali(d)!!.contains("const-string") }
        val text = editor.smali(target)!!
        val edited = text.replaceFirst(Regex("const-string (\\w+), \"[^\"]*\""), "const-string $1, \"WEX-PATCHED\"")
        assertNotEquals(text, edited)

        val out = editor.rebuild(listOf(DexClassEditor.compile(edited)))
        val after = DexClassEditor(out)
        assertEquals(editor.classes, after.classes)
        assertTrue(after.smali(target)!!.contains("\"WEX-PATCHED\""))
        for (d in editor.classes) if (d != target) assertEquals(editor.smali(d), after.smali(d), d)
        if (DexDump.available()) DexDump.assertValid(out, "правка одного класса")
    }

    @Test
    fun `строки и методы прежнего тела в DEX не остаются`() {
        val bytes = Fixtures.dex("gen/hello")
        val editor = DexClassEditor(bytes)
        val target = editor.classes.first { d -> Regex("const-string \\w+, \"[^\"]+\"").containsMatchIn(editor.smali(d)!!) }
        val text = editor.smali(target)!!
        val old = Regex("const-string \\w+, \"([^\"]+)\"").find(text)!!.groupValues[1]
        val edited = text.replace("\"$old\"", "\"WEX-NEW\"")
        val out = DexReader().read(editor.rebuild(listOf(DexClassEditor.compile(edited))))
        assertTrue("WEX-NEW" in out.strings)
        // Строка была только в этом классе — после замены её в пуле нет
        if (editor.classes.none { it != target && editor.smali(it)!!.contains("\"$old\"") }) {
            assertTrue(old !in out.strings, "строка «$old» осталась в пуле")
        }
    }

    @Test
    fun `call site пересобранного класса — прежние, без копий`() {
        val bytes = Fixtures.dex("gen/invoke")
        val editor = DexClassEditor(bytes)
        val indy = editor.classes.first { editor.smali(it)!!.contains("invoke-custom") }
        val out = editor.rebuild(listOf(DexClassEditor.compile(editor.smali(indy)!!)))
        assertEquals(DexReader().read(bytes).callSiteIds.size, DexReader().read(out).callSiteIds.size)
        assertEquals(editor.smali(indy), DexClassEditor(out).smali(indy))
    }

    @Test
    fun `hidden API — у замены другое число членов`() {
        val bytes = Fixtures.dex("gen/hiddenapi")
        val editor = DexClassEditor(bytes)
        val text = editor.smali("Lfx/hid/Hidden;")!!
        // Без метода b(): флагов у класса на один меньше
        val fewer = text.replace(Regex("\\.method public [^\\n]* b\\(\\)V[\\s\\S]*?\\.end method\\n?"), "")
        assertNotEquals(text, fewer)
        val out = editor.rebuild(listOf(DexClassEditor.compile(fewer)))
        val after = DexClassEditor(out).smali("Lfx/hid/Hidden;")!!
        assertTrue("b()V" !in after, after)
        assertTrue(after.contains(".field public static whitelist sA:I"), after)
        if (DexDump.available()) DexDump.assertValid(out, "hidden API: меньше членов")

        // Все слова hidden API убраны: у класса флаги «нет ограничений» по числу его членов
        val plain = text.replace(Regex(" (whitelist|greylist-max-[a-z]|greylist|blacklist|core-platform-api|test-api)"), "")
        val out2 = editor.rebuild(listOf(DexClassEditor.compile(plain)))
        assertNotNull(DexClassEditor(out2).smali("Lfx/hid/Hidden;"))
        if (DexDump.available()) DexDump.assertValid(out2, "hidden API: без флагов")
    }

    @Test
    fun `новый класс добавляется`() {
        val bytes = Fixtures.dex("gen/hello")
        val editor = DexClassEditor(bytes)
        val added = DexClassEditor.compile(
            """
            .class public Lwex/Added;
            .super Ljava/lang/Object;

            .method public static f()I
                .registers 1
                const/4 v0, 0x7
                return v0
            .end method
            """.trimIndent()
        )
        val after = DexClassEditor(editor.rebuild(listOf(added)))
        assertEquals(editor.classes + "Lwex/Added;", after.classes)
        assertTrue(after.smali("Lwex/Added;")!!.contains("const/4 v0, 0x7"))
    }

    @Test
    fun `один класс дважды — ошибка`() {
        val editor = DexClassEditor(Fixtures.dex("gen/hello"))
        val cls = DexClassEditor.compile(editor.smali(editor.classes.first())!!)
        assertFailsWith<IllegalArgumentException> { editor.rebuild(listOf(cls, cls)) }
    }

    // ---- Ошибки: строка и причина ----

    private val base = """
        .class public Lwex/E;
        .super Ljava/lang/Object;

        .field public static x:I = 0x1

        .method public static f(I)I
            .registers 2
            if-eqz p0, :done
            const/4 v0, 0x3
            return v0
            :done
            return p0
        .end method
    """.trimIndent()

    private fun lineIn(text: String, fragment: String): Int = text.lines().indexOfFirst { fragment in it } + 1

    private fun failure(text: String): Exception = assertFailsWith<Exception> { DexClassEditor.compile(text) }

    @Test
    fun `исходный текст собирается`() {
        assertEquals("Lwex/E;", DexClassEditor.compile(base).descriptor)
    }

    @Test
    fun `неизвестная инструкция — строка разбора`() {
        val text = base.replace("const/4 v0, 0x3", "const/42 v0, 0x3")
        val e = failure(text)
        assertTrue(e is SmaliParseException, e.toString())
        assertEquals(lineIn(text, "const/42"), lineOf(e))
        assertTrue(reasonOf(e).contains("const/42"), reasonOf(e))
        assertTrue(!reasonOf(e).startsWith("SmaliParser:"), reasonOf(e))
    }

    @Test
    fun `регистр вне кадра — строка инструкции`() {
        val text = base.replace("const/4 v0, 0x3", "const/4 v9, 0x3")
        val e = failure(text)
        assertEquals(lineIn(text, "const/4 v9"), lineOf(e), e.toString())
    }

    @Test
    fun `нет метки — строка перехода или метода`() {
        val text = base.replace("if-eqz p0, :done", "if-eqz p0, :nowhere")
        val e = failure(text)
        val line = lineOf(e)
        assertTrue(line == lineIn(text, ":nowhere") || line == lineIn(text, ".method"), "строка $line: $e")
    }

    @Test
    fun `значение поля — строка поля`() {
        val text = base.replace("x:I = 0x1", "x:I = 0xZZ")
        val e = failure(text)
        assertEquals(lineIn(text, ".field"), lineOf(e), e.toString())
    }

    @Test
    fun `пустой текст — ошибка на первой строке`() {
        assertEquals(1, lineOf(failure("# только комментарий\n")))
    }
}
