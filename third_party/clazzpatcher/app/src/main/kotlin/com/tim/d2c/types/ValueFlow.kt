package com.tim.d2c.types

import com.tim.d2c.cfg.Insns
import com.tim.d2c.cfg.MethodCode
import com.tim.d2c.model.Descriptors
import com.tim.d2c.model.MethodRef
import com.tim.d2c.model.Proto
import com.tim.dasm.model.*
import com.tim.dex.data.Opcode

/** Метод, код которого анализируется. */
internal class MethodContext(
    /** Дескриптор класса-владельца. */
    val owner: String,
    val name: String,
    val proto: Proto,
    val isStatic: Boolean,
    /** Дескриптор прямого суперкласса владельца (null — нет). */
    val superName: String? = null
) {
    val isConstructor: Boolean get() = name == "<init>"
    override fun toString(): String = "$owner->$name${proto.descriptor}"
}

/**
 * Поток значений по регистрам: для каждого базового блока — узлы значений регистров на входе.
 * Слияние разных значений создаёт φ. Переходы по исключениям берут состояние перед каждой
 * бросающей инструкцией диапазона try (так делает верификатор ART).
 */
internal class ValueFlow(
    val code: MethodCode,
    val method: MethodContext,
    private val methodRef: (Int) -> MethodRef
) {
    val nodes = Nodes()
    val registers = code.registers

    /** Начала блоков (индексы инструкций) и блок каждой инструкции. */
    val blockStart: IntArray
    val blockOf = IntArray(code.count)
    val blockCount: Int

    /** Состояние на входе блока; null — блок недостижим. */
    val entry: Array<IntArray?>

    private var tagChanged = false

    /**
     * Пара long/double записывается в последний регистр перед параметрами: при повороте
     * нумерации (параметры — в слоты 0..ins-1) она разорвалась бы, нужна схема с копированием.
     */
    var wideAcrossParams = false
        private set

    /**
     * Конструктор перезаписывает регистр `this`. При повороте нумерации это слот 0, и после записи
     * в нём не остаётся uninitializedThis: у обработчика, куда попадают до вызова `super()`, фрейм
     * без флага flagThisUninit не совпадёт с входящими — HotSpot отвергнет метод. Нужна схема
     * с копированием: слот 0 держит `this` нетронутым.
     */
    var thisOverwritten = false
        private set

    private val thisReg = if (method.isStatic || method.name != "<init>") -1 else code.registers - code.ins

    init {
        val starts = ArrayList<Int>()
        for (i in 0 until code.count) {
            if (code.isLeader[i]) starts.add(i)
            blockOf[i] = starts.size - 1
        }
        blockStart = starts.toIntArray()
        blockCount = blockStart.size
        entry = arrayOfNulls(blockCount)
    }

    fun blockEnd(b: Int): Int = if (b + 1 < blockCount) blockStart[b + 1] else code.count

    fun isReachable(i: Int): Boolean = entry[blockOf[i]] != null

    fun run() {
        if (code.count == 0) return
        checkFrame()
        entry[0] = initialState()
        val queue = IntArray(blockCount)
        val queued = BooleanArray(blockCount)
        var head = 0
        var tail = 0
        var size = 0
        fun push(b: Int) {
            if (queued[b]) return
            queued[b] = true
            queue[tail] = b
            tail = (tail + 1) % blockCount
            size++
        }
        push(0)
        val st = IntArray(registers)
        while (size > 0) {
            val b = queue[head]
            head = (head + 1) % blockCount
            size--
            queued[b] = false
            System.arraycopy(entry[b]!!, 0, st, 0, registers)
            val end = blockEnd(b)
            for (i in blockStart[b] until end) {
                if (Insns.canThrow(code.insns[i].opcode) && code.coverage[i] >= 0) {
                    for (h in code.handlers[code.coverage[i]]) {
                        if (merge(blockOf[h.target], st)) push(blockOf[h.target])
                    }
                }
                step(i, st)
            }
            val last = end - 1
            forEachSuccessor(last) { t -> if (merge(blockOf[t], st)) push(blockOf[t]) }
            if (tagChanged) {
                // Метка неинициализированного объекта у φ сузилась: пересчитать всё (редкий случай).
                tagChanged = false
                for (k in 0 until blockCount) if (entry[k] != null) push(k)
            }
        }
    }

    /** Преемники последней инструкции блока по обычному управлению. */
    inline fun forEachSuccessor(last: Int, visit: (Int) -> Unit) {
        val insn = code.insns[last]
        if (code.isPayload(last)) throw code.error(last, "управление попадает в payload")
        val jump = code.jumpTarget[last]
        if (jump >= 0) visit(jump)
        code.switchTargets[last]?.forEach(visit)
        if (!Insns.isGoto(insn.opcode) && code.fallsThrough(last)) {
            if (last + 1 >= code.count) throw code.error(last, "выполнение выходит за конец кода")
            visit(last + 1)
        }
    }

    /**
     * Структурные проверки верификатора ART до анализа: ins_size ≤ registers_size и равен числу слов
     * параметров (с this), каждый регистр-операнд — в пределах кадра, у инструкций с типом — тип
     * нужного вида (new-instance — класс, new-array — массив, check-cast — ссылочный тип…).
     */
    private fun checkFrame() {
        val words = method.proto.paramWords + if (method.isStatic) 0 else 1
        if (code.ins > registers) throw IllegalStateException("ins_size (${code.ins}) больше registers_size ($registers)")
        if (code.ins != words) throw IllegalStateException("ins_size (${code.ins}) не совпадает с параметрами ${method.proto.descriptor} ($words слов)")
        for (i in 0 until code.count) {
            val insn = code.insns[i]
            for (r in registersOf(insn)) {
                if (r < 0 || r >= registers) throw code.error(i, "регистр v$r вне кадра (registers_size=$registers)")
            }
            when (insn.opcode) {
                Opcode.NEW_INSTANCE -> requireType(i, (insn as Ins21c).index, "класс", Descriptors::isClass)
                Opcode.CONST_CLASS, Opcode.CHECK_CAST -> requireType(i, (insn as Ins21c).index, "ссылочный тип", Descriptors::isReference)
                Opcode.INSTANCE_OF -> requireType(i, (insn as Ins22c).index, "ссылочный тип", Descriptors::isReference)
                Opcode.NEW_ARRAY -> requireType(i, (insn as Ins22c).index, "тип массива", Descriptors::isArray)
                Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> requireType(i, indexOf(insn), "тип массива", Descriptors::isArray)
                else -> {}
            }
        }
        for (list in code.handlers) for (h in list) {
            val t = h.catchType ?: continue
            if (!Descriptors.isClass(t)) throw IllegalStateException("тип обработчика исключений $t — не класс")
        }
    }

    private inline fun requireType(i: Int, typeIdx: Int, kind: String, ok: (String) -> Boolean) {
        val t = code.typeName(typeIdx)
        if (!ok(t)) throw code.error(i, "ожидался $kind, а тип $t")
    }

    private fun registersOf(insn: Instruction): IntArray = when (insn) {
        is Ins11n -> intArrayOf(insn.regA)
        is Ins11x -> intArrayOf(insn.regA)
        is Ins21c -> intArrayOf(insn.regA)
        is Ins21h -> intArrayOf(insn.regA)
        is Ins21s -> intArrayOf(insn.regA)
        is Ins21t -> intArrayOf(insn.regA)
        is Ins31c -> intArrayOf(insn.regA)
        is Ins31i -> intArrayOf(insn.regA)
        is Ins31t -> intArrayOf(insn.regA)
        is Ins51l -> intArrayOf(insn.regA)
        is Ins12x -> intArrayOf(insn.regA, insn.regB)
        is Ins22x -> intArrayOf(insn.regA, insn.regB)
        is Ins32x -> intArrayOf(insn.regA, insn.regB)
        is Ins22b -> intArrayOf(insn.regA, insn.regB)
        is Ins22c -> intArrayOf(insn.regA, insn.regB)
        is Ins22s -> intArrayOf(insn.regA, insn.regB)
        is Ins22t -> intArrayOf(insn.regA, insn.regB)
        is Ins23x -> intArrayOf(insn.regA, insn.regB, insn.regC)
        is Ins35c, is Ins45cc, is Ins3rc, is Ins4rcc -> argRegs(insn)
        else -> NO_REGS
    }

    private fun initialState(): IntArray {
        val st = IntArray(registers) // UNDEF
        var r = registers - code.ins
        var index = 0
        if (!method.isStatic) {
            val tag = if (method.isConstructor && method.owner != "Ljava/lang/Object;") Nodes.THIS_TAG else Nodes.NO_TAG
            st[r] = nodes.param(index++, r, tag)
            r++
        }
        for (p in method.proto.params) {
            st[r] = nodes.param(index++, r, Nodes.NO_TAG)
            if (p == "J" || p == "D") {
                st[r + 1] = Nodes.HIGH
                r += 2
            } else r++
        }
        if (r != registers) throw IllegalStateException("ins_size (${code.ins}) не совпадает с параметрами ${method.proto.descriptor}")
        return st
    }

    /** Слияние состояния [st] во вход блока [b]; `true` — вход изменился. */
    private fun merge(b: Int, st: IntArray): Boolean {
        val cur = entry[b]
        if (cur == null) {
            entry[b] = st.copyOf()
            return true
        }
        var changed = false
        for (r in 0 until registers) {
            val a = cur[r]
            val v = st[r]
            if (a == v) continue
            val existing = nodes.existingPhi(b, r)
            if (existing >= 0 && a == existing) {
                if (nodes.addOperand(existing, v)) tagChanged = true
                continue
            }
            val phi = nodes.phi(b, r)
            if (r + 1 < registers && (cur[r + 1] == Nodes.HIGH || st[r + 1] == Nodes.HIGH)) nodes.markWide(phi)
            if (nodes.addOperand(phi, a)) tagChanged = true
            if (nodes.addOperand(phi, v)) tagChanged = true
            cur[r] = phi
            changed = true
        }
        return changed
    }

    // ---- Передаточная функция ----

    /** Запись значения в регистр с учётом пар long/double. */
    private fun write(st: IntArray, r: Int, node: Int, wide: Boolean) {
        if (r >= registers || (wide && r + 1 >= registers)) throw IllegalStateException("регистр v$r вне кадра ($registers)")
        if (wide && code.ins > 0 && r == registers - code.ins - 1) wideAcrossParams = true
        if (thisReg >= 0 && (r == thisReg || (wide && r + 1 == thisReg))) thisOverwritten = true
        if (st[r] == Nodes.HIGH && r > 0) st[r - 1] = Nodes.UNDEF
        if (!wide && r + 1 < registers && st[r + 1] == Nodes.HIGH) st[r + 1] = Nodes.UNDEF
        if (wide && r + 2 < registers && st[r + 2] == Nodes.HIGH) st[r + 2] = Nodes.UNDEF
        st[r] = node
        if (wide) st[r + 1] = Nodes.HIGH
    }

    /** Применяет инструкцию [i] к состоянию [st] (узлы стабильны между проходами). */
    fun step(i: Int, st: IntArray) {
        val insn = code.insns[i]
        val op = insn.opcode
        when (op) {
            Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                val (a, b) = twoRegs(insn)
                write(st, a, nodes.move(i, a, st[b]), false)
            }
            Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16 -> {
                val (a, b) = twoRegs(insn)
                write(st, a, nodes.move(i, a, st[b]), true)
            }
            Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE -> {
                if (i == 0 || !producesResult(i - 1)) throw code.error(i, "move-result не сразу после invoke/filled-new-array")
                val a = (insn as Ins11x).regA
                write(st, a, nodes.typed(i, a), op == Opcode.MOVE_RESULT_WIDE)
            }
            Opcode.MOVE_EXCEPTION -> {
                if (!code.isHandlerEntry[i]) throw code.error(i, "move-exception не в начале обработчика")
                val a = (insn as Ins11x).regA
                write(st, a, nodes.typed(i, a), false)
            }
            Opcode.CONST_4 -> constDef(st, i, (insn as Ins11n).regA, insn.value.toLong(), false)
            Opcode.CONST_16 -> constDef(st, i, (insn as Ins21s).regA, insn.value.toLong(), false)
            Opcode.CONST -> constDef(st, i, (insn as Ins31i).regA, insn.value.toLong(), false)
            Opcode.CONST_HIGH16 -> constDef(st, i, (insn as Ins21h).regA, (insn.value shl 16).toLong(), false)
            Opcode.CONST_WIDE_16 -> constDef(st, i, (insn as Ins21s).regA, insn.value.toLong(), true)
            Opcode.CONST_WIDE_32 -> constDef(st, i, (insn as Ins31i).regA, insn.value.toLong(), true)
            Opcode.CONST_WIDE -> constDef(st, i, (insn as Ins51l).regA, insn.value, true)
            Opcode.CONST_WIDE_HIGH16 -> constDef(st, i, (insn as Ins21h).regA, insn.value.toLong() shl 48, true)

            Opcode.NEW_INSTANCE -> {
                val a = (insn as Ins21c).regA
                write(st, a, nodes.typed(i, a, tag = i), false)
            }
            Opcode.AGET, Opcode.AGET_WIDE, Opcode.AGET_OBJECT, Opcode.AGET_BOOLEAN,
            Opcode.AGET_BYTE, Opcode.AGET_CHAR, Opcode.AGET_SHORT -> {
                insn as Ins23x
                write(st, insn.regA, nodes.typed(i, insn.regA, input = st[insn.regB]), op == Opcode.AGET_WIDE)
            }
            Opcode.CHECK_CAST -> {
                val a = (insn as Ins21c).regA
                write(st, a, nodes.typed(i, a, input = st[a]), false)
            }
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> {
                val ref = methodRef(indexOf(insn))
                if (ref.name == "<init>") initialize(i, st, firstReg(insn))
            }
            else -> {
                val d = typedDest(insn) ?: return
                write(st, d.first, nodes.typed(i, d.first), d.second)
            }
        }
    }

    private fun constDef(st: IntArray, i: Int, reg: Int, value: Long, wide: Boolean) =
        write(st, reg, nodes.const(i, reg, value, wide), wide)

    /** После invoke-direct <init> все копии неинициализированного значения становятся объектом. */
    private fun initialize(i: Int, st: IntArray, receiverReg: Int) {
        val receiver = st[receiverReg]
        val tag = nodes.tag(receiver)
        if (tag == Nodes.NO_TAG) throw code.error(i, "<init> вызывается не для неинициализированного объекта (v$receiverReg)")
        for (r in 0 until registers) {
            val n = st[r]
            if (n != Nodes.UNDEF && n != Nodes.HIGH && nodes.tag(n) == tag) st[r] = nodes.init(i, r, n)
        }
    }

    /** Регистр-приёмник и его ширина для инструкций с обычным типизированным результатом. */
    private fun typedDest(insn: Instruction): Pair<Int, Boolean>? {
        val op = insn.opcode
        val v = op.value
        return when {
            op == Opcode.CONST_STRING || op == Opcode.CONST_CLASS || op == Opcode.CONST_METHOD_HANDLE ||
                op == Opcode.CONST_METHOD_TYPE -> (insn as Ins21c).regA to false
            op == Opcode.CONST_STRING_JUMBO -> (insn as Ins31c).regA to false
            op == Opcode.INSTANCE_OF || op == Opcode.NEW_ARRAY -> (insn as Ins22c).regA to false
            op == Opcode.ARRAY_LENGTH -> (insn as Ins12x).regA to false
            v in Opcode.CMPL_FLOAT.value..Opcode.CMP_LONG.value -> (insn as Ins23x).regA to false
            v in Opcode.IGET.value..Opcode.IGET_SHORT.value -> (insn as Ins22c).regA to (op == Opcode.IGET_WIDE)
            v in Opcode.SGET.value..Opcode.SGET_SHORT.value -> (insn as Ins21c).regA to (op == Opcode.SGET_WIDE)
            v in Opcode.NEG_INT.value..Opcode.INT_TO_SHORT.value -> (insn as Ins12x).regA to Results.isWideResult(op)
            v in Opcode.ADD_INT.value..Opcode.REM_DOUBLE.value -> (insn as Ins23x).regA to Results.isWideResult(op)
            v in Opcode.ADD_INT_2ADDR.value..Opcode.REM_DOUBLE_2ADDR.value -> (insn as Ins12x).regA to Results.isWideResult(op)
            v in Opcode.ADD_INT_LIT16.value..Opcode.XOR_INT_LIT16.value -> (insn as Ins22s).regA to false
            v in Opcode.ADD_INT_LIT8.value..Opcode.USHR_INT_LIT8.value -> (insn as Ins22b).regA to false
            else -> null
        }
    }

    /** Даёт ли инструкция результат для move-result (invoke-* или filled-new-array). */
    fun producesResult(i: Int): Boolean {
        val op = code.insns[i].opcode
        return op == Opcode.FILLED_NEW_ARRAY || op == Opcode.FILLED_NEW_ARRAY_RANGE || Insns.isInvoke(op)
    }

    companion object {
        private val NO_REGS = IntArray(0)

        fun twoRegs(insn: Instruction): Pair<Int, Int> = when (insn) {
            is Ins12x -> insn.regA to insn.regB
            is Ins22x -> insn.regA to insn.regB
            is Ins32x -> insn.regA to insn.regB
            else -> throw IllegalArgumentException("${insn.opcode.mnemonic}: неожиданный формат ${insn.format}")
        }

        fun indexOf(insn: Instruction): Int = when (insn) {
            is Ins35c -> insn.index
            is Ins3rc -> insn.index
            is Ins45cc -> insn.methodIdx
            is Ins4rcc -> insn.methodIdx
            is Ins21c -> insn.index
            is Ins22c -> insn.index
            is Ins31c -> insn.index
            else -> throw IllegalArgumentException("${insn.opcode.mnemonic}: нет индекса пула")
        }

        /** Регистры аргументов invoke / filled-new-array в порядке следования. */
        fun argRegs(insn: Instruction): IntArray = when (insn) {
            is Ins35c -> insn.getRegisters().toIntArray()
            is Ins45cc -> insn.getRegisters().toIntArray()
            is Ins3rc -> IntArray(insn.regCount) { insn.regStart + it }
            is Ins4rcc -> IntArray(insn.regCount) { insn.regStart + it }
            else -> throw IllegalArgumentException("${insn.opcode.mnemonic}: не вызов")
        }

        fun firstReg(insn: Instruction): Int {
            val regs = argRegs(insn)
            if (regs.isEmpty()) throw IllegalArgumentException("${insn.opcode.mnemonic}: нет получателя")
            return regs[0]
        }
    }
}

