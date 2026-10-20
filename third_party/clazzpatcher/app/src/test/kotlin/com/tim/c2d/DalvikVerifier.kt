package com.tim.c2d

import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.*
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.Opcode
import com.tim.dex.model.CodeItem
import com.tim.dex.model.DexFile

/**
 * Проверка типов регистров в духе верификатора ART (art/runtime/verifier): поток данных
 * по инструкциям с типами регистров, слиянием в точках перехода и в обработчиках исключений.
 *
 * Проверяется то, что ART считает жёсткой ошибкой: категория значения (int/float/long/double/
 * ссылка), целостность пар регистров, неинициализированные регистры и объекты, соответствие
 * вариантов aget/iget/… типу массива или поля, типы аргументов вызовов и результата,
 * move-result сразу после invoke, move-exception только в начале обработчика.
 * Иерархия классов не известна, поэтому совместимость ссылочных типов не проверяется.
 */
object DalvikVerifier {

    /** Ошибки по методам: пусто — всё корректно. */
    fun verify(dex: DexFile): List<String> {
        val errors = ArrayList<String>()
        val privates = privateMembers(dex)
        for (cls in dex.classDefs) {
            if (cls.classDataIdx < 0) continue
            val data = dex.classDataItems[cls.classDataIdx]
            val classDesc = type(dex, cls.classIdx)
            for (m in data.directMethods + data.virtualMethods) {
                if (m.codeIdx < 0) continue
                val id = dex.methodIds[m.methodIdx]
                val name = dex.strings[id.nameIdx]
                val proto = dex.protoIds[id.protoIdx]
                val params = if (proto.parametersTypeListIdx < 0) emptyList()
                else dex.typeLists[proto.parametersTypeListIdx].typeIdx.map { type(dex, it) }
                val ret = type(dex, proto.returnTypeIdx)
                val where = "$classDesc->$name(${params.joinToString("")})$ret"
                try {
                    MethodCheck(dex, classDesc, name, params, ret, m.accessFlags and 0x8 != 0, dex.codeItems[m.codeIdx], privates).run()
                } catch (e: VerifyError) {
                    errors.add("$where: ${e.message}")
                }
            }
        }
        return errors
    }

    private class VerifyError(msg: String) : RuntimeException(msg)

    /**
     * private-члены классов этого DEX: `владелец->имя:тип` (поля) и `владелец->имя(протоId)` (методы).
     * ART не пускает к ним из других классов (IllegalAccessError), гнёзда Java 11+ он не знает.
     */
    private fun privateMembers(dex: DexFile): Set<String> {
        val out = HashSet<String>()
        for (cls in dex.classDefs) {
            if (cls.classDataIdx < 0) continue
            val data = dex.classDataItems[cls.classDataIdx]
            for (f in data.staticFields + data.instanceFields) if (f.accessFlags and 0x2 != 0) out.add("f${f.fieldIdx}")
            for (m in data.directMethods + data.virtualMethods) if (m.accessFlags and 0x2 != 0) out.add("m${m.methodIdx}")
        }
        return out
    }

    private fun type(dex: DexFile, idx: Int): String = dex.strings[dex.typeIds[idx].descriptorIdx]

    // ---- типы регистров ----

    private const val UNDEF = 0
    private const val CONFLICT = 1
    private const val CONST = 2
    private const val BOOLEAN = 3
    private const val BYTE = 4
    private const val SHORT = 5
    private const val CHAR = 6
    private const val INT = 7
    private const val FLOAT = 8
    private const val LONG_LO = 9
    private const val LONG_HI = 10
    private const val DOUBLE_LO = 11
    private const val DOUBLE_HI = 12
    private const val CWIDE_LO = 13
    private const val CWIDE_HI = 14
    private const val REF = 15
    private const val UNINIT = 16
    private const val UNINIT_THIS = 17

    private data class T(val kind: Int, val desc: String? = null, val lo: Long = 0, val hi: Long = 0, val pc: Int = -1) {
        override fun toString(): String = when (kind) {
            UNDEF -> "undefined"
            CONFLICT -> "conflict"
            CONST -> if (lo == hi) "const($lo)" else "const($lo..$hi)"
            REF -> desc!!
            UNINIT -> "uninit($desc@$pc)"
            UNINIT_THIS -> "uninitThis($desc)"
            else -> listOf("", "", "", "boolean", "byte", "short", "char", "int", "float", "long", "long-hi",
                "double", "double-hi", "const-wide", "const-wide-hi")[kind]
        }
    }

    private val UNDEF_T = T(UNDEF)
    private val CONFLICT_T = T(CONFLICT)
    private val BOOLEAN_T = T(BOOLEAN)
    private val BYTE_T = T(BYTE)
    private val SHORT_T = T(SHORT)
    private val CHAR_T = T(CHAR)
    private val INT_T = T(INT)
    private val FLOAT_T = T(FLOAT)
    private val OBJECT_T = T(REF, "Ljava/lang/Object;")

    private fun const(v: Long) = T(CONST, lo = v, hi = v)
    private fun ref(desc: String) = T(REF, desc)

