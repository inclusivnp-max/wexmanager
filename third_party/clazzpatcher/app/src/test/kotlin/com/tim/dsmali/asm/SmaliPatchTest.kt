package com.tim.dsmali.asm

import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmaliPatchTest {

    private fun tree(): SmaliTree {
        val t = SmaliTree()
        t.addClass(
            SmaliParser.parseClass(
                """
                .class public LA;
                .super Ljava/lang/Object;
                .field x:I
                .field x:J
                .method foo()V
                    .registers 1
                    return-void
                .end method
                .method foo(I)V
                    .registers 2
                    return-void
                .end method
                """.trimIndent()
            )!!
        )
        return t
    }

    private val patch = """
        .class public LA;
        .super Ljava/lang/Object;
        .field x:I = 0x5
        .method foo(I)V
            .registers 3
            nop
            return-void
        .end method
    """.trimIndent()

    @Test
    fun replacesOnlyMatchingOverloadAndField() {
        val t = tree()
        Smali.patch(t) { patchClass("LA;", patch) }
        val a = t.findClass("LA;")!!
        assertEquals(2, a.methods.size)
        assertEquals(1, a.methods.first { it.paramTypes.isEmpty() }.registers) // foo()V не тронут
        assertEquals(3, a.methods.first { it.paramTypes == listOf("I") }.registers)
        assertEquals(listOf("I" to "0x5", "J" to null), a.fields.map { it.type to it.initialValue })
    }

    @Test
    fun missingClassOrBadTextIsAnError() {
        val t = tree()
        assertFailsWith<IllegalArgumentException> { Smali.patch(t) { patchClass("LB;", patch) } }
        assertFailsWith<IllegalArgumentException> { Smali.patch(t) { patchClass("LA;", ".method foo()V") } }
        assertFailsWith<IllegalArgumentException> { Smali.patch(t) { applyText("# пусто") } }
        assertFailsWith<IllegalArgumentException> { Smali.patch(t) { removeClass("LB;") } }
        assertFailsWith<IllegalArgumentException> { Smali.patch(t) { addMethod("LA;", patch) } } // foo(I)V уже есть
    }
}
