package com.tim.c2d.translate

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.LineNumberTable
import com.tim.clazz.model.attributes.LocalVariableTable
import com.tim.clazz.model.attributes.LocalVariableTypeTable

/**
 * Отладочные события метода (номера строк и локальные переменные), упорядоченные по адресу JVM.
 * На одном адресе сначала заканчиваются старые переменные, затем начинаются новые, затем строка.
 */
class DebugEvents private constructor(
    val events: List<Event>,
    /** Имена параметров по индексу параметра (без `this`). */
    val paramNames: Map<Int, String>
) {
    sealed class Event(val pc: Int, val rank: Int) {
        class EndLocal(pc: Int, val slot: Int) : Event(pc, 0)
        class StartLocal(pc: Int, val slot: Int, val name: String, val type: String, val signature: String?) : Event(pc, 1)
        class Line(pc: Int, val line: Int) : Event(pc, 2)
    }

    companion object {
        val NONE = DebugEvents(emptyList(), emptyMap())

        /**
         * @param paramSlots слот каждого параметра (без `this`), чтобы узнать имена параметров
         *                   из записей LocalVariableTable, начинающихся с pc 0.
         */
        fun of(code: Code, pool: ConstantPoolView, paramSlots: IntArray, isStatic: Boolean): DebugEvents {
            val events = ArrayList<Event>()
            val paramNames = HashMap<Int, String>()
            val signatures = HashMap<Long, String>()
            for (a in code.attributes) {
                if (a is LocalVariableTypeTable) {
                    for (e in a.entries) signatures[key(e.startPc, e.length, e.index)] = pool.utf8(e.signatureIndex)
                }
            }
            for (a in code.attributes) {
                when (a) {
                    is LineNumberTable -> for (e in a.entries) events.add(Event.Line(e.startPc, e.lineNumber))
                    is LocalVariableTable -> for (e in a.entries) {
                        val name = pool.utf8(e.nameIndex)
                        if (e.startPc == 0) {
                            if (!isStatic && e.index == 0) continue
                            val param = paramSlots.indexOf(e.index)
                            if (param >= 0) { paramNames[param] = name; continue }
                        }
                        val signature = signatures[key(e.startPc, e.length, e.index)]
                        events.add(Event.StartLocal(e.startPc, e.index, name, pool.utf8(e.descriptorIndex), signature))
                        val end = e.startPc + e.length
                        if (end < code.code.size) events.add(Event.EndLocal(end, e.index))
                    }
                    else -> {}
                }
            }
            events.sortWith(compareBy<Event>({ it.pc }, { it.rank }))
            return DebugEvents(events, paramNames)
        }

        private fun key(startPc: Int, length: Int, index: Int): Long =
            (startPc.toLong() shl 40) or (length.toLong() shl 20) or index.toLong()
    }
}
