package com.tim.d2c.translate

import com.tim.d2c.cfg.MethodCode
import com.tim.d2c.model.DexView
import com.tim.dex.model.DebugInfoItem
import com.tim.dex.model.DebugOpcode

/** Строка исходника, начинающаяся с инструкции [index]. */
internal class LineEntry(val index: Int, val line: Int)

/** Локальная переменная регистра [reg] на инструкциях [start, end) ([end] = count — до конца). */
internal class LocalEntry(val start: Int, val end: Int, val reg: Int, val name: String, val type: String, val signature: String?)

/**
 * Разбор debug_info_item (конечный автомат DBG_*): строки и диапазоны локальных переменных
 * в индексах инструкций метода.
 */
internal class DebugInfo(val lines: List<LineEntry>, val locals: List<LocalEntry>, val parameterNames: List<String?>) {

    companion object {
        fun read(item: DebugInfoItem, code: MethodCode, view: DexView): DebugInfo {
            val lines = ArrayList<LineEntry>()
            val locals = ArrayList<LocalEntry>()
            class Open(val start: Int, val name: String?, val type: String?, val sig: String?)
            val open = HashMap<Int, Open>()
            val last = HashMap<Int, Open>()
            var address = 0
            var line = item.lineStart

            fun index(addr: Int): Int {
                var lo = 0
                var hi = code.count
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (code.address(mid) < addr) lo = mid + 1 else hi = mid
                }
                return lo
            }
            fun str(idx: Int): String? = if (idx < 0) null else view.string(idx)
            fun typ(idx: Int): String? = if (idx < 0) null else view.type(idx)
            fun close(reg: Int, at: Int) {
                val o = open.remove(reg) ?: return
                if (o.name != null && o.type != null && at > o.start) {
                    locals.add(LocalEntry(o.start, at, reg, o.name, o.type, o.sig))
                }
            }
            fun start(reg: Int, o: Open) {
                close(reg, o.start)
                open[reg] = o
                last[reg] = o
            }

            loop@ for (op in item.opcodes) {
                when (op) {
                    is DebugOpcode.EndSequence -> break@loop
                    is DebugOpcode.AdvancePc -> address += op.addressDelta
                    is DebugOpcode.AdvanceLine -> line += op.lineDelta
                    is DebugOpcode.StartLocal -> start(op.registerNum, Open(index(address), str(op.nameIdx), typ(op.typeIdx), null))
                    is DebugOpcode.StartLocalExtended ->
                        start(op.registerNum, Open(index(address), str(op.nameIdx), typ(op.typeIdx), str(op.sigIdx)))
                    is DebugOpcode.EndLocal -> close(op.registerNum, index(address))
                    is DebugOpcode.RestartLocal -> {
                        val prev = last[op.registerNum] ?: continue@loop
                        start(op.registerNum, Open(index(address), prev.name, prev.type, prev.sig))
                    }
                    is DebugOpcode.SpecialOpcode -> {
                        line += op.lineDelta
                        address += op.addressDelta
                        val at = index(address)
                        if (at < code.count) lines.add(LineEntry(at, line))
                    }
                    is DebugOpcode.SetPrologueEnd, is DebugOpcode.SetEpilogueBegin, is DebugOpcode.SetFile -> {
                        // В JVM нет аналогов: граница пролога/эпилога и смена файла не переносятся.
                    }
                }
            }
            for (reg in open.keys.toList()) close(reg, code.count)
            return DebugInfo(lines, locals, item.parameterNames.map { str(it) })
        }
    }
}