    private fun isIntegral(t: T) = t.kind == CONST || t.kind in BOOLEAN..INT
    private fun isFloatish(t: T) = t.kind == CONST || t.kind == FLOAT
    private fun isZero(t: T) = t.kind == CONST && t.lo == 0L && t.hi == 0L
    private fun isRef(t: T) = t.kind == REF || isZero(t)
    private fun isRefOrUninit(t: T) = isRef(t) || t.kind == UNINIT || t.kind == UNINIT_THIS

    /** Наименьший целочисленный тип, вмещающий диапазон константы. */
    private fun classify(t: T): Int = when {
        t.kind != CONST -> t.kind
        t.lo >= 0 && t.hi <= 1 -> BOOLEAN
        t.lo >= -128 && t.hi <= 127 -> BYTE
        t.lo >= 0 && t.hi <= 65535 -> CHAR
        t.lo >= -32768 && t.hi <= 32767 -> SHORT
        else -> INT
    }

    private fun integralLub(a: Int, b: Int): Int {
        if (a == b) return a
        val (x, y) = if (a < b) a to b else b to a
        return when {
            x == BOOLEAN -> y
            x == BYTE && y == SHORT -> SHORT
            else -> INT
        }
    }

    private fun fits(t: T, kind: Int): Boolean = when (kind) {
        BOOLEAN -> t.kind == BOOLEAN || (t.kind == CONST && t.lo >= 0 && t.hi <= 1)
        BYTE -> t.kind == BYTE || t.kind == BOOLEAN || (t.kind == CONST && t.lo >= -128 && t.hi <= 127)
        SHORT -> t.kind == SHORT || t.kind == BYTE || t.kind == BOOLEAN || (t.kind == CONST && t.lo >= -32768 && t.hi <= 32767)
        CHAR -> t.kind == CHAR || t.kind == BOOLEAN || (t.kind == CONST && t.lo >= 0 && t.hi <= 65535)
        INT -> isIntegral(t)
        FLOAT -> isFloatish(t)
        else -> false
    }

    private fun merge(a: T, b: T): T {
        if (a == b) return a
        if (a.kind == UNDEF || b.kind == UNDEF || a.kind == CONFLICT || b.kind == CONFLICT) return CONFLICT_T
        if (a.kind == CONST && b.kind == CONST) return T(CONST, lo = minOf(a.lo, b.lo), hi = maxOf(a.hi, b.hi))
        if (isIntegral(a) && isIntegral(b)) {
            if (a.kind == CONST && fits(a, b.kind)) return b
            if (b.kind == CONST && fits(b, a.kind)) return a
            return T(integralLub(classify(a), classify(b)))
        }
        if ((a.kind == CONST && b.kind == FLOAT) || (a.kind == FLOAT && b.kind == CONST)) return FLOAT_T
        if (isRef(a) && isRef(b)) {
            if (isZero(a)) return b
            if (isZero(b)) return a
            return ref(mergeRefDesc(a.desc!!, b.desc!!))
        }
        if (a.kind == CWIDE_LO && (b.kind == LONG_LO || b.kind == DOUBLE_LO)) return b
        if (b.kind == CWIDE_LO && (a.kind == LONG_LO || a.kind == DOUBLE_LO)) return a
        if (a.kind == CWIDE_HI && (b.kind == LONG_HI || b.kind == DOUBLE_HI)) return b
        if (b.kind == CWIDE_HI && (a.kind == LONG_HI || a.kind == DOUBLE_HI)) return a
        return CONFLICT_T
    }

    /**
     * Общий предок двух ссылочных типов без знания иерархии классов: массивы ссылок сливаются
     * покомпонентно (как у ART), остальное — в `Object`.
     */
    private fun mergeRefDesc(a: String, b: String): String {
        if (a == b) return a
        if (a.startsWith("[") && b.startsWith("[")) {
            val ca = a.substring(1)
            val cb = b.substring(1)
            val refs = (ca[0] == 'L' || ca[0] == '[') && (cb[0] == 'L' || cb[0] == '[')
            if (refs) return "[" + mergeRefDesc(ca, cb)
        }
        return "Ljava/lang/Object;"
    }

    private fun ofDesc(desc: String): T = when (desc[0]) {
        'Z' -> BOOLEAN_T
        'B' -> BYTE_T
        'S' -> SHORT_T
        'C' -> CHAR_T
        'I' -> INT_T
        'F' -> FLOAT_T
        'L', '[' -> ref(desc)
        else -> throw VerifyError("недопустимый тип значения $desc")
    }

    private fun isWideDesc(d: String) = d == "J" || d == "D"

    // ---- проверка метода ----

    private class State(val regs: Array<T>, var result: T?) {
        fun copy() = State(regs.copyOf(), result)
    }

