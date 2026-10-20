package com.tim.dsmali.disasm

import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.dsmali.io.SmaliWriter
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliMethod
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexToTreeTest {
    private fun tree(bytes: ByteArray) = DexToTree().build(DexReader().read(bytes))

    private fun SmaliClass.method(name: String): SmaliMethod = methods.single { it.name == name }

    private fun SmaliMethod.insns(): List<String> = body.filterIsInstance<SmaliInsn>().map { it.text }

    private fun assertLines(text: String, vararg lines: String) {
        val all = text.lines().toSet()
        for (l in lines) assertTrue(l in all, "нет строки [$l] в:\n$text")
    }

    @Test
    fun `three switches in one method match baksmali byte for byte`() {
        val cls = tree(DexFixtures.SW).classes.single()
        assertEquals(DexFixtures.SW_SMALI, SmaliWriter.write(cls))
    }

    @Test
    fun `literals keep their full value and type`() {
        val cls = tree(DexFixtures.CRAFTED).classes.single { it.descriptor == "LCrafted;" }
        val insns = cls.method("s").insns()
        for (expected in listOf(
            "const/high16 v1, -0x40800000",
            "const-wide/high16 v2, -0x4010000000000000L",
            "const-wide/high16 v4, 0x4024000000000000L",
            "const-wide/32 v6, -0x80000000",
            "const-wide v6, -0x8000000000000000L",
            "const-wide v6, 0x100000000L",
            "const p0, -0x80000000",
            "const/4 p1, -0x8",
            "const/16 p1, -0x8000",
            "const-wide/16 v6, 0x7fff",
            "const/high16 p1, 0x7fff0000",
            "const-string/jumbo p1, \"jumbo\""
        )) assertTrue(expected in insns, "нет [$expected] в $insns")

        val fields = cls.fields.associateBy { it.name }
        assertEquals("-0x1t", fields.getValue("b").initialValue)
        assertEquals("0x7fffs", fields.getValue("sh").initialValue)
        assertEquals("'\\n'", fields.getValue("ch").initialValue)
        assertEquals("0x7fffffffffffffffL", fields.getValue("lo").initialValue)
        assertEquals("3.14f", fields.getValue("fl").initialValue)
        assertEquals("-2.5", fields.getValue("db").initialValue)
        assertEquals("true", fields.getValue("bo").initialValue)
        assertEquals("\"\\u0000\\u0080\"", fields.getValue("str").initialValue)
        assertEquals("LCrafted;", fields.getValue("ty").initialValue)
        assertEquals("0x0", fields.getValue("last").initialValue)
        assertNull(fields.getValue("inst").initialValue)
    }

    @Test
    fun `annotation values use smali syntax`() {
        val cls = tree(DexFixtures.CRAFTED).classes.single { it.descriptor == "LCrafted;" }
        val ann = cls.annotations.single { it.type == "LAnn;" }
        val v = ann.elements.associate { it.name to it.value }
        assertEquals("invoke-static@LCrafted;->s(IJLjava/lang/String;)V", v["mh"])
        assertEquals("static-get@LCrafted;->sf:I", v["mh2"])
        assertEquals("instance-put@LCrafted;->inst:J", v["mh3"])
        assertEquals("invoke-constructor@LCrafted;-><init>()V", v["mh4"])
        assertEquals("(IJ)Ljava/lang/String;", v["mt"])
        assertEquals("0x7ft", v["b"])
        assertEquals("-0x80t", v["b2"])
        assertEquals("-0x8000s", v["s"])
        assertEquals("'\u00e9'", v["c"])
        assertEquals("'\\''", v["c2"])
        assertEquals("'\\\\'", v["c5"])
        assertEquals("-0x8000000000000000L", v["l"])
        assertEquals("-0x80000000", v["i"])
        assertEquals("NaNf", v["f"])
        assertEquals("-Infinityf", v["f2"])
        assertEquals("Infinity", v["d"])
        assertEquals("-0.0", v["d2"])
        assertEquals("\"a\\\"b\\\\c\\n\\t\\r\\u0001\u00ff\uD83D\uDE00\\ud800 \\' \\u2028\"", v["str"])
        assertEquals("{\n    {\n        0x1,\n        {}\n    },\n    .subannotation LSub;\n        x = .enum LE;->A:LE;\n" +
            "        y = {\n            .subannotation LSub2;\n            .end subannotation\n        }\n    .end subannotation\n}", v["arr"])
        assertEquals("[Ljava/lang/String;", v["t"])
        assertEquals("null", v["n"])
    }

    @Test
    fun `method handles, method types, call sites and polymorphic invokes`() {
        val insns = tree(DexFixtures.CRAFTED).classes.single { it.descriptor == "LCrafted;" }.method("s").insns()
        val expected = listOf(
            "const-method-handle v0, invoke-static@LCrafted;->s(IJLjava/lang/String;)V",
            "const-method-type v0, (IJ)V",
            "invoke-static/range {}, LCrafted;->e()V",
            "invoke-polymorphic {v0, v1, v2, v3}, Ljava/lang/invoke/MethodHandle;->invoke([Ljava/lang/Object;)Ljava/lang/Object;, (IJ)V",
            "invoke-polymorphic/range {v0 .. v3}, Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (IJ)V",
            "invoke-custom {v1}, call_site_2(\"run\", (I)V, 0x1, \"s\", invoke-static@LCrafted;->e()V, (J)V, 0x2L, 1.5f, 2.5, LCrafted;)" +
                "@LBoot;->bsm(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            "invoke-custom/range {v1 .. v2}, call_site_0(\"run2\", (J)V)@LBoot;->bsm2(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            "invoke-custom/range {}, call_site_1(\"run3\", ()V)@LBoot;->bsm2(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            "invoke-static {p0, p1, p2, p3}, LCrafted;->s(IJLjava/lang/String;)V",
            "invoke-static/range {p0 .. p3}, LCrafted;->s(IJLjava/lang/String;)V"
        )
        for (e in expected) assertTrue(e in insns, "нет [$e] в $insns")
    }

    @Test
    fun `debug info, parameters and register numbering`() {
        val cls = tree(DexFixtures.CRAFTED).classes.single { it.descriptor == "LCrafted;" }
        val m = cls.method("s")
        // Модель: имена и аннотации параметров — по индексу параметра.
        assertEquals(listOf("x\u0000y", null, "tail"), m.paramNames)
        assertEquals(setOf(1, 2), m.parameterAnnotations.keys)
        val debug = m.body.filterIsInstance<SmaliDebug>().map { it.raw }
        for (e in listOf(".prologue", ".line 5", ".local v0", ".local v1, \"a\":I, \"sig<T>\"", ".local v3, null:Ljava/lang/String;",
            ".local v4, \"n\":V", ".local v5, null:V, \"onlysig\"", ".local p0, \"px\":I", ".end local v0", ".restart local v0",
            ".source \"Other.java\"", ".source", ".end local p0", ".restart local p0", ".epilogue")) {
            assertTrue(e in debug, "нет [$e] в $debug")
        }
        val text = SmaliWriter.write(cls)
        // Текст: .param с номером регистра (J занимает p1-p2).
        assertLines(text,
            "    .param p0, \"x\\u0000y\"    # I",
            "    .param p1    # J",
            "    .param p3, \"tail\"    # Ljava/lang/String;",
            "    .end param")
    }

    @Test
    fun `payloads of all widths and try-catch labels`() {
        val cls = tree(DexFixtures.CRAFTED).classes.single { it.descriptor == "LCrafted;" }
        val text = SmaliWriter.write(cls)
        assertLines(text,
            "    .packed-switch -0x2",
            "        -0x80000000 -> :sswitch_21",
            "        -0x8000000000000000L",
            "        0x7fffffffffffffffL",
            "        0x1",
            "        -0x80t",
            "        -0x8000s",
            "        0x7fffffff",
            "    fill-array-data v0, :array_72",
            "    :try_end_21",
            "    .catch Ljava/lang/RuntimeException; {:try_start_0 .. :try_end_21} :catch_21",
            "    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_21} :catch_21",
            "    .catchall {:try_start_0 .. :try_end_21} :catchall_21",
            "    goto/32 :goto_21")
        // :try_end_ и .catch — сразу после последней покрытой инструкции, затем пустая строка.
        val afterTry = text.lines().dropWhile { it != "    fill-array-data v0, :array_72" }.take(6)
        assertEquals("    :try_end_21", afterTry[1])
        assertEquals("", afterTry[5])
        // Метки по одному адресу — в порядке baksmali (по префиксу).
        val labels = text.lines().dropWhile { it != "    :catch_21" }.take(7)
        assertEquals(listOf("    :catch_21", "    :catchall_21", "    :cond_21", "    :goto_21", "    :pswitch_21",
            "    :sswitch_21", "    if-eqz p0, :cond_21"), labels)
    }

    @Test
    fun `shared switch payload is duplicated like baksmali`() {
        val cls = tree(DexFixtures.SHARED).classes.single()
        val insns = cls.method("packed").insns()
        assertEquals("packed-switch p0, :pswitch_data_c", insns[0])
        assertEquals("packed-switch p0, :pswitch_data_12", insns[1])
        assertEquals(".packed-switch -0x5\n    :pswitch_6\n.end packed-switch", insns[insns.size - 2])
        assertEquals(".packed-switch -0x5\n    :pswitch_9\n.end packed-switch", insns[insns.size - 1])
        val sparse = cls.method("sparse").insns()
        assertEquals(".sparse-switch\n    -0x7 -> :sswitch_9\n    0x64 -> :sswitch_a\n.end sparse-switch", sparse.last())
        // switch, указывающий на nop перед payload'ом, сохраняется (baksmali его комментирует)
        assertEquals("packed-switch p0, :pswitch_data_5", cls.method("toNop").insns()[0])
    }

    @Test
    fun `class flags, super and source`() {
        val classes = tree(DexFixtures.CRAFTED).classes.associateBy { it.descriptor }
        val ann = classes.getValue("LAnn;")
        assertEquals(listOf("public", "interface", "abstract", "annotation"), ann.accessFlags)
        val e = classes.getValue("LE;")
        assertEquals(listOf("final", "enum"), e.accessFlags)
        assertEquals("", e.sourceFile)
        assertEquals(listOf("public", "static", "final", "enum"), e.fields.single { it.name == "A" }.accessFlags)
        assertEquals("Ljava/lang/Enum;", e.superClass)
    }

    @Test
    fun `errors name the class, method and address`() {
        val dex = DexReader().read(DexFixtures.SW)
        // метод f: 0: const/4 v0, 0x0; 1: packed-switch ... — вместо packed-switch ставим неизвестный опкод 0x3e
        val fIdx = dex.methodIds.indexOfFirst { dex.strings[it.nameIdx] == "f" }
        val codeIdx = dex.classDataItems.flatMap { it.directMethods }.single { it.methodIdx == fIdx }.codeIdx
        val broken = dex.codeItems[codeIdx].let { c -> c.copy(insns = c.insns.copyOf().also { it[2] = 0x3e; it[3] = 0 }) }
        val bad: DexFile = dex.copy(codeItems = dex.codeItems.toMutableList().also { it[codeIdx] = broken })
        val e = assertFailsWith<IllegalStateException> { DexToTree().build(bad) }
        val msg = generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertTrue("LSw;" in msg && "f(II)I" in msg && "0x1" in msg && "0x3e" in msg, msg)
    }

    @Test
    fun `hidden api flags become smali restriction names`() {
        val dex = DexReader().read(DexFixtures.CRAFTED)
        val e = dex.classDefs.indexOfFirst { dex.strings[dex.typeIds[it.classIdx].descriptorIdx] == "LE;" }
        // LE;: static $VALUES, A, B; direct <clinit>, <init>
        val flags = arrayOfNulls<IntArray>(dex.classDefs.size).also { it[e] = intArrayOf(0, 2 or 8, 7, 3 or 16, 6) }
        val cls = DexToTree().build(dex, flags).classes.single { it.descriptor == "LE;" }
        val f = cls.fields.associateBy { it.name }
        assertEquals(listOf("private", "static", "final", "synthetic", "whitelist"), f.getValue("\$VALUES").accessFlags)
        assertEquals(listOf("public", "static", "final", "enum", "blacklist", "core-platform-api"), f.getValue("A").accessFlags)
        // 7 в старой раскладке — «нет списка» (ART 10/11 такой файл отвергает): число, чтобы сборка была точной.
        assertEquals(listOf("public", "static", "final", "enum", "hiddenapi-0x7"), f.getValue("B").accessFlags)
        assertEquals(listOf("static", "constructor", "greylist-max-o", "test-api"), cls.method("<clinit>").accessFlags)
        assertEquals(listOf("private", "constructor", "greylist-max-r"), cls.method("<init>").accessFlags)
        assertTrue(".field public static final enum blacklist core-platform-api A:LE;" in SmaliWriter.write(cls).lines())

        // Новая раскладка (Android 12+, признак — test-api 0x20): max-target-s, домены 0x10 и 0x20;
        // невыразимое именами — числом.
        flags[e] = intArrayOf(0, 7 or 0x10, 1 or 0x20, 0xF, 0x40)
        val modern = DexToTree().build(dex, flags).classes.single { it.descriptor == "LE;" }
        val mf = modern.fields.associateBy { it.name }
        assertEquals(listOf("sdk"), mf.getValue("\$VALUES").accessFlags.takeLast(1))
        assertEquals(listOf("max-target-s", "core-platform-api"), mf.getValue("A").accessFlags.takeLast(2))
        assertEquals(listOf("unsupported", "test-api"), mf.getValue("B").accessFlags.takeLast(2))
        assertEquals(listOf("hiddenapi-0xf"), modern.method("<clinit>").accessFlags.takeLast(1))
        assertEquals(listOf("hiddenapi-0x40"), modern.method("<init>").accessFlags.takeLast(1))
        // Несовпадение числа флагов — ошибка.
        flags[e] = intArrayOf(0, 0, 0, 0)
        assertFailsWith<IllegalStateException> { DexToTree().build(dex, flags) }
    }

    @Test
    fun `hidden api section shapes are both accepted`() {
        val t = DexToTree()
        val fromList = t.hiddenApiFlagsOf(listOf(listOf(null, intArrayOf(1, 2))), 2)
        assertNull(fromList[0])
        assertEquals(listOf(1, 2), fromList[1]!!.toList())
        val fromMap = t.hiddenApiFlagsOf(listOf(mapOf(1 to listOf(3, 4))), 2)
        assertNull(fromMap[0])
        assertEquals(listOf(3, 4), fromMap[1]!!.toList())
        assertFailsWith<IllegalStateException> { t.hiddenApiFlagsOf(listOf(mapOf(5 to listOf(1))), 2) }
        assertFailsWith<IllegalStateException> { t.hiddenApiFlagsOf(listOf(listOf(null)), 2) }
    }
}