/** Типы результатов арифметики и преобразований Dalvik. */
internal object Results {

    fun isWideResult(op: Opcode): Boolean = when (resultCat(op)) {
        Cat.J, Cat.D -> true
        else -> false
    }

    /** Категория результата унарной/бинарной операции. */
    fun resultCat(op: Opcode): Cat {
        val v = op.value
        return when {
            v in Opcode.ADD_INT_LIT16.value..Opcode.USHR_INT_LIT8.value -> Cat.I
            v in Opcode.CMPL_FLOAT.value..Opcode.CMP_LONG.value -> Cat.I
            else -> when (op) {
                Opcode.NEG_INT, Opcode.NOT_INT, Opcode.LONG_TO_INT, Opcode.FLOAT_TO_INT, Opcode.DOUBLE_TO_INT,
                Opcode.INT_TO_BYTE, Opcode.INT_TO_CHAR, Opcode.INT_TO_SHORT -> Cat.I
                Opcode.NEG_LONG, Opcode.NOT_LONG, Opcode.INT_TO_LONG, Opcode.FLOAT_TO_LONG, Opcode.DOUBLE_TO_LONG -> Cat.J
                Opcode.NEG_FLOAT, Opcode.INT_TO_FLOAT, Opcode.LONG_TO_FLOAT, Opcode.DOUBLE_TO_FLOAT -> Cat.F
                Opcode.NEG_DOUBLE, Opcode.INT_TO_DOUBLE, Opcode.LONG_TO_DOUBLE, Opcode.FLOAT_TO_DOUBLE -> Cat.D
                else -> binaryCat(op)
            }
        }
    }

