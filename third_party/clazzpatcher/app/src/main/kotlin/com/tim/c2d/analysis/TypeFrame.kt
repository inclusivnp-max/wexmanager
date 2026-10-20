package com.tim.c2d.analysis

/**
 * Состояние перед инструкцией: типы локалов и слов стека операндов.
 * Слово стека с индексом `d` соответствует глубине `d` (0 — дно).
 */
class TypeFrame(
    @JvmField val locals: Array<VType>,
    @JvmField val stack: Array<VType>,
    @JvmField var sp: Int
) {
    fun copy(): TypeFrame = TypeFrame(locals.copyOf(), stack.copyOf(), sp)

    // ---- стек ----

    fun push(t: VType) {
        if (sp >= stack.size) throw IllegalArgumentException("переполнение стека операндов (max_stack = ${stack.size})")
        stack[sp++] = t
    }

    /** Значение по дескриптору: long/double — два слова. */
    fun pushValue(t: VType) {
        push(t)
        if (t.isWide) push(VType.highOf(t))
    }

    fun pop(): VType {
        if (sp == 0) throw IllegalArgumentException("стек операндов пуст")
        return stack[--sp]
    }

    fun pop(words: Int) {
        if (sp < words) throw IllegalArgumentException("на стеке $sp слов, а нужно $words")
        sp -= words
    }

    /** Снять значение (1 или 2 слова по типу вершины) и вернуть тип его первого слова. */
    fun popValue(): VType {
        val top = pop()
        return if (top.isHigh) pop() else top
    }

    /** Тип слова на глубине [fromTop] от вершины (0 — вершина). */
    fun peek(fromTop: Int = 0): VType {
        val i = sp - 1 - fromTop
        if (i < 0) throw IllegalArgumentException("стек операндов пуст")
        return stack[i]
    }

    // ---- локалы ----

    fun local(index: Int): VType {
        if (index !in locals.indices) throw IllegalArgumentException("локал $index вне max_locals = ${locals.size}")
        return locals[index]
    }

    /** Запись значения [t] в локал [index] (для long/double занимает два слота). */
    fun setLocal(index: Int, t: VType) {
        val w = t.width
        if (index < 0 || index + w > locals.size) {
            throw IllegalArgumentException("локал $index вне max_locals = ${locals.size}")
        }
        // Запись в вершину пары разрушает пару слева, запись в начало пары — её вершину.
        if (index > 0 && locals[index - 1].isWide) locals[index - 1] = VType.Top
        val last = index + w - 1
        if (locals[last].isWide && last + 1 < locals.size) locals[last + 1] = VType.Top
        locals[index] = t
        if (w == 2) locals[index + 1] = VType.highOf(t)
    }

    /** Заменить все вхождения типа [from] (неинициализированный объект) на [to]. */
    fun replaceAll(from: VType, to: VType) {
        for (i in locals.indices) if (locals[i] == from) locals[i] = to
        for (i in 0 until sp) if (stack[i] == from) stack[i] = to
    }

    // ---- слияние ----

    /** Слить [other] в этот кадр; `true`, если что-то изменилось. */
    fun mergeFrom(other: TypeFrame): Boolean {
        if (sp != other.sp) {
            throw IllegalArgumentException("разная глубина стека в точке схождения: $sp и ${other.sp}")
        }
        var changed = mergeLocals(other.locals)
        for (i in 0 until sp) {
            val m = VType.merge(stack[i], other.stack[i])
            if (m != stack[i]) { stack[i] = m; changed = true }
        }
        return changed
    }

    fun mergeLocals(other: Array<VType>): Boolean {
        var changed = false
        for (i in locals.indices) {
            val m = VType.merge(locals[i], other[i])
            if (m != locals[i]) { locals[i] = m; changed = true }
        }
        return changed
    }
}
