package com.tim.c2d.translate

import com.tim.c2d.analysis.VType

/** Категория регистра для выбора `move` / `move-wide` / `move-object`. */
enum class RegKind(val width: Int) {
    NARROW(1), WIDE(2), OBJECT(1);

    companion object {
        fun of(t: VType): RegKind = when {
            t.isWide -> WIDE
            t.isReference -> OBJECT
            t.kind == VType.INT || t.kind == VType.FLOAT -> NARROW
            else -> throw IllegalStateException("значение типа $t нельзя держать в регистре")
        }
    }
}

/**
 * Раскладка регистров метода:
 *
 * ```
 * [временные | блокировка | частые локалы | стек операндов | прочие локалы | параметры (ins)]
 * ```
 *
 * Слово стека `d` живёт в регистре `stack(d)`; long/double занимают пару соседних регистров
 * так же, как пару слов на стеке JVM. Параметры DEX всегда в последних регистрах кадра, и
 * локалы-параметры JVM по умолчанию отображаются прямо на них. Но в большом кадре эти регистры
 * старше 15 и не помещаются в 4-битные поля (`iget`, `invoke` 35c, `if-*`): каждое обращение
 * стоило бы копии во временный регистр. Поэтому частые параметры (`this` и т. п.) в прологе
 * копируются в младшие регистры, а остальные локалы идут по убыванию числа обращений. В большом
 * кадре самые частые локалы встают даже ниже стека, пока первые слова стека остаются в 4-битных
 * полях (байткод R8 держит значения глубоко в стеке, а частый локал читается чаще глубоких слов).
 * Регистр блокировки synchronized-метода — сразу после временных (8-битные поля monitor-enter/exit).
 *
 * Слоты, между которыми хоть раз лежит long/double, образуют цепочку соседних регистров
 * (пара `(l, l+1)` должна оставаться парой). Цепочка, заходящая с параметров на прочие локалы
 * (long/double пишется в последний слот параметров), копируется целиком.
 *
 * Временные регистры `v0..v3` или `v0..v5` (две-три пары) нужны, только когда номера регистров
 * не помещаются в поля инструкций (4/8 бит) или при перестановке значений по кругу (`swap`, `dup_x1` …).
 */
