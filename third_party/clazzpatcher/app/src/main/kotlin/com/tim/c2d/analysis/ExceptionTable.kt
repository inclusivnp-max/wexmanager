package com.tim.c2d.analysis

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.DecodedCode
import com.tim.clazz.model.attributes.Code

/** Обработчик исключений: индекс первой инструкции и тип (`null` — catch-all). */
class Handler(val handlerIndex: Int, val handlerPc: Int, val catchType: String?)

/**
 * Таблица исключений метода, разрезанная на отрезки инструкций с одинаковым набором обработчиков.
 *
 * Набор строится по правилам JVM (побеждает первая подходящая запись), поэтому из него
 * исключено недостижимое: всё после catch-all и повторы одного типа. Одинаковые наборы
 * получают один идентификатор — по нему переводчик находит границы try-диапазонов DEX.
 */
class ExceptionTable(code: DecodedCode, entries: List<Code.ExceptionTableEntry>, pool: ConstantPoolView) {

    /** Наборы обработчиков по идентификатору; `0` — пустой набор. */
    val handlerSets: List<List<Handler>>

    /** Идентификатор набора обработчиков для каждой инструкции. */
    val handlerSetOfInsn: IntArray

    val isEmpty: Boolean get() = handlerSets.size == 1

    init {
        val n = code.size
        val starts = IntArray(entries.size)
        val ends = IntArray(entries.size)
        val handlers = arrayOfNulls<Handler>(entries.size)
        for ((i, e) in entries.withIndex()) {
            val s = code.indexOf(e.startPc)
            val end = if (e.endPc == code.codeLength) n else code.indexOf(e.endPc)
            val h = code.indexOf(e.handlerPc)
            if (s < 0 || end < 0 || h < 0 || e.startPc >= e.endPc) {
                throw IllegalArgumentException(
                    "запись таблицы исключений #$i (${e.startPc}..${e.endPc} → ${e.handlerPc}) указывает не на границы инструкций"
                )
            }
            starts[i] = s
            ends[i] = end
            handlers[i] = Handler(h, e.handlerPc, if (e.catchType == 0) null else pool.classDescriptor(e.catchType))
        }

        val sets = ArrayList<List<Handler>>()
        sets.add(emptyList())
        val ids = HashMap<List<Pair<Int, String?>>, Int>()
        ids[emptyList()] = 0
        handlerSetOfInsn = IntArray(n)

        if (entries.isNotEmpty()) {
            // Набор покрывающих записей меняется только на границах диапазонов.
            val bounds = (starts.asList() + ends.asList()).toSortedSet().toIntArray()
            var b = 0
            var current = 0
            for (i in 0 until n) {
                if (b < bounds.size && bounds[b] == i) {
                    b++
                    val chosen = select(i, starts, ends, handlers)
                    val key = chosen.map { it.handlerIndex to it.catchType }
                    current = ids.getOrPut(key) { sets.add(chosen); sets.size - 1 }
                }
                handlerSetOfInsn[i] = current
            }
        }
        handlerSets = sets
    }

    /** Действующие обработчики инструкции [insn] в порядке приоритета. */
    private fun select(insn: Int, starts: IntArray, ends: IntArray, handlers: Array<Handler?>): List<Handler> {
        val chosen = ArrayList<Handler>(2)
        var types: HashSet<String>? = null
        for (e in starts.indices) {
            if (insn < starts[e] || insn >= ends[e]) continue
            val h = handlers[e]!!
            val t = h.catchType
            if (t == null) { chosen.add(h); break }
            val seen = types ?: HashSet<String>().also { types = it }
            if (seen.add(t)) chosen.add(h)
        }
        return chosen
    }
}
