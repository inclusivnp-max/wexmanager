package com.tim.dsmali.disasm

import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.FillArrayData
import com.tim.dasm.model.Ins10x
import com.tim.dasm.model.Ins31t
import com.tim.dasm.model.PackedSwitchData
import com.tim.dasm.model.SparseSwitchData
import com.tim.dex.data.Opcode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DexDisassemblerTest {
    /** Байткод из 16-битных code units (little endian). */
    private fun code(vararg units: Int): ByteArray {
        val b = ByteArray(units.size * 2)
        units.forEachIndexed { i, u ->
            b[2 * i] = (u and 0xFF).toByte()
            b[2 * i + 1] = ((u ushr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun `targets of each payload are resolved against its own switch`() {
        val insns = code(
            0x002b, 0x0008, 0x0000,          // 0: packed-switch v0, +8 -> payload @8
            0x002c, 0x000d, 0x0000,          // 3: sparse-switch v0, +13 -> payload @16
            0x000e,                          // 6: return-void
            0x0000,                          // 7: nop (выравнивание)
            0x0100, 0x0002, 0x0001, 0x0000,  // 8: packed payload, size 2, first_key 1,
            0x0006, 0x0000, 0x0007, 0x0000,  //    targets +6, +7 (от switch'а @0)
            0x0200, 0x0001, 0x0005, 0x0000,  // 16: sparse payload, size 1, key 5,
            0x0003, 0x0000                   //    target +3 (от switch'а @3)
        )
        val code = DexDisassembler().decode(insns)
        assertEquals(listOf(0, 3, 6, 7, 8, 16), code.offsets.toList())
        val packed = code.instructions[4] as PackedSwitchData
        assertEquals(listOf(6, 7), packed.targets)
        assertEquals(1, packed.firstKey)
        val sparse = code.instructions[5] as SparseSwitchData
        assertEquals(listOf(6), sparse.targets)
        assertEquals(listOf(5), sparse.keys)
        assertEquals(4, code.payloadIndex[0])
        assertEquals(5, code.payloadIndex[1])
        assertEquals(0, code.switchBase[4])
        assertEquals(3, code.switchBase[5])
        assertEquals(22, code.codeUnits)
    }

    @Test
    fun `payload is not padded to 4 bytes after its data`() {
        val insns = code(
            0x0026, 0x0004, 0x0000,          // 0: fill-array-data v0, +4
            0x0000,                          // 3: nop
            0x0300, 0x0001, 0x0003, 0x0000,  // 4: array payload: width 1, 3 элемента
            0x0201, 0x0003,                  //    01 02 03 + 1 байт выравнивания до code unit
            0x000e                           // 10: return-void
        )
        val code = DexDisassembler().decode(insns)
        assertEquals(listOf(0, 3, 4, 10), code.offsets.toList())
        assertTrue(code.instructions[1] is Ins10x)
        val data = code.instructions[2] as FillArrayData
        assertEquals(listOf<Byte>(1, 2, 3), data.data.toList())
        assertEquals(Opcode.RETURN_VOID, code.instructions[3].opcode)
        assertEquals(11, code.codeUnits)
    }

    @Test
    fun `shared payload is based on the first switch`() {
        val insns = code(
            0x012b, 0x000c, 0x0000,          // 0: packed-switch v1, +12
            0x012b, 0x0009, 0x0000,          // 3: packed-switch v1, +9 (тот же payload)
            0x1012, 0x000f, 0x0000, 0x2012, 0x000f, 0x0000,
            0x0100, 0x0001, 0xfffb, 0xffff, 0x0006, 0x0000
        )
        val code = DexDisassembler().decode(insns)
        assertEquals(8, code.payloadIndex[0])
        assertEquals(8, code.payloadIndex[1])
        assertEquals(0, code.switchBase[8])
        assertEquals(listOf(6), (code.instructions[8] as PackedSwitchData).targets)
        assertEquals(-5, (code.instructions[8] as PackedSwitchData).firstKey)
        assertEquals(9, (code.instructions[1] as Ins31t).offset)
    }

    @Test
    fun `switch may point to the padding nop before its payload`() {
        // 0: packed-switch v1, +3 -> nop @3, payload @4 (target +3 -> nop)
        val insns = code(0x012b, 0x0003, 0x0000, 0x0000, 0x0100, 0x0001, 0x0000, 0x0000, 0x0003, 0x0000)
        val code = DexDisassembler().decode(insns)
        assertEquals(2, code.payloadIndex[0])
        assertEquals(0, code.switchBase[2])
        assertEquals(listOf(3), (code.instructions[2] as PackedSwitchData).targets)
    }

    @Test
    fun `unknown opcode fails with its address`() {
        val e = assertFailsWith<IllegalStateException> { DexDisassembler().decode(code(0x000e, 0x003e)) }
        assertTrue(e.message!!.contains("0x1"), e.message)
        assertTrue(e.message!!.contains("0x3e"), e.message)
        assertFailsWith<IllegalStateException> { DexDisassembler().decode(code(0x0400)) }
    }

    @Test
    fun `malformed code fails loudly`() {
        // обрезанная инструкция
        assertFailsWith<IllegalStateException> { DexDisassembler().decode(code(0x0013)) }
        // payload по нечётному адресу
        assertFailsWith<IllegalStateException> { DexDisassembler().decode(code(0x000e, 0x0100, 0x0000, 0, 0)) }
        // packed-switch-payload, на который не ссылается ни один switch
        assertFailsWith<IllegalStateException> { DexDisassembler().decode(code(0x0100, 0x0000, 0x0000, 0x0000)) }
        // switch ссылается не на payload
        assertFailsWith<IllegalStateException> { DexDisassembler().decode(code(0x002b, 0x0000, 0x0000)) }
        // нечётный размер байткода
        assertFailsWith<IllegalStateException> { DexDisassembler().decode(ByteArray(3)) }
    }

    @Test
    fun `wide literals and invoke registers are decoded`() {
        val insns = code(
            0x0019, 0x4024,                                  // const-wide/high16 v0, 0x4024
            0x0118, 0x0000, 0x0000, 0x0000, 0x8000,          // const-wide v1, Long.MIN_VALUE
            0x5071, 0x0007, 0x4321                           // invoke-static {v1, v2, v3, v4, v0}, method@7
        )
        val list = DexDisassembler().disassemble(insns)
        assertEquals(3, list.size)
        assertEquals(Long.MIN_VALUE, (list[1] as com.tim.dasm.model.Ins51l).value)
        val invoke = list[2] as com.tim.dasm.model.Ins35c
        assertEquals(listOf(1, 2, 3, 4, 0), invoke.getRegisters())
        assertEquals(7, invoke.index)
    }
}