    private class MethodCheck(
        private val dex: DexFile,
        private val classDesc: String,
        private val name: String,
        private val params: List<String>,
        private val ret: String,
        private val isStatic: Boolean,
        private val code: CodeItem,
        private val privates: Set<String>
    ) {
        private val decoded = DexDisassembler().decode(code.insns)
        private val insns = decoded.instructions
        private val n = insns.size
        private val states = arrayOfNulls<State>(n)
        private val handlerStart = BooleanArray(n)
        private val normalTarget = BooleanArray(n)
        private val handlersOf = arrayOfNulls<List<Pair<Int, String?>>>(n)
        private var pcIndex = 0

        fun run() {
            buildHandlers()
            states[0] = initial()
            val queue = ArrayDeque<Int>()
            val queued = BooleanArray(n)
            queue.add(0); queued[0] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                queued[i] = false
                pcIndex = i
                val before = states[i]!!
                val st = before.copy()
                val insn = insns[i]
                if (insn.isPayload()) fail("исполнение попадает в payload")
                if (insn.opcode != Opcode.MOVE_RESULT && insn.opcode != Opcode.MOVE_RESULT_WIDE &&
                    insn.opcode != Opcode.MOVE_RESULT_OBJECT) st.result = null
                val flow = execute(insn, st, before)
                fun enqueue(target: Int, s: State) {
                    val old = states[target]
                    if (old == null) {
                        states[target] = s.copy(); if (!queued[target]) { queue.add(target); queued[target] = true }
                    } else {
                        var changed = false
                        for (r in old.regs.indices) {
                            val m = merge(old.regs[r], s.regs[r])
                            if (m != old.regs[r]) { old.regs[r] = m; changed = true }
                        }
                        val res = if (old.result == null || s.result == null) null else merge(old.result!!, s.result!!)
                        if (res != old.result) { old.result = res; changed = true }
                        if (changed && !queued[target]) { queue.add(target); queued[target] = true }
                    }
                }
                if (canThrow(insn.opcode)) {
                    handlersOf[i]?.let { hs ->
                        val exc = before.copy(); exc.result = null
                        for ((h, _) in hs) enqueue(h, exc)
                    }
                }
                for (t in flow.targets) {
                    if (insns[t].opcode == Opcode.MOVE_EXCEPTION) fail("переход на move-exception")
                    enqueue(t, st)
                }
                if (flow.fallsThrough) {
                    val next = i + 1
                    if (next >= n) fail("исполнение выходит за конец кода")
                    if (insns[next].isPayload()) fail("проваливание в payload")
                    if (insns[next].opcode == Opcode.MOVE_EXCEPTION) fail("проваливание в move-exception")
                    enqueue(next, st)
                }
            }
        }

        private fun buildHandlers() {
            for (t in code.tries) {
                val h = code.catchHandlers[t.handlerIdx]
                val list = ArrayList<Pair<Int, String?>>()
                for (p in h.handlers) list.add(indexAt(p.addr) to dex.strings[dex.typeIds[p.typeIdx].descriptorIdx])
                if (h.catchAllAddr >= 0) list.add(indexAt(h.catchAllAddr) to null)
                for ((idx, _) in list) handlerStart[idx] = true
                for (i in 0 until n) {
                    val a = decoded.offsets[i]
                    if (a >= t.startAddr && a < t.startAddr + t.insnCount) handlersOf[i] = list
                }
            }
        }

        private fun indexAt(addr: Int): Int {
            val i = decoded.indexAt(addr)
            if (i < 0) fail("адрес $addr — не начало инструкции")
            return i
        }

        private fun initial(): State {
            val regs = Array(code.registersSize) { UNDEF_T }
            val ins = (if (isStatic) 0 else 1) + params.size + params.count { isWideDesc(it) }
            if (ins != code.insSize) fail("ins_size ${code.insSize}, а по сигнатуре $ins")
            var r = code.registersSize - ins
            if (!isStatic) {
                regs[r++] = if (name == "<init>" && classDesc != "Ljava/lang/Object;") T(UNINIT_THIS, classDesc) else ref(classDesc)
            }
            for (p in params) {
                when (p) {
                    "J" -> { regs[r] = T(LONG_LO); regs[r + 1] = T(LONG_HI); r += 2 }
                    "D" -> { regs[r] = T(DOUBLE_LO); regs[r + 1] = T(DOUBLE_HI); r += 2 }
                    else -> regs[r++] = ofDesc(p)
                }
            }
            return State(regs, null)
        }

        private class Flow(val fallsThrough: Boolean, val targets: List<Int>)

        private val CONTINUE = Flow(true, emptyList())
        private val STOP = Flow(false, emptyList())

        private fun fail(msg: String): Nothing =
            throw VerifyError("0x${Integer.toHexString(decoded.offsets.getOrElse(pcIndex) { 0 })} ${insns.getOrNull(pcIndex)?.opcode?.mnemonic}: $msg")

        // ---- регистры ----

        private fun get(s: State, r: Int): T {
            if (r < 0 || r >= s.regs.size) fail("регистр v$r вне кадра (${s.regs.size})")
            val t = s.regs[r]
            if (t.kind == UNDEF) fail("регистр v$r не инициализирован")
            if (t.kind == CONFLICT) fail("регистр v$r с противоречивым типом")
            return t
        }

        private fun set(s: State, r: Int, t: T) {
            if (r < 0 || r >= s.regs.size) fail("регистр v$r вне кадра (${s.regs.size})")
            if (r > 0 && s.regs[r - 1].kind.let { it == LONG_LO || it == DOUBLE_LO || it == CWIDE_LO }) s.regs[r - 1] = CONFLICT_T
            if (s.regs[r].kind.let { it == LONG_LO || it == DOUBLE_LO || it == CWIDE_LO } && r + 1 < s.regs.size) s.regs[r + 1] = CONFLICT_T
            s.regs[r] = t
        }

