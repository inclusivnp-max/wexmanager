package com.tim.c2d.translate

import com.tim.dasm.dsl.InstructionsDsl

/**
 * Низкоуровневая запись кода: выбор формы `move`/`const` по номерам регистров и «подгонка»
 * операндов под разрядность поля инструкции через временные регистры.
 *
 * Временные регистры выдаются парами (`v0/v1`, `v2/v3`, `v4/v5`) и живут только в пределах
 * одной инструкции JVM — перед каждой вызывается [resetTemps].
 */
class DexCodeWriter(val code: InstructionsDsl, private val tempCount: Int) {

    private var nextTemp = 0

    /** Регистры, в которые копировались ссылки (move-object), — для [MethodTranslator] (блокировки). */
    val objectCopies = java.util.BitSet()

    fun resetTemps() { nextTemp = 0 }

    /** Свободных временных пар в пределах текущей инструкции JVM. */
    fun tempPairsLeft(): Int = (tempCount - nextTemp) / 2

    /** Временная пара (ниже v16) под значение на время одной инструкции JVM. */
    fun takeTemp(): Int = temp()

    private fun temp(): Int {
        if (nextTemp + 2 > tempCount) {
            throw IllegalStateException("не хватает временных регистров (их $tempCount)")
        }
        val t = nextTemp
        nextTemp += 2
        return t
    }

    // ---- перемещения ----

    fun move(dst: Int, src: Int, kind: RegKind) {
        if (dst == src) return
        val small = dst < 16 && src < 16
        val from16 = dst < 256
        when (kind) {
            RegKind.NARROW -> when {
                small -> code.move(dst, src)
                from16 -> code.moveFrom16(dst, src)
                else -> code.move16(dst, src)
            }
            RegKind.WIDE -> when {
                small -> code.moveWide(dst, src)
                from16 -> code.moveWideFrom16(dst, src)
                else -> code.moveWide16(dst, src)
            }
            RegKind.OBJECT -> when {
                small -> code.moveObject(dst, src).also { objectCopies.set(dst) }
                from16 -> code.moveObjectFrom16(dst, src).also { objectCopies.set(dst) }
                else -> code.moveObject16(dst, src).also { objectCopies.set(dst) }
            }
        }
    }

    /**
     * Параллельное присваивание `dst[i] ← src[i]`: источники читаются до записи приёмников.
     * Циклы (`swap`, `dup_x1` …) разрываются через временную пару.
     */
    fun parallelMove(moves: List<Move>) {
        val pending = ArrayList<Move>(moves.size)
        for (m in moves) if (m.dst != m.src) pending.add(m)
        while (pending.isNotEmpty()) {
            var progressed = false
            var i = 0
            while (i < pending.size) {
                val m = pending[i]
                if (pending.none { it !== m && overlaps(m.dst, m.kind.width, it.src, it.kind.width) }) {
                    move(m.dst, m.src, m.kind)
                    pending.removeAt(i)
                    progressed = true
                } else {
                    i++
                }
            }
            if (!progressed) {
                // Тупик: у каждого хода приёмник ещё читается. Источник хода Y, который читает приёмник
                // первого хода, копируется во временный, и все ходы с тем же источником читают копию.
                // Временные не бывают приёмниками, поэтому число «чтений из приёмников» строго убывает.
                val blocked = pending[0]
                val y = pending.first { it !== blocked && overlaps(blocked.dst, blocked.kind.width, it.src, it.kind.width) }
                val t = temp()
                move(t, y.src, y.kind)
                for (k in pending.indices) {
                    val z = pending[k]
                    if (z.src == y.src && z.kind.width == y.kind.width) pending[k] = Move(z.dst, t, z.kind)
                }
            }
        }
    }

    class Move(val dst: Int, val src: Int, val kind: RegKind)

    private fun overlaps(a: Int, aw: Int, b: Int, bw: Int): Boolean = a < b + bw && b < a + aw

    // ---- подгонка операндов ----

    /** Источник для поля шириной [bits] бит: сам регистр или его копия во временном. */
    fun src(reg: Int, kind: RegKind, bits: Int): Int {
        if (reg < (1 shl bits)) return reg
        val t = temp()
        move(t, reg, kind)
        return t
    }

    /** Приёмник для поля шириной [bits] бит; если это временный — после инструкции вызвать [commit]. */
    fun dst(reg: Int, bits: Int): Int = if (reg < (1 shl bits)) reg else temp()

    fun commit(reg: Int, written: Int, kind: RegKind) = move(reg, written, kind)

    // ---- константы ----

    fun constInt(reg: Int, value: Int) {
        val d = dst(reg, 8)
        when {
            d < 16 && value in -8..7 -> code.const4(d, value)
            value in -32768..32767 -> code.const16(d, value)
            value and 0xFFFF == 0 -> code.constHigh16(d, value)
            else -> code.const32(d, value)
        }
        commit(reg, d, RegKind.NARROW)
    }

    fun constWide(reg: Int, value: Long) {
        val d = dst(reg, 8)
        when {
            value in -32768L..32767L -> code.constWide16(d, value.toInt())
            value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> code.constWide32(d, value.toInt())
            value and 0xFFFFFFFFFFFFL == 0L -> code.constWideHigh16(d, value)
            else -> code.constWide(d, value)
        }
        commit(reg, d, RegKind.WIDE)
    }
}
