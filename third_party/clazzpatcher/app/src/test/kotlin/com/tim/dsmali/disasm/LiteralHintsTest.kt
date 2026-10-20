package com.tim.dsmali.disasm

import com.tim.dsmali.io.LiteralHints
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.io.SmaliWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Подсказки baksmali у литералов (NumberUtils из smali): те же, что печатает baksmali. */
class LiteralHintsTest {

    @Test
    fun `как у baksmali`() {
        // Значения и подсказки — из вывода baksmali 2.5.2
        assertEquals("0.999f", LiteralHints.forInsn("const v2, 0x3f7fbe77"))
        assertEquals("10000.0f", LiteralHints.forInsn("const v1, 0x461c4000"))
        assertEquals("1.0f", LiteralHints.forInsn("const/high16 v0, 0x3f800000"))
        assertEquals("2.0", LiteralHints.forInsn("const-wide/high16 v0, 0x4000000000000000L"))
        assertEquals("6.9", LiteralHints.forInsn("const-wide v3, 0x401b99999999999aL"))
        assertEquals("(float)Math.PI", LiteralHints.forInsn("const v0, 0x40490fdb"))
        assertEquals("Float.NaN", LiteralHints.forInsn("const v0, 0x7fc00000"))
    }

    @Test
    fun `целые и ресурсы — без подсказки`() {
        assertNull(LiteralHints.forInsn("const/4 v0, 0x1"))
        assertNull(LiteralHints.forInsn("const/16 v0, 0x2a"))
        assertNull(LiteralHints.forInsn("const v0, 0x7f0b0012")) // R.id
        assertNull(LiteralHints.forInsn("const-wide/16 v1, 0x5"))
        assertNull(LiteralHints.forInsn("const-string v0, \"0x3f800000\""))
        assertNull(LiteralHints.forInsn("const v0, 0x7fffffff"))
    }

    @Test
    fun `array-data и повторный разбор`() {
        val text = """
            .class public LA;
            .super Ljava/lang/Object;

            .method public static f()V
                .registers 2
                const v0, 0x3f800000
                fill-array-data v1, :array_0
                return-void

                :array_0
                .array-data 4
                    0x3f800000
                    0x1
                .end array-data
            .end method
        """.trimIndent()
        val cls = SmaliParser.parseClass(text)!!
        val printed = SmaliWriter.write(cls)
        assertEquals(1, Regex("const v0, 0x3f800000 {4}# 1\\.0f").findAll(printed).count(), printed)
        assertEquals(1, Regex("0x3f800000 {4}# 1\\.0f\\n").findAll(printed.substringAfter(".array-data")).count(), printed)
        // Подсказки — комментарии: разбор их отбрасывает, модель та же
        assertEquals(cls, SmaliParser.parseClass(printed))
    }
}
