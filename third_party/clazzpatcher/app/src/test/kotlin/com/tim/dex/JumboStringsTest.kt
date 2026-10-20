package com.tim.dex

import com.tim.c2d.C2dTestKit
import com.tim.c2d.DexInterpreter
import com.tim.dasm.DexAsm
import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.Ins10t
import com.tim.dasm.model.Ins20t
import com.tim.dasm.model.Ins21c
import com.tim.dasm.model.Ins30t
import com.tim.dasm.model.Ins31c
import com.tim.dex.data.Opcode
import com.tim.dex.model.DebugOpcode
import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import com.tim.testutil.Adb
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Строк в DEX больше 65536: `const-string`, чей индекс после сортировки не помещается в 16 бит,
 * DexWriter делает `const-string/jumbo` и перекладывает код — ветвления (в том числе расширение
 * goto и if), switch, fill-array-data, try/catch, отладочную информацию. Эталон — тот же код без
 * лишних строк (перекладка не нужна): вывод интерпретатора и ART должен совпасть.
 */
class JumboStringsTest {
    @TempDir
    lateinit var tmp: File

    private val bigBlock = 12000

    private fun smali(): String = buildString {
        append(".class public LJ;\n.super Ljava/lang/Object;\n.source \"J.java\"\n\n")
        append(
            """
            .method public static main([Ljava/lang/String;)V
                .registers 8
                .line 1
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                const/4 v1, 0x0
                :loop
                .line 2
                const-string v2, "zz-loop"
                invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                packed-switch v1, :pswitch_data
                const-string v2, "zz-default"
                goto :after
                :p0
                const-string v2, "zz-p0"
                goto :after
                :p1
                const-string v2, "aa-p1"
                :after
                .line 3
                invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                sparse-switch v1, :sswitch_data
                const-string v2, "zz-s-default"
                goto :after2
                :s1
                const-string v2, "zz-s1"
                :after2
                invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                add-int/lit8 v1, v1, 0x1
                const/4 v3, 0x3
                if-lt v1, v3, :loop
                .line 4
                :try_start
                const-string v2, "zz-thrown"
                new-instance v3, Ljava/lang/RuntimeException;
                invoke-direct {v3, v2}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;)V
                throw v3
                :try_end
                .catch Ljava/lang/RuntimeException; {:try_start .. :try_end} :handler
                :handler
                .line 5
                move-exception v3
                invoke-virtual {v3}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                const/4 v1, 0x3
                new-array v4, v1, [I
                fill-array-data v4, :array_data
                const/4 v1, 0x2
                aget v5, v4, v1
                invoke-virtual {v0, v5}, Ljava/io/PrintStream;->println(I)V
                invoke-static {}, LJ;->shortLoop()V
                const/4 v1, 0x0
                invoke-static {v1}, LJ;->big(I)V
                const/4 v1, 0x1
                invoke-static {v1}, LJ;->big(I)V
                return-void
                :pswitch_data
                .packed-switch 0x0
                    :p0
                    :p1
                .end packed-switch
                :sswitch_data
                .sparse-switch
                    0x1 -> :s1
                    0x7 -> :s1
                .end sparse-switch
                :array_data
                .array-data 4
                    0x1
                    0x2
                    0x2a
                .end array-data
            .end method

            """.trimIndent()
        )
        // Цикл, чей обратный goto помещался в 8 бит, а после расширения const-string — уже нет.
        append("\n.method public static shortLoop()V\n    .registers 4\n")
        append("    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;\n    const/4 v1, 0x0\n    :top\n")
        for (k in 0 until 50) append("    const-string v2, \"zz-a$k\"\n")
        append("    add-int/lit8 v1, v1, 0x1\n    const/4 v3, 0x2\n    if-ge v1, v3, :done\n    goto :top\n    :done\n")
        append("    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V\n    return-void\n.end method\n")
        // if через большой блок: 16-битное смещение переполняется, нужен обход через goto/32.
        append("\n.method public static big(I)V\n    .registers 4\n")
        append("    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;\n    const-string v2, \"aa-skipped\"\n")
        append("    if-eqz p0, :skip\n")
        for (k in 0 until bigBlock) append("    const-string v2, \"zz-b$k\"\n")
        append("    :skip\n    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V\n")
        append("    if-nez p0, :back\n    return-void\n    :back\n")
        append("    const-string v2, \"zz-back\"\n    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V\n")
        append("    return-void\n.end method\n")
    }

