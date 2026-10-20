package com.tim.dsmali.asm

import com.tim.dsmali.data.SmaliDebugKind
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliLabel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmaliParserTest {

    private val sample = """
        .class public final Lcom/example/A;
        .super Ljava/lang/Object;
        .source "A.java"
        .implements Ljava/lang/Runnable;

        # annotations
        .annotation system Ldalvik/annotation/MemberClasses;
            value = {
                Lcom/example/A${'$'}B;,
                Lcom/example/A${'$'}C;
            }
        .end annotation

        # static fields
        .field public static final X:I = 0x10    # комментарий
        .field private static final S:Ljava/lang/String; = "a # b"
        .field private y:J
            .annotation runtime Ljava/lang/Deprecated;
            .end annotation
        .end field

        .method public static f(IJLjava/lang/String;D)V
            .registers 8
            .param p0, "a"    # I
            .param p3, "s"    # Ljava/lang/String;
                .annotation runtime Ljava/lang/Deprecated;
                .end annotation
            .end param
            .param p4    # D
            .annotation runtime Lcom/example/OnMethod;
            .end annotation

            .line 10
            const-string v0, "x#y"    # строка с #
            .local v0, "s":Ljava/lang/String;, "TT;"
            .end local v0    # "s":Ljava/lang/String;
            :label_1
            packed-switch p0, :pswitch_data_0
            return-void
            :pswitch_data_0
            .packed-switch 0x1
                :label_1
            .end packed-switch
            .catch Ljava/lang/Exception; {:label_1 .. :pswitch_data_0} :label_1
            .catchall {:label_1 .. :pswitch_data_0} :label_1
        .end method

        .method public run(I)V
            .locals 0
            .param p1, "unused"
        .end method
    """.trimIndent()

    @Test
    fun parsesClassHeaderFieldsAndAnnotations() {
        val c = SmaliParser.parseClass(sample)!!
        assertEquals("Lcom/example/A;", c.descriptor)
        assertEquals(listOf("public", "final"), c.accessFlags)
        assertEquals("Ljava/lang/Object;", c.superClass)
        assertEquals("A.java", c.sourceFile)
        assertEquals(listOf("Ljava/lang/Runnable;"), c.interfaces)
        assertEquals(1, c.annotations.size)
        assertEquals("{\nLcom/example/A\$B;,\nLcom/example/A\$C;\n}", c.annotations[0].elements[0].value)

        assertEquals("0x10", c.fields[0].initialValue)
        assertEquals("\"a # b\"", c.fields[1].initialValue)
        assertEquals(1, c.fields[2].annotations.size)
    }

    @Test
    fun convertsParamRegistersToParameterIndexes() {
        val m = SmaliParser.parseClass(sample)!!.methods[0]
        // p0 -> #0 (static), p3 -> #2 (после J в p1..p2), p4 -> #3
        assertEquals(listOf("a", null, "s"), m.paramNames)
        assertEquals(setOf(2), m.parameterAnnotations.keys)
        // аннотация после .param без .end param относится к методу
        assertEquals(listOf("Lcom/example/OnMethod;"), m.annotations.map { it.type })
    }

    @Test
    fun stripsCommentsAndKeepsBodyOrder() {
        val m = SmaliParser.parseClass(sample)!!.methods[0]
        val body = m.body
        assertEquals(SmaliDebug(SmaliDebugKind.LINE, ".line 10"), body[0])
        assertEquals(SmaliInsn("const-string v0, \"x#y\""), body[1])
        assertEquals(SmaliDebugKind.LOCAL, (body[2] as SmaliDebug).kind)
        assertEquals(".end local v0", (body[3] as SmaliDebug).raw)
        assertEquals(SmaliLabel(":label_1"), body[4])
        assertEquals(".packed-switch 0x1\n:label_1\n.end packed-switch", (body.last() as SmaliInsn).text)
        assertEquals(2, m.catches.size)
        assertNull(m.catches[1].exceptionType)
    }

    @Test
    fun instanceMethodParamIndexSkipsThis() {
        val m = SmaliParser.parseClass(sample)!!.methods[1]
        assertEquals(0, m.locals)
        assertEquals(listOf<String?>("unused"), m.paramNames)
    }

    @Test
    fun paramErrors() {
        fun cls(method: String) = ".class LA;\n.super Ljava/lang/Object;\n$method\n.end method"
        assertFailsWith<IllegalArgumentException> { SmaliParser.parseClass(cls(".method public f(I)V\n.param p0, \"x\"")) }
            .also { assertTrue(it.message!!.contains("this")) }
        assertFailsWith<IllegalArgumentException> { SmaliParser.parseClass(cls(".method static f(J)V\n.param p1, \"x\"")) }
        assertFailsWith<IllegalArgumentException> { SmaliParser.parseClass(cls(".method static f(I)V\n.param p5")) }
        val ok = SmaliParser.parseClass(cls(".method static f(JI)V\n.registers 5\n.param v4, \"i\""))!!
        assertEquals(listOf(null, "i"), ok.methods[0].paramNames)
    }

    @Test
    fun oldParameterSyntax() {
        val text = """
            .class LA;
            .super Ljava/lang/Object;
            .method static f(IJ)V
                .parameter "first"
                .parameter
                    .annotation runtime Ljava/lang/Deprecated;
                    .end annotation
                .end parameter
            .end method
        """.trimIndent()
        val m = SmaliParser.parseClass(text)!!.methods[0]
        assertEquals(listOf<String?>("first"), m.paramNames)
        assertEquals(setOf(1), m.parameterAnnotations.keys)
    }

    @Test
    fun fieldAnnotationsWithoutEndFieldBelongToClass() {
        val text = """
            .class LA;
            .super Ljava/lang/Object;
            .field private x:I
            .annotation runtime Ljava/lang/Deprecated;
            .end annotation
            .field private y:I
        """.trimIndent()
        val c = SmaliParser.parseClass(text)!!
        assertEquals(1, c.annotations.size)
        assertTrue(c.fields.all { it.annotations.isEmpty() })
    }

    @Test
    fun superIsNullOnlyWhenAbsent() {
        val c = SmaliParser.parseClass(".class public Ljava/lang/Object;\n")!!
        assertNull(c.superClass)
        assertNull(SmaliParser.parseClass("# только комментарий\n\n"))
    }

    @Test
    fun hiddenApiFlagsAreKept() {
        val c = SmaliParser.parseClass(".class LA;\n.super Ljava/lang/Object;\n.field public whitelist core-platform-api x:I\n")!!
        assertEquals(listOf("public", "whitelist", "core-platform-api"), c.fields[0].accessFlags)
        assertEquals("x", c.fields[0].name)
    }

    @Test
    fun multilineSubannotationValues() {
        val text = """
            .class LA;
            .super Ljava/lang/Object;
            .annotation system Ldalvik/annotation/AnnotationDefault;
                value = .subannotation Lkotlin/Deprecated;
                    level = .enum Lkotlin/DeprecationLevel;->WARNING:Lkotlin/DeprecationLevel;
                    replaceWith = .subannotation Lkotlin/ReplaceWith;
                        expression = "}{"
                        imports = {}
                    .end subannotation
                .end subannotation
            .end annotation
        """.trimIndent()
        val v = SmaliParser.parseClass(text)!!.annotations[0].elements.single()
        assertEquals("value", v.name)
        assertTrue(v.value.startsWith(".subannotation Lkotlin/Deprecated;\n"))
        assertTrue(v.value.endsWith(".end subannotation\n.end subannotation"))
    }

    @Test
    fun errorsCarryLineNumbers() {
        val unknownDirective = assertFailsWith<IllegalArgumentException> {
            SmaliParser.parseClass(".class LA;\n.super Ljava/lang/Object;\n.bogus 1\n")
        }
        assertTrue(unknownDirective.message!!.contains("строка 3"), unknownDirective.message)
        val unknownInsn = assertFailsWith<IllegalArgumentException> {
            SmaliParser.parseClass(".class LA;\n.method f()V\n.registers 1\nfrobnicate v0\n.end method\n")
        }
        assertTrue(unknownInsn.message!!.contains("строка 4"), unknownInsn.message)
        assertFailsWith<IllegalArgumentException> { SmaliParser.parseClass(".class LA;\n.method f()V\n") }
        assertFailsWith<IllegalArgumentException> { SmaliParser.parseClass(".field x:I\n") }
    }

    @Test
    fun parseIsThreadSafe() {
        val expected = SmaliParser.parseClass(sample)
        val pool = Executors.newFixedThreadPool(8)
        val results = (0 until 64).map { pool.submit<Any?> { SmaliParser.parseClass(sample) } }.map { it.get() }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        results.forEach { assertEquals(expected, it) }
    }

    /** Варианты синтаксиса, которые принимает эталонный smali (тесты ART 459, 472, 559, 660, 954). */
    @Test
    fun `reference smali syntax variants`() {
        val text = """
            .source "S.smali"
            .super Ljava/lang/Object;
            .class public LS;
            .method public static f(I)I
                .registers 2
                if-eqz p0, : zero
                goto: done
                : zero
                const/4 p0, 0x1
                :done
                return p0
            .end method
            .method public static f(I)I
                .registers 2
                const/4 v0, 0x7
                return v0
            .end method
        """.trimIndent()
        val cls = SmaliParser.parseClass(text)!!
        assertEquals("LS;", cls.descriptor)
        assertEquals("S.smali", cls.sourceFile)
        assertEquals("Ljava/lang/Object;", cls.superClass)
        assertTrue(cls.methods[0].body.filterIsInstance<SmaliLabel>().map { it.name }.containsAll(listOf(":zero", ":done")))
        // Повторное объявление: действует первое (как dexlib2).
        val dex = com.tim.dsmali.Smali.assemble(com.tim.dsmali.model.SmaliTree(mutableListOf(cls)))
        assertEquals(1, dex.classDataItems.single().directMethods.size)
        val back = com.tim.dsmali.Smali.disassemble(dex).classes.single()
        assertTrue(back.methods.single().body.any { it is SmaliInsn && it.text.startsWith("if-eqz") })
    }

    /** Имена с пробелами (DEX 040+) — в обратных кавычках: разбор, сборка (версия 040) и обратная разборка. */
    @Test
    fun `spaced names in backticks`() {
        val text = """
            .class public Lp/`my pkg`/B;
            .super Ljava/lang/Object;
            .field public static `a b`:I
            .method public static `my test`(Lp/`my pkg`/B;)V
                .registers 2
                sget v0, Lp/`my pkg`/B;->`a b`:I
                invoke-static {p0}, Lp/`my pkg`/B;->`my test`(Lp/`my pkg`/B;)V
                return-void
            .end method
        """.trimIndent()
        val cls = SmaliParser.parseClass(text)!!
        assertEquals("Lp/my pkg/B;", cls.descriptor)
        assertEquals("a b", cls.fields.single().name)
        assertEquals("my test", cls.methods.single().name)
        assertEquals(listOf("Lp/my pkg/B;"), cls.methods.single().paramTypes)
        val bytes = com.tim.dex.writer.DexWriter(com.tim.dsmali.Smali.assemble(com.tim.dsmali.model.SmaliTree(mutableListOf(cls)))).buildDex()
        assertEquals("040", String(bytes, 4, 3))
        val back = com.tim.dsmali.io.SmaliWriter.write(com.tim.dsmali.Smali.disassemble(com.tim.dex.parser.DexReader().read(bytes)).classes.single())
        assertTrue(".field public static `a b`:I" in back, back)
        assertTrue("invoke-static {p0}, Lp/`my pkg`/B;->`my test`(Lp/`my pkg`/B;)V" in back, back)
    }
}