        private fun setWide(s: State, r: Int, lo: Int) {
            if (r < 0 || r + 1 >= s.regs.size) fail("пара v$r/v${r + 1} вне кадра (${s.regs.size})")
            set(s, r, T(lo))
            if (s.regs[r + 1].kind.let { it == LONG_LO || it == DOUBLE_LO || it == CWIDE_LO } && r + 2 < s.regs.size) s.regs[r + 2] = CONFLICT_T
            s.regs[r + 1] = T(lo + 1)
        }

        private fun reqInt(s: State, r: Int) { val t = get(s, r); if (!isIntegral(t)) fail("v$r: ожидался int, а там $t") }
        private fun reqFloat(s: State, r: Int) { val t = get(s, r); if (!isFloatish(t)) fail("v$r: ожидался float, а там $t") }
        private fun reqRef(s: State, r: Int) { val t = get(s, r); if (!isRef(t)) fail("v$r: ожидалась ссылка, а там $t") }

        /** Пара регистров: long (want = LONG_LO), double (DOUBLE_LO) или любая (0). */
        private fun reqWide(s: State, r: Int, want: Int): Int {
            val lo = get(s, r)
            if (r + 1 >= s.regs.size) fail("пара v$r/v${r + 1} вне кадра")
            val hi = s.regs[r + 1]
            val ok = when (lo.kind) {
                CWIDE_LO -> hi.kind == CWIDE_HI && (want == 0 || true)
                LONG_LO -> hi.kind == LONG_HI && (want == 0 || want == LONG_LO)
                DOUBLE_LO -> hi.kind == DOUBLE_HI && (want == 0 || want == DOUBLE_LO)
                else -> false
            }
            if (!ok) fail("v$r/v${r + 1}: ожидалась пара ${if (want == DOUBLE_LO) "double" else "long"}, а там $lo/$hi")
            return lo.kind
        }

        /**
         * Аргумент вызова по правилам ART (VerifyInvocationArgsFromIterator): для целочисленных
         * параметров (Z/B/S/C/I) достаточно целочисленного значения, остальное — по категории.
         */
        private fun reqArg(s: State, r: Int, desc: String) {
            when (desc) {
                "J" -> reqWide(s, r, LONG_LO)
                "D" -> reqWide(s, r, DOUBLE_LO)
                else -> {
                    val t = get(s, r)
                    val ok = when (desc[0]) {
                        'Z', 'B', 'S', 'C', 'I' -> isIntegral(t)
                        'F' -> isFloatish(t)
                        else -> isRef(t)
                    }
                    if (!ok) fail("v$r: аргумент типа $desc, а в регистре $t")
                }
            }
        }

        /** Значение для iput/sput/aput: у примитивов ART требует лишь ту же категорию. */
        private fun reqValue(s: State, r: Int, desc: String) {
            when (desc[0]) {
                'J' -> reqWide(s, r, LONG_LO)
                'D' -> reqWide(s, r, DOUBLE_LO)
                'F' -> reqFloat(s, r)
                'Z', 'B', 'S', 'C', 'I' -> reqInt(s, r)
                else -> reqRef(s, r)
            }
        }

        private fun setValue(s: State, r: Int, desc: String) {
            when (desc) {
                "J" -> setWide(s, r, LONG_LO)
                "D" -> setWide(s, r, DOUBLE_LO)
                else -> set(s, r, ofDesc(desc))
            }
        }

        private fun target(insn: Instruction): Int = indexAt(decoded.offsets[pcIndex] + insn.getBranchOffset()!!)

        // ---- семантика инструкций ----

