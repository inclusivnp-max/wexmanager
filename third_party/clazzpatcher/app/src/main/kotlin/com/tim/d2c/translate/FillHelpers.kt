package com.tim.d2c.translate

import com.tim.casm.CasmState
import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.frames.MethodShape
import com.tim.casm.model.MethodInfo

/**
 * Компактный `fill-array-data` для методов, которые иначе не помещаются в 64 КиБ кода JVM
 * (большие `<clinit>` с таблицами). Данные payload лежат строкой в пуле констант (16-битные единицы
 * в порядке байтов DEX; у byte/boolean — по элементу на символ), синтетический метод класса
 * `d2c$fill<тип>(массив, смещение, строка)` раскладывает их в массив. Проверки ART (null, длина)
 * делаются до вызова, как и при поэлементной записи.
 */
internal object FillHelpers {

    fun name(element: Char): String = "d2c\$fill$element"

    fun descriptor(element: Char): String = "([${element}ILjava/lang/String;)V"

    /** Символов строки на элемент. */
    private fun charsPerElement(element: Char): Int = when (element) {
        'I', 'F' -> 2
        'J', 'D' -> 4
        else -> 1
    }

    /**
     * Данные payload ([width] байт на элемент) — строками не длиннее 65535 байт MUTF-8, по целым элементам:
     * пары «индекс первого элемента → строка».
     */
    fun chunks(data: ByteArray, width: Int, count: Int, element: Char): List<Pair<Int, String>> {
        val per = charsPerElement(element)
        val chars = CharArray(count * per)
        if (width == 1) {
            for (k in 0 until count) chars[k] = (data[k].toInt() and 0xFF).toChar()
        } else {
            for (c in chars.indices) chars[c] = ((data[2 * c].toInt() and 0xFF) or ((data[2 * c + 1].toInt() and 0xFF) shl 8)).toChar()
        }
        val out = ArrayList<Pair<Int, String>>()
        var start = 0
        while (start < count) {
            var end = start
            var bytes = 0
            while (end < count) {
                var elementBytes = 0
                for (j in 0 until per) {
                    val ch = chars[end * per + j].code
                    elementBytes += if (ch in 1..0x7F) 1 else if (ch <= 0x7FF) 2 else 3
                }
                if (bytes + elementBytes > 0xFFFF) break
                bytes += elementBytes
                end++
            }
            out.add(start to String(chars, start * per, (end - start) * per))
            start = end
        }
        return out
    }

    /** `static void d2c$fill<тип>(T[] a, int off, String s)` — раскладка строки [chunks] в массив. */
    fun method(state: CasmState, owner: String, element: Char, isInterface: Boolean): MethodInfo {
        val pool = state.pool
        val per = charsPerElement(element)
        val charAt = pool.methodref("java/lang/String", "charAt", "(I)C")
        val code = CodeDsl()
        fun char(offset: Int) {
            code.aload(2)
            code.iload(3)
            if (offset > 0) { code.iconst(offset); code.iadd() }
            code.invokevirtual(charAt)
        }
        code.iconst(0)
        code.istore(3)
        code.label("loop")
        code.iload(3)
        code.aload(2)
        code.invokevirtual(pool.methodref("java/lang/String", "length", "()I"))
        code.ifIcmpge("end")
        code.aload(0)
        code.iload(1)
        code.iload(3)
        if (per > 1) { code.iconst(if (per == 2) 1 else 2); code.ishr() }
        code.iadd()
        when (element) {
            'Z', 'B' -> { char(0); code.bastore() }
            'S' -> { char(0); code.sastore() }
            'C' -> { char(0); code.castore() }
            'I', 'F' -> {
                char(0)
                char(1); code.bipush(16); code.ishl()
                code.ior()
                if (element == 'F') {
                    code.invokestatic(pool.methodref("java/lang/Float", "intBitsToFloat", "(I)F"))
                    code.fastore()
                } else code.iastore()
            }
            else -> {
                char(0); code.i2l()
                for (j in 1..3) { char(j); code.i2l(); code.bipush(16 * j); code.lshl(); code.lor() }
                if (element == 'D') {
                    code.invokestatic(pool.methodref("java/lang/Double", "longBitsToDouble", "(J)D"))
                    code.dastore()
                } else code.lastore()
            }
        }
        code.iinc(3, per)
        code.goto("loop")
        code.label("end")
        code.returnVoid()
        val a = CodeAssembler.assemble(
            code, pool, MethodShape(owner, name(element), descriptor(element), true),
            options = CodeAssembler.Options(frames = state.frameMode, hierarchy = state.hierarchy)
        )
        // В интерфейсе private-методы — с версии 53: там метод открытый (синтетический).
        val flags = ACC_STATIC or ACC_SYNTHETIC or (if (isInterface) ACC_PUBLIC else ACC_PRIVATE)
        return MethodInfo(
            name = name(element), descriptor = descriptor(element), accessFlags = flags,
            code = a.code, maxStack = a.maxStack, maxLocals = a.maxLocals, exceptionTable = a.exceptionTable,
            stackMapFrames = a.stackMapFrames, hasCode = true
        )
    }

    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PRIVATE = 0x0002
    private const val ACC_STATIC = 0x0008
    private const val ACC_SYNTHETIC = 0x1000
}
