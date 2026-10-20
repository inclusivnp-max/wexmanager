package com.tim.casm.frames

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.CommonSuperClass
import com.tim.clazz.model.opcodes.Opcodes

/** Диапазон обработчика исключений в pc; [catchType] — внутреннее имя, `null` — любой. */
internal class TryRange(val start: Int, val end: Int, val handler: Int, val catchType: String?)

/** Состояние перед инструкцией: локалы по слотам и стек по элементам (long/double — один элемент). */
internal class Frame(val locals: Array<VerType>, val stack: Array<VerType>) {
    val stackWords: Int get() = stack.sumOf { it.size }
}

/**
 * Поток данных по байткоду метода (JVMS §4.10.1) для StackMapTable и max_stack/max_locals.
 *
 * Слияние ссылок — через [CommonSuperClass]; обработчики исключений получают локалы
 * каждой инструкции диапазона так же, как проверяет HotSpot: до инструкций записи в локал,
 * после остальных, а у `invokespecial <init>` — и до, и после.
 * Недостижимые инструкции остаются без состояния ([Result.entry] = null).
 */
internal class FrameAnalyzer(
    private val code: Bytecode,
    private val pool: PoolAccess,
    private val hierarchy: ClassHierarchy,
    private val owner: String,
    private val methodName: String,
    private val descriptor: String,
    private val isStatic: Boolean,
    private val tries: List<TryRange>,
    /** `true` — нужны фреймы: jsr/ret запрещены, несовместимые типы на стеке — ошибка. */
    private val strict: Boolean = true
) {
    class Result(
        /** Входные состояния начал блоков (для прочих инструкций — null). */
        val entry: Array<Frame?>,
        /** Достижима ли инструкция. */
        val reached: BooleanArray,
        val initial: Frame,
        val maxStack: Int,
        val maxLocals: Int
    )

    private val lub = CommonSuperClass(hierarchy)
    private val n = code.count
    private val numLocals: Int
    private val entry = arrayOfNulls<Frame>(n)
    private val reached = BooleanArray(n)
    private val newTypes = HashMap<Int, VerType.Ref>()
    private var maxStack = 0

    /** Для каждой инструкции — индексы try-диапазонов, её покрывающих (null — ни одного). */
    private val handlersOf = arrayOfNulls<IntArray>(n)


    init {
        var need = paramSlots()
        for (i in 0 until n) {
            val op = code.op[i]
            val top = when (op) {
                Opcodes.LLOAD, Opcodes.DLOAD, Opcodes.LSTORE, Opcodes.DSTORE -> code.a[i] + 2
                in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.IINC, Opcodes.RET -> code.a[i] + 1
                else -> 0
            }
            if (top > need) need = top
        }
        numLocals = need
        for ((t, r) in tries.withIndex()) {
            if (code.indexAt(r.handler) < 0) throw error(r.handler, "обработчик исключения не на границе инструкции")
            for (i in 0 until n) {
                val pc = code.pc[i]
                if (pc >= r.start && pc < r.end) {
                    val old = handlersOf[i]
                    handlersOf[i] = if (old == null) intArrayOf(t) else old + t
                }
            }
        }
    }

    fun run(): Result {
        val initial = initialFrame()
        if (n == 0) return Result(entry, reached, initial, 0, numLocals)
        val leader = leaders()
        entry[0] = initial
        val work = IntArray(n)
        val queued = BooleanArray(n)
        var top = 0
        work[top++] = 0
        queued[0] = true
        val cur = Working(numLocals)
        while (top > 0) {
            var i = work[--top]
            queued[i] = false
            cur.load(entry[i]!!)
            // Внутри блока состояние идёт потоком: у не-лидера единственный предшественник —
            // предыдущая инструкция, поэтому его входное состояние не хранится и не копируется.
            while (true) {
                reached[i] = true
                var fallthrough = -1
                try {
                    val op = code.op[i]
                    // Как HotSpot: до записи в локал — входное состояние; у <init> — и входное
                    // (исключение из конструктора оставляет объект неинициализированным), и выходное.
                    val isStore = op in Opcodes.ISTORE..Opcodes.ASTORE
                    val isInit = op == Opcodes.INVOKESPECIAL && handlersOf[i] != null && pool.member(code.a[i]).name == "<init>"
                    if (isStore || isInit) mergeHandlers(i, cur) { t -> if (!queued[t]) { queued[t] = true; work[top++] = t } }
                    execute(i, cur)
                    if (!isStore) mergeHandlers(i, cur) { t -> if (!queued[t]) { queued[t] = true; work[top++] = t } }
                    if (cur.words > maxStack) maxStack = cur.words
                    if (op == Opcodes.JSR || op == Opcodes.JSR_W) {
                        // Подпрограмма получает адрес возврата; продолжение после неё — со стеком до jsr
                        // (приближение, достаточное для max_stack/max_locals: фреймы здесь не строятся).
                        val target = indexOrFail(i, code.a[i])
                        if (mergeInto(target, cur) && !queued[target]) { queued[target] = true; work[top++] = target }
                        cur.pop()
                        val after = next(i)
                        if (mergeInto(after, cur) && !queued[after]) { queued[after] = true; work[top++] = after }
                    } else {
                        val self = i
                        forEachSuccessor(i) { t ->
                            if (t == self + 1 && !leader[t]) fallthrough = t
                            else if (mergeInto(t, cur) && !queued[t]) { queued[t] = true; work[top++] = t }
                        }
                    }
                } catch (e: AnalysisException) {
                    throw e
                } catch (e: Exception) {
                    throw error(code.pc[i], e.message ?: e.toString(), e)
                }
                if (fallthrough < 0) break
                i = fallthrough
            }
        }
        return Result(entry, reached, initial, maxOf(maxStack, if (tries.isEmpty()) 0 else 1), numLocals)
    }

    /**
     * Начала блоков — инструкции, в которые можно попасть не только из предыдущей: вход метода,
     * цели переходов и switch, обработчики исключений, продолжение после jsr.
     */
    private fun leaders(): BooleanArray {
        val leader = BooleanArray(n)
        leader[0] = true
        for (i in 0 until n) {
            val sw = code.switchTargets[i]
            if (sw != null) {
                for (t in sw) leader[indexOrFail(i, t)] = true
                continue
            }
            when (code.op[i]) {
                in Opcodes.IFEQ..Opcodes.JSR, Opcodes.GOTO_W, Opcodes.JSR_W, Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                    leader[indexOrFail(i, code.a[i])] = true
                    if ((code.op[i] == Opcodes.JSR || code.op[i] == Opcodes.JSR_W) && i + 1 < n) leader[i + 1] = true
                }
            }
        }
        for (r in tries) leader[code.indexAt(r.handler)] = true
        return leader
    }

    // ---- Начальное состояние ----

    private fun paramSlots(): Int {
        var s = if (isStatic) 0 else 1
        for (p in desc(descriptor).params) s += p.size
        return s
    }

    private fun initialFrame(): Frame {
        val locals = Array<VerType>(numLocals) { VerType.Top }
        var s = 0
        if (!isStatic) {
            locals[s++] = if (methodName == "<init>" && owner != ClassHierarchy.OBJECT) VerType.UninitThis
            else VerType.ofClassName(owner)
        }
        for (p in desc(descriptor).params) {
            locals[s] = p
            s += p.size
        }
        return Frame(locals, EMPTY)
    }

    // ---- Слияние ----

    private fun mergeHandlers(i: Int, cur: Working, schedule: (Int) -> Unit) {
        val hs = handlersOf[i] ?: return
        for (t in hs) {
            val r = tries[t]
            val target = code.indexAt(r.handler)
            val ex = if (r.catchType == null) VerType.THROWABLE else VerType.ofClassName(r.catchType)
            val incoming = Frame(cur.locals.copyOf(), arrayOf(ex))
            if (mergeFrame(target, incoming)) schedule(target)
        }
    }

    /** Слияние рабочего состояния без промежуточных копий: копируется только новое или изменённое. */
    private fun mergeInto(target: Int, cur: Working): Boolean {
        val old = entry[target]
        if (old == null) {
            entry[target] = Frame(cur.locals.copyOf(), cur.stackArray())
            return true
        }
        if (old.stack.size != cur.sp) {
            throw error(code.pc[target], "разная высота стека при слиянии (${old.stack.size} и ${cur.sp})")
        }
        var same = true
        val ol = old.locals
        val nl = cur.locals
        for (k in ol.indices) if (ol[k] != nl[k]) { same = false; break }
        if (same) {
            val os = old.stack
            val ns = cur.stack
            for (k in os.indices) if (os[k] != ns[k]) { same = false; break }
        }
        if (same) return false
        return mergeFrame(target, Frame(nl.copyOf(), cur.stackArray()))
    }

    /** Слияние в состояние перед инструкцией [target]; `true` — состояние изменилось. */
    private fun mergeFrame(target: Int, incoming: Frame): Boolean {
        val old = entry[target]
        if (old == null) {
            entry[target] = incoming
            return true
        }
        if (old.stack.size != incoming.stack.size) {
            throw error(code.pc[target], "разная высота стека при слиянии (${old.stack.size} и ${incoming.stack.size})")
        }
        var changed = false
        val locals = old.locals.copyOf()
        for (k in locals.indices) {
            val m = merge(locals[k], incoming.locals[k])
            if (m != locals[k]) { locals[k] = m; changed = true }
        }
        // Вторая половина long/double без первой — не значение.
        for (k in 0 until locals.size - 1) {
            if (locals[k].size == 2 && locals[k + 1] != VerType.Top) { locals[k] = VerType.Top; changed = true }
        }
        val stack = old.stack.copyOf()
        for (k in stack.indices) {
            val m = merge(stack[k], incoming.stack[k])
            if (m == VerType.Top && strict) {
                throw error(code.pc[target], "несовместимые типы на стеке при слиянии: ${stack[k]} и ${incoming.stack[k]}")
            }
            if (m != stack[k]) { stack[k] = m; changed = true }
        }
        if (changed) entry[target] = Frame(locals, stack)
        return changed
    }

    private fun merge(a: VerType, b: VerType): VerType {
        if (a == b) return a
        if (a === VerType.Null && b is VerType.Ref) return b
        if (b === VerType.Null && a is VerType.Ref) return a
        if (a is VerType.Ref && b is VerType.Ref) return VerType.Ref(lubDescriptor(a.descriptor, b.descriptor))
        return VerType.Top
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

    // ---- Переходы ----

    private inline fun forEachSuccessor(i: Int, visit: (Int) -> Unit) {
        val op = code.op[i]
        val targets = code.switchTargets[i]
        if (targets != null) {
            for (t in targets) visit(indexOrFail(i, t))
            return
        }
        when (op) {
            Opcodes.GOTO, Opcodes.GOTO_W -> visit(indexOrFail(i, code.a[i]))
            in Opcodes.IFEQ..Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                visit(indexOrFail(i, code.a[i]))
                visit(next(i))
            }
            in Opcodes.IRETURN..Opcodes.RETURN, Opcodes.ATHROW, Opcodes.RET -> {}
            else -> visit(next(i))
        }
    }

    private fun indexOrFail(i: Int, pc: Int): Int {
        val t = code.indexAt(pc)
        if (t < 0) throw error(code.pc[i], "переход на pc=$pc не на границу инструкции")
        return t
    }

    private fun next(i: Int): Int {
        if (i + 1 >= n) throw error(code.pc[i], "выполнение выходит за конец кода")
        return i + 1
    }

    // ---- Семантика инструкций ----

    private fun execute(i: Int, s: Working) {
        val op = code.op[i]
        val a = code.a[i]
        when (op) {
            Opcodes.NOP, Opcodes.IINC, Opcodes.GOTO, Opcodes.GOTO_W, Opcodes.RETURN,
            Opcodes.BREAKPOINT, Opcodes.IMPDEP1, Opcodes.IMPDEP2 -> {}
            Opcodes.ACONST_NULL -> s.push(VerType.Null)
            in Opcodes.ICONST_M1..Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH -> s.push(VerType.IntT)
            Opcodes.LCONST_0, Opcodes.LCONST_1 -> s.push(VerType.LongT)
            Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> s.push(VerType.FloatT)
            Opcodes.DCONST_0, Opcodes.DCONST_1 -> s.push(VerType.DoubleT)
            Opcodes.LDC, Opcodes.LDC_W, Opcodes.LDC2_W -> s.push(pool.ldcType(a))

            Opcodes.ILOAD -> s.push(VerType.IntT)
            Opcodes.LLOAD -> s.push(VerType.LongT)
            Opcodes.FLOAD -> s.push(VerType.FloatT)
            Opcodes.DLOAD -> s.push(VerType.DoubleT)
            Opcodes.ALOAD -> s.push(s.locals[a])

            Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE, Opcodes.LSTORE, Opcodes.DSTORE -> s.store(a, s.pop())

            Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> { s.pop(); s.pop(); s.push(VerType.IntT) }
            Opcodes.LALOAD -> { s.pop(); s.pop(); s.push(VerType.LongT) }
            Opcodes.FALOAD -> { s.pop(); s.pop(); s.push(VerType.FloatT) }
            Opcodes.DALOAD -> { s.pop(); s.pop(); s.push(VerType.DoubleT) }
            Opcodes.AALOAD -> { s.pop(); s.push(componentOf(s.pop())) }
            in Opcodes.IASTORE..Opcodes.SASTORE -> { s.pop(); s.pop(); s.pop() }

            Opcodes.POP -> s.pop()
            Opcodes.POP2 -> if (s.pop().size == 1) s.pop()
            Opcodes.DUP -> { val v = s.pop(); s.push(v); s.push(v) }
            Opcodes.DUP_X1 -> { val v1 = s.pop(); val v2 = s.pop(); s.push(v1); s.push(v2); s.push(v1) }
            Opcodes.DUP_X2 -> {
                val v1 = s.pop(); val v2 = s.pop()
                if (v2.size == 2) { s.push(v1); s.push(v2); s.push(v1) }
                else { val v3 = s.pop(); s.push(v1); s.push(v3); s.push(v2); s.push(v1) }
            }
            Opcodes.DUP2 -> {
                val v1 = s.pop()
                if (v1.size == 2) { s.push(v1); s.push(v1) }
                else { val v2 = s.pop(); s.push(v2); s.push(v1); s.push(v2); s.push(v1) }
            }
            Opcodes.DUP2_X1 -> {
                val v1 = s.pop()
                if (v1.size == 2) { val v2 = s.pop(); s.push(v1); s.push(v2); s.push(v1) }
                else { val v2 = s.pop(); val v3 = s.pop(); s.push(v2); s.push(v1); s.push(v3); s.push(v2); s.push(v1) }
            }
            Opcodes.DUP2_X2 -> {
                val v1 = s.pop()
                if (v1.size == 2) {
                    val v2 = s.pop()
                    if (v2.size == 2) { s.push(v1); s.push(v2); s.push(v1) }
                    else { val v3 = s.pop(); s.push(v1); s.push(v3); s.push(v2); s.push(v1) }
                } else {
                    val v2 = s.pop(); val v3 = s.pop()
                    if (v3.size == 2) { s.push(v2); s.push(v1); s.push(v3); s.push(v2); s.push(v1) }
                    else { val v4 = s.pop(); s.push(v2); s.push(v1); s.push(v4); s.push(v3); s.push(v2); s.push(v1) }
                }
            }
            Opcodes.SWAP -> { val v1 = s.pop(); val v2 = s.pop(); s.push(v1); s.push(v2) }

            Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL, Opcodes.ISHR,
            Opcodes.IUSHR, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> { s.pop(); s.pop(); s.push(VerType.IntT) }
            Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LSHL, Opcodes.LSHR,
            Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> { s.pop(); s.pop(); s.push(VerType.LongT) }
            Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM -> { s.pop(); s.pop(); s.push(VerType.FloatT) }
            Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> { s.pop(); s.pop(); s.push(VerType.DoubleT) }
            Opcodes.INEG -> { s.pop(); s.push(VerType.IntT) }
            Opcodes.LNEG -> { s.pop(); s.push(VerType.LongT) }
            Opcodes.FNEG -> { s.pop(); s.push(VerType.FloatT) }
            Opcodes.DNEG -> { s.pop(); s.push(VerType.DoubleT) }

            Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> { s.pop(); s.push(VerType.LongT) }
            Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> { s.pop(); s.push(VerType.FloatT) }
            Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> { s.pop(); s.push(VerType.DoubleT) }
            Opcodes.L2I, Opcodes.F2I, Opcodes.D2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> { s.pop(); s.push(VerType.IntT) }
            Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> { s.pop(); s.pop(); s.push(VerType.IntT) }

            in Opcodes.IFEQ..Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL -> s.pop()
            in Opcodes.IF_ICMPEQ..Opcodes.IF_ACMPNE -> { s.pop(); s.pop() }
            Opcodes.JSR, Opcodes.JSR_W -> {
                if (strict) throw IllegalArgumentException("jsr/ret несовместимы с StackMapTable (запрещены с версии класса 51)")
                s.push(VerType.ReturnAddress)
            }
            Opcodes.RET -> if (strict) throw IllegalArgumentException("jsr/ret несовместимы с StackMapTable (запрещены с версии класса 51)")
            Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> s.pop()
            in Opcodes.IRETURN..Opcodes.ARETURN, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> s.pop()

            Opcodes.GETSTATIC -> s.push(VerType.of(pool.member(a).descriptor))
            Opcodes.PUTSTATIC -> s.pop()
            Opcodes.GETFIELD -> { s.pop(); s.push(VerType.of(pool.member(a).descriptor)) }
            Opcodes.PUTFIELD -> { s.pop(); s.pop() }

            Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                val ref = pool.member(a)
                val d = desc(ref.descriptor)
                repeat(d.params.size) { s.pop() }
                if (op != Opcodes.INVOKESTATIC) {
                    val receiver = s.pop()
                    if (op == Opcodes.INVOKESPECIAL && ref.name == "<init>") initialize(s, receiver, code.pc[i])
                }
                d.ret?.let { s.push(it) }
            }
            Opcodes.INVOKEDYNAMIC -> {
                val d = desc(pool.invokeDynamicDescriptor(a))
                repeat(d.params.size) { s.pop() }
                d.ret?.let { s.push(it) }
            }

            Opcodes.NEW -> {
                val pc = code.pc[i]
                newTypes[pc] = VerType.ofClassName(pool.className(a))
                s.push(VerType.Uninit(pc))
            }
            Opcodes.NEWARRAY -> { s.pop(); s.push(VerType.Ref("[" + primitiveArrayElement(code.b[i]))) }
            Opcodes.ANEWARRAY -> { s.pop(); s.push(VerType.Ref("[" + VerType.ofClassName(pool.className(a)).descriptor)) }
            Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF -> { s.pop(); s.push(VerType.IntT) }
            Opcodes.CHECKCAST -> { s.pop(); s.push(VerType.ofClassName(pool.className(a))) }
            Opcodes.MULTIANEWARRAY -> { repeat(code.b[i]) { s.pop() }; s.push(VerType.ofClassName(pool.className(a))) }

            else -> throw IllegalArgumentException("неподдерживаемый опкод 0x${Integer.toHexString(op)}")
        }
    }

    /** После invokespecial <init> все копии неинициализированного значения становятся объектом класса. */
    private fun initialize(s: Working, receiver: VerType, pc: Int) {
        val target: VerType = when (receiver) {
            is VerType.Uninit -> newTypes[receiver.newPc]
                ?: throw IllegalArgumentException("<init> для uninitialized(${receiver.newPc}) без new")
            VerType.UninitThis -> VerType.ofClassName(owner)
            else -> throw IllegalArgumentException("invokespecial <init> на инициализированном значении $receiver (pc=$pc)")
        }
        for (k in s.locals.indices) if (s.locals[k] == receiver) s.locals[k] = target
        for (k in 0 until s.sp) if (s.stack[k] == receiver) s.stack[k] = target
    }

    private fun componentOf(array: VerType): VerType = when {
        array === VerType.Null -> VerType.Null
        array is VerType.Ref && array.descriptor.startsWith("[") -> VerType.of(array.descriptor.substring(1))
        else -> VerType.OBJECT
    }

    private fun primitiveArrayElement(atype: Int): String = when (atype) {
        4 -> "Z"; 5 -> "C"; 6 -> "F"; 7 -> "D"; 8 -> "B"; 9 -> "S"; 10 -> "I"; 11 -> "J"
        else -> throw IllegalArgumentException("newarray: неизвестный atype $atype")
    }

    private fun desc(d: String): MethodDesc = MethodDesc.of(d)

    private fun error(pc: Int, msg: String, cause: Throwable? = null) =
        AnalysisException("Метод $owner.$methodName$descriptor, pc=$pc: $msg", cause)

    /** Рабочее состояние интерпретации одной инструкции. */
    private class Working(numLocals: Int) {
        val locals = Array<VerType>(numLocals) { VerType.Top }
        var stack = arrayOfNulls<VerType>(16)
        var sp = 0
        var words = 0

        fun load(f: Frame) {
            System.arraycopy(f.locals, 0, locals, 0, locals.size)
            if (stack.size < f.stack.size + 8) stack = arrayOfNulls(f.stack.size + 16)
            sp = 0
            words = 0
            for (t in f.stack) push(t)
        }

        fun push(t: VerType) {
            if (sp == stack.size) stack = stack.copyOf(sp * 2)
            stack[sp++] = t
            words += t.size
        }

        fun pop(): VerType {
            if (sp == 0) throw IllegalArgumentException("снятие со пустого стека")
            val t = stack[--sp]!!
            words -= t.size
            return t
        }

        fun store(slot: Int, t: VerType) {
            if (slot > 0 && locals[slot - 1].size == 2) locals[slot - 1] = VerType.Top
            locals[slot] = t
            if (t.size == 2) locals[slot + 1] = VerType.Top
        }

        @Suppress("UNCHECKED_CAST")
        fun stackArray(): Array<VerType> = if (sp == 0) EMPTY else (stack.copyOf(sp) as Array<VerType>)
    }

    private companion object {
        val EMPTY = emptyArray<VerType>()
    }
}

/** Ошибка анализа кода метода (некорректный байткод). */
class AnalysisException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Разобранный дескриптор метода (общий кэш: одни и те же дескрипторы встречаются во всех методах). */
internal class MethodDesc(val params: Array<VerType>, val ret: VerType?) {
    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, MethodDesc>()

        fun of(d: String): MethodDesc = cache[d] ?: parse(d).also { if (cache.size < (1 shl 18)) cache.putIfAbsent(d, it) }

        private fun parse(d: String): MethodDesc {
            require(d.startsWith("(")) { "Некорректный дескриптор метода: $d" }
            val params = ArrayList<VerType>(4)
            var p = 1
            while (d[p] != ')') {
                val start = p
                while (d[p] == '[') p++
                if (d[p] == 'L') p = d.indexOf(';', p)
                require(p > 0) { "Некорректный дескриптор метода: $d" }
                p++
                params.add(VerType.of(d.substring(start, p)))
            }
            val ret = d.substring(p + 1)
            return MethodDesc(params.toTypedArray(), if (ret == "V") null else VerType.of(ret))
        }
    }
}