    /** Категория операндов/результата бинарной операции (23x и 2addr). */
    fun binaryCat(op: Opcode): Cat {
        val v = op.value
        val base = when {
            v in Opcode.ADD_INT.value..Opcode.REM_DOUBLE.value -> v - Opcode.ADD_INT.value
            v in Opcode.ADD_INT_2ADDR.value..Opcode.REM_DOUBLE_2ADDR.value -> v - Opcode.ADD_INT_2ADDR.value
            else -> throw IllegalArgumentException("${op.mnemonic}: не бинарная операция")
        }
        return when (base) {
            in 0..10 -> Cat.I
            in 11..21 -> Cat.J
            in 22..26 -> Cat.F
            else -> Cat.D
        }
    }

    /** Категория источника унарной операции/преобразования. */
    fun unarySourceCat(op: Opcode): Cat = when (op) {
        Opcode.NEG_INT, Opcode.NOT_INT, Opcode.INT_TO_LONG, Opcode.INT_TO_FLOAT, Opcode.INT_TO_DOUBLE,
        Opcode.INT_TO_BYTE, Opcode.INT_TO_CHAR, Opcode.INT_TO_SHORT -> Cat.I
        Opcode.NEG_LONG, Opcode.NOT_LONG, Opcode.LONG_TO_INT, Opcode.LONG_TO_FLOAT, Opcode.LONG_TO_DOUBLE -> Cat.J
        Opcode.NEG_FLOAT, Opcode.FLOAT_TO_INT, Opcode.FLOAT_TO_LONG, Opcode.FLOAT_TO_DOUBLE -> Cat.F
        Opcode.NEG_DOUBLE, Opcode.DOUBLE_TO_INT, Opcode.DOUBLE_TO_LONG, Opcode.DOUBLE_TO_FLOAT -> Cat.D
        else -> throw IllegalArgumentException("${op.mnemonic}: не унарная операция")
    }

    /** Сдвиги long: второй операнд — int. */
    fun isLongShift(op: Opcode): Boolean = when (op) {
        Opcode.SHL_LONG, Opcode.SHR_LONG, Opcode.USHR_LONG,
        Opcode.SHL_LONG_2ADDR, Opcode.SHR_LONG_2ADDR, Opcode.USHR_LONG_2ADDR -> true
        else -> false
    }
}