    private fun build(extraStrings: Int): ByteArray {
        val dex = Smali.assemble(SmaliTree(mutableListOf(SmaliParser.parseClass(smali())!!)))
        val asm = DexAsm.load(dex)
        for (i in 0 until extraStrings) asm.strings.add("m%06d".format(i))
        return DexWriter(asm.build()).buildDex()
    }

    private fun code(dex: DexFile, method: String) = dex.classDataItems.flatMap { it.directMethods + it.virtualMethods }
        .first { dex.strings[dex.methodIds[it.methodIdx].nameIdx] == method }
        .let { dex.codeItems[it.codeIdx] }

    /** Позиции отладочной информации: адрес → строка. */
    private fun positions(dex: DexFile, method: String): List<Pair<Int, Int>> {
        val c = code(dex, method)
        val d = dex.debugInfoItems[c.debugInfoIdx]
        var addr = 0
        var line = d.lineStart
        val out = ArrayList<Pair<Int, Int>>()
        for (op in d.opcodes) when (op) {
            is DebugOpcode.AdvancePc -> addr += op.addressDelta
            is DebugOpcode.AdvanceLine -> line += op.lineDelta
            is DebugOpcode.SpecialOpcode -> { addr += op.addressDelta; line += op.lineDelta; out.add(addr to line) }
            else -> {}
        }
        return out
    }

    @Test
    fun constStringBecomesJumboAndCodeIsRelaidOut() {
        val plainBytes = build(0)
        val plain = DexReader().read(plainBytes)
        assertTrue(plain.strings.size < 0x10000)
        val expected = DexInterpreter(plain).runMain("LJ;")

        val wideBytes = build(70000)
        C2dTestKit.assertValidDex(wideBytes, "jumbo", expectedClasses = 1)
        val wide = DexReader().read(wideBytes)
        assertTrue(wide.strings.size > 0x10000)
        assertEquals(expected, DexInterpreter(wide).runMain("LJ;"), "вывод после перекладки")

        val dis = DexDisassembler()
        val main = dis.decode(code(wide, "main").insns)
        val jumbo = main.instructions.count { it is Ins31c && it.opcode == Opcode.CONST_STRING_JUMBO }
        assertEquals(6, jumbo, "const-string «zz-*» в main — /jumbo")
        assertTrue(main.instructions.none { it is Ins21c && it.opcode == Opcode.CONST_STRING && wide.strings[it.index].startsWith("zz") })

        // Строки отладочной информации указывают на те же инструкции, что и без перекладки.
        val plainMain = dis.decode(code(plain, "main").insns)
        val before = positions(plain, "main").map { (a, l) -> l to plainMain.instructions[plainMain.indexAt(a)].opcode.let { if (it == Opcode.CONST_STRING) Opcode.CONST_STRING_JUMBO else it } }
        val after = positions(wide, "main").map { (a, l) -> l to main.instructions[main.indexAt(a)].opcode }
        assertEquals(before, after, "позиции .line после перекладки")

        val loop = dis.decode(code(wide, "shortLoop").insns).instructions
        assertTrue(dis.decode(code(plain, "shortLoop").insns).instructions.any { it is Ins10t }, "в исходном цикле goto 8-битный")
        assertTrue(loop.none { it is Ins10t } && loop.any { it is Ins20t }, "goto расширен до goto/16")

        val big = dis.decode(code(wide, "big").insns).instructions
        assertTrue(big.any { it is Ins30t }, "if через большой блок — обход через goto/32")
        assertEquals(bigBlock + 1, big.count { it is Ins31c })

        val device = Adb.configured() ?: return
        device.prepare()
        try {
            val file = File(tmp, "jumbo.dex").also { it.writeBytes(wideBytes) }
            val (code, out) = device.runDex(file, "J")
            assertEquals(0 to expected.trim(), code to out.trim(), "ART")
            val (vc, log) = device.verifyDex("jumbo", listOf(file))
            assertEquals(0 to "", vc to log, "верификатор ART")
        } finally {
            device.cleanup()
        }
    }
}