        private fun execute(insn: Instruction, s: State, before: State): Flow {
            val op = insn.opcode
            when (insn) {
                is Ins10x -> when (op) {
                    Opcode.NOP -> {}
                    Opcode.RETURN_VOID -> {
                        if (ret != "V") fail("return-void в методе с результатом $ret")
                        checkConstructorReturn(s)
                        return STOP
                    }
                    else -> fail("неожиданная инструкция")
                }
                is Ins12x -> unaryOrMove(op, insn.regA, insn.regB, s)
                is Ins22x -> unaryOrMove(op, insn.regA, insn.regB, s)
                is Ins32x -> unaryOrMove(op, insn.regA, insn.regB, s)
                is Ins11x -> return oneReg(op, insn.regA, s, before)
                is Ins11n -> set(s, insn.regA, const(insn.value.toLong()))
                is Ins21s -> if (op == Opcode.CONST_16) set(s, insn.regA, const(insn.value.toLong())) else setWide(s, insn.regA, CWIDE_LO)
                is Ins21h -> if (op == Opcode.CONST_HIGH16) set(s, insn.regA, const((insn.value shl 16).toLong())) else setWide(s, insn.regA, CWIDE_LO)
                is Ins31i -> if (op == Opcode.CONST) set(s, insn.regA, const(insn.value.toLong())) else setWide(s, insn.regA, CWIDE_LO)
                is Ins51l -> setWide(s, insn.regA, CWIDE_LO)
                is Ins21c -> index21c(op, insn.regA, insn.index, s)
                is Ins31c -> set(s, insn.regA, ref("Ljava/lang/String;"))
                is Ins22c -> index22c(op, insn.regA, insn.regB, insn.index, s)
                is Ins23x -> binary23x(op, insn.regA, insn.regB, insn.regC, s)
                is Ins22b -> { reqInt(s, insn.regB); set(s, insn.regA, INT_T) }
                is Ins22s -> { reqInt(s, insn.regB); set(s, insn.regA, INT_T) }
                is Ins10t, is Ins20t, is Ins30t -> return Flow(false, listOf(target(insn)))
                is Ins21t -> {
                    val t = get(s, insn.regA)
                    val ok = if (op == Opcode.IF_EQZ || op == Opcode.IF_NEZ) isIntegral(t) || isRef(t) else isIntegral(t)
                    if (!ok) fail("v${insn.regA}: сравнение с нулём для $t")
                    return Flow(true, listOf(target(insn)))
                }
                is Ins22t -> {
                    val a = get(s, insn.regA)
                    val b = get(s, insn.regB)
                    val ok = if (op == Opcode.IF_EQ || op == Opcode.IF_NE) {
                        (isIntegral(a) && isIntegral(b)) || (isRef(a) && isRef(b))
                    } else isIntegral(a) && isIntegral(b)
                    if (!ok) fail("v${insn.regA}, v${insn.regB}: несравнимые типы $a и $b")
                    return Flow(true, listOf(target(insn)))
                }
                is Ins31t -> {
                    val payloadIdx = decoded.payloadIndex[pcIndex]
                    if (payloadIdx < 0) fail("нет payload")
                    when (op) {
                        Opcode.FILL_ARRAY_DATA -> {
                            val t = get(s, insn.regA)
                            if (!(t.kind == REF && t.desc!!.length == 2 && t.desc[0] == '[') && !isZero(t)) {
                                fail("fill-array-data для $t")
                            }
                        }
                        else -> {
                            reqInt(s, insn.regA)
                            val payload = insns[payloadIdx]
                            val targets = when (payload) {
                                is PackedSwitchData -> payload.targets
                                is SparseSwitchData -> payload.targets
                                else -> fail("switch ссылается не на switch-payload")
                            }
                            return Flow(true, targets.map { indexAt(it) })
                        }
                    }
                }
                is Ins35c -> invoke(op, insn.index, insn.getRegisters(), s)
                is Ins3rc -> invoke(op, insn.index, (insn.regStart until insn.regStart + insn.regCount).toList(), s)
                is Ins45cc -> polymorphic(insn.protoIdx, insn.getRegisters(), s)
                is Ins4rcc -> polymorphic(insn.protoIdx, (insn.regStart until insn.regStart + insn.regCount).toList(), s)
                else -> fail("неподдерживаемый формат ${insn.format}")
            }
            return CONTINUE
        }

        private fun checkConstructorReturn(s: State) {
            if (name == "<init>" && s.regs.any { it.kind == UNINIT_THIS }) fail("конструктор возвращается без вызова <init> суперкласса")
        }

