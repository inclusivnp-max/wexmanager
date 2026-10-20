package com.tim.d2c.cfg

import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.FillArrayData
import com.tim.dasm.model.Ins10t
import com.tim.dasm.model.Ins20t
import com.tim.dasm.model.Ins21t
import com.tim.dasm.model.Ins22t
import com.tim.dasm.model.Ins30t
import com.tim.dasm.model.Instruction
import com.tim.dasm.model.PackedSwitchData
import com.tim.dasm.model.SparseSwitchData
import com.tim.dex.data.Opcode
import com.tim.dex.model.CodeItem

/** Обработчик исключения: адрес (индекс инструкции) и тип (`null` — catch-all). */
internal class Handler(val target: Int, val catchType: String?)

/**
 * Код метода DEX как граф: инструкции (по адресам), базовые блоки, переходы,
 * покрытие try и списки обработчиков. Индексы — номера инструкций в [insns].
 */
internal class MethodCode(item: CodeItem, val typeName: (Int) -> String) {

    private val decoded = DexDisassembler().decode(item.insns)

    val insns: List<Instruction> = decoded.instructions
    val count: Int = insns.size
    val registers: Int = item.registersSize
    val ins: Int = item.insSize

    /** Адрес инструкции в code units. */
    fun address(i: Int): Int = decoded.offsets[i]

    /** Индекс инструкции по адресу; -1 — адрес не на границе инструкции. */
    fun indexAt(address: Int): Int = decoded.indexAt(address)

    fun isPayload(i: Int): Boolean = insns[i].let { it is PackedSwitchData || it is SparseSwitchData || it is FillArrayData }

    /** Для каждой инструкции — индекс списка обработчиков ([handlers]) или -1. */
    val coverage = IntArray(count) { -1 }

    /** Списки обработчиков по try_item (порядок = приоритет, catch-all последним). */
    val handlers: List<List<Handler>>

    /** Цель перехода (индекс) для goto/if; -1 — нет. */
    val jumpTarget = IntArray(count) { -1 }

    /** Цели switch (индексы) в порядке ключей; ключи — в [switchKeys]. */
    val switchTargets = arrayOfNulls<IntArray>(count)
    val switchKeys = arrayOfNulls<IntArray>(count)

    /** Payload fill-array-data для инструкции fill-array-data. */
    val fillData = arrayOfNulls<FillArrayData>(count)

    /** Начало базового блока. */
    val isLeader = BooleanArray(count)

    /** Инструкция — вход обработчика исключений. */
    val isHandlerEntry = BooleanArray(count)

    /** Инструкция — цель обычного перехода (goto/if/switch). */
    val isJumpTarget = BooleanArray(count)

    init {
        val tryHandlers = ArrayList<List<Handler>>(item.tries.size)
        for ((t, tryItem) in item.tries.withIndex()) {
            val ch = item.catchHandlers.getOrNull(tryItem.handlerIdx)
                ?: throw IllegalStateException("try #$t: нет списка обработчиков #${tryItem.handlerIdx}")
            val list = ArrayList<Handler>(ch.handlers.size + 1)
            for (h in ch.handlers) list.add(Handler(handlerIndex(h.addr), typeName(h.typeIdx)))
            if (ch.catchAllAddr >= 0) list.add(Handler(handlerIndex(ch.catchAllAddr), null))
            tryHandlers.add(list)
            val end = tryItem.startAddr + tryItem.insnCount
            var i = firstAtOrAfter(tryItem.startAddr)
            while (i < count && address(i) < end) coverage[i++] = t
        }
        handlers = tryHandlers

        for (i in 0 until count) {
            val insn = insns[i]
            val here = address(i)
            when {
                Insns.isGoto(insn.opcode) || Insns.isIf(insn.opcode) -> {
                    val off = when (insn) {
                        is Ins10t -> insn.offset
                        is Ins20t -> insn.offset
                        is Ins30t -> insn.offset
                        is Ins21t -> insn.offset
                        is Ins22t -> insn.offset
                        else -> throw error(i, "неожиданный формат перехода ${insn.format}")
                    }
                    jumpTarget[i] = target(i, here + off)
                }
                Insns.isSwitch(insn.opcode) -> {
                    val p = decoded.payloadIndex[i]
                    // Цели payload'а посчитаны от первого ссылающегося switch — пересчитываем от этого.
                    val shift = here - decoded.switchBase[p]
                    when (val payload = insns[p]) {
                        is PackedSwitchData -> {
                            switchKeys[i] = IntArray(payload.size) { payload.firstKey + it }
                            switchTargets[i] = IntArray(payload.size) { target(i, payload.targets[it] + shift) }
                        }
                        is SparseSwitchData -> {
                            switchKeys[i] = payload.keys.toIntArray()
                            switchTargets[i] = IntArray(payload.size) { target(i, payload.targets[it] + shift) }
                        }
                        else -> throw error(i, "switch ссылается не на switch-payload")
                    }
                }
                insn.opcode == Opcode.FILL_ARRAY_DATA -> {
                    fillData[i] = insns[decoded.payloadIndex[i]] as? FillArrayData
                        ?: throw error(i, "fill-array-data ссылается не на fill-array-data-payload")
                }
            }
        }

        if (count > 0) isLeader[0] = true
        for (i in 0 until count) {
            val op = insns[i].opcode
            if (jumpTarget[i] >= 0) { isLeader[jumpTarget[i]] = true; isJumpTarget[jumpTarget[i]] = true }
            switchTargets[i]?.forEach { isLeader[it] = true; isJumpTarget[it] = true }
            val ends = jumpTarget[i] >= 0 || switchTargets[i] != null || Insns.endsFlow(op)
            if (ends && i + 1 < count) isLeader[i + 1] = true
        }
        for (list in handlers) for (h in list) { isLeader[h.target] = true; isHandlerEntry[h.target] = true }
    }

    /** Первая инструкция с адресом не меньше [address]. */
    private fun firstAtOrAfter(address: Int): Int {
        val k = java.util.Arrays.binarySearch(decoded.offsets, address)
        return if (k >= 0) k else -k - 1
    }

    private fun handlerIndex(address: Int): Int {
        val i = indexAt(address)
        if (i < 0) throw IllegalStateException("Обработчик исключения по адресу 0x${Integer.toHexString(address)} не на границе инструкции")
        return i
    }

    private fun target(i: Int, address: Int): Int {
        val t = indexAt(address)
        if (t < 0 || isPayload(t)) throw error(i, "переход на адрес 0x${Integer.toHexString(address)} вне инструкций")
        return t
    }

    /** Может ли управление перейти к следующей по адресу инструкции. */
    fun fallsThrough(i: Int): Boolean = !Insns.endsFlow(insns[i].opcode)

    fun error(i: Int, message: String) =
        IllegalStateException("адрес 0x${Integer.toHexString(address(i))} (${insns[i].opcode.mnemonic}): $message")
}
