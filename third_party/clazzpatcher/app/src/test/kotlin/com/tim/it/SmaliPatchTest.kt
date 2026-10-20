package com.tim.it

import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliMethod
import com.tim.dsmali.model.SmaliTree
import com.tim.dsmali.patch.SmaliPatch
import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Патчи smali: замена ровно одной перегрузки, замена/добавление класса, громкие ошибки. */
class SmaliPatchTest {
    private val target = """
        .class public Lp/Target;
        .super Ljava/lang/Object;

        .field public x:I

        .field public x:J

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public foo(I)V
            .registers 2
            return-void
        .end method

        .method public foo(J)V
            .registers 3
            return-void
        .end method

        .method public foo()I
            .registers 2
            const/4 v0, 0x1
            return v0
        .end method

        .method public bar()V
            .registers 1
            return-void
        .end method
    """.trimIndent()

    private fun tree(): SmaliTree = SmaliTree().apply {
        addClass(SmaliParser.parseClass(target) ?: throw IllegalStateException("SmaliParser вернул null"))
    }

    private fun signature(m: SmaliMethod) = m.name + m.paramTypes.joinToString("", "(", ")") + m.returnType

    private fun instructions(m: SmaliMethod) = m.body.filterIsInstance<SmaliInsn>().map { it.text.trim() }

    @Test
    fun `patchClass заменяет ровно одну перегрузку`() {
        val tree = tree()
        SmaliPatch(tree).patchClass(
            "Lp/Target;",
            """
            .class public Lp/Target;
            .super Ljava/lang/Object;

            .method public foo(I)V
                .registers 2
                const/4 v0, 0x5
                return-void
            .end method
            """.trimIndent()
        )
        val cls = tree.findClass("Lp/Target;")!!
        assertEquals(
            listOf("<init>()V", "bar()V", "foo()I", "foo(I)V", "foo(J)V"),
            cls.methods.map { signature(it) }.sorted(),
            "после патча должны остаться все перегрузки"
        )
        val patched = cls.methods.single { signature(it) == "foo(I)V" }
        assertTrue(instructions(patched).any { it.startsWith("const/4") }, "foo(I)V не заменён: ${instructions(patched)}")
        assertEquals(listOf("return-void"), instructions(cls.methods.single { signature(it) == "foo(J)V" }), "foo(J)V изменён")
        assertEquals(2, cls.fields.size, "поля не должны меняться")

        val dex = DexWriter(Smali.assemble(tree)).buildDex()
        DexDump.assertValid(dex, "DEX после патча")
    }

    @Test
    fun `patchClass заменяет поле по имени и типу`() {
        val tree = tree()
        SmaliPatch(tree).patchClass(
            "Lp/Target;",
            """
            .class public Lp/Target;
            .super Ljava/lang/Object;

            .field public volatile x:J
            """.trimIndent()
        )
        val fields = tree.findClass("Lp/Target;")!!.fields
        assertEquals(listOf("x:I", "x:J"), fields.map { it.name + ":" + it.type }.sorted(), "поле x:I не должно пропасть")
        assertTrue("volatile" in fields.single { it.type == "J" }.accessFlags, "поле x:J не заменено")
    }

    @Test
    fun `Smali patch добавляет новый класс и заменяет существующий`() {
        val tree = tree()
        Smali.patch(
            tree,
            """
            .class public Lp/Added;
            .super Lp/Target;

            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Lp/Target;-><init>()V
                return-void
            .end method
            """.trimIndent()
        )
        Smali.patch(
            tree,
            """
            .class public Lp/Target;
            .super Ljava/lang/Object;

            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            """.trimIndent()
        )
        assertEquals(listOf("Lp/Added;", "Lp/Target;"), tree.classes.map { it.descriptor }.sorted())
        assertEquals(1, tree.findClass("Lp/Target;")!!.methods.size, "класс должен быть заменён целиком")

        val dex = DexWriter(Smali.assemble(tree)).buildDex()
        DexDump.assertValid(dex, "DEX после патча")
        val sem = DexSemantics.of(dex)
        assertNotNull(sem.classIndex["Lp/Added;"])
        assertTrue(sem.dumpClass("Lp/Added;", DexSemantics.Options()).contains("invoke-direct {v0}, method@Lp/Target;-><init>()V"))
    }

    @Test
    fun `patchClass несуществующего класса — ошибка, а не молчаливый пропуск`() {
        val tree = tree()
        assertThrows(Exception::class.java) {
            SmaliPatch(tree).patchClass("Lp/Missing;", ".class public Lp/Missing;\n.super Ljava/lang/Object;\n")
        }
    }

    @Test
    fun `некорректный smali в патче — ошибка, а не молчаливый пропуск`() {
        val tree = tree()
        assertThrows(Exception::class.java) { Smali.patch(tree, "это не smali") }
        assertThrows(Exception::class.java) { SmaliPatch(tree).patchClass("Lp/Target;", "это не smali") }
    }
}
