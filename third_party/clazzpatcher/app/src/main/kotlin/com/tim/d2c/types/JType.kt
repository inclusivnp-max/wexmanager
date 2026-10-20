package com.tim.d2c.types

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.CommonSuperClass

/** Категория значения в JVM: какой вариант xload/xstore/xreturn нужен. */
internal enum class Cat { NONE, I, J, F, D, A, CONFLICT;

    val isWide: Boolean get() = this == J || this == D

    companion object {
        /** Категория по дескриптору типа поля / параметра / возврата. */
        fun of(descriptor: String): Cat = when (descriptor[0]) {
            'Z', 'B', 'C', 'S', 'I' -> I
            'J' -> J
            'F' -> F
            'D' -> D
            'L', '[' -> A
            else -> throw IllegalArgumentException("Некорректный дескриптор типа: $descriptor")
        }
    }
}

/**
 * Тип значения регистра в терминах верификатора JVM (так же его увидит расчёт фреймов casm).
 * [Top] — ещё не вычислен, [Conflict] — несовместимые значения.
 */
internal sealed class JType {
    object Top : JType()
    object Conflict : JType()
    object IntT : JType()
    object LongT : JType()
    object FloatT : JType()
    object DoubleT : JType()
    object Null : JType()
    object UninitThis : JType()
    data class Ref(val descriptor: String) : JType()
    /** Результат new-instance (индекс инструкции), до вызова <init>. */
    data class Uninit(val newIndex: Int) : JType()

    val cat: Cat
        get() = when (this) {
            Top -> Cat.NONE
            Conflict -> Cat.CONFLICT
            IntT -> Cat.I
            LongT -> Cat.J
            FloatT -> Cat.F
            DoubleT -> Cat.D
            Null, UninitThis, is Ref, is Uninit -> Cat.A
        }

    override fun toString(): String = when (this) {
        Top -> "?"
        Conflict -> "conflict"
        IntT -> "int"
        LongT -> "long"
        FloatT -> "float"
        DoubleT -> "double"
        Null -> "null"
        UninitThis -> "uninitializedThis"
        is Ref -> descriptor
        is Uninit -> "uninitialized(#$newIndex)"
    }

    companion object {
        // Экземпляры подклассов здесь не создаются: иначе инициализация JType и его подклассов
        // из разных потоков взаимно блокируется (конвертация идёт параллельно).
        val OBJECT: Ref get() = KnownTypes.OBJECT
        val THROWABLE: Ref get() = KnownTypes.THROWABLE

        fun of(descriptor: String): JType = when (descriptor[0]) {
            'Z', 'B', 'C', 'S', 'I' -> IntT
            'J' -> LongT
            'F' -> FloatT
            'D' -> DoubleT
            'L', '[' -> Ref(descriptor)
            else -> throw IllegalArgumentException("Некорректный дескриптор типа: $descriptor")
        }
    }
}

/** Часто нужные ссылочные типы (отдельный объект — см. комментарий в [JType.Companion]). */
internal object KnownTypes {
    val OBJECT = JType.Ref("Ljava/lang/Object;")
    val THROWABLE = JType.Ref("Ljava/lang/Throwable;")
}

/**
 * Операции над типами с учётом иерархии: слияние (как в casm FrameAnalyzer — те же правила,
 * иначе решения о checkcast разойдутся с фреймами) и доказуемая присваиваемость.
 */
internal class TypeOps(private val hierarchy: ClassHierarchy) {
    private val lub = CommonSuperClass(hierarchy)

    fun join(a: JType, b: JType): JType {
        if (a == b) return a
        if (a === JType.Top) return b
        if (b === JType.Top) return a
        if (a === JType.Conflict || b === JType.Conflict) return JType.Conflict
        if (a === JType.Null && b is JType.Ref) return b
        if (b === JType.Null && a is JType.Ref) return a
        if (a is JType.Ref && b is JType.Ref) return JType.Ref(lubDescriptor(a.descriptor, b.descriptor))
        return JType.Conflict
    }

    private fun lubDescriptor(x: String, y: String): String {
        if (x == y) return x
        if (x[0] == 'L' && y[0] == 'L') {
            return "L" + lub.of(x.substring(1, x.length - 1), y.substring(1, y.length - 1)) + ";"
        }
        if (x[0] == '[' && y[0] == '[') {
            val ex = x.substring(1)
            val ey = y.substring(1)
            if ((ex[0] == 'L' || ex[0] == '[') && (ey[0] == 'L' || ey[0] == '[')) return "[" + lubDescriptor(ex, ey)
        }
        return "Ljava/lang/Object;"
    }

    /** Интерфейс по иерархии (для верификатора JVM такие типы равносильны Object). */
    fun isInterface(descriptor: String): Boolean =
        descriptor[0] == 'L' && hierarchy.isInterface(descriptor.substring(1, descriptor.length - 1)) == true

    /**
     * Доказуемо ли значение типа [from] присваиваемо типу [to] для верификатора JVM без checkcast.
     * Неизвестная иерархия — «нет» (тогда вставляется checkcast, это всегда безопасно).
     */
    fun assignable(from: JType, to: String): Boolean {
        if (from === JType.Null) return true
        if (from !is JType.Ref) return false
        return assignable(from.descriptor, to)
    }

    private fun assignable(from: String, to: String): Boolean {
        if (from == to || to == "Ljava/lang/Object;") return true
        if (to[0] == 'L') {
            if (isInterface(to)) return true
            if (from[0] != 'L') return false
            var c: String? = from.substring(1, from.length - 1)
            val target = to.substring(1, to.length - 1)
            var guard = 0
            while (c != null && guard++ < 256) {
                if (c == target) return true
                c = hierarchy.superClass(c)
            }
            return false
        }
        // to — массив
        if (from[0] != '[') return false
        val ef = from.substring(1)
        val et = to.substring(1)
        if (et[0] != 'L' && et[0] != '[') return ef == et
        return (ef[0] == 'L' || ef[0] == '[') && assignable(ef, et)
    }
}
