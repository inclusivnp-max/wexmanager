package com.tim.dsmali.disasm

import com.tim.dsmali.data.SmaliDebugKind
import com.tim.dsmali.io.SmaliWriter
import com.tim.dsmali.model.SmaliAnnotation
import com.tim.dsmali.model.SmaliAnnotationElement
import com.tim.dsmali.model.SmaliCatchDirective
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliField
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliLabel
import com.tim.dsmali.model.SmaliMethod
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmaliWriterTest {
    @Test
    fun `param directives use register numbers`() {
        val m = SmaliMethod(accessFlags = mutableListOf("public"), name = "m", returnType = "V").apply {
            paramTypes.addAll(listOf("J", "I", "D", "Ljava/lang/String;"))
            paramNames.addAll(listOf("wide", null, "d", null))
            parameterAnnotations[3] = mutableListOf(SmaliAnnotation("runtime", "LA;"))
            registers = 7
            body.add(SmaliInsn("return-void"))
        }
        val cls = SmaliClass("LT;", mutableListOf("public"), superClass = null).apply { methods.add(m) }
        val expected = """
            |.class public LT;
            |
            |
            |# virtual methods
            |.method public m(JIDLjava/lang/String;)V
            |    .registers 7
            |    .param p1, "wide"    # J
            |    .param p4, "d"    # D
            |    .param p6    # Ljava/lang/String;
            |        .annotation runtime LA;
            |        .end annotation
            |    .end param
            |
            |    return-void
            |.end method
            |""".trimMargin()
        assertEquals(expected, SmaliWriter.write(cls))
        // для static-метода нумерация начинается с p0
        m.accessFlags.add("static")
        val text = SmaliWriter.write(cls)
        assertEquals(true, "    .param p0, \"wide\"    # J" in text.lines())
        assertEquals(true, "    .param p5    # Ljava/lang/String;" in text.lines())
    }

    @Test
    fun `layout follows baksmali`() {
        val cls = SmaliClass("LT;", mutableListOf("public", "final"), sourceFile = "T\".java").apply {
            interfaces.add("LI;")
            annotations.add(SmaliAnnotation("system", "Ldalvik/annotation/Signature;",
                mutableListOf(SmaliAnnotationElement("value", "{\n    \"a\",\n    \"b\"\n}"))))
            fields.add(SmaliField(mutableListOf("static"), "s", "I", initialValue = "0x1"))
            fields.add(SmaliField(mutableListOf("private"), "f", "Lsp/x y;").apply {
                annotations.add(SmaliAnnotation("runtime", "LA;", mutableListOf(SmaliAnnotationElement("v", "0x1t"))))
                annotations.add(SmaliAnnotation("build", "LB;"))
            })
            methods.add(SmaliMethod(mutableListOf("public", "abstract"), "a", "V"))
            methods.add(SmaliMethod(mutableListOf("static"), "b", "V").apply {
                registers = 1
                body.add(SmaliDebug(SmaliDebugKind.LINE, ".line 3"))
                body.add(SmaliLabel(":try_start_0"))
                body.add(SmaliInsn("nop"))
                body.add(SmaliLabel(":try_end_1"))
                body.add(SmaliLabel(":catchall_1"))
                body.add(SmaliInsn(".packed-switch 0x1\n    :catchall_1\n.end packed-switch"))
                catches.add(SmaliCatchDirective(null, ":try_start_0", ":try_end_1", ":catchall_1"))
                catches.add(SmaliCatchDirective("Ljava/lang/Exception;", ":try_start_0", ":try_end_1", ":catchall_1"))
            })
        }
        val expected = """
            |.class public final LT;
            |.super Ljava/lang/Object;
            |.source "T\".java"
            |
            |# interfaces
            |.implements LI;
            |
            |
            |# annotations
            |.annotation system Ldalvik/annotation/Signature;
            |    value = {
            |        "a",
            |        "b"
            |    }
            |.end annotation
            |
            |
            |# static fields
            |.field static s:I = 0x1
            |
            |
            |# instance fields
            |.field private f:Lsp/`x y`;
            |    .annotation runtime LA;
            |        v = 0x1t
            |    .end annotation
            |
            |    .annotation build LB;
            |    .end annotation
            |.end field
            |
            |
            |# direct methods
            |.method static b()V
            |    .registers 1
            |
            |    .line 3
            |    :try_start_0
            |    nop
            |    :try_end_1
            |    .catchall {:try_start_0 .. :try_end_1} :catchall_1
            |    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_1} :catchall_1
            |
            |    :catchall_1
            |    .packed-switch 0x1
            |        :catchall_1
            |    .end packed-switch
            |.end method
            |
            |
            |# virtual methods
            |.method public abstract a()V
            |.end method
            |""".trimMargin()
        assertEquals(expected, SmaliWriter.write(cls))
    }

    @Test
    fun `inconsistent parameters are rejected`() {
        val m = SmaliMethod(name = "m").apply { paramNames.addAll(listOf(null, "extra")) }
        val cls = SmaliClass("LT;").apply { methods.add(m) }
        assertFailsWith<IllegalStateException> { SmaliWriter.write(cls) }
    }
}
