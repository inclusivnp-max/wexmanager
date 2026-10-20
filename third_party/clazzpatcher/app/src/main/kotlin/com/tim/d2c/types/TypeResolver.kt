package com.tim.d2c.types

import com.tim.d2c.cfg.Insns
import com.tim.d2c.model.DexView
import com.tim.dasm.model.Ins21c
import com.tim.dasm.model.Ins22c
import com.tim.dex.data.Opcode

/**
 * Разрешение значений после [ValueFlow]:
 * 1. константность (узлы, равные известной константе, — рематериализуются в месте использования);
 * 2. типы узлов (как их увидит верификатор JVM);
 * 3. спрос: какие неконстантные значения должны лежать в слотах и в какой категории,
 *    какие константы нужно записать в слот (операнды φ).
 */
internal class TypeResolver(
    private val flow: ValueFlow,
    private val view: DexView,
    private val ops: TypeOps
) {
    private val nodes = flow.nodes
    private val code = flow.code
    private val n = nodes.size

    // ---- Константность ----
    private val constState = ByteArray(n) // 0 — неизвестно (оптимистично), 1 — константа, 2 — не константа
    private val constBits = LongArray(n)
    private val constWide = BooleanArray(n)

    // ---- Типы и категории ----
    private val types = arrayOfNulls<JType>(n)
    private val cats = arrayOfNulls<Cat>(n)
    private val demanded = BooleanArray(n)
    /** Категории прямых использований узла (биты по [Cat.ordinal]). */
    private val demandedMask = IntArray(n)
    private val storeCat = arrayOfNulls<Cat>(n)
    /**
     * Узел хранит 32/64 бита как int/long, а использования как float/double читают их через
     * Float.intBitsToFloat / Double.longBitsToDouble: слияние одних констант (у ART — тип «константа»)
     * используется и как целое, и как вещественное.
     */
    private val bitsNode = BooleanArray(n)

    private val users = arrayOfNulls<IntArray>(n)
    private val userCounts = IntArray(n)

    fun isConst(node: Int): Boolean = constState[node].toInt() == 1
    fun constBits(node: Int): Long = constBits[node]
    fun constWide(node: Int): Boolean = constWide[node]
    fun type(node: Int): JType = types[node] ?: JType.Top
    fun isDemanded(node: Int): Boolean = demanded[node]

    /** Категория неконстантного узла в слоте JVM. */
    fun cat(node: Int): Cat = cats[node] ?: type(node).cat

    /**
     * Значение узла читают из слота не только инструкции: move, φ или init (объект после `<init>` —
     * тот же слот). Пользователи-типы (aget, check-cast) слот не читают.
     */
    fun hasSlotUsers(node: Int): Boolean {
        val us = users[node] ?: return false
        for (k in 0 until userCounts[node]) {
            when (nodes.kind(us[k])) {
                Nodes.Kind.MOVE, Nodes.Kind.PHI, Nodes.Kind.INIT -> return true
                else -> {}
            }
        }
        return false
    }

    /** Слот узла хранит биты (int/long), а чтение как float/double — через преобразование битов. */
    fun isBits(node: Int): Boolean = bitsNode[node]

    /** Категория, в которой константу нужно записать в слот (NONE — не нужно). */
    fun storeCat(node: Int): Cat = storeCat[node] ?: Cat.NONE

    fun resolve() {
        buildUsers()
        resolveConstants()
        resolveTypes()
    }

    private fun buildUsers() {
        for (x in 2 until n) {
            when (nodes.kind(x)) {
                Nodes.Kind.MOVE, Nodes.Kind.INIT -> addUser(nodes.source(x), x)
                Nodes.Kind.TYPED -> if (nodes.source(x) != Nodes.UNDEF) addUser(nodes.source(x), x)
                Nodes.Kind.PHI -> for (k in 0 until nodes.operandCount(x)) addUser(nodes.operand(x, k), x)
                else -> {}
            }
        }
    }

    private fun addUser(of: Int, user: Int) {
        val arr = users[of] ?: IntArray(2).also { users[of] = it }
        val c = userCounts[of]
        if (c == arr.size) users[of] = arr.copyOf(c * 2)
        users[of]!![c] = user
        userCounts[of] = c + 1
    }

    // ---- 1. Константы (оптимистичное распространение, как SCCP) ----

    private fun resolveConstants() {
        val work = ArrayDeque<Int>()
        for (x in 0 until n) {
            when (nodes.kind(x)) {
                Nodes.Kind.CONST -> {
                    constState[x] = 1
                    constBits[x] = nodes.bits(x)
                    constWide[x] = nodes.isWideConst(x)
                    work.add(x)
                }
                Nodes.Kind.MOVE, Nodes.Kind.PHI -> {}
                else -> { constState[x] = 2; work.add(x) }
            }
        }
        while (work.isNotEmpty()) {
            val x = work.removeFirst()
            val us = users[x] ?: continue
            for (k in 0 until userCounts[x]) {
                val u = us[k]
                val kind = nodes.kind(u)
                if (kind != Nodes.Kind.MOVE && kind != Nodes.Kind.PHI) continue
                val old = constState[u]
                if (old.toInt() == 2) continue
                val (state, bits, wide) = evalConst(u)
                if (state != old || (state.toInt() == 1 && (bits != constBits[u] || wide != constWide[u]))) {
                    constState[u] = state
                    constBits[u] = bits
                    constWide[u] = wide
                    work.add(u)
                }
            }
        }
        // Узлы без определённых входов (циклы φ без констант) — не константы.
        for (x in 0 until n) if (constState[x].toInt() == 0) constState[x] = 2
    }

    private fun evalConst(u: Int): Triple<Byte, Long, Boolean> {
        if (nodes.kind(u) == Nodes.Kind.MOVE) {
            val s = nodes.source(u)
            return Triple(constState[s], constBits[s], constWide[s])
        }
        var seen = false
        var bits = 0L
        var wide = false
        for (k in 0 until nodes.operandCount(u)) {
            val o = nodes.operand(u, k)
            when (constState[o].toInt()) {
                0 -> continue
                2 -> return Triple(2, 0, false)
                else -> {
                    if (!seen) { seen = true; bits = constBits[o]; wide = constWide[o] }
                    else if (bits != constBits[o] || wide != constWide[o]) return Triple(2, 0, false)
                }
            }
        }
        return if (seen) Triple(1, bits, wide) else Triple(0, 0, false)
    }

    // ---- 2. Типы ----

    private fun resolveTypes() {
        val work = ArrayDeque<Int>()
        val queued = BooleanArray(n)
        for (x in 2 until n) {
            if (isConst(x)) continue
            val t = initialType(x)
            if (t != null) types[x] = t
            work.add(x); queued[x] = true
        }
        while (work.isNotEmpty()) {
            val x = work.removeFirst()
            queued[x] = false
            if (isConst(x)) continue
            val t = computeType(x)
            if (t != types[x]) {
                types[x] = t
                val us = users[x] ?: continue
                for (k in 0 until userCounts[x]) {
                    val u = us[k]
                    if (!queued[u]) { queued[u] = true; work.add(u) }
                }
            }
        }
    }

    /** Типы, не зависящие от других узлов. */
    private fun initialType(x: Int): JType? = when (nodes.kind(x)) {
        Nodes.Kind.PARAM -> paramType(x)
        Nodes.Kind.INIT -> initType(x)
        else -> null
    }

    private fun computeType(x: Int): JType = when (nodes.kind(x)) {
        Nodes.Kind.PARAM -> paramType(x)
        Nodes.Kind.INIT -> initType(x)
        Nodes.Kind.MOVE -> type(nodes.source(x))
        Nodes.Kind.TYPED -> typedType(x)
        Nodes.Kind.PHI -> {
            var t: JType = JType.Top
            for (k in 0 until nodes.operandCount(x)) {
                val o = nodes.operand(x, k)
                t = when {
                    o == Nodes.UNDEF || o == Nodes.HIGH -> JType.Conflict
                    isConst(o) -> t
                    else -> ops.join(t, type(o))
                }
                if (t === JType.Conflict) break
            }
            t
        }
        else -> JType.Conflict
    }

    private fun paramType(x: Int): JType {
        val index = nodes.source(x)
        val m = flow.method
        if (!m.isStatic) {
            if (index == 0) return if (nodes.tag(x) == Nodes.THIS_TAG) JType.UninitThis else JType.Ref(m.owner)
            return JType.of(m.proto.params[index - 1])
        }
        return JType.of(m.proto.params[index])
    }

    private fun initType(x: Int): JType {
        val tag = nodes.tag(nodes.source(x))
        if (tag == Nodes.THIS_TAG) return JType.Ref(flow.method.owner)
        if (tag < 0) return JType.Conflict
        val newInsn = code.insns[tag] as Ins21c
        return JType.Ref(view.type(newInsn.index))
    }

    private fun typedType(x: Int): JType {
        val i = nodes.instr(x)
        val insn = code.insns[i]
        val op = insn.opcode
        val v = op.value
        return when {
            op == Opcode.MOVE_RESULT || op == Opcode.MOVE_RESULT_WIDE || op == Opcode.MOVE_RESULT_OBJECT ->
                JType.of(resultType(i - 1))
            op == Opcode.MOVE_EXCEPTION -> exceptionType(i)
            op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO -> JType.Ref("Ljava/lang/String;")
            op == Opcode.CONST_CLASS -> JType.Ref("Ljava/lang/Class;")
            op == Opcode.CONST_METHOD_HANDLE -> JType.Ref("Ljava/lang/invoke/MethodHandle;")
            op == Opcode.CONST_METHOD_TYPE -> JType.Ref("Ljava/lang/invoke/MethodType;")
            op == Opcode.CHECK_CAST -> JType.Ref(view.type((insn as Ins21c).index))
            op == Opcode.NEW_INSTANCE -> JType.Uninit(i)
            op == Opcode.NEW_ARRAY -> JType.Ref(view.type((insn as Ins22c).index))
            op == Opcode.INSTANCE_OF || op == Opcode.ARRAY_LENGTH -> JType.IntT
            v in Opcode.AGET.value..Opcode.AGET_SHORT.value -> arrayElement(op, arrayTypeOf(nodes.source(x)))
            v in Opcode.IGET.value..Opcode.IGET_SHORT.value -> JType.of(view.field((insn as Ins22c).index).type)
            v in Opcode.SGET.value..Opcode.SGET_SHORT.value -> JType.of(view.field((insn as Ins21c).index).type)
            else -> when (Results.resultCat(op)) {
                Cat.I -> JType.IntT
                Cat.J -> JType.LongT
                Cat.F -> JType.FloatT
                Cat.D -> JType.DoubleT
                else -> JType.Conflict
            }
        }
    }

    /** Тип массива во входном узле aget/aput (константа — только null). */
    fun arrayTypeOf(node: Int): JType = when {
        node == Nodes.UNDEF || node == Nodes.HIGH -> JType.Conflict
        isConst(node) -> if (constBits(node) == 0L) JType.Null else JType.Conflict
        else -> type(node)
    }

    /**
     * Тип элемента для aget* по типу массива. У aget/aget-wide на null (всегда NPE) тип, как у ART, —
     * «константа»: [JType.Top], категорию (int/float, long/double) выберет спрос.
     */
    fun arrayElement(op: Opcode, array: JType): JType {
        if (array === JType.Top) return JType.Top
        val d = (array as? JType.Ref)?.descriptor
        return when (op) {
            Opcode.AGET -> when (d) { "[I" -> JType.IntT; "[F" -> JType.FloatT; else -> if (array === JType.Null) JType.Top else JType.Conflict }
            Opcode.AGET_WIDE -> when (d) { "[J" -> JType.LongT; "[D" -> JType.DoubleT; else -> if (array === JType.Null) JType.Top else JType.Conflict }
            Opcode.AGET_OBJECT -> when {
                array === JType.Null -> JType.Null
                d != null && d.length > 1 && d[0] == '[' && (d[1] == 'L' || d[1] == '[') -> JType.Ref(d.substring(1))
                else -> JType.Conflict
            }
            else -> JType.IntT
        }
    }

    /** Тип результата инструкции перед move-result. */
    fun resultType(i: Int): String {
        val insn = code.insns[i]
        val op = insn.opcode
        return when {
            op == Opcode.FILLED_NEW_ARRAY || op == Opcode.FILLED_NEW_ARRAY_RANGE -> view.type(ValueFlow.indexOf(insn))
            op == Opcode.INVOKE_POLYMORPHIC || op == Opcode.INVOKE_POLYMORPHIC_RANGE -> view.proto(protoIndexOf(insn)).returnType
            op == Opcode.INVOKE_CUSTOM || op == Opcode.INVOKE_CUSTOM_RANGE -> callSiteProto(ValueFlow.indexOf(insn)).returnType
            Insns.isInvoke(op) -> view.method(ValueFlow.indexOf(insn)).proto.returnType
            else -> throw code.error(i, "move-result после инструкции без результата")
        }
    }

    fun protoIndexOf(insn: com.tim.dasm.model.Instruction): Int = when (insn) {
        is com.tim.dasm.model.Ins45cc -> insn.protoIdx
        is com.tim.dasm.model.Ins4rcc -> insn.protoIdx
        else -> throw IllegalArgumentException("${insn.opcode.mnemonic}: нет прототипа")
    }

    /** Прототип call site (второй аргумент после имени). */
    fun callSiteProto(callSiteIdx: Int): com.tim.d2c.model.Proto {
        val cs = view.dex.callSiteItems[view.dex.callSiteIds[callSiteIdx].callSiteIdx]
        val mt = cs.arguments.getOrNull(1)
            ?: throw IllegalStateException("call site #$callSiteIdx: нет типа метода")
        return view.proto(mt.value as Int)
    }

    /** Тип исключения в обработчике: общий предок типов всех catch, ведущих сюда. */
    private fun exceptionType(i: Int): JType {
        var t: JType = JType.Top
        for (list in code.handlers) for (h in list) {
            if (h.target != i) continue
            t = ops.join(t, if (h.catchType == null) JType.THROWABLE else JType.Ref(h.catchType))
        }
        return if (t === JType.Top) JType.THROWABLE else t
    }

    // ---- 3. Спрос ----

    /** Прямое использование значения инструкцией; [cat] = null — категория любая (if-eqz и т. п.). */
    fun demand(node: Int, cat: Cat?) {
        if (isConst(node)) return
        demanded[node] = true
        if (cat != null) demandedMask[node] = demandedMask[node] or (1 shl cat.ordinal)
    }

    /**
     * Распространяет спрос через move/φ, выбирает категории (у связанных узлов — одна)
     * и отмечает константы, которые нужно записать в слоты (операнды нужных φ).
     */
    fun finishDemand() {
        val work = ArrayDeque<Int>()
        for (x in 0 until n) if (demanded[x]) work.add(x)
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            var c = a
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        fun need(x: Int) { if (!demanded[x]) { demanded[x] = true; work.add(x) } }

        while (work.isNotEmpty()) {
            val x = work.removeFirst()
            when (nodes.kind(x)) {
                Nodes.Kind.MOVE -> { val s = nodes.source(x); if (!isConst(s)) { union(x, s); need(s) } }
                Nodes.Kind.INIT -> { val s = nodes.source(x); if (!isConst(s)) { union(x, s); need(s) } }
                Nodes.Kind.PHI -> for (k in 0 until nodes.operandCount(x)) {
                    val o = nodes.operand(x, k)
                    if (o == Nodes.UNDEF || o == Nodes.HIGH) {
                        throw errorAt(x, "регистр v${nodes.reg(x)} не определён на одном из путей к слиянию")
                    }
                    // Константа тоже связывает φ: она одна пишется в слот, и все её φ хранят одно представление.
                    union(x, o)
                    if (!isConst(o)) need(o)
                }
                Nodes.Kind.UNDEF, Nodes.Kind.HIGH -> throw IllegalStateException("использование неопределённого регистра")
                else -> {}
            }
        }

        // Категория компоненты: из типов участников и прямых требований.
        val typedMask = HashMap<Int, Int>()
        val usedMask = HashMap<Int, Int>()
        val compWide = HashSet<Int>()
        for (x in 2 until n) {
            if (!demanded[x] || isConst(x)) continue
            val root = find(x)
            val t = type(x)
            if (t === JType.Conflict) {
                throw errorAt(x, "регистр v${nodes.reg(x)}: несовместимые значения при слиянии")
            }
            if (nodes.kind(x) == Nodes.Kind.PHI && nodes.wideHint(x)) compWide.add(root)
            if (nodes.kind(x) == Nodes.Kind.TYPED && code.insns[nodes.instr(x)].opcode == Opcode.AGET_WIDE) compWide.add(root)
            if (t.cat != Cat.NONE) typedMask[root] = (typedMask[root] ?: 0) or (1 shl t.cat.ordinal)
            if (demandedMask[x] != 0) usedMask[root] = (usedMask[root] ?: 0) or demandedMask[x]
        }
        val compCat = HashMap<Int, Cat>()
        val compBits = HashSet<Int>()
        for (root in (typedMask.keys + usedMask.keys)) {
            val typed = typedMask[root] ?: 0
            val all = typed or (usedMask[root] ?: 0)
            val cats = Cat.entries.filter { all and (1 shl it.ordinal) != 0 }
            compCat[root] = when {
                cats.size == 1 -> cats[0]
                // Целое и вещественное в одной паутине слияний: слот хранит биты (int/long), вещественные
                // значения пишутся через floatToRawIntBits/doubleToRawLongBits и читаются обратно — точно
                // как нетипизированные регистры Dalvik.
                cats == listOf(Cat.I, Cat.F) -> Cat.I.also { compBits.add(root) }
                cats == listOf(Cat.J, Cat.D) -> Cat.J.also { compBits.add(root) }
                else -> throw errorAt(root, "регистр v${nodes.reg(root)}: значение нужно и как ${cats[0].name}, и как ${cats[1].name}")
            }
        }
        for (x in 2 until n) {
            if (!demanded[x] || isConst(x)) continue
            val root = find(x)
            val c = compCat[root] ?: if (root in compWide) Cat.J else Cat.I
            cats[x] = c
            if (root in compBits) bitsNode[x] = true
            if (types[x] == null || types[x] === JType.Top) types[x] = when (c) {
                Cat.I -> JType.IntT
                Cat.J -> JType.LongT
                Cat.F -> JType.FloatT
                Cat.D -> JType.DoubleT
                else -> JType.Null
            }
        }
        // Константы-операнды нужных φ записываются в слот в категории φ.
        for (x in 2 until n) {
            if (!demanded[x] || isConst(x) || nodes.kind(x) != Nodes.Kind.PHI) continue
            val c = cats[x]!!
            for (k in 0 until nodes.operandCount(x)) {
                val o = nodes.operand(x, k)
                if (isConst(o)) markStore(o, c)
            }
        }
    }

    /** Ошибка с адресом инструкции, задающей узел (у φ — начала блока слияния). */
    private fun errorAt(node: Int, message: String): IllegalStateException {
        val i = if (nodes.kind(node) == Nodes.Kind.PHI) flow.blockStart[nodes.instr(node)] else nodes.instr(node)
        return if (i in 0 until code.count) code.error(i, message) else IllegalStateException(message)
    }

    private fun markStore(x: Int, c: Cat) {
        val old = storeCat[x]
        if (old == c) return
        if (old != null) {
            throw IllegalStateException(
                "константа в регистре v${nodes.reg(x)} сливается и как ${old.name}, и как ${c.name} (не поддерживается)"
            )
        }
        if (c == Cat.A && constBits[x] != 0L) {
            throw IllegalStateException("ненулевая константа в регистре v${nodes.reg(x)} сливается со ссылкой")
        }
        if (c.isWide != constWide[x]) {
            throw IllegalStateException("константа в регистре v${nodes.reg(x)}: ширина не совпадает с категорией ${c.name}")
        }
        storeCat[x] = c
        if (nodes.kind(x) == Nodes.Kind.PHI) {
            for (k in 0 until nodes.operandCount(x)) markStore(nodes.operand(x, k), c)
        }
    }
}
