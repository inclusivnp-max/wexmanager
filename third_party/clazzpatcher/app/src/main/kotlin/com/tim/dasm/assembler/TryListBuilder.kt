package com.tim.dasm.assembler

import com.tim.dex.model.CatchHandler
import com.tim.dex.model.TryItem
import com.tim.dex.model.TypeAddrPair

/**
 * Построение непересекающихся try_item из произвольных (в т.ч. вложенных) диапазонов,
 * как `TryListBuilder` из dexlib2: диапазоны режутся по границам, обработчик добавляется
 * в конец списка каждого покрытого отрезка, «дыры» заполняются новыми отрезками.
 *
 * Отличие от dexlib2: повторный обработчик того же типа не отбрасывается (DEX это
 * допускает, ART выбирает первый) — так сохраняется точное содержимое исходного DEX.
 * Два разных catch-all на одном отрезке — ошибка.
 */
class TryListBuilder {

    private class Block(var start: Int, var end: Int, val handlers: MutableList<TypeAddrPair>, var catchAll: Int)

    private val blocks = ArrayList<Block>() // отсортированы по start, не пересекаются

    /**
     * @param typeIdx type_idx исключения или `-1` для catch-all.
     */
    fun addHandler(start: Int, end: Int, typeIdx: Int, handlerAddr: Int) {
        require(end > start) { "Пустой или обратный диапазон try: $start..$end" }
        split(start)
        split(end)
        var pos = start
        var i = blocks.indexOfFirst { it.end > start }.let { if (it < 0) blocks.size else it }
        while (pos < end) {
            val b = blocks.getOrNull(i)
            if (b == null || b.start >= end) {
                val gapEnd = if (b == null) end else minOf(end, b.start)
                val nb = Block(pos, gapEnd, ArrayList(), -1)
                blocks.add(i, nb)
                addTo(nb, typeIdx, handlerAddr)
                pos = gapEnd
                i++
                continue
            }
            if (b.start > pos) {
                val nb = Block(pos, b.start, ArrayList(), -1)
                blocks.add(i, nb)
                addTo(nb, typeIdx, handlerAddr)
                pos = b.start
                i++
                continue
            }
            addTo(b, typeIdx, handlerAddr)
            pos = b.end
            i++
        }
    }

    private fun addTo(b: Block, typeIdx: Int, handlerAddr: Int) {
        if (typeIdx < 0) {
            if (b.catchAll >= 0) {
                require(b.catchAll == handlerAddr) {
                    "Пересекающиеся catch-all с разными обработчиками (${b.catchAll}, $handlerAddr) на ${b.start}..${b.end}"
                }
                return
            }
            b.catchAll = handlerAddr
        } else {
            b.handlers.add(TypeAddrPair(typeIdx, handlerAddr))
        }
    }

    /** Разрезать блок, содержащий [addr] строго внутри. */
    private fun split(addr: Int) {
        for (i in blocks.indices) {
            val b = blocks[i]
            if (addr > b.start && addr < b.end) {
                val tail = Block(addr, b.end, ArrayList(b.handlers), b.catchAll)
                b.end = addr
                blocks.add(i + 1, tail)
                return
            }
            if (b.start >= addr) return
        }
    }

    fun isEmpty(): Boolean = blocks.isEmpty()

    /** try_item'ы и обработчики (одинаковые списки обработчиков разделяются). */
    fun build(): Pair<List<TryItem>, List<CatchHandler>> {
        val tries = ArrayList<TryItem>(blocks.size)
        val handlers = ArrayList<CatchHandler>()
        val handlerIndex = HashMap<CatchHandler, Int>()
        for (b in blocks) {
            val h = CatchHandler(ArrayList(b.handlers), b.catchAll)
            val idx = handlerIndex.getOrPut(h) { handlers.add(h); handlers.size - 1 }
            tries.add(TryItem(b.start, b.end - b.start, idx))
        }
        return tries to handlers
    }
}
