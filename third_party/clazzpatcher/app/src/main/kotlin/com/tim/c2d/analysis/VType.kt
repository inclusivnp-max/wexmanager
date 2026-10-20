package com.tim.c2d.analysis

/**
 * Тип значения в слоте стека или локала — упрощённая система типов верификатора JVM.
 *
 * Long/double занимают два слова: [LONG]/[DOUBLE] и следом [LONG_HI]/[DOUBLE_HI].
 * Для ссылок хранится дескриптор ([desc]) — он нужен, чтобы различать `boolean[]` и `byte[]`
 * (в DEX это разные инструкции `aget-boolean`/`aget-byte`).
 */
class VType private constructor(val kind: Int, val desc: String?, val newPc: Int) {

    val isWide: Boolean get() = kind == LONG || kind == DOUBLE
    val isHigh: Boolean get() = kind == LONG_HI || kind == DOUBLE_HI

    /** Ссылка любого вида (включая null и неинициализированные объекты). */
    val isReference: Boolean get() = kind >= NULL

    /** Слотов у значения этого типа (для вершин пар — 2). */
    val width: Int get() = if (isWide) 2 else 1

    override fun equals(other: Any?): Boolean =
        this === other || (other is VType && kind == other.kind && newPc == other.newPc && desc == other.desc)

    override fun hashCode(): Int = (kind * 31 + newPc) * 31 + (desc?.hashCode() ?: 0)

    override fun toString(): String = when (kind) {
        TOP -> "top"
        INT -> "int"
        FLOAT -> "float"
        LONG -> "long"
        LONG_HI -> "long-hi"
        DOUBLE -> "double"
        DOUBLE_HI -> "double-hi"
        NULL -> "null"
        REF -> desc!!
        UNINIT -> "uninit@$newPc"
        else -> "uninitThis"
    }

    companion object {
        const val TOP = 0
        const val INT = 1
        const val FLOAT = 2
        const val LONG = 3
        const val LONG_HI = 4
        const val DOUBLE = 5
        const val DOUBLE_HI = 6
        const val NULL = 7
        const val REF = 8
        const val UNINIT = 9
        const val UNINIT_THIS = 10

        @JvmField val Top = VType(TOP, null, -1)
        @JvmField val Int = VType(INT, null, -1)
        @JvmField val Float = VType(FLOAT, null, -1)
        @JvmField val Long = VType(LONG, null, -1)
        @JvmField val LongHi = VType(LONG_HI, null, -1)
        @JvmField val Double = VType(DOUBLE, null, -1)
        @JvmField val DoubleHi = VType(DOUBLE_HI, null, -1)
        @JvmField val Null = VType(NULL, null, -1)
        @JvmField val UninitThis = VType(UNINIT_THIS, null, -1)
        @JvmField val Object = VType(REF, "Ljava/lang/Object;", -1)
        @JvmField val StringType = VType(REF, "Ljava/lang/String;", -1)
        @JvmField val ClassType = VType(REF, "Ljava/lang/Class;", -1)
        @JvmField val Throwable = VType(REF, "Ljava/lang/Throwable;", -1)

        fun ref(desc: String): VType = when (desc) {
            "Ljava/lang/Object;" -> Object
            "Ljava/lang/String;" -> StringType
            else -> VType(REF, desc, -1)
        }

        fun uninit(newPc: Int): VType = VType(UNINIT, null, newPc)

        /** Тип значения по дескриптору поля/параметра (первое слово для long/double). */
        fun ofDescriptor(desc: String): VType = when (desc[0]) {
            'Z', 'B', 'C', 'S', 'I' -> Int
            'F' -> Float
            'J' -> Long
            'D' -> Double
            'L', '[' -> ref(desc)
            else -> throw IllegalArgumentException("Недопустимый тип значения: $desc")
        }

        /** Верхнее слово пары для [LONG]/[DOUBLE]. */
        fun highOf(t: VType): VType = if (t.kind == LONG) LongHi else DoubleHi

        /**
         * Слияние типов в точке схождения потоков. Разные ссылки сводятся к `Object`
         * (точный общий предок здесь не нужен: типы используются только для выбора инструкций),
         * null поглощается ссылкой, несовместимые типы дают [Top].
         */
        fun merge(a: VType, b: VType): VType {
            if (a == b) return a
            if (a.kind == NULL && b.kind == REF) return b
            if (b.kind == NULL && a.kind == REF) return a
            if (a.kind == REF && b.kind == REF) return Object
            return Top
        }
    }
}