        private fun unaryOrMove(op: Opcode, a: Int, b: Int, s: State) {
            when (op) {
                Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16 -> {
                    val t = get(s, b)
                    if (!(isIntegral(t) || t.kind == FLOAT)) fail("move из v$b с типом $t")
                    set(s, a, t)
                }
                Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16 -> {
                    val lo = reqWide(s, b, 0)
                    setWide(s, a, lo)
                }
                Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                    val t = get(s, b)
                    if (!isRefOrUninit(t)) fail("move-object из v$b с типом $t")
                    set(s, a, t)
                }
                Opcode.ARRAY_LENGTH -> {
                    val t = get(s, b)
                    if (!(isZero(t) || (t.kind == REF && t.desc!!.startsWith("[")))) fail("array-length для $t")
                    set(s, a, INT_T)
                }
                Opcode.NEG_INT, Opcode.NOT_INT -> { reqInt(s, b); set(s, a, INT_T) }
                Opcode.NEG_LONG, Opcode.NOT_LONG -> { reqWide(s, b, LONG_LO); setWide(s, a, LONG_LO) }
                Opcode.NEG_FLOAT -> { reqFloat(s, b); set(s, a, FLOAT_T) }
                Opcode.NEG_DOUBLE -> { reqWide(s, b, DOUBLE_LO); setWide(s, a, DOUBLE_LO) }
                Opcode.INT_TO_LONG -> { reqInt(s, b); setWide(s, a, LONG_LO) }
                Opcode.INT_TO_FLOAT -> { reqInt(s, b); set(s, a, FLOAT_T) }
                Opcode.INT_TO_DOUBLE -> { reqInt(s, b); setWide(s, a, DOUBLE_LO) }
                Opcode.LONG_TO_INT -> { reqWide(s, b, LONG_LO); set(s, a, INT_T) }
                Opcode.LONG_TO_FLOAT -> { reqWide(s, b, LONG_LO); set(s, a, FLOAT_T) }
                Opcode.LONG_TO_DOUBLE -> { reqWide(s, b, LONG_LO); setWide(s, a, DOUBLE_LO) }
                Opcode.FLOAT_TO_INT -> { reqFloat(s, b); set(s, a, INT_T) }
                Opcode.FLOAT_TO_LONG -> { reqFloat(s, b); setWide(s, a, LONG_LO) }
                Opcode.FLOAT_TO_DOUBLE -> { reqFloat(s, b); setWide(s, a, DOUBLE_LO) }
                Opcode.DOUBLE_TO_INT -> { reqWide(s, b, DOUBLE_LO); set(s, a, INT_T) }
                Opcode.DOUBLE_TO_LONG -> { reqWide(s, b, DOUBLE_LO); setWide(s, a, LONG_LO) }
                Opcode.DOUBLE_TO_FLOAT -> { reqWide(s, b, DOUBLE_LO); set(s, a, FLOAT_T) }
                Opcode.INT_TO_BYTE -> { reqInt(s, b); set(s, a, BYTE_T) }
                Opcode.INT_TO_CHAR -> { reqInt(s, b); set(s, a, CHAR_T) }
                Opcode.INT_TO_SHORT -> { reqInt(s, b); set(s, a, SHORT_T) }
                else -> {
                    // 2addr: vA = vA op vB
                    val m = op.mnemonic
                    when {
                        m.endsWith("-long/2addr") -> {
                            reqWide(s, a, LONG_LO)
                            if (m.startsWith("shl") || m.startsWith("shr") || m.startsWith("ushr")) reqInt(s, b) else reqWide(s, b, LONG_LO)
                            setWide(s, a, LONG_LO)
                        }
                        m.endsWith("-double/2addr") -> { reqWide(s, a, DOUBLE_LO); reqWide(s, b, DOUBLE_LO); setWide(s, a, DOUBLE_LO) }
                        m.endsWith("-float/2addr") -> { reqFloat(s, a); reqFloat(s, b); set(s, a, FLOAT_T) }
                        m.endsWith("-int/2addr") -> { reqInt(s, a); reqInt(s, b); set(s, a, INT_T) }
                        else -> fail("неожиданная инструкция")
                    }
                }
            }
        }

        private fun oneReg(op: Opcode, a: Int, s: State, before: State): Flow {
            when (op) {
                Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT -> {
                    val res = before.result ?: fail("move-result не сразу после invoke/filled-new-array")
                    if (pcIndex == 0 || !isInvoke(insns[pcIndex - 1].opcode)) fail("move-result не сразу после invoke")
                    when (op) {
                        Opcode.MOVE_RESULT -> {
                            if (!(isIntegral(res) || res.kind == FLOAT)) fail("move-result для результата $res")
                            set(s, a, res)
                        }
                        Opcode.MOVE_RESULT_WIDE -> {
                            if (res.kind != LONG_LO && res.kind != DOUBLE_LO) fail("move-result-wide для результата $res")
                            setWide(s, a, res.kind)
                        }
                        else -> {
                            if (!isRef(res)) fail("move-result-object для результата $res")
                            set(s, a, res)
                        }
                    }
                    s.result = null
                }
                Opcode.MOVE_EXCEPTION -> {
                    if (!handlerStart[pcIndex]) fail("move-exception не в начале обработчика")
                    set(s, a, ref("Ljava/lang/Throwable;"))
                }
                Opcode.RETURN -> {
                    val t = get(s, a)
                    val ok = when (ret) {
                        "Z", "B", "S", "C" -> isIntegral(t)
                        "I" -> isIntegral(t)
                        "F" -> isFloatish(t)
                        else -> false
                    }
                    if (!ok) fail("return v$a ($t) в методе с результатом $ret")
                    return STOP
                }
                Opcode.RETURN_WIDE -> {
                    if (ret != "J" && ret != "D") fail("return-wide в методе с результатом $ret")
                    reqWide(s, a, if (ret == "J") LONG_LO else DOUBLE_LO)
                    return STOP
                }
                Opcode.RETURN_OBJECT -> {
                    if (ret[0] != 'L' && ret[0] != '[') fail("return-object в методе с результатом $ret")
                    reqRef(s, a)
                    checkConstructorReturn(s)
                    return STOP
                }
                Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT -> reqRef(s, a)
                Opcode.THROW -> { reqRef(s, a); return STOP }
                else -> fail("неожиданная инструкция")
            }
            return CONTINUE
        }

        private fun index21c(op: Opcode, a: Int, index: Int, s: State) {
            when (op) {
                Opcode.CONST_STRING -> set(s, a, ref("Ljava/lang/String;"))
                Opcode.CONST_CLASS -> set(s, a, ref("Ljava/lang/Class;"))
                Opcode.CONST_METHOD_HANDLE -> set(s, a, ref("Ljava/lang/invoke/MethodHandle;"))
                Opcode.CONST_METHOD_TYPE -> set(s, a, ref("Ljava/lang/invoke/MethodType;"))
                Opcode.CHECK_CAST -> { reqRef(s, a); set(s, a, ref(typeAt(index))) }
                Opcode.NEW_INSTANCE -> {
                    val pc = decoded.offsets[pcIndex]
                    for (r in s.regs.indices) if (s.regs[r].kind == UNINIT && s.regs[r].pc == pc) s.regs[r] = CONFLICT_T
                    set(s, a, T(UNINIT, typeAt(index), pc = pc))
                }
                else -> {
                    // sget/sput
                    checkAccess("f", index, dex.fieldIds[index].classIdx)
                    val field = dex.fieldIds[index]
                    val desc = typeAt(field.typeIdx)
                    checkFieldVariant(op, desc)
                    if (op.mnemonic.startsWith("sget")) setValue(s, a, desc) else reqValue(s, a, desc)
                }
            }
        }

        private fun index22c(op: Opcode, a: Int, b: Int, index: Int, s: State) {
            when (op) {
                Opcode.INSTANCE_OF -> { reqRef(s, b); set(s, a, BOOLEAN_T) }
                Opcode.NEW_ARRAY -> {
                    reqInt(s, b)
                    val desc = typeAt(index)
                    if (!desc.startsWith("[")) fail("new-array не для массива: $desc")
                    set(s, a, ref(desc))
                }
                else -> {
                    checkAccess("f", index, dex.fieldIds[index].classIdx)
                    val field = dex.fieldIds[index]
                    val desc = typeAt(field.typeIdx)
                    checkFieldVariant(op, desc)
                    val obj = get(s, b)
                    val isPut = op.mnemonic.startsWith("iput")
                    val uninitThisPut = isPut && obj.kind == UNINIT_THIS && typeAt(field.classIdx) == classDesc
                    if (!isRef(obj) && !uninitThisPut) fail("v$b: объект поля — $obj")
                    if (isPut) reqValue(s, a, desc) else setValue(s, a, desc)
                }
            }
        }

        private fun checkAccess(kind: String, index: Int, ownerIdx: Int) {
            if ("$kind$index" in privates && typeAt(ownerIdx) != classDesc) {
                fail("обращение к private-члену другого класса ${typeAt(ownerIdx)}")
            }
        }

        private fun checkFieldVariant(op: Opcode, desc: String) {
            val m = op.mnemonic
            val suffix = m.substringAfter('-', "")
            val expected = when (desc[0]) {
                'Z' -> "boolean"
                'B' -> "byte"
                'S' -> "short"
                'C' -> "char"
                'I', 'F' -> ""
                'J', 'D' -> "wide"
                else -> "object"
            }
            if (suffix != expected) fail("$m для поля типа $desc")
        }

        private fun binary23x(op: Opcode, a: Int, b: Int, c: Int, s: State) {
            val m = op.mnemonic
            if (m.startsWith("aget") || m.startsWith("aput")) {
                array(op, a, b, c, s)
                return
            }
            when (op) {
                Opcode.CMPL_FLOAT, Opcode.CMPG_FLOAT -> { reqFloat(s, b); reqFloat(s, c); set(s, a, INT_T) }
                Opcode.CMPL_DOUBLE, Opcode.CMPG_DOUBLE -> { reqWide(s, b, DOUBLE_LO); reqWide(s, c, DOUBLE_LO); set(s, a, INT_T) }
                Opcode.CMP_LONG -> { reqWide(s, b, LONG_LO); reqWide(s, c, LONG_LO); set(s, a, INT_T) }
                else -> when {
                    m.endsWith("-int") -> {
                        val tb = get(s, b); val tc = get(s, c)
                        reqInt(s, b); reqInt(s, c)
                        val bool = (m.startsWith("and") || m.startsWith("or") || m.startsWith("xor")) && fits(tb, BOOLEAN) && fits(tc, BOOLEAN)
                        set(s, a, if (bool) BOOLEAN_T else INT_T)
                    }
                    m.endsWith("-long") -> {
                        reqWide(s, b, LONG_LO)
                        if (m.startsWith("shl") || m.startsWith("shr") || m.startsWith("ushr")) reqInt(s, c) else reqWide(s, c, LONG_LO)
                        setWide(s, a, LONG_LO)
                    }
                    m.endsWith("-float") -> { reqFloat(s, b); reqFloat(s, c); set(s, a, FLOAT_T) }
                    m.endsWith("-double") -> { reqWide(s, b, DOUBLE_LO); reqWide(s, c, DOUBLE_LO); setWide(s, a, DOUBLE_LO) }
                    else -> fail("неожиданная инструкция")
                }
            }
        }

        private fun array(op: Opcode, a: Int, b: Int, c: Int, s: State) {
            val arr = get(s, b)
            reqInt(s, c)
            val m = op.mnemonic
            val isPut = m.startsWith("aput")
            val variant = m.substringAfter('-', "")
            if (isZero(arr)) {
                // Массив null: ART разрешает, результат — ноль нужного вида.
                if (isPut) {
                    if (variant == "wide") reqWide(s, a, 0) else get(s, a)
                } else when (variant) {
                    "wide" -> setWide(s, a, CWIDE_LO)
                    "object" -> set(s, a, const(0))
                    else -> set(s, a, const(0))
                }
                return
            }
            val desc = arr.desc
            if (arr.kind != REF || desc == null || !desc.startsWith("[")) fail("v$b: ожидался массив, а там $arr")
            val component = desc.substring(1)
            val ok = when (variant) {
                "" -> component == "I" || component == "F"
                "wide" -> component == "J" || component == "D"
                "object" -> component[0] == 'L' || component[0] == '['
                "boolean" -> component == "Z"
                "byte" -> component == "B"
                "char" -> component == "C"
                "short" -> component == "S"
                else -> false
            }
            if (!ok) fail("$m для массива $desc")
            if (isPut) reqValue(s, a, component) else setValue(s, a, component)
        }

        private fun invoke(op: Opcode, index: Int, regs: List<Int>, s: State) {
            when (op) {
                Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> {
                    val desc = typeAt(index)
                    val component = desc.substring(1)
                    for (r in regs) if (component == "I") reqInt(s, r) else reqRef(s, r)
                    s.result = ref(desc)
                    return
                }
                Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> {
                    val cs = dex.callSiteItems[dex.callSiteIds[index].callSiteIdx]
                    val type = cs.arguments.getOrNull(1)
                    if (type == null || type.type != EncodedValueType.VALUE_METHOD_TYPE) fail("call site без method type")
                    val proto = (type.value as Number).toInt()
                    checkArgs(protoParams(proto), regs, 0, s)
                    s.result = resultOf(protoReturn(proto))
                    return
                }
                else -> {}
            }
            val method = dex.methodIds[index]
            checkAccess("m", index, method.classIdx)
            val mName = dex.strings[method.nameIdx]
            val params = protoParams(method.protoIdx)
            val isStaticCall = op == Opcode.INVOKE_STATIC || op == Opcode.INVOKE_STATIC_RANGE
            var first = 0
            if (!isStaticCall) {
                if (regs.isEmpty()) fail("нет получателя")
                val recv = get(s, regs[0])
                val isInit = mName == "<init>"
                if (isInit) {
                    if (op != Opcode.INVOKE_DIRECT && op != Opcode.INVOKE_DIRECT_RANGE) fail("<init> не через invoke-direct")
                    if (recv.kind == UNINIT || recv.kind == UNINIT_THIS) {
                        val initialized = if (recv.kind == UNINIT) ref(recv.desc!!) else ref(classDesc)
                        checkArgs(params, regs, 1, s)
                        for (r in s.regs.indices) if (s.regs[r] == recv) s.regs[r] = initialized
                        s.result = null
                        return
                    }
                    if (!isRef(recv)) fail("получатель <init> — $recv")
                } else if (!isRef(recv)) {
                    fail("получатель ${op.mnemonic} — $recv")
                }
                first = 1
            }
            checkArgs(params, regs, first, s)
            s.result = resultOf(protoReturn(method.protoIdx))
        }

        private fun polymorphic(protoIdx: Int, regs: List<Int>, s: State) {
            if (regs.isEmpty()) fail("invoke-polymorphic без получателя")
            reqRef(s, regs[0])
            checkArgs(protoParams(protoIdx), regs, 1, s)
            s.result = resultOf(protoReturn(protoIdx))
        }

        private fun checkArgs(params: List<String>, regs: List<Int>, first: Int, s: State) {
            var i = first
            for (p in params) {
                if (i >= regs.size) fail("не хватает регистров аргументов")
                reqArg(s, regs[i], p)
                if (isWideDesc(p)) {
                    if (i + 1 >= regs.size || regs[i + 1] != regs[i] + 1) fail("аргумент $p не в паре соседних регистров")
                    i += 2
                } else i++
            }
            if (i != regs.size) fail("лишние регистры аргументов: ${regs.size}, нужно $i")
        }

        private fun resultOf(desc: String): T? = when (desc) {
            "V" -> null
            "J" -> T(LONG_LO)
            "D" -> T(DOUBLE_LO)
            else -> ofDesc(desc)
        }

        private fun protoParams(protoIdx: Int): List<String> {
            val p = dex.protoIds[protoIdx]
            return if (p.parametersTypeListIdx < 0) emptyList() else dex.typeLists[p.parametersTypeListIdx].typeIdx.map { typeAt(it) }
        }

        private fun protoReturn(protoIdx: Int): String = typeAt(dex.protoIds[protoIdx].returnTypeIdx)

        private fun typeAt(idx: Int): String = dex.strings[dex.typeIds[idx].descriptorIdx]

        private fun isInvoke(op: Opcode): Boolean = op.mnemonic.startsWith("invoke") || op == Opcode.FILLED_NEW_ARRAY ||
            op == Opcode.FILLED_NEW_ARRAY_RANGE

        /** Инструкции, которые ART считает способными бросить исключение. */
        private fun canThrow(op: Opcode): Boolean {
            val m = op.mnemonic
            return when {
                m.startsWith("invoke") || m.startsWith("aget") || m.startsWith("aput") ||
                    m.startsWith("iget") || m.startsWith("iput") || m.startsWith("sget") || m.startsWith("sput") -> true
                m.startsWith("div-int") || m.startsWith("rem-int") || m.startsWith("div-long") || m.startsWith("rem-long") -> true
                else -> op in THROWING
            }
        }

        private companion object {
            val THROWING = setOf(
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO, Opcode.CONST_CLASS, Opcode.MONITOR_ENTER,
                Opcode.MONITOR_EXIT, Opcode.CHECK_CAST, Opcode.INSTANCE_OF, Opcode.ARRAY_LENGTH, Opcode.NEW_INSTANCE,
                Opcode.NEW_ARRAY, Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE, Opcode.FILL_ARRAY_DATA,
                Opcode.THROW, Opcode.CONST_METHOD_HANDLE, Opcode.CONST_METHOD_TYPE
            )
        }
    }
}
