package com.tim.dsmali.asm

import com.tim.dex.data.DexMagic
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.Opcode
import com.tim.dex.model.CodeItem
import com.tim.dex.model.DebugOpcode
import com.tim.dex.model.DexFile
import com.tim.dex.model.EncodedValue
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TreeToDexTest {

    private fun assemble(vararg classes: String): DexFile {
        val tree = SmaliTree()
        classes.forEach { tree.addClass(SmaliParser.parseClass(it.trimIndent())!!) }
        return Smali.assemble(tree)
    }

    private fun DexFile.string(idx: Int) = strings[idx]
    private fun DexFile.type(idx: Int) = strings[typeIds[idx].descriptorIdx]

    private fun DexFile.method(name: String): Pair<Int, CodeItem?> {
        for (cd in classDataItems) {
            for (m in cd.directMethods + cd.virtualMethods) {
                if (string(methodIds[m.methodIdx].nameIdx) == name) return m.methodIdx to codeItems.getOrNull(m.codeIdx)
            }
        }
        throw AssertionError("нет метода $name")
    }

    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun s32(b: ByteArray, off: Int) = u16(b, off) or (u16(b, off + 2) shl 16)

    /** Адрес (в code units) -> первый code unit инструкции/payload'а. */
    private fun layout(code: ByteArray): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        var pos = 0
        while (pos < code.size) {
            val unit = u16(code, pos)
            out[pos / 2] = unit
            pos += when (unit) {
                0x0100 -> 8 + u16(code, pos + 2) * 4
                0x0200 -> 4 + u16(code, pos + 2) * 8
                0x0300 -> 8 + ((u16(code, pos + 2) * s32(code, pos + 4) + 1) and -2)
                else -> Opcode.of(unit and 0xFF)!!.format.sizeUnits * 2
            }
        }
        return out
    }

    @Test
    fun wideAndHighLiterals() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .method static f()V
                .registers 2
                const-wide/high16 v0, 0x4024000000000000L    # 10.0
                const/high16 v0, -0x80000000
                const-wide v0, -0x8000000000000000L
                const/4 v0, 0xf
                const/16 v0, 0xffff
                add-int/lit8 v0, v1, 0xff
                return-void
            .end method
            """
        )
        val code = dex.method("f").second!!.insns
        assertEquals(0x19, u16(code, 0) and 0xFF)
        assertEquals(0x4024, u16(code, 2))
        assertEquals(0x15, u16(code, 4) and 0xFF)
        assertEquals(0x8000, u16(code, 6))
        assertEquals(0x18, u16(code, 8) and 0xFF)
        assertEquals(0, u16(code, 10)); assertEquals(0, u16(code, 12)); assertEquals(0, u16(code, 14))
        assertEquals(0x8000, u16(code, 16))
        assertEquals(0xF012, u16(code, 18)) // const/4 v0, -1
        assertEquals(0xFFFF, u16(code, 22)) // const/16 v0, -1
        assertEquals(0xFF, u16(code, 26) shr 8) // add-int/lit8 ... -1
    }

    @Test
    fun eachPayloadBelongsToItsSwitch() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .method static f(I)I
                .registers 2
                packed-switch p0, :p1
                :a
                sparse-switch p0, :s1
                :b
                packed-switch p0, :p2
                const/4 v0, 0x0
                return v0
                :p2
                .packed-switch 0x5
                    :a
                    :b
                .end packed-switch
                :p1
                .packed-switch 0x1
                    :b
                .end packed-switch
                :s1
                .sparse-switch
                    -0x1 -> :a
                    0x10 -> :b
                .end sparse-switch
            .end method
            """
        )
        val code = dex.method("f").second!!.insns
        val l = layout(code)
        // switch: 0 (p1), 3 (s1), 6 (p2); :a = 3, :b = 6
        val switches = l.filter { (it.value and 0xFF) == 0x2b || (it.value and 0xFF) == 0x2c }.keys.toList()
        assertEquals(listOf(0, 3, 6), switches)
        fun payloadOf(sw: Int) = sw + s32(code, sw * 2 + 2)
        val p1 = payloadOf(0); val s1 = payloadOf(3); val p2 = payloadOf(6)
        assertEquals(0x0100, l[p1]); assertEquals(0x0200, l[s1]); assertEquals(0x0100, l[p2])
        // p1: firstKey 1, target :b (6) относительно switch 0
        assertEquals(1, s32(code, p1 * 2 + 4)); assertEquals(6 - 0, s32(code, p1 * 2 + 8))
        // p2: targets :a, :b относительно switch 6
        assertEquals(3 - 6, s32(code, p2 * 2 + 8)); assertEquals(6 - 6, s32(code, p2 * 2 + 12))
        // s1: keys -1, 16; targets относительно switch 3
        assertEquals(-1, s32(code, s1 * 2 + 4)); assertEquals(16, s32(code, s1 * 2 + 8))
        assertEquals(3 - 3, s32(code, s1 * 2 + 12)); assertEquals(6 - 3, s32(code, s1 * 2 + 16))
        assertTrue(p2 % 2 == 0 && p1 % 2 == 0 && s1 % 2 == 0, "payload'ы выровнены")
    }

    @Test
    fun arrayDataElementsOfAllWidths() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .method static f()V
                .registers 1
                fill-array-data v0, :b
                fill-array-data v0, :j
                fill-array-data v0, :f
                return-void
                :b
                .array-data 1
                    -0x80t
                    0x7ft
                    0xfft
                .end array-data
                :j
                .array-data 8
                    -0x8000000000000000L
                    0x1
                .end array-data
                :f
                .array-data 4
                    0x3f800000    # 1.0f
                    1.5f
                .end array-data
            .end method
            """
        )
        val code = dex.method("f").second!!.insns
        val l = layout(code)
        val arrays = l.filter { it.value == 0x0300 }.keys.toList()
        assertEquals(3, arrays.size)
        val b = arrays[0] * 2
        assertEquals(1, u16(code, b + 2)); assertEquals(3, s32(code, b + 4))
        assertEquals(listOf(0x80, 0x7F, 0xFF), (0 until 3).map { code[b + 8 + it].toInt() and 0xFF })
        val j = arrays[1] * 2
        assertEquals(Long.MIN_VALUE, java.nio.ByteBuffer.wrap(code, j + 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(1L, java.nio.ByteBuffer.wrap(code, j + 16, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
        val f = arrays[2] * 2
        assertEquals(1.5f, java.lang.Float.intBitsToFloat(s32(code, f + 12)))
    }

    @Test
    fun debugInfoParametersAndLocals() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .method static f(IJI)V
                .registers 5
                .param p3, "last"    # I
                .line 7
                .local v0, null:V
                .local v0, "x":I
                .end local p0
                .restart local p0
                .line 7
                .source
                return-void
            .end method
            """
        )
        val (_, code) = dex.method("f")
        val dbg = dex.debugInfoItems[code!!.debugInfoIdx]
        assertEquals(3, dbg.parametersSize)
        assertEquals(-1, dbg.parameterNames[0]); assertEquals(-1, dbg.parameterNames[1])
        assertEquals("last", dex.string(dbg.parameterNames[2]))
        assertEquals(7, dbg.lineStart)
        val ops = dbg.opcodes
        assertEquals(DebugOpcode.StartLocal(0, -1, -1), ops[1])
        assertEquals("x", dex.string((ops[2] as DebugOpcode.StartLocal).nameIdx))
        assertEquals(DebugOpcode.EndLocal(1), ops[3])      // p0 = v1 (5 регистров - 4 на параметры)
        assertEquals(DebugOpcode.RestartLocal(1), ops[4])
        assertEquals(2, ops.count { it is DebugOpcode.SpecialOpcode }) // оба .line 7
        assertEquals(DebugOpcode.SetFile(-1), ops[6])
        assertEquals(DebugOpcode.EndSequence, ops.last())
        assertEquals(0, ops.count { it is DebugOpcode.EndLocal && it.registerNum == 0 }) // без автоматических end_local
    }

    @Test
    fun staticValuesArePositional() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .field static a:I
            .field static b:J = 0x5L
            .field static c:Ljava/lang/String;
            .field static d:Z = false
            .field static e:I
            """
        )
        val values = dex.encodedArrayItems[dex.classDefs[0].staticValuesIdx]
        assertEquals(
            listOf(
                EncodedValue(EncodedValueType.VALUE_INT, 0),
                EncodedValue(EncodedValueType.VALUE_LONG, 5L),
                EncodedValue(EncodedValueType.VALUE_NULL, null),
                EncodedValue(EncodedValueType.VALUE_BOOLEAN, false)
            ),
            values
        )
        val statics = dex.classDataItems[dex.classDefs[0].classDataIdx].staticFields
        assertEquals(listOf("a", "b", "c", "d", "e"), statics.map { dex.string(dex.fieldIds[it.fieldIdx].nameIdx) })
    }

    @Test
    fun encodedValueLiterals() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .field static a:J = -0x8000000000000000L
            .field static b:F = -1.5f
            .field static c:D = 10.0
            .field static d:C = 'é'
            .field static e:B = -0x80t
            .field static f:S = 0x7fffs
            .field static g:Ljava/lang/Class; = [I
            .field static h:Ljava/lang/String; = "a\\n\"b"
            """
        )
        val v = dex.encodedArrayItems[dex.classDefs[0].staticValuesIdx]
        assertEquals(EncodedValue(EncodedValueType.VALUE_LONG, Long.MIN_VALUE), v[0])
        assertEquals(EncodedValue(EncodedValueType.VALUE_FLOAT, -1.5f), v[1])
        assertEquals(EncodedValue(EncodedValueType.VALUE_DOUBLE, 10.0), v[2])
        assertEquals(EncodedValue(EncodedValueType.VALUE_CHAR, 'é'), v[3])
        assertEquals(EncodedValue(EncodedValueType.VALUE_BYTE, Byte.MIN_VALUE), v[4])
        assertEquals(EncodedValue(EncodedValueType.VALUE_SHORT, Short.MAX_VALUE), v[5])
        assertEquals("[I", dex.type(v[6].value as Int))
        assertEquals("a\\n\"b", dex.string(v[7].value as Int))
    }

    @Test
    fun parameterAnnotationsSizedToParameters() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .method static f(IJLjava/lang/Object;)V
                .registers 4
                .param p1    # J
                    .annotation runtime Ljava/lang/Deprecated;
                    .end annotation
                .end param
                return-void
            .end method
            """
        )
        val dir = dex.annotationsDirectoryItems[dex.classDefs[0].annotationsDirectoryIdx]
        val refs = dex.annotationSetRefLists[dir.parameterAnnotations.single().annotationsSetRefListIdx].annotationSetIdxs
        assertEquals(3, refs.size)
        assertEquals(-1, refs[0]); assertEquals(-1, refs[2])
        assertTrue(refs[1] >= 0)
    }

    @Test
    fun callSitesMethodHandlesAndVersion() {
        val dex = assemble(
            """
            .class LA;
            .super Ljava/lang/Object;
            .method static bsm(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;
                .registers 4
                const/4 v0, 0x0
                return-object v0
            .end method
            .method static f()V
                .registers 2
                invoke-custom {v0, v1}, call_site_1("run", (II)V, 0x7)@LA;->bsm(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;
                invoke-custom {}, call_site_0("go", ()V, 0x1)@LA;->bsm(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;
                const-method-handle v0, static-get@LA;->x:I
                const-method-type v0, (I)V
                return-void
            .end method
            """
        )
        assertEquals("039", DexMagic.getVersion(dex.header.magic))
        assertEquals(2, dex.callSiteIds.size)
        // call_site_0 добавлен первым, несмотря на порядок в коде
        assertEquals("go", dex.string(dex.callSiteItems[dex.callSiteIds[0].callSiteIdx].arguments[0].value as Int))
        assertEquals(listOf(0x04, 0x01), dex.methodHandles.map { it.methodHandleType }.sorted().reversed())
        val code = dex.method("f").second!!.insns
        assertEquals(0xfc, u16(code, 0) and 0xFF)
        assertEquals(1, u16(code, 2)) // call_site_1
        assertEquals(0, u16(code, 8)) // call_site_0
    }

    @Test
    fun interfaceDefaultMethodNeedsDex037() {
        val dex = assemble(
            """
            .class public interface abstract LI;
            .super Ljava/lang/Object;
            .method public f()V
                .registers 1
                return-void
            .end method
            """
        )
        assertEquals("037", DexMagic.getVersion(dex.header.magic))
    }

    @Test
    fun constStringIndexesFitWithManyStrings() {
        val sb = StringBuilder(".class LA;\n.super Ljava/lang/Object;\n")
        for (i in 0 until 70000) sb.append(".field static f").append(i).append(":Ljava/lang/String; = \"s").append(i).append("\"\n")
        sb.append(".method static m()Ljava/lang/String;\n.registers 1\nconst-string v0, \"zzz\"\nreturn-object v0\n.end method\n")
        val dex = assemble(sb.toString())
        assertTrue(dex.strings.size > 65536)
        val code = dex.method("m").second!!.insns
        assertEquals("zzz", dex.string(u16(code, 2)))
    }

    /** Классы собираются параллельно, но DEX от числа потоков не зависит — байт в байт. */
    @Test
    fun parallelBuildIsDeterministic() {
        for (fixture in listOf("gson", "user")) {
            val dex = com.tim.dex.parser.DexReader().read(java.io.File(javaClass.getResource("/fixtures/$fixture/classes.dex")!!.toURI()))
            val tree = com.tim.dsmali.disasm.DexToTree().build(dex)
            fun bytes(threads: Int) = com.tim.dex.writer.DexWriter(TreeToDex(threads).build(tree)).buildDex()
            val one = bytes(1)
            for (threads in listOf(2, 8)) assertTrue(one.contentEquals(bytes(threads)), "$fixture: DEX различается ($threads потоков)")
        }
    }

    @Test
    fun superclassDefinedBeforeSubclass() {
        val dex = assemble(
            ".class LB;\n.super LA;\n",
            ".class LA;\n.super Ljava/lang/Object;\n.implements LI;\n",
            ".class public interface abstract LI;\n.super Ljava/lang/Object;\n"
        )
        assertEquals(listOf("LI;", "LA;", "LB;"), dex.classDefs.map { dex.type(it.classIdx) })
    }

    @Test
    fun errorsNameClassMethodAndInstruction() {
        val e = assertFailsWith<IllegalStateException> {
            assemble(
                """
                .class LA;
                .super Ljava/lang/Object;
                .method static f()V
                    .registers 1
                    goto :nowhere
                .end method
                """
            )
        }
        val msg = generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertTrue(msg.contains("LA;") && msg.contains("f()V") && msg.contains(":nowhere"), msg)

        val reg = assertFailsWith<IllegalStateException> {
            assemble(".class LA;\n.super Ljava/lang/Object;\n.method static f()V\n.registers 1\nconst/4 v3, 0x1\nreturn-void\n.end method\n")
        }
        assertTrue(generateSequence<Throwable>(reg) { it.cause }.any { it.message!!.contains("v3") })
    }
}
