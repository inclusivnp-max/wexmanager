package com.tim.dasm.assembler

import com.tim.dasm.DexAsm
import com.tim.dex.data.DebugConstants
import com.tim.dex.model.DebugInfoItem
import com.tim.dex.model.DebugOpcode

/**
 * Билдер [DebugInfoItem].
 *
 * События записываются в порядке вызовов и привязываются к позиции в списке инструкций
 * ([positionProvider] — индекс следующей инструкции). Адреса вычисляются в [build] по
 * окончательной раскладке кода, поэтому расширение goto и выравнивание payload'ов
 * не сдвигают отладочную информацию.
 *
 * Кодирование повторяет dexlib2: каждый `.line` порождает запись позиций (special opcode),
 * даже если строка и адрес не изменились; `line_start` — номер первой строки (или 0).
 */
class DebugInfoBuilder(
    private val asm: DexAsm,
    private val positionProvider: () -> Int = { 0 }
) {
    private sealed class Event(val position: Int) {
        class Line(position: Int, val line: Int) : Event(position)
        class StartLocal(position: Int, val reg: Int, val name: String?, val type: String?, val signature: String?) : Event(position)
        class EndLocal(position: Int, val reg: Int) : Event(position)
        class RestartLocal(position: Int, val reg: Int) : Event(position)
        class PrologueEnd(position: Int) : Event(position)
        class EpilogueBegin(position: Int) : Event(position)
        class SetFile(position: Int, val name: String?) : Event(position)
    }

    private var explicitLineStart: Int? = null
    private val paramNames = ArrayList<String?>()
    private val events = ArrayList<Event>()

    /** Явный `line_start`; по умолчанию — номер первой строки. */
    fun lineStart(line: Int) {
        require(line >= 0) { "line_start не может быть отрицательным: $line" }
        explicitLineStart = line
    }

    /** Имя очередного параметра (`null` — без имени). */
    fun paramName(name: String?) {
        paramNames.add(name)
    }

    fun paramNames(vararg names: String?) = names.forEach { paramName(it) }

    /** Задать имя параметра по индексу (0 — первый объявленный параметр, без `this`). */
    fun setParamName(index: Int, name: String?) {
        require(index >= 0) { "Некорректный индекс параметра: $index" }
        while (paramNames.size <= index) paramNames.add(null)
        paramNames[index] = name
    }

    fun setFile(name: String?) { events.add(Event.SetFile(positionProvider(), name)) }

    fun prologueEnd() { events.add(Event.PrologueEnd(positionProvider())) }

    fun epilogueBegin() { events.add(Event.EpilogueBegin(positionProvider())) }

    fun lineNumber(line: Int) { events.add(Event.Line(positionProvider(), line)) }

    /** DBG_START_LOCAL(_EXTENDED). Разрешён и на уже живом регистре (неявно завершает прежнюю переменную). */
    fun startLocal(reg: Int, name: String?, type: String?, signature: String? = null) {
        require(reg >= 0) { "Некорректный регистр v$reg" }
        events.add(Event.StartLocal(positionProvider(), reg, name, type, signature))
    }

    /** DBG_END_LOCAL на любом регистре (параметры живы неявно). */
    fun endLocal(reg: Int) {
        require(reg >= 0) { "Некорректный регистр v$reg" }
        events.add(Event.EndLocal(positionProvider(), reg))
    }

    fun restartLocal(reg: Int) {
        require(reg >= 0) { "Некорректный регистр v$reg" }
        events.add(Event.RestartLocal(positionProvider(), reg))
    }

    fun hasParamNames(): Boolean = paramNames.isNotEmpty()

    /** Нужен ли debug_info_item: есть события или хотя бы одно имя параметра. */
    fun hasData(): Boolean = events.isNotEmpty() || paramNames.any { it != null }

    /**
     * @param offsets        адреса позиций инструкций (см. [ResolvedCode.offsets]).
     * @param parameterCount число объявленных параметров метода (`this` не считается);
     *                       `parameters_size` всегда равен ему, безымянные — NO_INDEX.
     */
    fun build(offsets: IntArray, parameterCount: Int): DebugInfoItem {
        require(paramNames.size <= parameterCount) {
            "Имён параметров (${paramNames.size}) больше, чем параметров ($parameterCount)"
        }
        val paramNameIdxs = ArrayList<Int>(parameterCount)
        for (i in 0 until parameterCount) paramNameIdxs.add(asm.strings.addNullable(paramNames.getOrNull(i)))

        val firstLine = (events.firstOrNull { it is Event.Line } as Event.Line?)?.line
        val lineStart = explicitLineStart ?: if (firstLine != null && firstLine >= 0) firstLine else 0

        val opcodes = ArrayList<DebugOpcode>(events.size * 2 + 1)
        var line = lineStart
        var address = 0

        fun advancePc(target: Int) {
            if (target > address) {
                opcodes.add(DebugOpcode.AdvancePc(target - address))
                address = target
            }
        }

        for (e in events) {
            val addr = offsets[e.position]
            if (addr < address) {
                throw IllegalStateException("Адреса отладочной информации убывают: $address -> $addr")
            }
            when (e) {
                is Event.Line -> {
                    var lineDelta = e.line - line
                    var addrDelta = addr - address
                    if (lineDelta < DebugConstants.DBG_LINE_BASE ||
                        lineDelta > DebugConstants.DBG_LINE_BASE + DebugConstants.DBG_LINE_RANGE - 1) {
                        opcodes.add(DebugOpcode.AdvanceLine(lineDelta))
                        line = e.line
                        lineDelta = 0
                    }
                    if ((lineDelta < 2 && addrDelta > 16) || (lineDelta > 1 && addrDelta > 15)) {
                        advancePc(addr)
                        addrDelta = 0
                    }
                    val special = DebugConstants.DBG_FIRST_SPECIAL +
                        (lineDelta - DebugConstants.DBG_LINE_BASE) + addrDelta * DebugConstants.DBG_LINE_RANGE
                    opcodes.add(DebugOpcode.SpecialOpcode(special, lineDelta, addrDelta))
                    line += lineDelta
                    address += addrDelta
                }
                is Event.StartLocal -> {
                    advancePc(addr)
                    val nameIdx = asm.strings.addNullable(e.name)
                    val typeIdx = asm.types.addNullable(e.type)
                    opcodes.add(
                        if (e.signature != null) {
                            DebugOpcode.StartLocalExtended(e.reg, nameIdx, typeIdx, asm.strings.add(e.signature))
                        } else {
                            DebugOpcode.StartLocal(e.reg, nameIdx, typeIdx)
                        }
                    )
                }
                is Event.EndLocal -> { advancePc(addr); opcodes.add(DebugOpcode.EndLocal(e.reg)) }
                is Event.RestartLocal -> { advancePc(addr); opcodes.add(DebugOpcode.RestartLocal(e.reg)) }
                is Event.PrologueEnd -> { advancePc(addr); opcodes.add(DebugOpcode.SetPrologueEnd) }
                is Event.EpilogueBegin -> { advancePc(addr); opcodes.add(DebugOpcode.SetEpilogueBegin) }
                is Event.SetFile -> { advancePc(addr); opcodes.add(DebugOpcode.SetFile(asm.strings.addNullable(e.name))) }
            }
        }
        opcodes.add(DebugOpcode.EndSequence)

        return DebugInfoItem(
            lineStart = lineStart,
            parametersSize = parameterCount,
            parameterNames = paramNameIdxs,
            opcodes = opcodes
        )
    }
}
