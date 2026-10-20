package com.tim.d2c.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Синтетические конструкторы-переходники для вызовов `<init>`, которые допускает ART, но не JVM.
 *
 * R8 встраивает тривиальные конструкторы, после чего в DEX встречается:
 * 1. `new-instance C` + `invoke-direct A.<init>(D)`, где A — суперкласс C (JVM требует ровно C.<init>);
 * 2. в конструкторе C вызов `A.<init>(D)` на this, где A — не C и не прямой суперкласс C.
 *
 * Каждый класс P на пути от X (C в случае 1, прямой суперкласс C в случае 2) до класса
 * непосредственно под A получает `public synthetic <init>(D…, M × n)V`, который передаёт
 * параметры следующему переходнику (или A.<init>(D)). Промежуточные конструкторы не выполняются —
 * ровно как в исходном DEX. Число n — расстояние от P до A по цепочке суперклассов, поэтому
 * переходники к разным A не совпадают; если такой конструктор уже объявлен, n растёт на [SPREAD].
 * Маркер M — `Void`, а если D сам кончается на `Void` — `Void[]` ([marker]): иначе переходники
 * `A1.<init>(Void)` на расстоянии 1 и `A2.<init>()` на расстоянии 2 получили бы один дескриптор.
 * Всё детерминировано: место вызова знает итоговый дескриптор ещё в первой фазе перевода.
 */
internal class ConstructorBridges(private val index: ClassIndex) {

    /** Переходник класса: цель A и параметры D исходного `A.<init>(D)`. */
    data class Target(val ancestor: String, val params: List<String>) {
        val key: String get() = ancestor + params.joinToString("", "(", ")")
    }

    /** Итог планирования вызова. */
    sealed class Plan {
        /** Вызвать `owner.<init>(descriptor)`, дописав [nulls] аргументов `null` (маркер [marker]). */
        class Call(val owner: String, val descriptor: String, val nulls: Int) : Plan()
        /** Переходник невозможен: класс цепочки не из переводимых DEX или A — не предок X. */
        class Impossible(val reason: String) : Plan()
    }

    private val pending = ConcurrentHashMap<String, MutableSet<Target>>()

    /**
     * Вызов `ancestor.<init>(params)` на объекте, который должен инициализировать класс [receiver]:
     * путь переходников от [receiver] до [ancestor]. [record] — запомнить нужные переходники.
     */
    fun plan(receiver: String, ancestor: String, params: List<String>, record: Boolean): Plan {
        val path = ArrayList<String>(4)
        var cur: String = receiver
        while (cur != ancestor) {
            if (!index.contains(cur)) {
                return Plan.Impossible("класс $cur цепочки от $receiver до $ancestor не из переводимых DEX — переходник в него не добавить")
            }
            if (index.isInterface(cur)) return Plan.Impossible("$cur в цепочке суперклассов $receiver — интерфейс")
            if (path.size >= MAX_DEPTH) return Plan.Impossible("слишком длинная цепочка суперклассов от $receiver")
            path.add(cur)
            cur = index.superOf(cur)
                ?: return Plan.Impossible("$ancestor не является суперклассом $receiver")
        }
        if (path.isEmpty()) return Plan.Impossible("переходник не нужен: $receiver и есть $ancestor")
        if (record) {
            val t = Target(ancestor, params)
            for (p in path) pending.getOrPut(p) { ConcurrentHashMap.newKeySet() }.add(t)
        }
        val n = voids(receiver, path.size, t = Target(ancestor, params))
        return Plan.Call(receiver, descriptor(params, n), n)
    }

    /**
     * Класс, чей конструктор `<init>(params)V` выполнит ART для ссылки `owner.<init>`: разрешение
     * метода идёт от [owner] вверх по суперклассам. Класс вне переводимых DEX считается объявляющим
     * (его конструкторы неизвестны) — так же, как и сам [owner], если цепочка оборвана.
     */
    fun declaringConstructor(owner: String, params: List<String>): String {
        val desc = descriptor(params, 0)
        var cur: String = owner
        var guard = 0
        while (index.contains(cur) && guard++ < MAX_DEPTH) {
            // Интерфейс в цепочке суперклассов (некорректная иерархия: ART бросит ICCE/VerifyError) — не разбираем.
            if (index.isInterface(cur)) return owner
            if (desc in index.constructorDescriptors(cur)) return cur
            cur = index.superOf(cur) ?: return owner
        }
        return cur
    }

    /** Переходники, которые нужно добавить: класс → цели в детерминированном порядке. */
    fun requests(): Map<String, List<Target>> =
        pending.entries.sortedBy { it.key }.associate { (type, set) -> type to set.sortedBy { it.key } }

    /** Число аргументов-маркеров у переходника класса [type] к цели [t] ([distance] — шагов до A). */
    fun voids(type: String, distance: Int, t: Target): Int {
        val existing = index.constructorDescriptors(type)
        var n = distance
        while (descriptor(t.params, n) in existing) n += SPREAD
        return n
    }

    /** Шагов от [type] до [ancestor] по цепочке суперклассов (класс под A — 1). */
    fun distance(type: String, ancestor: String): Int {
        var d = 0
        var cur: String? = type
        while (cur != null && cur != ancestor) {
            d++
            cur = index.superOf(cur)
        }
        check(cur == ancestor) { "$ancestor не является суперклассом $type" }
        return d
    }

    companion object {
        const val VOID = "Ljava/lang/Void;"
        const val VOID_ARRAY = "[Ljava/lang/Void;"
        /** Шаг при занятом дескрипторе: больше любой реальной глубины иерархии — переходники не пересекаются. */
        const val SPREAD = 64
        private const val MAX_DEPTH = 60

        /**
         * Тип дописываемых аргументов: отличается от последнего параметра D, поэтому по дескриптору
         * переходника (D, M × n) однозначно восстанавливаются D и n — разные цели не совпадают.
         */
        fun marker(params: List<String>): String = if (params.lastOrNull() == VOID) VOID_ARRAY else VOID

        fun descriptor(params: List<String>, voids: Int): String = buildString {
            append('(')
            for (p in params) append(p)
            val m = marker(params)
            repeat(voids) { append(m) }
            append(")V")
        }
    }
}
