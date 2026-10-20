package com.tim.dasm

import com.tim.dasm.assembler.CodeResolver
import com.tim.dasm.assembler.TryListBuilder
import com.tim.dasm.dsl.InstructionsDsl
import com.tim.dasm.model.*
import com.tim.dex.data.Opcode
import com.tim.dex.model.TypeAddrPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodeAssemblyTest {

    private fun code(block: InstructionsDsl.() -> Unit): InstructionsDsl {
        val asm = DexAsm.load(TestAsm.emptyDex())
        return InstructionsDsl(asm).apply(block)
    }

    @Test
    fun gotoIsWidenedWhenTooFar() {
        val c = code {
            goto("far")
            repeat(100) { const32(0, it) } // 300 code units
            label("far")
            returnVoid()
        }
        val r = CodeResolver.resolve(c)
        val g = r.instructions[0]
        assertTrue(g is Ins20t && g.opcode == Opcode.GOTO_16, "goto расширен до goto/16: $g")
        assertEquals(302, (g as Ins20t).offset)
    }

    @Test
    fun unneededNopBeforePayloadIsRemoved() {
        // nop перед payload'ом, который без него уже выровнен, удаляется (как в smali)
        val c = code {
            fillArrayData(0, "data")   // 3 units
            nop()                      // адрес 3 -> payload на 4
            nop()                      // лишний: payload оказался бы на нечётном адресе 5
            label("data")
            fillArrayDataPayload(1, 2, byteArrayOf(1, 2))
        }
        val r = CodeResolver.resolve(c)
        assertEquals(3, r.instructions.size)
        assertEquals(4, r.labelOffset("data"))
        assertEquals(4, (r.instructions[0] as Ins31t).offset)
    }

    @Test
    fun payloadMisalignedWithoutNopGetsPadding() {
        val c = code {
            fillArrayData(0, "data")   // 3 units -> payload на 3 -> выравнивание до 4
            label("data")
            fillArrayDataPayload(1, 1, byteArrayOf(7))
        }
        val r = CodeResolver.resolve(c)
        assertEquals(4, r.labelOffset("data"))
        val bytes = com.tim.dasm.assembler.DexAssembler.assemble(r.instructions)
        assertEquals(0, bytes[6].toInt() or bytes[7].toInt()) // nop-выравнивание
        assertEquals(18, bytes.size) // 3 + 1 (nop) + 4 (заголовок) units + 1 байт данных + 1 байт выравнивания
    }

    @Test
    fun switchPayloadErrors() {
        assertFailsWith<IllegalStateException> {
            CodeResolver.resolve(code {
                packedSwitch(0, "p"); packedSwitch(0, "p"); returnVoid()
                label("p"); packedSwitchPayload(0, listOf("p"))
            })
        }
        assertFailsWith<IllegalStateException> {
            CodeResolver.resolve(code { returnVoid(); label("p"); packedSwitchPayload(0, emptyList()) })
        }
        assertFailsWith<IllegalStateException> {
            CodeResolver.resolve(code { sparseSwitch(0, "p"); returnVoid(); label("p"); packedSwitchPayload(0, emptyList()) })
        }
        assertFailsWith<IllegalArgumentException> {
            code { sparseSwitchPayload(intArrayOf(2, 1), listOf("a", "b")) }
        }
    }

    @Test
    fun nestedTriesAreSplit() {
        val t = TryListBuilder()
        t.addHandler(4, 8, 1, 100)   // внутренний
        t.addHandler(0, 12, 2, 200)  // внешний
        t.addHandler(0, 12, -1, 300) // catch-all
        val (tries, handlers) = t.build()
        assertEquals(listOf(0 to 4, 4 to 4, 8 to 4), tries.map { it.startAddr to it.insnCount })
        assertEquals(listOf(TypeAddrPair(2, 200)), handlers[tries[0].handlerIdx].handlers)
        assertEquals(listOf(TypeAddrPair(1, 100), TypeAddrPair(2, 200)), handlers[tries[1].handlerIdx].handlers)
        assertEquals(tries[0].handlerIdx, tries[2].handlerIdx) // одинаковые списки обработчиков разделяются
        assertEquals(300, handlers[tries[1].handlerIdx].catchAllAddr)
        assertFailsWith<IllegalArgumentException> { t.addHandler(0, 4, -1, 999) }
        assertFailsWith<IllegalArgumentException> { TryListBuilder().addHandler(4, 4, 1, 0) }
    }

    @Test
    fun instructionRangeChecksHaveMessages() {
        val e = assertFailsWith<IllegalArgumentException> { Ins12x(Opcode.MOVE, 16, 0) }
        assertTrue(e.message!!.contains("v16"))
        assertFailsWith<IllegalArgumentException> { code { constHigh16(0, 0x12345) } }
        assertFailsWith<IllegalArgumentException> { code { constWideHigh16(0, 1L) } }
        assertFailsWith<IllegalArgumentException> { Ins21c(Opcode.CONST_STRING, 0, 0x10000) }
            .also { assertTrue(it.message!!.contains("jumbo")) }
    }

    @Test
    fun wideRegistersOfLongShifts() {
        // величина сдвига — int, её «вторая половина» не используется
        assertEquals(listOf(0, 1, 2, 3, 4), Ins23x(Opcode.SHL_LONG, 0, 2, 4).getWideRegisters())
        assertEquals(listOf(0, 1, 2), Ins12x(Opcode.SHL_LONG_2ADDR, 0, 2).getWideRegisters())
    }
}
