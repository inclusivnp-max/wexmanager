package com.tim.d2c.types

/**
 * Значения регистров метода (узлы SSA без копирования через move).
 *
 * Каждый узел, кроме φ, принадлежит одному регистру и задан одной инструкцией, поэтому
 * «записать значение в слот JVM» всегда означает запись в конкретном месте кода.
 * Узлы [UNDEF] (регистр не задан) и [HIGH] (вторая половина long/double) — служебные.
 */
internal class Nodes {

    enum class Kind { UNDEF, HIGH, PARAM, CONST, MOVE, TYPED, INIT, PHI }

    private var kinds = arrayOfNulls<Kind>(64)
    /** Инструкция-определение (для PHI — блок). */
    private var instrs = IntArray(64)
    private var regs = IntArray(64)
    /** MOVE/INIT — исходный узел; TYPED — входной узел (массив для aget); PARAM — номер параметра. */
    private var sources = IntArray(64)
    private var bits = LongArray(64)
    private var wides = BooleanArray(64)
    /** Метка неинициализированного объекта: индекс new-instance, [THIS_TAG] или [NO_TAG]. */
    private var tags = IntArray(64)
    private var operands = arrayOfNulls<IntArray>(64)
    private var operandCounts = IntArray(64)

    var size = 0
        private set

    private val byDef = LongIntMap()

    init {
        add(Kind.UNDEF, -1, -1) // UNDEF = 0
        add(Kind.HIGH, -1, -1)  // HIGH = 1
    }

    fun kind(n: Int): Kind = kinds[n]!!
    fun instr(n: Int): Int = instrs[n]
    fun reg(n: Int): Int = regs[n]
    fun source(n: Int): Int = sources[n]
    fun bits(n: Int): Long = bits[n]
    fun isWideConst(n: Int): Boolean = wides[n]
    fun tag(n: Int): Int = tags[n]
    fun wideHint(n: Int): Boolean = wides[n]

    fun operandCount(n: Int): Int = operandCounts[n]
    fun operand(n: Int, k: Int): Int = operands[n]!![k]

    private fun add(kind: Kind, instr: Int, reg: Int): Int {
        if (size == kinds.size) grow()
        val id = size++
        kinds[id] = kind
        instrs[id] = instr
        regs[id] = reg
        tags[id] = NO_TAG
        return id
    }

    private fun grow() {
        val cap = kinds.size * 2
        kinds = kinds.copyOf(cap)
        instrs = instrs.copyOf(cap)
        regs = regs.copyOf(cap)
        sources = sources.copyOf(cap)
        bits = bits.copyOf(cap)
        wides = wides.copyOf(cap)
        tags = tags.copyOf(cap)
        operands = operands.copyOf(cap)
        operandCounts = operandCounts.copyOf(cap)
    }

    /**
     * Ключ узла; умножение на золотое сечение перемешивает биты — иначе у узлов одного регистра на
     * разных инструкциях младшие биты хеша совпадают и корзины HashMap вырождаются в деревья.
     */
    private fun key(kind: Kind, at: Int, reg: Int): Long =
        ((at.toLong() shl 24) or (reg.toLong() shl 4) or kind.ordinal.toLong()) * -0x61c8864680b583ebL

    /** Узел, заданный инструкцией [at] для регистра [reg] (создаётся один раз — повторные проходы получают тот же). */
    fun def(kind: Kind, at: Int, reg: Int): Int {
        val k = key(kind, at, reg)
        val found = byDef[k]
        if (found >= 0) return found
        val id = add(kind, at, reg)
        byDef.put(k, id)
        return id
    }

    fun param(index: Int, reg: Int, tag: Int): Int {
        val id = def(Kind.PARAM, -1, reg)
        sources[id] = index
        tags[id] = tag
        return id
    }

    fun const(at: Int, reg: Int, value: Long, wide: Boolean): Int {
        val id = def(Kind.CONST, at, reg)
        bits[id] = value
        wides[id] = wide
        return id
    }

    fun move(at: Int, reg: Int, source: Int): Int {
        val id = def(Kind.MOVE, at, reg)
        sources[id] = source
        tags[id] = tags[source]
        return id
    }

    fun typed(at: Int, reg: Int, input: Int = UNDEF, tag: Int = NO_TAG): Int {
        val id = def(Kind.TYPED, at, reg)
        sources[id] = input
        tags[id] = tag
        return id
    }

    fun init(at: Int, reg: Int, source: Int): Int {
        val id = def(Kind.INIT, at, reg)
        sources[id] = source
        return id
    }

    /** φ блока [block] для регистра [reg] (создаётся при первом обращении). */
    fun phi(block: Int, reg: Int): Int {
        val k = key(Kind.PHI, block, reg)
        val found = byDef[k]
        if (found >= 0) return found
        val id = add(Kind.PHI, block, reg)
        byDef.put(k, id)
        operands[id] = IntArray(2)
        tags[id] = UNSET_TAG
        return id
    }

    fun existingPhi(block: Int, reg: Int): Int = byDef[key(Kind.PHI, block, reg)]

    fun markWide(phi: Int) { wides[phi] = true }

    /** Добавляет операнд φ; `true` — изменилась метка неинициализированного объекта. */
    fun addOperand(phi: Int, value: Int): Boolean {
        val arr = operands[phi]!!
        val count = operandCounts[phi]
        for (k in 0 until count) if (arr[k] == value) return false
        val target = if (count == arr.size) arr.copyOf(count * 2).also { operands[phi] = it } else arr
        target[count] = value
        operandCounts[phi] = count + 1
        val old = tags[phi]
        val t = tags[value]
        val nt = if (old == UNSET_TAG) t else if (old == t) old else NO_TAG
        tags[phi] = nt
        return old != UNSET_TAG && nt != old
    }

    companion object {
        const val UNDEF = 0
        const val HIGH = 1
        const val NO_TAG = -1
        const val THIS_TAG = -2
        private const val UNSET_TAG = -3
    }
}