class RegisterLayout private constructor(
    /** Число временных регистров (0, 4 или 6). */
    val temps: Int,
    /** Регистр объекта блокировки synchronized-метода или `-1`. */
    val lockReg: Int,
    private val stackBase: Int,
    /** Слов в области стека (max_stack + запас под служебные значения). */
    val stackSize: Int,
    /** Регистр каждого слота локалов. */
    private val localRegs: IntArray,
    /** Первый регистр параметров. */
    val insBase: Int,
    val ins: Int,
    val maxLocals: Int,
    /** Всего регистров в кадре. */
    val total: Int
) {
    fun stack(word: Int): Int = stackBase + word

    /** Регистр слота; у мёртвого слота ([LocalSlots.dead]) регистра нет. */
    fun local(index: Int): Int {
        val r = localRegs[index]
        check(r >= 0) { "у слота $index (его никто не читает) нет регистра" }
        return r
    }

    /** У слота есть регистр (у мёртвого — нет). */
    fun hasRegister(slot: Int): Boolean = localRegs[slot] >= 0

    /** Параметр в слоте [slot] копируется в прологе из `insBase + slot` в [local]. */
    fun isCopiedParam(slot: Int): Boolean = slot < ins && localRegs[slot] != insBase + slot

    /** Регистр принадлежит области стека операндов. */
    fun isStackReg(reg: Int): Boolean = reg >= stackBase && reg < stackBase + stackSize

    companion object {
        /** Запас слов стека: `multianewarray` держит рядом массив измерений и класс элемента. */
        private const val STACK_SLACK = 2
        private const val TEMPS = 6
        private const val TEMPS_SMALL = 4

        /** Предел 4-битных полей: копировать параметр имеет смысл, только если копия ляжет ниже. */
        private const val LOW = 16

        /** Параметр копируется, если к нему не меньше стольких обращений. */
        private const val COPY_MIN_USES = 2

        /** Слов стека, которые остаются ниже v16, когда частые локалы ставятся перед стеком. */
        private const val SHALLOW_STACK = 4

        /** Перед стеком — только локалы с таким числом обращений на регистр. */
        private const val FRONT_MIN_USES = 2

        /** Раскладка по [LocalSlots]: мёртвым слотам регистр не нужен, копии живут в регистре источника. */
        fun create(
            maxStack: Int,
            maxLocals: Int,
            ins: Int,
            synchronized: Boolean,
            needsCycleTemps: Boolean,
            slots: LocalSlots
        ): RegisterLayout {
            require(maxLocals >= ins) { "max_locals ($maxLocals) меньше, чем занимают параметры ($ins)" }
            val uses = slots.uses
            val wideAt = slots.wideAt
            fun placed(s: Int) = s < ins || (!slots.dead[s] && slots.aliasOf[s] < 0)
            val stackSize = maxStack + STACK_SLACK
            val lock = if (synchronized) 1 else 0
            val withoutTemps = lock + stackSize + (0 until maxLocals).count { placed(it) }
            // Поля в 8 бит нужны временные, только если кадр больше 256 регистров: тогда у инструкции
            // бывает три таких операнда (aput, 23x). Иначе — не больше двух 4-битных (две пары).
            val temps = when {
                needsCycleTemps || withoutTemps + TEMPS > 256 -> TEMPS
                withoutTemps > LOW -> TEMPS_SMALL
                else -> 0
            }

            // Цепочки [начало, длина]: слот e присоединяется, если в e-1 начинается long/double.
            val chains = ArrayList<IntArray>()
            var l = 0
            while (l < maxLocals) {
                var e = l + 1
                while (e < maxLocals && wideAt[e - 1]) e++
                chains.add(intArrayOf(l, e - l))
                l = e
            }
            fun heat(c: IntArray): Int {
                var h = 0
                for (s in c[0] until c[0] + c[1]) h += uses[s]
                return h
            }
            // Частые — раньше (по обращениям на регистр), при равенстве — по номеру слота.
            val order = chains.sortedWith(
                compareByDescending<IntArray> { heat(it).toDouble() / it[1] }.thenBy { it[0] }
            )

            val localRegs = IntArray(maxLocals) { -1 }
            // Копии параметров увеличивают кадр: не у кадров, которым и так тесно в 8-битных полях.
            val copyAllowed = temps > 0 && temps + withoutTemps + LOW <= 256
            val done = BooleanArray(chains.size)
            val chainIndex = HashMap<IntArray, Int>().also { m -> chains.forEachIndexed { k, c -> m[c] = k } }
            // Регистр блокировки synchronized-метода — сразу после временных: monitor-enter/exit и
            // const-class держат его в 8-битном поле, а кадр бывает больше 256 регистров.
            val lockReg = if (synchronized) temps else -1
            var next = temps + lock
            /** Разместить цепочку с [next], если можно; параметр — только частый и если копия ляжет ниже [limit]. */
            fun place(c: IntArray, limit: Int): Boolean {
                val start = c[0]
                val len = c[1]
                if ((start until start + len).none { placed(it) }) return true
                val param = start < ins
                val crossing = param && start + len > ins
                if (param && !crossing && (!copyAllowed || heat(c) < COPY_MIN_USES)) return false
                if (next + len > limit && !crossing && (param || limit < Int.MAX_VALUE)) return false
                for (k in 0 until len) localRegs[start + k] = next + k
                next += len
                return true
            }
            // В большом кадре самые частые локалы — ниже стека, пока первые слова стека остаются
            // в 4-битных полях: частый локал читается чаще глубоких слов стека.
            if (temps > 0) {
                val front = LOW - minOf(stackSize, SHALLOW_STACK)
                for (c in order) {
                    if (heat(c) < FRONT_MIN_USES * c[1]) break
                    val k = chainIndex.getValue(c)
                    if (!done[k] && next + c[1] <= front && place(c, front)) done[k] = true
                }
            }
            val stackBase = next
            next += stackSize
            for (c in order) {
                val k = chainIndex.getValue(c)
                if (done[k]) continue
                val start = c[0]
                val param = start < ins && start + c[1] <= ins
                // Параметр остаётся на месте, если копия не ляжет в младшие регистры или он редок.
                if (place(c, if (param) LOW else Int.MAX_VALUE)) done[k] = true
            }
            val insBase = next
            val total = next + ins
            require(total <= 0xFFFF) { "методу нужно $total регистров, а в DEX их не больше 65535" }
            for (s in 0 until ins) if (localRegs[s] < 0) localRegs[s] = insBase + s
            for (s in 0 until maxLocals) if (slots.aliasOf[s] >= 0) localRegs[s] = localRegs[slots.aliasOf[s]]
            return RegisterLayout(
                temps = temps,
                lockReg = lockReg,
                stackBase = stackBase,
                stackSize = stackSize,
                localRegs = localRegs,
                insBase = insBase,
                ins = ins,
                maxLocals = maxLocals,
                total = total
            )
        }
    }
}
