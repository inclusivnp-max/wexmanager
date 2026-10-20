package com.tim.c2d.analysis

import com.tim.c2d.bytecode.DecodedCode
import com.tim.c2d.bytecode.JvmInsn
import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Базовые блоки метода и типы на входе в каждый из них.
 *
 * @property blockStart     индекс первой инструкции блока (блоки идут по возрастанию адресов).
 * @property blockOfInsn    блок каждой инструкции.
 * @property entry          типы на входе в блок; `null` — блок недостижим.
 * @property normalEntry    в блок можно попасть обычным потоком (вход в метод, переход, проваливание),
 *                          а не только как в обработчик исключения.
 */
class MethodFlow(
    val blockStart: IntArray,
    val blockOfInsn: IntArray,
    val entry: Array<TypeFrame?>,
    val normalEntry: BooleanArray
) {
    val blockCount: Int get() = blockStart.size

    fun blockEnd(block: Int, insnCount: Int): Int =
        if (block + 1 < blockStart.size) blockStart[block + 1] else insnCount

    fun isReachable(block: Int): Boolean = entry[block] != null
}

/**
 * Поиск базовых блоков и потоковый анализ типов до неподвижной точки.
 * Обработчик исключения получает слияние локалов перед каждой инструкцией своего диапазона.
 */
object FlowAnalysis {

    fun analyze(code: DecodedCode, handlers: ExceptionTable, interpreter: TypeInterpreter, initial: TypeFrame): MethodFlow {
        val insns = code.insns
        val n = insns.size
        if (n == 0) throw IllegalArgumentException("пустой код метода")

        val leader = BooleanArray(n)
        leader[0] = true
        for (i in 0 until n) {
            val insn = insns[i]
            if (endsBlock(insn.opcode) && i + 1 < n) leader[i + 1] = true
            forEachTarget(insn) { leader[code.indexOf(it)] = true }
        }
        for (set in handlers.handlerSets) for (h in set) leader[h.handlerIndex] = true

        var count = 0
        for (l in leader) if (l) count++
        val blockStart = IntArray(count)
        val blockOfInsn = IntArray(n)
        var b = -1
        for (i in 0 until n) {
            if (leader[i]) blockStart[++b] = i
            blockOfInsn[i] = b
        }

        val entry = arrayOfNulls<TypeFrame>(count)
        val queued = BooleanArray(count)
        val queue = IntArray(count)
        var head = 0
        var tail = 0
        var size = 0
        fun enqueue(block: Int) {
            if (queued[block]) return
            queued[block] = true
            queue[tail] = block
            tail = (tail + 1) % count
            size++
        }
        fun mergeInto(block: Int, frame: TypeFrame) {
            val old = entry[block]
            if (old == null) {
                entry[block] = frame.copy()
                enqueue(block)
            } else if (old.mergeFrom(frame)) {
                enqueue(block)
            }
        }

        entry[0] = initial
        enqueue(0)
        while (size > 0) {
            val block = queue[head]
            head = (head + 1) % count
            size--
            queued[block] = false

            val frame = entry[block]!!.copy()
            val end = if (block + 1 < count) blockStart[block + 1] else n
            var last: JvmInsn? = null
            for (i in blockStart[block] until end) {
                val insn = insns[i]
                val set = handlers.handlerSets[handlers.handlerSetOfInsn[i]]
                for (h in set) {
                    val hb = blockOfInsn[h.handlerIndex]
                    val old = entry[hb]
                    if (old == null) {
                        entry[hb] = handlerFrame(frame, h, initial.stack.size)
                        enqueue(hb)
                    } else if (old.mergeLocals(frame.locals)) {
                        enqueue(hb)
                    }
                }
                try {
                    interpreter.execute(frame, insn)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("pc=${insn.pc}: ${e.message}", e)
                }
                last = insn
            }
            val lastInsn = last!!
            forEachTarget(lastInsn) { mergeInto(blockOfInsn[code.indexOf(it)], frame) }
            if (fallsThrough(lastInsn.opcode)) {
                if (end >= n) throw IllegalArgumentException("pc=${lastInsn.pc}: исполнение выходит за конец кода метода")
                mergeInto(blockOfInsn[end], frame)
            }
        }

        val normalEntry = BooleanArray(count)
        normalEntry[0] = true
        for (block in 0 until count) {
            if (entry[block] == null) continue
            val end = if (block + 1 < count) blockStart[block + 1] else n
            val lastInsn = insns[end - 1]
            forEachTarget(lastInsn) { normalEntry[blockOfInsn[code.indexOf(it)]] = true }
            if (fallsThrough(lastInsn.opcode) && end < n) normalEntry[blockOfInsn[end]] = true
        }
        return MethodFlow(blockStart, blockOfInsn, entry, normalEntry)
    }

    /** Кадр на входе в обработчик: локалы как перед инструкцией, на стеке — исключение. */
    private fun handlerFrame(frame: TypeFrame, h: Handler, maxStack: Int): TypeFrame {
        if (maxStack == 0) throw IllegalArgumentException("pc=${h.handlerPc}: обработчик исключений при max_stack = 0")
        val stack = Array(maxStack) { VType.Top }
        stack[0] = if (h.catchType == null) VType.Throwable else VType.ref(h.catchType)
        return TypeFrame(frame.locals.copyOf(), stack, 1)
    }

    /** Инструкция завершает базовый блок (после неё начинается новый). */
    fun endsBlock(opcode: Int): Boolean = when (opcode) {
        in Op.IFEQ..Op.RETURN, Op.IFNULL, Op.IFNONNULL, Op.ATHROW -> true
        else -> false
    }

    /** Управление может перейти к следующей по адресу инструкции. */
    fun fallsThrough(opcode: Int): Boolean = when (opcode) {
        Op.GOTO, Op.JSR, Op.RET, Op.TABLESWITCH, Op.LOOKUPSWITCH, Op.ATHROW -> false
        in Op.IRETURN..Op.RETURN -> false
        else -> true
    }

    /** Явные цели перехода инструкции (без проваливания). */
    inline fun forEachTarget(insn: JvmInsn, action: (Int) -> Unit) {
        when (insn.opcode) {
            in Op.IFEQ..Op.JSR, Op.IFNULL, Op.IFNONNULL -> action(insn.a)
            Op.TABLESWITCH, Op.LOOKUPSWITCH -> {
                action(insn.a)
                for (t in insn.targets!!) action(t)
            }
        }
    }
}
