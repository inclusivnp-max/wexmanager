package com.tim.d2c.translate

import com.tim.casm.dsl.CodeDsl
import com.tim.d2c.types.Cat

/** Выбор инструкций JVM по категории значения. */
internal object JvmOps {

    fun load(c: CodeDsl, cat: Cat, slot: Int) = when (cat) {
        Cat.I -> c.iload(slot)
        Cat.J -> c.lload(slot)
        Cat.F -> c.fload(slot)
        Cat.D -> c.dload(slot)
        Cat.A -> c.aload(slot)
        else -> throw IllegalArgumentException("Нельзя загрузить значение категории $cat")
    }

    fun store(c: CodeDsl, cat: Cat, slot: Int) = when (cat) {
        Cat.I -> c.istore(slot)
        Cat.J -> c.lstore(slot)
        Cat.F -> c.fstore(slot)
        Cat.D -> c.dstore(slot)
        Cat.A -> c.astore(slot)
        else -> throw IllegalArgumentException("Нельзя сохранить значение категории $cat")
    }

    fun pop(c: CodeDsl, cat: Cat) = if (cat.isWide) c.pop2() else c.pop()

    /** Бинарная операция: [index] — порядок в Dalvik (add, sub, mul, div, rem, and, or, xor, shl, shr, ushr). */
    fun binary(c: CodeDsl, cat: Cat, index: Int) {
        when (cat) {
            Cat.I -> when (index) {
                0 -> c.iadd(); 1 -> c.isub(); 2 -> c.imul(); 3 -> c.idiv(); 4 -> c.irem()
                5 -> c.iand(); 6 -> c.ior(); 7 -> c.ixor(); 8 -> c.ishl(); 9 -> c.ishr(); 10 -> c.iushr()
                else -> bad(cat, index)
            }
            Cat.J -> when (index) {
                0 -> c.ladd(); 1 -> c.lsub(); 2 -> c.lmul(); 3 -> c.ldiv(); 4 -> c.lrem()
                5 -> c.land(); 6 -> c.lor(); 7 -> c.lxor(); 8 -> c.lshl(); 9 -> c.lshr(); 10 -> c.lushr()
                else -> bad(cat, index)
            }
            Cat.F -> when (index) {
                0 -> c.fadd(); 1 -> c.fsub(); 2 -> c.fmul(); 3 -> c.fdiv(); 4 -> c.frem()
                else -> bad(cat, index)
            }
            Cat.D -> when (index) {
                0 -> c.dadd(); 1 -> c.dsub(); 2 -> c.dmul(); 3 -> c.ddiv(); 4 -> c.drem()
                else -> bad(cat, index)
            }
            else -> bad(cat, index)
        }
    }

    private fun bad(cat: Cat, index: Int): Nothing =
        throw IllegalArgumentException("Нет бинарной операции #$index для категории $cat")

    fun returnOf(c: CodeDsl, cat: Cat) = when (cat) {
        Cat.I -> c.ireturn()
        Cat.J -> c.lreturn()
        Cat.F -> c.freturn()
        Cat.D -> c.dreturn()
        Cat.A -> c.areturn()
        else -> throw IllegalArgumentException("Нет return для категории $cat")
    }

    /** Чтение элемента массива по дескриптору элемента. */
    fun arrayLoad(c: CodeDsl, element: Char) = when (element) {
        'I' -> c.iaload(); 'J' -> c.laload(); 'F' -> c.faload(); 'D' -> c.daload()
        'Z', 'B' -> c.baload(); 'C' -> c.caload(); 'S' -> c.saload()
        else -> c.aaload()
    }

    fun arrayStore(c: CodeDsl, element: Char) = when (element) {
        'I' -> c.iastore(); 'J' -> c.lastore(); 'F' -> c.fastore(); 'D' -> c.dastore()
        'Z', 'B' -> c.bastore(); 'C' -> c.castore(); 'S' -> c.sastore()
        else -> c.aastore()
    }

    /** atype для newarray. */
    fun primitiveArrayType(element: Char): Int = when (element) {
        'Z' -> 4; 'C' -> 5; 'F' -> 6; 'D' -> 7; 'B' -> 8; 'S' -> 9; 'I' -> 10; 'J' -> 11
        else -> throw IllegalArgumentException("Не примитивный тип элемента: $element")
    }
}
