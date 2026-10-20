package com.tim.dasm.assembler

import com.tim.dasm.dsl.InstructionsDsl
import com.tim.dasm.model.*
import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

/**
 * Результат раскладки кода метода.
 *
 * @property instructions финальные инструкции (без удалённых выравнивающих nop).
 * @property offsets      адрес (в code units) каждой исходной позиции `0..n`
 *                        (позиция `n` — конец кода); по ним считаются метки и отладочные события.
 * @property codeUnits    размер кода в code units.
 */
class ResolvedCode(
    val instructions: List<Instruction>,
    val offsets: IntArray,
    val codeUnits: Int,
    private val labels: Map<String, Int>
) {
    fun labelOffset(label: String): Int {
        val idx = labels[label] ?: throw IllegalStateException("Неизвестная метка: $label")
        return offsets[idx]
    }
}

/**
 * Раскладка кода: разрешение меток ветвлений, привязка switch/fill-array-data к payload'ам,
 * выравнивание payload'ов и расширение goto — по правилам smali (dexlib2):
 *
 * - switch ссылается на метку payload'а; nop'ы после метки пропускаются;
 *   один payload не может использоваться двумя switch;
 * - payload по нечётному адресу выравнивается: предшествующий явный `nop` удаляется,
 *   иначе вставляется выравнивание (как вставленный smali `nop`);
 * - `goto`/`goto/16`, чьё смещение не помещается, расширяются до `goto/16`/`goto/32`.
 */
object CodeResolver {

    fun resolve(code: InstructionsDsl): ResolvedCode {
        val insns = code.instructions
        val n = insns.size
        val labels = code.labels
        val branches = code.branches

        val sizes = IntArray(n) { insns[it].byteSize / 2 }
        val payload = BooleanArray(n) { insns[it].isPayload() }
        val removed = BooleanArray(n)

        // Цели ветвлений (индексы инструкций)
        val targets = IntArray(branches.size)
        val ops = arrayOfNulls<Opcode>(branches.size)
        val referrer = HashMap<Int, Int>() // индекс switch-payload -> индекс ветвления
        for ((bi, b) in branches.withIndex()) {
            var t = labels[b.label] ?: throw IllegalStateException("${b.opcode.mnemonic}: неизвестная метка ${b.label}")
            ops[bi] = b.opcode
            if (b.opcode.format == InsnFormat.FORMAT_31t) {
                while (t < n && isNop(insns[t])) t++
                val target = insns.getOrNull(t)
                val ok = when (b.opcode) {
                    Opcode.PACKED_SWITCH -> target is PackedSwitchData
                    Opcode.SPARSE_SWITCH -> target is SparseSwitchData
                    else -> target is FillArrayData
                }
                if (!ok) {
                    throw IllegalStateException(
                        "${b.opcode.mnemonic}: метка ${b.label} указывает не на ${payloadName(b.opcode)}"
                    )
                }
                if (b.opcode != Opcode.FILL_ARRAY_DATA && referrer.put(t, bi) != null) {
                    throw IllegalStateException("На payload по метке ${b.label} ссылаются несколько switch-инструкций")
                }
            }
            targets[bi] = t
        }

        // Итеративная раскладка: удаление лишних nop перед payload'ами и расширение goto
        val offsets = IntArray(n + 1)
        while (true) {
            var pos = 0
            var changed = false
            for (i in 0 until n) {
                if (removed[i]) { offsets[i] = pos; continue }
                if (payload[i] && (pos and 1) != 0) {
                    val prev = previousLive(removed, i)
                    if (prev >= 0 && isNop(insns[prev])) {
                        removed[prev] = true
                        changed = true
                        break
                    }
                    pos++
                }
                offsets[i] = pos
                pos += sizes[i]
            }
            if (changed) continue
            offsets[n] = pos

            for (bi in branches.indices) {
                val op = ops[bi]!!
                if (op != Opcode.GOTO && op != Opcode.GOTO_16) continue
                val idx = branches[bi].index
                val delta = offsets[targets[bi]] - offsets[idx]
                if (op == Opcode.GOTO && delta !in -128..127) {
                    val wide = if (delta in Short.MIN_VALUE..Short.MAX_VALUE) Opcode.GOTO_16 else Opcode.GOTO_32
                    ops[bi] = wide
                    sizes[idx] = wide.format.sizeUnits
                    changed = true
                } else if (op == Opcode.GOTO_16 && delta !in Short.MIN_VALUE..Short.MAX_VALUE) {
                    ops[bi] = Opcode.GOTO_32
                    sizes[idx] = Opcode.GOTO_32.format.sizeUnits
                    changed = true
                }
            }
            if (!changed) break
        }

        val out = ArrayList<Instruction>(insns)

        for ((bi, b) in branches.withIndex()) {
            val op = ops[bi]!!
            val delta = offsets[targets[bi]] - offsets[b.index]
            out[b.index] = try {
                when (op.format) {
                    InsnFormat.FORMAT_10t -> Ins10t(op, delta)
                    InsnFormat.FORMAT_20t -> Ins20t(op, delta)
                    InsnFormat.FORMAT_30t -> Ins30t(op, delta)
                    InsnFormat.FORMAT_21t -> Ins21t(op, b.regA, delta)
                    InsnFormat.FORMAT_22t -> Ins22t(op, b.regA, b.regB, delta)
                    InsnFormat.FORMAT_31t -> Ins31t(op, b.regA, delta)
                    else -> throw IllegalStateException("${op.mnemonic}: не ветвление")
                }
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("${op.mnemonic} ${b.label}: ${e.message}", e)
            }
        }

        for (def in code.switchPayloads) {
            val base = if (def.baseLabel != null) {
                offsets[labels[def.baseLabel] ?: throw IllegalStateException("Неизвестная метка switch: ${def.baseLabel}")]
            } else {
                val bi = referrer[def.index] ?: throw IllegalStateException(
                    "${if (def.packed) "packed" else "sparse"}-switch-payload не используется ни одной switch-инструкцией"
                )
                offsets[branches[bi].index]
            }
            val rel = ArrayList<Int>(def.targetLabels.size)
            for (label in def.targetLabels) {
                val t = labels[label] ?: throw IllegalStateException("switch-payload: неизвестная метка $label")
                rel.add(offsets[t] - base)
            }
            out[def.index] = if (def.packed) {
                PackedSwitchData(rel.size, def.firstKey, rel)
            } else {
                SparseSwitchData(rel.size, def.keys!!.toList(), rel)
            }
        }

        val finalList = if (removed.any { it }) out.filterIndexed { i, _ -> !removed[i] } else out
        return ResolvedCode(finalList, offsets, offsets[n], labels)
    }

    private fun isNop(ins: Instruction) = ins is Ins10x && ins.opcode == Opcode.NOP

    private fun previousLive(removed: BooleanArray, i: Int): Int {
        var j = i - 1
        while (j >= 0 && removed[j]) j--
        return j
    }

    private fun payloadName(op: Opcode) = when (op) {
        Opcode.PACKED_SWITCH -> "packed-switch-payload"
        Opcode.SPARSE_SWITCH -> "sparse-switch-payload"
        else -> "fill-array-data-payload"
    }
}
