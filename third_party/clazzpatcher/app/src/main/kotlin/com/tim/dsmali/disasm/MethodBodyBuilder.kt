package com.tim.dsmali.disasm

import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.*
import com.tim.dex.data.IndexPool
import com.tim.dex.data.Opcode
import com.tim.dex.model.CodeItem
import com.tim.dex.model.DebugOpcode
import com.tim.dsmali.SmaliFormatter
import com.tim.dsmali.data.SmaliDebugKind
import com.tim.dsmali.model.SmaliCatchDirective
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliLabel
import com.tim.dsmali.model.SmaliMethod

/**
 * Тело метода (инструкции, метки, отладочная информация, try/catch) в smali-модель.
 *
 * Порядок элементов совпадает с baksmali: по адресу — отладочные директивы (в порядке потока),
 * метки (по префиксу), инструкция, затем `:try_end_` после последней покрытой инструкции;
 * `.catch` — в [SmaliMethod.catches] в порядке try-блоков.
 */
internal class MethodBodyBuilder(
    private val names: DexNames,
    private val code: CodeItem,
    isStatic: Boolean,
    private val paramTypes: List<String>
) {
    private val decoded: DexDisassembler.Code = DexDisassembler().decode(code.insns)
    private val instructions = decoded.instructions
    private val offsets = decoded.offsets
    private val codeUnits = decoded.codeUnits

    /** Первый регистр параметров: регистры от него выводятся как `pN`. */
    private val firstParamReg: Int

    /** Битовые маски меток по адресу (биты — [LABEL_PREFIXES] в порядке сортировки baksmali). */
    private val labels = IntArray(codeUnits + 1)

    /** Имя `:try_end_` после инструкции с данным индексом. */
    private val tryEndAfter = arrayOfNulls<String>(instructions.size)

    /** Копии switch-payload'ов, на которые ссылается больше одного switch (как в baksmali — в конец метода). */
    private val extraPayloads = ArrayList<ExtraPayload>()
    private val switchPayloadAddress = IntArray(instructions.size) { -1 }

    private val sb = StringBuilder(64)

    private class ExtraPayload(val address: Int, val payload: Instruction, val shift: Int)

    init {
        var paramRegs = if (isStatic) 0 else 1
        for (t in paramTypes) paramRegs += if (SmaliFormatter.isWide(t)) 2 else 1
        if (paramRegs > code.registersSize) {
            throw IllegalStateException("Параметрам нужно $paramRegs регистров, а в методе всего ${code.registersSize}")
        }
        firstParamReg = code.registersSize - paramRegs
    }

    fun build(method: SmaliMethod) {
        collectBranchLabels()
        collectTries(method)
        val debug = decodeDebugInfo(method)

        val body = method.body
        var d = 0
        for (i in instructions.indices) {
            val addr = offsets[i]
            while (d < debug.size && debug[d].address <= addr) body.add(debug[d++].element)
            addLabels(body, addr)
            body.add(SmaliInsn(format(i)))
            tryEndAfter[i]?.let { body.add(SmaliLabel(it)) }
        }
        while (d < debug.size) body.add(debug[d++].element)
        for (extra in extraPayloads) {
            body.add(SmaliLabel(labelName(if (extra.payload is PackedSwitchData) PSWITCH_DATA else SSWITCH_DATA, extra.address)))
            body.add(SmaliInsn(formatPayload(extra.payload, extra.shift)))
        }
    }

    // ---- метки ----

    private fun collectBranchLabels() {
        var extraAddress = codeUnits
        for (i in instructions.indices) {
            val ins = instructions[i]
            val addr = offsets[i]
            when (ins) {
                is Ins10t -> addBranchLabel(GOTO, addr, addr + ins.offset)
                is Ins20t -> addBranchLabel(GOTO, addr, addr + ins.offset)
                is Ins30t -> addBranchLabel(GOTO, addr, addr + ins.offset)
                is Ins21t -> addBranchLabel(COND, addr, addr + ins.offset)
                is Ins22t -> addBranchLabel(COND, addr, addr + ins.offset)
                is Ins31t -> {
                    val target = addr + ins.offset
                    val payloadIdx = decoded.payloadIndex[i]
                    when (ins.opcode) {
                        Opcode.FILL_ARRAY_DATA -> labels[target] = labels[target] or ARRAY
                        else -> {
                            val kind = if (ins.opcode == Opcode.PACKED_SWITCH) PSWITCH_DATA else SSWITCH_DATA
                            val base = decoded.switchBase[payloadIdx]
                            if (base == addr) {
                                labels[target] = labels[target] or kind
                                switchPayloadAddress[i] = target
                            } else {
                                // Payload уже занят другим switch'ем: как baksmali, дописываем копию в конец.
                                val payload = instructions[payloadIdx]
                                extraPayloads.add(ExtraPayload(extraAddress, payload, addr - base))
                                switchPayloadAddress[i] = extraAddress
                                addSwitchTargetLabels(payload, addr - base)
                                extraAddress += payloadUnits(payload)
                            }
                        }
                    }
                }
                is PackedSwitchData -> addSwitchTargetLabels(ins, 0)
                is SparseSwitchData -> addSwitchTargetLabels(ins, 0)
                else -> {}
            }
        }
    }

    private fun addSwitchTargetLabels(payload: Instruction, shift: Int) {
        when (payload) {
            is PackedSwitchData -> for (t in payload.targets) addTargetLabel(PSWITCH, t + shift, "packed-switch")
            is SparseSwitchData -> for (t in payload.targets) addTargetLabel(SSWITCH, t + shift, "sparse-switch")
            else -> {}
        }
    }

    private fun payloadUnits(payload: Instruction): Int = when (payload) {
        is PackedSwitchData -> 4 + payload.size * 2
        is SparseSwitchData -> 2 + payload.size * 4
        else -> throw IllegalStateException("Не switch-payload: $payload")
    }

    private fun addBranchLabel(kind: Int, from: Int, target: Int) {
        addTargetLabel(kind, target, "переход с адреса 0x${Integer.toHexString(from)}")
    }

    private fun addTargetLabel(kind: Int, target: Int, what: String) {
        if (target < 0 || target >= codeUnits || decoded.indexAt(target) < 0) {
            throw IllegalStateException("$what: адрес 0x${Integer.toHexString(target)} не является началом инструкции")
        }
        labels[target] = labels[target] or kind
    }

    private fun addLabels(body: MutableList<in SmaliLabel>, addr: Int) {
        val bits = labels[addr]
        if (bits == 0) return
        for (k in LABEL_PREFIXES.indices) {
            if (bits and (1 shl k) != 0) body.add(SmaliLabel(labelName(1 shl k, addr)))
        }
    }

    private fun labelName(kind: Int, addr: Int): String {
        val s = StringBuilder(24).append(':').append(LABEL_PREFIXES[Integer.numberOfTrailingZeros(kind)])
        SmaliFormatter.appendUnsignedHex(s, addr.toLong())
        return s.toString()
    }

    private fun appendLabel(out: StringBuilder, kind: Int, addr: Int) {
        out.append(':').append(LABEL_PREFIXES[Integer.numberOfTrailingZeros(kind)])
        SmaliFormatter.appendUnsignedHex(out, addr.toLong())
    }

    // ---- try/catch ----

    private fun collectTries(method: SmaliMethod) {
        if (code.tries.isEmpty()) return
        for (t in code.tries) {
            val start = t.startAddr
            val end = start + t.insnCount
            if (t.insnCount <= 0 || start < 0 || end > codeUnits || decoded.indexAt(start) < 0) {
                throw IllegalStateException("Некорректный try-блок [0x${Integer.toHexString(start)}, 0x${Integer.toHexString(end)})")
            }
            val handler = code.catchHandlers.getOrNull(t.handlerIdx)
                ?: throw IllegalStateException("try-блок ссылается на отсутствующий обработчик #${t.handlerIdx}")
            labels[start] = labels[start] or TRY_START
            // Метка конца — после инструкции, покрывающей последний code unit блока (как в baksmali).
            var last = java.util.Arrays.binarySearch(offsets, end - 1)
            if (last < 0) last = -last - 2
            val endName = tryEndAfter[last] ?: labelName(TRY_END, end).also { tryEndAfter[last] = it }
            val startName = labelName(TRY_START, start)
            for (p in handler.handlers) {
                addTargetLabel(CATCH, p.addr, "обработчик исключения")
                method.catches.add(SmaliCatchDirective(names.type(p.typeIdx), startName, endName, labelName(CATCH, p.addr)))
            }
            if (handler.catchAllAddr != -1) {
                addTargetLabel(CATCHALL, handler.catchAllAddr, "обработчик catch-all")
                method.catches.add(SmaliCatchDirective(null, startName, endName, labelName(CATCHALL, handler.catchAllAddr)))
            }
        }
    }

    // ---- отладочная информация ----

    private class DebugEntry(val address: Int, val element: SmaliDebug)

    private fun decodeDebugInfo(method: SmaliMethod): List<DebugEntry> {
        if (code.debugInfoIdx < 0) return emptyList()
        val info = names.dex.debugInfoItems.getOrNull(code.debugInfoIdx)
            ?: throw IllegalStateException("Ссылка на отсутствующий debug_info #${code.debugInfoIdx}")

        val paramNames = info.parameterNames
        for (i in paramTypes.indices) {
            val idx = if (i < paramNames.size) paramNames[i] else -1
            method.paramNames.add(if (idx == -1) null else names.string(idx))
        }
        for (i in paramTypes.size until paramNames.size) {
            if (paramNames[i] != -1) {
                throw IllegalStateException("debug_info содержит имя для несуществующего параметра #$i (параметров ${paramTypes.size})")
            }
        }

        val out = ArrayList<DebugEntry>(info.opcodes.size)
        var address = 0
        var line = info.lineStart
        fun add(kind: SmaliDebugKind, text: String) {
            out.add(DebugEntry(address, SmaliDebug(kind, text)))
        }
        loop@ for (op in info.opcodes) {
            when (op) {
                is DebugOpcode.EndSequence -> break@loop
                is DebugOpcode.AdvancePc -> address += op.addressDelta
                is DebugOpcode.AdvanceLine -> line += op.lineDelta
                is DebugOpcode.StartLocal -> add(SmaliDebugKind.LOCAL, local(op.registerNum, op.nameIdx, op.typeIdx, -1))
                is DebugOpcode.StartLocalExtended ->
                    add(SmaliDebugKind.LOCAL, local(op.registerNum, op.nameIdx, op.typeIdx, op.sigIdx))
                is DebugOpcode.EndLocal -> add(SmaliDebugKind.END_LOCAL, withReg(".end local ", op.registerNum))
                is DebugOpcode.RestartLocal -> add(SmaliDebugKind.RESTART_LOCAL, withReg(".restart local ", op.registerNum))
                is DebugOpcode.SetPrologueEnd -> add(SmaliDebugKind.PROLOGUE, ".prologue")
                is DebugOpcode.SetEpilogueBegin -> add(SmaliDebugKind.EPILOGUE, ".epilogue")
                is DebugOpcode.SetFile -> add(SmaliDebugKind.SOURCE,
                    if (op.nameIdx == -1) ".source" else ".source " + names.quotedString(op.nameIdx))
                is DebugOpcode.SpecialOpcode -> {
                    address += op.addressDelta
                    line += op.lineDelta
                    add(SmaliDebugKind.LINE, ".line " + (line.toLong() and 0xFFFFFFFFL))
                }
            }
        }
        return out
    }

    private fun withReg(prefix: String, reg: Int): String {
        sb.setLength(0)
        sb.append(prefix)
        appendReg(sb, reg)
        return sb.toString()
    }

    private fun local(reg: Int, nameIdx: Int, typeIdx: Int, sigIdx: Int): String {
        sb.setLength(0)
        sb.append(".local ")
        appendReg(sb, reg)
        if (nameIdx != -1 || typeIdx != -1 || sigIdx != -1) {
            sb.append(", ")
            if (nameIdx != -1) sb.append(names.quotedString(nameIdx)) else sb.append("null")
            sb.append(':')
            if (typeIdx != -1) sb.append(names.typeText(typeIdx)) else sb.append('V')
            if (sigIdx != -1) sb.append(", ").append(names.quotedString(sigIdx))
        }
        return sb.toString()
    }

    // ---- инструкции ----

    private fun appendReg(out: StringBuilder, reg: Int) {
        if (reg >= firstParamReg) out.append('p').append(reg - firstParamReg) else out.append('v').append(reg)
    }

    private fun appendRange(out: StringBuilder, start: Int, count: Int) {
        if (count == 0) {
            out.append("{}")
            return
        }
        val last = start + count - 1
        if (start >= firstParamReg) {
            out.append("{p").append(start - firstParamReg).append(" .. p").append(last - firstParamReg).append('}')
        } else {
            out.append("{v").append(start).append(" .. v").append(last).append('}')
        }
    }

    private fun appendRef(out: StringBuilder, op: Opcode, index: Int) {
        when (op.indexPool) {
            IndexPool.STRING -> out.append(names.quotedString(index))
            IndexPool.TYPE -> out.append(names.typeText(index))
            IndexPool.FIELD -> out.append(names.fieldRef(index))
            IndexPool.METHOD -> out.append(names.methodRef(index))
            IndexPool.PROTO -> out.append(names.protoRef(index))
            IndexPool.CALL_SITE -> out.append(names.callSite(index))
            IndexPool.METHOD_HANDLE -> out.append(names.methodHandle(index))
            IndexPool.NONE -> throw IllegalStateException("${op.mnemonic}: нет пула индексов")
        }
    }

    private fun format(i: Int): String {
        val ins = instructions[i]
        if (ins.isPayload()) return formatPayload(ins, 0)
        val addr = offsets[i]
        val out = sb
        out.setLength(0)
        out.append(ins.opcode.mnemonic)
        when (ins) {
            is Ins10x -> {}
            is Ins12x -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB) }
            is Ins11n -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); SmaliFormatter.appendLiteral(out, ins.value.toLong()) }
            is Ins11x -> { out.append(' '); appendReg(out, ins.regA) }
            is Ins10t -> { out.append(' '); appendLabel(out, GOTO, addr + ins.offset) }
            is Ins20t -> { out.append(' '); appendLabel(out, GOTO, addr + ins.offset) }
            is Ins30t -> { out.append(' '); appendLabel(out, GOTO, addr + ins.offset) }
            is Ins22x -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB) }
            is Ins32x -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB) }
            is Ins21t -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); appendLabel(out, COND, addr + ins.offset) }
            is Ins21s -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); SmaliFormatter.appendLiteral(out, ins.value.toLong()) }
            is Ins21h -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", ")
                val value = if (ins.opcode == Opcode.CONST_WIDE_HIGH16) ins.value.toLong() shl 48 else (ins.value shl 16).toLong()
                SmaliFormatter.appendLiteral(out, value)
            }
            is Ins21c -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); appendRef(out, ins.opcode, ins.index) }
            is Ins23x -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB)
                out.append(", "); appendReg(out, ins.regC)
            }
            is Ins22b -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB)
                out.append(", "); SmaliFormatter.appendLiteral(out, ins.value.toLong())
            }
            is Ins22s -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB)
                out.append(", "); SmaliFormatter.appendLiteral(out, ins.value.toLong())
            }
            is Ins22t -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB)
                out.append(", "); appendLabel(out, COND, addr + ins.offset)
            }
            is Ins22c -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", "); appendReg(out, ins.regB)
                out.append(", "); appendRef(out, ins.opcode, ins.index)
            }
            is Ins31t -> {
                out.append(' '); appendReg(out, ins.regA); out.append(", ")
                when (ins.opcode) {
                    Opcode.FILL_ARRAY_DATA -> appendLabel(out, ARRAY, addr + ins.offset)
                    Opcode.PACKED_SWITCH -> appendLabel(out, PSWITCH_DATA, switchPayloadAddress[i])
                    else -> appendLabel(out, SSWITCH_DATA, switchPayloadAddress[i])
                }
            }
            is Ins31i -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); SmaliFormatter.appendLiteral(out, ins.value.toLong()) }
            is Ins31c -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); appendRef(out, ins.opcode, ins.index) }
            is Ins35c -> {
                out.append(' ')
                appendRegList(out, ins.regCount, ins.regC, ins.regD, ins.regA, ins.regB, ins.regG)
                out.append(", ")
                appendRef(out, ins.opcode, ins.index)
            }
            is Ins3rc -> { out.append(' '); appendRange(out, ins.regStart, ins.regCount); out.append(", "); appendRef(out, ins.opcode, ins.index) }
            is Ins45cc -> {
                out.append(' ')
                appendRegList(out, ins.regCount, ins.regC, ins.regD, ins.regA, ins.regB, ins.regG)
                out.append(", ").append(names.methodRef(ins.methodIdx)).append(", ").append(names.protoRef(ins.protoIdx))
            }
            is Ins4rcc -> {
                out.append(' '); appendRange(out, ins.regStart, ins.regCount)
                out.append(", ").append(names.methodRef(ins.methodIdx)).append(", ").append(names.protoRef(ins.protoIdx))
            }
            is Ins51l -> { out.append(' '); appendReg(out, ins.regA); out.append(", "); SmaliFormatter.appendLiteral(out, ins.value) }
            else -> throw IllegalStateException("Неподдерживаемая инструкция ${ins.name} по адресу 0x${Integer.toHexString(addr)}")
        }
        return out.toString()
    }

    private fun appendRegList(out: StringBuilder, count: Int, c: Int, d: Int, e: Int, f: Int, g: Int) {
        out.append('{')
        if (count > 0) appendReg(out, c)
        if (count > 1) { out.append(", "); appendReg(out, d) }
        if (count > 2) { out.append(", "); appendReg(out, e) }
        if (count > 3) { out.append(", "); appendReg(out, f) }
        if (count > 4) { out.append(", "); appendReg(out, g) }
        out.append('}')
    }

    /** Payload; [shift] — сдвиг targets для копии, привязанной к другому switch'у. */
    private fun formatPayload(ins: Instruction, shift: Int): String {
        val out = StringBuilder(64)
        when (ins) {
            is PackedSwitchData -> {
                out.append(".packed-switch ")
                SmaliFormatter.appendIntegral(out, ins.firstKey.toLong(), null)
                for (t in ins.targets) {
                    out.append("\n    ")
                    appendLabel(out, PSWITCH, t + shift)
                }
                out.append("\n.end packed-switch")
            }
            is SparseSwitchData -> {
                out.append(".sparse-switch")
                for (k in 0 until ins.size) {
                    out.append("\n    ")
                    SmaliFormatter.appendIntegral(out, ins.keys[k].toLong(), null)
                    out.append(" -> ")
                    appendLabel(out, SSWITCH, ins.targets[k] + shift)
                }
                out.append("\n.end sparse-switch")
            }
            is FillArrayData -> {
                out.append(".array-data ").append(ins.elementSize)
                val data = ins.data
                val width = ins.elementSize
                for (k in 0 until ins.size) {
                    out.append("\n    ")
                    var v = 0L
                    for (j in width - 1 downTo 0) v = (v shl 8) or (data[k * width + j].toLong() and 0xFF)
                    when (width) {
                        1 -> { SmaliFormatter.appendLiteral(out, v.toByte().toLong()); out.append('t') }
                        2 -> { SmaliFormatter.appendLiteral(out, v.toShort().toLong()); out.append('s') }
                        4 -> SmaliFormatter.appendLiteral(out, v.toInt().toLong())
                        else -> SmaliFormatter.appendLiteral(out, v)
                    }
                }
                out.append("\n.end array-data")
            }
            else -> throw IllegalStateException("Не payload: $ins")
        }
        return out.toString()
    }

    companion object {
        // Префиксы меток baksmali в порядке их сортировки при совпадении адреса.
        private val LABEL_PREFIXES = arrayOf(
            "array_", "catch_", "catchall_", "cond_", "goto_", "pswitch_", "pswitch_data_",
            "sswitch_", "sswitch_data_", "try_start_", "try_end_"
        )
        private const val ARRAY = 1
        private const val CATCH = 1 shl 1
        private const val CATCHALL = 1 shl 2
        private const val COND = 1 shl 3
        private const val GOTO = 1 shl 4
        private const val PSWITCH = 1 shl 5
        private const val PSWITCH_DATA = 1 shl 6
        private const val SSWITCH = 1 shl 7
        private const val SSWITCH_DATA = 1 shl 8
        private const val TRY_START = 1 shl 9
        private const val TRY_END = 1 shl 10
    }
}
