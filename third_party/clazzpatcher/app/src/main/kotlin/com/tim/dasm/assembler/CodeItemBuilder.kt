package com.tim.dasm.assembler

import com.tim.dasm.model.*
import com.tim.dex.data.Opcode
import com.tim.dex.model.CatchHandler
import com.tim.dex.model.CodeItem
import com.tim.dex.model.TryItem

object CodeItemBuilder {

    /**
     * @param registers число регистров; `-1` — вычислить по использованным (не меньше ins_size).
     *                  Явно заданное число проверяется: регистр за его пределами — ошибка.
     */
    fun build(
        instructions: List<Instruction>,
        registers: Int,
        paramTypes: List<String>,
        isStatic: Boolean,
        debugInfoIdx: Int,
        tries: List<TryItem>,
        catchHandlers: List<CatchHandler>
    ): CodeItem {
        val insSize = insSize(paramTypes, isStatic)
        val registersSize = resolveRegisters(registers, insSize, instructions)
        val bytecode = DexAssembler.assemble(instructions)
        return CodeItem(
            registersSize = registersSize,
            insSize = insSize,
            outsSize = outsSize(instructions),
            triesSize = tries.size,
            debugInfoIdx = debugInfoIdx,
            insnsSize = bytecode.size / 2,
            insns = bytecode,
            tries = tries,
            catchHandlers = catchHandlers
        )
    }

    /** Новый код для существующего метода (ins_size и прочее — из старого code_item). */
    fun rebuild(
        old: CodeItem,
        instructions: List<Instruction>,
        registers: Int,
        debugInfoIdx: Int,
        tries: List<TryItem>,
        catchHandlers: List<CatchHandler>
    ): CodeItem {
        val registersSize = resolveRegisters(registers, old.insSize, instructions)
        val bytecode = DexAssembler.assemble(instructions)
        return old.copy(
            registersSize = registersSize,
            outsSize = outsSize(instructions),
            triesSize = tries.size,
            debugInfoIdx = debugInfoIdx,
            insnsSize = bytecode.size / 2,
            insns = bytecode,
            tries = tries,
            catchHandlers = catchHandlers
        )
    }

    fun insSize(paramTypes: List<String>, isStatic: Boolean): Int {
        var size = if (isStatic) 0 else 1
        for (t in paramTypes) size += if (t == "J" || t == "D") 2 else 1
        return size
    }

    /** outs_size — максимум числа регистров-аргументов среди invoke-инструкций. */
    fun outsSize(instructions: List<Instruction>): Int {
        var outs = 0
        for (ins in instructions) {
            val n = when (ins) {
                is Ins35c -> if (ins.opcode == Opcode.FILLED_NEW_ARRAY) 0 else ins.regCount
                is Ins3rc -> if (ins.opcode == Opcode.FILLED_NEW_ARRAY_RANGE) 0 else ins.regCount
                is Ins45cc -> ins.regCount
                is Ins4rcc -> ins.regCount
                else -> 0
            }
            if (n > outs) outs = n
        }
        return outs
    }

    private fun resolveRegisters(registers: Int, insSize: Int, instructions: List<Instruction>): Int {
        var used = 0
        var culprit: Instruction? = null
        for (ins in instructions) {
            for (r in ins.getWideRegisters()) {
                if (r + 1 > used) { used = r + 1; culprit = ins }
            }
        }
        if (registers < 0) return maxOf(used, insSize)
        require(registers >= insSize) { "Регистров ($registers) меньше, чем занимают параметры ($insSize)" }
        require(used <= registers) {
            "Инструкция \"$culprit\" использует регистр v${used - 1}, а в методе всего $registers регистров"
        }
        require(registers <= 0xFFFF) { "Слишком много регистров: $registers" }
        return registers
    }
}
