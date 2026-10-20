package com.tim.c2d.translate

import com.tim.c2d.analysis.ExceptionTable
import com.tim.c2d.analysis.FlowAnalysis
import com.tim.c2d.analysis.Handler
import com.tim.c2d.analysis.LocalLiveness
import com.tim.c2d.analysis.MethodFlow
import com.tim.c2d.analysis.SameValues
import com.tim.c2d.analysis.TypeFrame
import com.tim.c2d.analysis.TypeInterpreter
import com.tim.c2d.analysis.VType
import com.tim.c2d.bytecode.BytecodeDecoder
import com.tim.c2d.bytecode.DecodedCode
import com.tim.c2d.bytecode.Descriptors
import com.tim.c2d.bytecode.JvmInsn
import com.tim.c2d.bytecode.PoolConstant
import com.tim.c2d.desugar.LambdaFactory
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.opcodes.Opcodes as Op
import com.tim.dasm.dsl.MethodDsl
import com.tim.dasm.model.Ins12x
import com.tim.dasm.model.Ins21c
import com.tim.dasm.model.Ins22b
import com.tim.dasm.model.Ins22c
import com.tim.dasm.model.Ins22s
import com.tim.dasm.model.Ins23x
import com.tim.dasm.model.Ins31c
import com.tim.dex.data.Opcode

/**
 * Перевод кода одного метода JVM в регистровый код DEX.
 *
 * Стек операндов отображается на регистры по глубине (см. [RegisterLayout]). Загрузка локала
 * и `dup` не порождают инструкций: значение на стеке может «виртуально» ссылаться на регистр
 * локала или на регистр более глубокого слова стека. Копия в собственный регистр делается,
 * только когда без неё нельзя — перед записью в этот локал, на границе блока, перед check-cast
 * или когда нужен непрерывный диапазон регистров. Ссылки только вниз по стеку безопасны:
 * слово стека перезаписывается, лишь когда всё, что выше, уже снято. Типы для выбора
 * инструкций даёт [FlowAnalysis].
 */
class MethodTranslator(
    private val ctx: ClassContext,
    private val name: String,
    private val descriptor: String,
    private val isStatic: Boolean,
    /** synchronized не-native метод: блокировку в DEX делает сам код. */
    private val isSynchronized: Boolean,
    private val codeAttr: Code,
    private val dsl: MethodDsl
) {
    private val pool = ctx.pool
    private val code = dsl.code
    private val type = Descriptors.method(descriptor)
    private val interpreter = TypeInterpreter(pool)

    private lateinit var decoded: DecodedCode
    private lateinit var handlers: ExceptionTable
    private lateinit var flow: MethodFlow
    private lateinit var layout: RegisterLayout
    private lateinit var slots: LocalSlots
    private lateinit var w: DexCodeWriter

    /** Текущие типы (перед переводимой инструкцией). */
    private lateinit var frame: TypeFrame

    /** Регистр значения, начинающегося со слова стека `d`. */
    private lateinit var regs: IntArray

    /** Блок начинается обработчиком исключения. */
    private lateinit var handlerBlock: BooleanArray

    /** Блоки-обработчики, в которые попадают и обычным потоком: для них нужна заглушка с move-exception. */
    private lateinit var needsStub: BooleanArray

    /** Регистр локала, в который следующий результат пишется сразу (запись в локал слита с инструкцией). */
    private var forwardTarget = -1

    /** Результат вызова сразу снимается pop/pop2: move-result не нужен. */
    private var discardResult = false

    /** В методе больше одного monitorenter: после захвата монитора копии ссылок убиваются ([killStaleCopies]). */
    private var nestedMonitors = false

    /** Индекс переводимой инструкции. */
    private var current = 0

    /** Живость локалов (для приведения на месте), считается при первом запросе. */
    private val liveness by lazy { LocalLiveness.analyze(decoded, flow, handlers) }

    /** Локалы и слова стека с тем же объектом, что у операнда monitorenter (только для методов с monitorenter). */
    private val sameValues by lazy {
        SameValues.analyze(decoded, flow, handlers, interpreter, pool, codeAttr.maxLocals, codeAttr.maxStack)
    }

    private val payloads = ArrayList<SwitchPayload>()
    private val arrayPayloads = ArrayList<ArrayPayload>()
    private val runs = ArrayList<TryRun>()

    /** Инструкции до этого индекса уже переведены (заполнение массива одной fill-array-data). */
    private var skipUntil = 0

    /** Целая константа на вершине стека не записана в регистр: её возьмёт литералом следующая операция. */
    private var hasLiteral = false
    private var literalValue = 0

    private class SwitchPayload(val label: String, val packed: Boolean, val low: Int, val keys: IntArray?, val targets: List<String>)
    private class ArrayPayload(val label: String, val width: Int, val count: Int, val data: ByteArray)
    private class TryRun(val start: String, val end: String, val handlerSet: Int)

    fun translate() {
        decoded = BytecodeDecoder.decode(codeAttr.code)
        handlers = ExceptionTable(decoded, codeAttr.exceptionTable, pool)
        val ins = (if (isStatic) 0 else 1) + type.paramWords
        if (codeAttr.maxLocals < ins) {
            throw IllegalArgumentException("max_locals (${codeAttr.maxLocals}) меньше, чем занимают параметры ($ins)")
        }
        flow = FlowAnalysis.analyze(decoded, handlers, interpreter, initialFrame())
        slots = LocalSlots.analyze(decoded.insns, flow.blockOfInsn, codeAttr.maxLocals, ins, wideParamSlots(), namedSlots())
        layout = RegisterLayout.create(
            maxStack = codeAttr.maxStack + lambdaSlack(),
            maxLocals = codeAttr.maxLocals,
            ins = ins,
            synchronized = isSynchronized,
            needsCycleTemps = hasCycleShuffles(),
            slots = slots
        )
        w = DexCodeWriter(code, layout.temps)
        nestedMonitors = decoded.insns.count { it.opcode == Op.MONITORENTER } > 1
        regs = IntArray(layout.stackSize)
        dsl.registers(layout.total)

        handlerBlock = BooleanArray(flow.blockCount)
        needsStub = BooleanArray(flow.blockCount)
        for (set in handlers.handlerSets) for (h in set) {
            val b = flow.blockOfInsn[h.handlerIndex]
            handlerBlock[b] = true
            if (flow.normalEntry[b]) needsStub[b] = true
        }

        val debug = if (ctx.options.debugInfo) DebugEvents.of(codeAttr, pool, paramSlots(), isStatic) else DebugEvents.NONE
        for ((index, paramName) in debug.paramNames) dsl.paramName(index, paramName)

        planReturnBlocks()
        emitPrologue(debug)
        emitBody(debug)
        emitStubs()
        emitPayloads()
        emitTries()
    }

    // ============================================================
    // Каркас метода
    // ============================================================

    private fun initialFrame(): TypeFrame {
        val locals = Array(codeAttr.maxLocals) { VType.Top }
        var slot = 0
        if (!isStatic) {
            locals[0] = if (name == "<init>" && ctx.thisClass != "java/lang/Object") VType.UninitThis
            else VType.ref(ctx.thisDescriptor)
            slot = 1
        }
        for (p in type.params) {
            val t = VType.ofDescriptor(p)
            locals[slot] = t
            if (t.isWide) locals[slot + 1] = VType.highOf(t)
            slot += t.width
        }
        return TypeFrame(locals, Array(codeAttr.maxStack) { VType.Top }, 0)
    }

    private fun paramSlots(): IntArray {
        val slots = IntArray(type.params.size)
        var slot = if (isStatic) 0 else 1
        for ((i, p) in type.params.withIndex()) {
            slots[i] = slot
            slot += Descriptors.width(p)
        }
        return slots
    }

    /** Блок состоит из одной инструкции возврата: goto на него переводится самим возвратом. */
    private lateinit var returnBlock: BooleanArray

    /** Блок-возврат, в который попадают только goto: после их замены он недостижим и не пишется. */
    private lateinit var elidedBlock: BooleanArray

    private fun planReturnBlocks() {
        val insns = decoded.insns
        val blocks = flow.blockCount
        returnBlock = BooleanArray(blocks)
        elidedBlock = BooleanArray(blocks)
        // В synchronized-методе возврат — ещё и monitor-exit: копия на каждый goto длиннее самого goto.
        if (isSynchronized) return
        for (b in 0 until blocks) {
            val start = flow.blockStart[b]
            returnBlock[b] = flow.blockEnd(b, insns.size) == start + 1 && insns[start].opcode in Op.IRETURN..Op.RETURN
        }
        val other = BooleanArray(blocks) // есть вход не через goto
        other[0] = true
        for (b in 0 until blocks) {
            if (handlerBlock[b]) other[b] = true
            val end = flow.blockEnd(b, insns.size)
            if (end < insns.size && FlowAnalysis.fallsThrough(insns[end - 1].opcode)) other[flow.blockOfInsn[end]] = true
        }
        for (insn in insns) {
            if (insn.opcode == Op.GOTO) continue
            FlowAnalysis.forEachTarget(insn) { pc -> other[flow.blockOfInsn[decoded.indexOf(pc)]] = true }
        }
        for (b in 0 until blocks) elidedBlock[b] = returnBlock[b] && !other[b]
    }

    /** Слоты параметров long/double. */
    private fun wideParamSlots(): IntArray {
        val out = ArrayList<Int>()
        var slot = if (isStatic) 0 else 1
        for (p in type.params) {
            val width = Descriptors.width(p)
            if (width == 2) out.add(slot)
            slot += width
        }
        return out.toIntArray()
    }

    /** Слоты с именами из LocalVariableTable (если отладочная информация переносится). */
    private fun namedSlots(): BooleanArray {
        val named = BooleanArray(codeAttr.maxLocals)
        if (!ctx.options.debugInfo) return named
        for (a in codeAttr.attributes) {
            if (a is com.tim.clazz.model.attributes.LocalVariableTable) {
                for (e in a.entries) if (e.index in named.indices) named[e.index] = true
            }
        }
        return named
    }

    /** Запись в слот, который никто не читает (значение отбрасывается), или в слот-копию (значение уже там). */
    private fun isSkippedStore(insn: JvmInsn): Boolean =
        insn.opcode in Op.ISTORE..Op.ASTORE && (slots.neverRead[insn.a] || slots.aliasOf[insn.a] >= 0)

    /** Инструкция только кладёт на стек готовое значение и ничего не бросает. */
    private fun isPurePush(op: Int): Boolean = when (op) {
        Op.ACONST_NULL, in Op.ICONST_M1..Op.DCONST_1, Op.BIPUSH, Op.SIPUSH, in Op.ILOAD..Op.ALOAD -> true
        else -> false
    }

    private fun hasCycleShuffles(): Boolean {
        for (insn in decoded.insns) {
            when (insn.opcode) {
                Op.DUP_X1, Op.DUP_X2, Op.DUP2_X1, Op.DUP2_X2, Op.SWAP -> return true
            }
        }
        return false
    }

    /**
     * Сразу после monitor-enter: копии ссылок (move-object) во временных регистрах и в свободных
     * регистрах стека затираются. Верификатор ART переносит на копию уровни блокировки источника
     * на момент копирования; копия, снятая до повторного захвата того же объекта, остаётся с
     * устаревшими уровнями, и на слиянии путей (обработчик вложенного блока) ART не может их
     * согласовать — метод помечается «failed lock verification» и работает медленнее.
     * Сам monitor-enter исключения бросает до захвата, так что его ребро видит прежнее состояние.
     */
    private fun killStaleCopies(locked: Int) {
        val copies = w.objectCopies
        val firstFree = layout.stack(frame.sp - 1)
        var r = copies.nextSetBit(0)
        while (r >= 0) {
            if (r != locked && (r < layout.temps || (layout.isStackReg(r) && r >= firstFree))) {
                copies.clear(r)
                w.constInt(r, 0)
            }
            r = copies.nextSetBit(r + 1)
        }
    }

    private fun emitPrologue(debug: DebugEvents) {
        // Типы параметров — из сигнатуры: вход блока 0 бывает головой цикла, и там слоты уже слиты.
        val initial = initialFrame()
        var slot = 0
        while (slot < layout.ins) {
            val t = initial.locals[slot]
            if (layout.isCopiedParam(slot)) w.move(layout.local(slot), layout.insBase + slot, RegKind.of(t))
            slot += t.width
        }
        // Имя скопированного параметра переходит на копию: код работает с ней, и после присваивания
        // параметру отладчик иначе показывал бы старое значение из регистра ins.
        val slotOfParam = paramSlots()
        for ((index, name) in debug.paramNames) {
            val s = slotOfParam.getOrNull(index) ?: continue
            if (!layout.isCopiedParam(s)) continue
            dsl.endLocal(layout.insBase + s)
            dsl.startLocal(layout.local(s), name, type.params[index], null)
        }
        if (isSynchronized) {
            val lock = layout.lockReg
            if (isStatic) code.constClass(lock, ctx.thisDescriptor)
            else w.move(lock, layout.local(0), RegKind.OBJECT)
            code.monitorEnter(lock)
        }
    }

    private fun emitBody(debug: DebugEvents) {
        val insns = decoded.insns
        var nextEvent = 0
        var currentSet = -1
        var runOpen = false
        var runStart = ""
        var runStartPos = 0
        var labelCounter = 0

        fun closeRun() {
            if (!runOpen) return
            val end = "R${labelCounter++}"
            code.label(end)
            if (code.instructions.size > runStartPos) runs.add(TryRun(runStart, end, currentSet))
            runOpen = false
        }

        fun enterSet(set: Int) {
            if (set == currentSet) return
            closeRun()
            currentSet = set
            if (set != 0 || isSynchronized) {
                runStart = "R${labelCounter++}"
                code.label(runStart)
                runStartPos = code.instructions.size
                runOpen = true
            }
        }

        for (block in 0 until flow.blockCount) {
            val entry = flow.entry[block] ?: continue
            if (elidedBlock[block]) {
                // Блок-возврат, в который попадают только goto (они сами стали возвратами): не пишется.
                // Строки его не нужны, концы и начала локалов остаются на следующем адресе.
                val end = insns[flow.blockEnd(block, insns.size) - 1].pc
                while (nextEvent < debug.events.size && debug.events[nextEvent].pc <= end) {
                    val e = debug.events[nextEvent++]
                    if (e !is DebugEvents.Event.Line) emitDebug(e)
                }
                continue
            }
            frame = entry.copy()
            forEachValue(0, frame.sp) { regs[it] = layout.stack(it) }

            val start = flow.blockStart[block]
            val end = flow.blockEnd(block, insns.size)
            code.label(blockLabel(insns[start].pc))
            if (handlerBlock[block] && !needsStub[block]) code.moveException(layout.stack(0))

            var storeDone = false
            for (i in start until end) {
                val insn = insns[i]
                if (i < skipUntil) {
                    // Внутри заполнения массива: строки не нужны (кода у них нет), тип стека — идёт.
                    while (nextEvent < debug.events.size && debug.events[nextEvent].pc <= insn.pc) {
                        val e = debug.events[nextEvent++]
                        if (e !is DebugEvents.Event.Line) emitDebug(e)
                    }
                    interpreter.execute(frame, insn)
                    continue
                }
                while (nextEvent < debug.events.size && debug.events[nextEvent].pc <= insn.pc) {
                    emitDebug(debug.events[nextEvent++])
                }
                enterSet(handlers.handlerSetOfInsn[i])
                w.resetTemps()
                current = i
                try {
                    if (storeDone) {
                        storeDone = false // значение уже в регистре локала
                    } else if (i + 1 < end && literalOperand(insn, insns[i + 1])) {
                        hasLiteral = true
                        literalValue = intConstant(insn)!!
                    } else if (i + 1 < end && isPurePush(insn.opcode) && isSkippedStore(insns[i + 1])) {
                        // Значение уходит в мёртвый слот или в копию — инструкции не нужны.
                    } else {
                        val forwarding = i + 1 < end && forwardIntoStore(insn, insns[i + 1])
                        discardResult = i + 1 < end && (insns[i + 1].opcode == Op.POP || insns[i + 1].opcode == Op.POP2 ||
                            (insns[i + 1].opcode in Op.ISTORE..Op.ASTORE && slots.neverRead[insns[i + 1].a]))
                        translate(insn)
                        discardResult = false
                        // Запись слита, только если результат действительно лёг в регистр локала.
                        storeDone = forwarding && forwardTarget < 0
                        forwardTarget = -1
                    }
                    interpreter.execute(frame, insn)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("pc=${insn.pc} (${Op.getOpcodeName(insn.opcode).lowercase()}): ${e.message}", e)
                } catch (e: IllegalStateException) {
                    throw IllegalStateException("pc=${insn.pc} (${Op.getOpcodeName(insn.opcode).lowercase()}): ${e.message}", e)
                }
            }
            // Следующий блок ждёт стек в канонических регистрах.
            if (FlowAnalysis.fallsThrough(insns[end - 1].opcode)) canonicalizeRange(0, frame.sp)
        }
        closeRun()
    }

    private fun emitDebug(e: DebugEvents.Event) {
        when (e) {
            is DebugEvents.Event.Line -> dsl.lineNumber(e.line)
            is DebugEvents.Event.StartLocal -> {
                if (e.slot < layout.maxLocals) dsl.startLocal(layout.local(e.slot), e.name, e.type, e.signature)
            }
            is DebugEvents.Event.EndLocal -> {
                if (e.slot < layout.maxLocals) dsl.endLocal(layout.local(e.slot))
            }
        }
    }

    /** Заглушки обработчиков, в которые можно попасть и обычным потоком, и обработчик synchronized. */
    private fun emitStubs() {
        val done = HashSet<Int>()
        for (set in handlers.handlerSets) for (h in set) {
            val block = flow.blockOfInsn[h.handlerIndex]
            if (!needsStub[block] || !flow.isReachable(block) || !done.add(h.handlerPc)) continue
            code.label(stubLabel(h.handlerPc))
            code.moveException(layout.stack(0))
            code.goto(blockLabel(h.handlerPc))
        }
        if (isSynchronized) {
            val exc = layout.stack(0)
            code.label(SYNC_HANDLER)
            code.moveException(exc)
            code.monitorExit(layout.lockReg)
            code.throwException(exc)
        }
    }

    private fun emitPayloads() {
        for (p in payloads) {
            code.label(p.label)
            if (p.packed) code.packedSwitchPayload(p.low, p.targets)
            else code.sparseSwitchPayload(p.keys!!, p.targets)
        }
        for (p in arrayPayloads) {
            code.label(p.label)
            code.fillArrayDataPayload(p.width, p.count, p.data)
        }
    }

    private fun emitTries() {
        for (run in runs) {
            var catchAll = false
            for (h in handlers.handlerSets[run.handlerSet]) {
                val label = handlerLabel(h)
                val type = h.catchType
                if (type == null) {
                    dsl.catchAll(run.start, run.end, label)
                    catchAll = true
                } else {
                    dsl.tryCatch(run.start, run.end, label, type)
                }
            }
            if (isSynchronized && !catchAll) dsl.catchAll(run.start, run.end, SYNC_HANDLER)
        }
    }

    private fun handlerLabel(h: Handler): String =
        if (needsStub[flow.blockOfInsn[h.handlerIndex]]) stubLabel(h.handlerPc) else blockLabel(h.handlerPc)

    private fun blockLabel(pc: Int) = "L$pc"
    private fun stubLabel(pc: Int) = "H$pc"

    /**
     * Результат [insn] сразу забирает следующая `xstore`: пишем его прямо в регистр локала.
     * Только если регистр локала не пересекается с операндами [insn] (иначе запись испортила бы
     * ещё не прочитанный операнд пары); ссылки на локал ниже по стеку копируются заранее.
     */
    private fun forwardIntoStore(insn: JvmInsn, next: JvmInsn): Boolean {
        if (next.opcode !in Op.ISTORE..Op.ASTORE) return false
        if (slots.neverRead[next.a] || slots.aliasOf[next.a] >= 0) return false
        if (insn.opcode == Op.CHECKCAST) {
            // Приведение сразу в регистре локала-приёмника (копия + check-cast вместо двух копий).
            // Копия портит старое значение локала ещё до проверки: если check-cast бросит, обработчик
            // увидел бы его испорченным — поэтому только вне try (или приведение на месте).
            val target = layout.local(next.a)
            if (handlers.handlerSetOfInsn[current] != 0 && regs[valuePos(0)] != target) return false
            spillReferences(target, 1, valuePos(0))
            forwardTarget = target
            return true
        }
        if (!producesFreshResult(insn.opcode)) return false
        val after = frame.copy()
        interpreter.execute(after, insn)
        if (after.sp == 0) return false
        val top = after.stack[after.sp - 1]
        val resultPos = after.sp - if (top.isHigh) 2 else 1
        val width = after.stack[resultPos].width
        val target = layout.local(next.a)
        var overlap = false
        forEachValue(resultPos, frame.sp) { p ->
            val r = regs[p]
            if (r < target + width && target < r + frame.stack[p].width) overlap = true
        }
        if (overlap) return false
        spillReferences(target, width, resultPos)
        forwardTarget = target
        return true
    }

    /** Инструкция кладёт на стек новое значение в свой регистр-приёмник (не на месте, не составная). */
    private fun producesFreshResult(op: Int): Boolean = when (op) {
        Op.ACONST_NULL, in Op.ICONST_M1..Op.LDC2_W,
        in Op.IALOAD..Op.SALOAD,
        in Op.IADD..Op.DNEG, in Op.ISHL..Op.LXOR, in Op.I2L..Op.DCMPG,
        Op.GETSTATIC, Op.GETFIELD,
        Op.INVOKEVIRTUAL, Op.INVOKESPECIAL, Op.INVOKESTATIC, Op.INVOKEINTERFACE, Op.INVOKEDYNAMIC,
        Op.NEWARRAY, Op.ANEWARRAY, Op.ARRAYLENGTH, Op.INSTANCEOF -> true
        else -> false
    }

    /** Регистр для результата, который ляжет на слово стека [pos]. */
    private fun resultReg(pos: Int): Int {
        val target = forwardTarget
        if (target < 0) return layout.stack(pos)
        forwardTarget = -1
        return target
    }

    // ============================================================
    // Стек значений
    // ============================================================

    /** Позиция (слово) [k]-го значения от вершины (0 — вершина). */
    private fun valuePos(k: Int): Int {
        var p = frame.sp
        repeat(k + 1) { p -= if (frame.stack[p - 1].isHigh) 2 else 1 }
        return p
    }

    private inline fun forEachValue(from: Int, to: Int, action: (Int) -> Unit) {
        var p = from
        while (p < to) {
            action(p)
            p += frame.stack[p].width
        }
    }

    private fun kindAt(pos: Int): RegKind = RegKind.of(frame.stack[pos])

    /** Перенести значение в его канонический регистр слова стека. */
    private fun materialize(pos: Int) {
        val c = layout.stack(pos)
        if (regs[pos] != c) {
            w.move(c, regs[pos], kindAt(pos))
            regs[pos] = c
        }
    }

    private fun canonicalizeRange(from: Int, to: Int) = forEachValue(from, to) { materialize(it) }

    /** Перед записью в регистры `[reg, reg + width)` скопировать значения стека ниже [below], которые на них ссылаются. */
    private fun spillReferences(reg: Int, width: Int, below: Int) {
        forEachValue(0, below) { p ->
            val r = regs[p]
            if (r != layout.stack(p) && r < reg + width && reg < r + frame.stack[p].width) materialize(p)
        }
    }

    // ============================================================
    // Инструкции
    // ============================================================

    private fun translate(insn: JvmInsn) {
        when (val op = insn.opcode) {
            Op.NOP -> {}
            Op.ACONST_NULL -> pushConst(0)
            in Op.ICONST_M1..Op.ICONST_5, Op.BIPUSH, Op.SIPUSH -> pushConst(insn.b)
            Op.LCONST_0, Op.LCONST_1 -> pushConstWide((op - Op.LCONST_0).toLong())
            Op.FCONST_0, Op.FCONST_1, Op.FCONST_2 -> pushConst(java.lang.Float.floatToRawIntBits((op - Op.FCONST_0).toFloat()))
            Op.DCONST_0, Op.DCONST_1 -> pushConstWide(java.lang.Double.doubleToRawLongBits((op - Op.DCONST_0).toDouble()))
            Op.LDC -> ldc(insn.a)
            Op.LDC2_W -> when (val c = pool.constant(insn.a)) {
                is PoolConstant.LongValue -> pushConstWide(c.value)
                is PoolConstant.DoubleValue -> pushConstWide(c.bits)
                else -> throw IllegalArgumentException("ldc2_w #${insn.a}: ожидалась константа long/double")
            }

            Op.ILOAD, Op.LLOAD, Op.FLOAD, Op.DLOAD, Op.ALOAD -> regs[frame.sp] = layout.local(insn.a)
            Op.ISTORE, Op.LSTORE, Op.FSTORE, Op.DSTORE, Op.ASTORE -> store(insn.a)
            Op.IINC -> iinc(insn.a, insn.b)

            Op.IALOAD -> arrayLoad(Opcode.AGET, RegKind.NARROW)
            Op.FALOAD -> arrayLoad(Opcode.AGET, RegKind.NARROW)
            Op.LALOAD, Op.DALOAD -> arrayLoad(Opcode.AGET_WIDE, RegKind.WIDE)
            Op.AALOAD -> arrayLoad(Opcode.AGET_OBJECT, RegKind.OBJECT)
            Op.BALOAD -> arrayLoad(if (isBooleanArray(1)) Opcode.AGET_BOOLEAN else Opcode.AGET_BYTE, RegKind.NARROW)
            Op.CALOAD -> arrayLoad(Opcode.AGET_CHAR, RegKind.NARROW)
            Op.SALOAD -> arrayLoad(Opcode.AGET_SHORT, RegKind.NARROW)
            Op.IASTORE, Op.FASTORE -> arrayStore(Opcode.APUT)
            Op.LASTORE, Op.DASTORE -> arrayStore(Opcode.APUT_WIDE)
            Op.AASTORE -> arrayStore(Opcode.APUT_OBJECT)
            Op.BASTORE -> arrayStore(if (isBooleanArray(2)) Opcode.APUT_BOOLEAN else Opcode.APUT_BYTE)
            Op.CASTORE -> arrayStore(Opcode.APUT_CHAR)
            Op.SASTORE -> arrayStore(Opcode.APUT_SHORT)

            Op.POP, Op.POP2 -> {}
            // Копия вершины ссылается на тот же регистр — без move.
            Op.DUP -> regs[frame.sp] = regs[valuePos(0)]
            Op.DUP_X1, Op.DUP_X2, Op.DUP2, Op.DUP2_X1, Op.DUP2_X2, Op.SWAP -> shuffle(op)

            in Op.IADD..Op.DREM, in Op.ISHL..Op.LXOR -> if (hasLiteral) binaryLiteral(op) else binary(Arithmetic.binary(op))
            in Op.INEG..Op.DNEG, in Op.I2L..Op.I2S -> unary(Arithmetic.unary(op))
            Op.LCMP, Op.FCMPL, Op.FCMPG, Op.DCMPL, Op.DCMPG -> binary(Arithmetic.compare(op))

            in Op.IFEQ..Op.IFLE, Op.IFNULL, Op.IFNONNULL -> ifZero(Arithmetic.ifZero(op), insn.a)
            in Op.IF_ICMPEQ..Op.IF_ACMPNE -> ifCompare(Arithmetic.ifCompare(op), insn.a)
            Op.GOTO -> {
                val target = decoded.indexOf(insn.a)
                if (returnBlock[flow.blockOfInsn[target]]) {
                    // goto на блок из одного возврата — сам возврат (как d8): без копии значения в регистр стека.
                    translate(decoded.insns[target])
                } else {
                    canonicalizeRange(0, frame.sp)
                    code.goto(blockLabel(insn.a))
                }
            }
            Op.TABLESWITCH, Op.LOOKUPSWITCH -> switch(insn)

            Op.IRETURN, Op.FRETURN -> returnValue(Opcode.RETURN, RegKind.NARROW)
            Op.LRETURN, Op.DRETURN -> returnValue(Opcode.RETURN_WIDE, RegKind.WIDE)
            Op.ARETURN -> returnValue(Opcode.RETURN_OBJECT, RegKind.OBJECT)
            Op.RETURN -> {
                exitMonitor()
                code.returnVoid()
            }

            Op.GETSTATIC -> getStatic(insn.a)
            Op.PUTSTATIC -> putStatic(insn.a)
            Op.GETFIELD -> getField(insn.a)
            Op.PUTFIELD -> putField(insn.a)
            Op.INVOKEVIRTUAL, Op.INVOKESPECIAL, Op.INVOKESTATIC, Op.INVOKEINTERFACE -> invoke(insn)
            Op.INVOKEDYNAMIC -> invokeDynamic(insn.a)

            Op.NEW -> newInstance(pool.classDescriptor(insn.a))
            Op.NEWARRAY -> {
                val element = TypeInterpreter.primitiveOfAtype(insn.a)
                newArray("[$element")
                fillArray(element[0])
            }
            Op.ANEWARRAY -> newArray("[" + pool.classDescriptor(insn.a))
            Op.ARRAYLENGTH -> {
                val p = valuePos(0)
                val dest = resultReg(p)
                op12x(Opcode.ARRAY_LENGTH, dest, RegKind.NARROW, regs[p], RegKind.OBJECT)
                regs[p] = dest
            }
            Op.ATHROW -> code.throwException(w.src(regs[valuePos(0)], RegKind.OBJECT, 8))
            Op.CHECKCAST -> checkCast(pool.classDescriptor(insn.a))
            Op.INSTANCEOF -> instanceOf(pool.classDescriptor(insn.a))
            Op.MONITORENTER -> monitorEnter()
            Op.MONITOREXIT -> code.monitorExit(w.src(regs[valuePos(0)], RegKind.OBJECT, 8))
            Op.MULTIANEWARRAY -> multiNewArray(pool.classDescriptor(insn.a), insn.b)

            // До версии 51 подпрограммы встроены заранее (JsrInliner), с 51 jsr/ret запрещены (JVMS §4.9.1).
            Op.JSR, Op.RET -> throw IllegalArgumentException("jsr/ret недопустимы в class-файле версии 51 и выше")
            else -> throw IllegalArgumentException("неподдерживаемый опкод 0x${Integer.toHexString(op)}")
        }
    }

    // ---- константы ----

    private fun pushConst(value: Int) {
        val dest = resultReg(frame.sp)
        w.constInt(dest, value)
        regs[frame.sp] = dest
    }

    private fun pushConstWide(value: Long) {
        val dest = resultReg(frame.sp)
        w.constWide(dest, value)
        regs[frame.sp] = dest
    }

    private fun ldc(index: Int) {
        when (val c = pool.constant(index)) {
            is PoolConstant.IntValue -> pushConst(c.value)
            is PoolConstant.FloatValue -> pushConst(c.bits)
            is PoolConstant.StringValue -> pushObject { d ->
                val idx = ctx.asm.strings.add(c.value)
                if (idx > 0xFFFF) code.emit(Ins31c(Opcode.CONST_STRING_JUMBO, d, idx))
                else code.emit(Ins21c(Opcode.CONST_STRING, d, idx))
            }
            is PoolConstant.ClassValue -> pushObject { d -> code.constClass(d, c.descriptor) }
            is PoolConstant.MethodTypeValue -> pushObject { d -> code.constMethodType(d, ctx.protoIndex(c.descriptor)) }
            is PoolConstant.MethodHandleValue -> pushObject { d -> code.constMethodHandle(d, ctx.methodHandleIndex(c)) }
            is PoolConstant.LongValue, is PoolConstant.DoubleValue ->
                throw IllegalArgumentException("ldc #$index: long/double загружаются только через ldc2_w")
        }
    }

    /** Положить на стек объект, который создаёт инструкция формата 21c/31c (8-битный приёмник). */
    private inline fun pushObject(emit: (Int) -> Unit) {
        val dest = resultReg(frame.sp)
        val d = w.dst(dest, 8)
        emit(d)
        w.commit(dest, d, RegKind.OBJECT)
        regs[frame.sp] = dest
    }

    // ---- локалы ----

    private fun store(index: Int) {
        // Мёртвый слот: значение просто снимается. Копия: значение уже в регистре источника.
        if (slots.neverRead[index] || slots.aliasOf[index] >= 0) return
        val p = valuePos(0)
        val kind = kindAt(p)
        val target = layout.local(index)
        spillReferences(target, kind.width, p)
        w.move(target, regs[p], kind)
    }

    /**
     * monitor-enter. Верификатор ART отслеживает блокировку по регистрам: monitor-exit должен
     * получить объект из регистра, который держит блокировку, или из его копии, снятой после
     * захвата. Поэтому после захвата блокировка передаётся копиями (значения не меняются) всем
     * регистрам, где лежит тот же объект ([SameValues]): регистру источника, если захват шёл через
     * временный (объект выше v255), локалам (javac: `dup; astore; monitorenter`, R8 — копия задолго
     * до захвата) и словам стека.
     */
    private fun monitorEnter() {
        val p = valuePos(0)
        val src = regs[p]
        val type = frame.stack[p]
        val obj = w.src(src, RegKind.OBJECT, 8)
        code.monitorEnter(obj)
        val done = HashSet<Int>()
        done.add(obj)
        if (obj != src) {
            w.move(src, obj, RegKind.OBJECT)
            done.add(src)
        }
        // Копия переносит и тип регистра: только туда, где тип тот же (после checkcast — уже другой).
        for (slot in sameValues.at(current)) {
            val reg = if (sameValues.isLocal(slot)) {
                if (frame.locals[slot] != type || slots.neverRead[slot]) continue
                layout.local(slot)
            } else {
                val word = sameValues.stackWord(slot)
                if (frame.stack[word] != type) continue
                regs[word]
            }
            if (done.add(reg)) {
                // Ссылки стека на регистр локала — сначала в свои регистры: после приведения на месте
                // (castInPlace) тип регистра уже не тот, что у копии блокировки.
                if (sameValues.isLocal(slot)) spillReferences(reg, 1, frame.sp)
                w.move(reg, obj, RegKind.OBJECT)
            }
        }
        if (nestedMonitors) killStaleCopies(obj)
    }

    private fun iinc(index: Int, delta: Int) {
        val reg = layout.local(index)
        spillReferences(reg, 1, frame.sp)
        if (delta in -128..127) {
            val s = w.src(reg, RegKind.NARROW, 8)
            val d = w.dst(reg, 8)
            code.emit(Ins22b(Opcode.ADD_INT_LIT8, d, s, delta))
            w.commit(reg, d, RegKind.NARROW)
        } else {
            val s = w.src(reg, RegKind.NARROW, 4)
            val d = w.dst(reg, 4)
            code.emit(Ins22s(Opcode.ADD_INT_LIT16, d, s, delta))
            w.commit(reg, d, RegKind.NARROW)
        }
    }

    // ---- массивы ----

    /** Массив [k]-м значением от вершины — `boolean[]` (иначе baload/bastore работают с `byte[]`). */
    private fun isBooleanArray(k: Int): Boolean {
        val t = frame.stack[valuePos(k)]
        return t.kind == VType.REF && t.desc == "[Z"
    }

    private fun arrayLoad(op: Opcode, resultKind: RegKind) {
        val index = valuePos(0)
        val array = valuePos(1)
        val dest = resultReg(array)
        op23x(op, dest, resultKind, regs[array], RegKind.OBJECT, regs[index], RegKind.NARROW)
        regs[array] = dest
    }

    private fun arrayStore(op: Opcode) {
        val value = valuePos(0)
        val index = valuePos(1)
        val array = valuePos(2)
        val v = w.src(regs[value], kindAt(value), 8)
        val a = w.src(regs[array], RegKind.OBJECT, 8)
        val i = w.src(regs[index], RegKind.NARROW, 8)
        code.emit(Ins23x(op, v, a, i))
    }

    // ---- арифметика ----

    private fun binary(op: Opcode) {
        val b = valuePos(0)
        val a = valuePos(1)
        val dest = resultReg(a)
        op23x(op, dest, Arithmetic.resultKind(op), regs[a], kindAt(a), regs[b], kindAt(b))
        regs[a] = dest
    }

    /**
     * Константа [insn] — второй операнд следующей целочисленной операции и помещается в её литерал
     * (`add-int/lit8`, `mul-int/lit16` …; вычитание — сложением с противоположным). Как в d8.
     */
    private fun literalOperand(insn: JvmInsn, next: JvmInsn): Boolean {
        val value = intConstant(insn) ?: return false
        val lit = if (next.opcode == Op.ISUB) {
            if (value == Int.MIN_VALUE) return false
            -value
        } else value
        return when (next.opcode) {
            Op.IADD, Op.ISUB, Op.IMUL, Op.IDIV, Op.IREM, Op.IAND, Op.IOR, Op.IXOR -> lit in -32768..32767
            Op.ISHL, Op.ISHR, Op.IUSHR -> lit in -128..127
            else -> false
        }
    }

    /** Целочисленная операция со вторым операндом-литералом ([hasLiteral]: константа не в регистре). */
    private fun binaryLiteral(jvmOp: Int) {
        hasLiteral = false
        val lit = if (jvmOp == Op.ISUB) -literalValue else literalValue
        val b = valuePos(0)
        val a = valuePos(1)
        val op8 = when (jvmOp) {
            Op.IADD, Op.ISUB -> Opcode.ADD_INT_LIT8
            Op.IMUL -> Opcode.MUL_INT_LIT8
            Op.IDIV -> Opcode.DIV_INT_LIT8
            Op.IREM -> Opcode.REM_INT_LIT8
            Op.IAND -> Opcode.AND_INT_LIT8
            Op.IOR -> Opcode.OR_INT_LIT8
            Op.IXOR -> Opcode.XOR_INT_LIT8
            Op.ISHL -> Opcode.SHL_INT_LIT8
            Op.ISHR -> Opcode.SHR_INT_LIT8
            else -> Opcode.USHR_INT_LIT8
        }
        val op16 = when (jvmOp) {
            Op.IADD, Op.ISUB -> Opcode.ADD_INT_LIT16
            Op.IMUL -> Opcode.MUL_INT_LIT16
            Op.IDIV -> Opcode.DIV_INT_LIT16
            Op.IREM -> Opcode.REM_INT_LIT16
            Op.IAND -> Opcode.AND_INT_LIT16
            Op.IOR -> Opcode.OR_INT_LIT16
            Op.IXOR -> Opcode.XOR_INT_LIT16
            else -> null
        }
        if (lit in -128..127) {
            val dest = resultReg(a)
            val s = w.src(regs[a], RegKind.NARROW, 8)
            val d = w.dst(dest, 8)
            code.emit(Ins22b(op8, d, s, lit))
            w.commit(dest, d, RegKind.NARROW)
            regs[a] = dest
            return
        }
        val dest = if (forwardTarget >= 0) forwardTarget else layout.stack(a)
        if (op16 != null && regs[a] < 16 && dest < 16) {
            resultReg(a)
            code.emit(Ins22s(op16, dest, regs[a], lit))
            regs[a] = dest
            return
        }
        // lit16 — только 4-битные регистры: константа в свой регистр (он свободен: ссылки на регистры
        // стека идут только вниз) и обычная форма.
        w.constInt(layout.stack(b), literalValue)
        regs[b] = layout.stack(b)
        binary(Arithmetic.binary(jvmOp))
    }

    private fun unary(op: Opcode) {
        val p = valuePos(0)
        val dest = resultReg(p)
        op12x(op, dest, Arithmetic.resultKind(op), regs[p], kindAt(p))
        regs[p] = dest
    }

    private fun op23x(op: Opcode, dest: Int, destKind: RegKind, b: Int, bKind: RegKind, c: Int, cKind: RegKind) {
        // Приёмник совпадает с первым операндом (у целых перестановочных — с любым): форма /2addr,
        // одна кодовая единица вместо двух. У float/double операнды не переставляются (какой NaN вернётся).
        if (op.value in 0x90..0xAF && dest < 16) {
            val swap = dest == c && dest != b && b < 16 && commutative(op)
            if ((dest == b && c < 16) || swap) {
                code.emit(Ins12x(Opcode.of(op.value + 0x20)!!, dest, if (swap) b else c))
                return
            }
        }
        val rb = w.src(b, bKind, 8)
        val rc = w.src(c, cKind, 8)
        val rd = w.dst(dest, 8)
        code.emit(Ins23x(op, rd, rb, rc))
        w.commit(dest, rd, destKind)
    }

    private fun commutative(op: Opcode): Boolean = when (op) {
        Opcode.ADD_INT, Opcode.MUL_INT, Opcode.AND_INT, Opcode.OR_INT, Opcode.XOR_INT,
        Opcode.ADD_LONG, Opcode.MUL_LONG, Opcode.AND_LONG, Opcode.OR_LONG, Opcode.XOR_LONG -> true
        else -> false
    }

    private fun op12x(op: Opcode, dest: Int, destKind: RegKind, src: Int, srcKind: RegKind) {
        val s = w.src(src, srcKind, 4)
        val d = w.dst(dest, 4)
        code.emit(Ins12x(op, d, s))
        w.commit(dest, d, destKind)
    }

    // ---- переходы ----

    private fun ifZero(op: Opcode, target: Int) {
        val p = valuePos(0)
        canonicalizeRange(0, p)
        code.branch(op, blockLabel(target), w.src(regs[p], kindAt(p), 8))
    }

    private fun ifCompare(op: Opcode, target: Int) {
        val b = valuePos(0)
        val a = valuePos(1)
        canonicalizeRange(0, a)
        val ra = w.src(regs[a], kindAt(a), 4)
        val rb = w.src(regs[b], kindAt(b), 4)
        code.branch(op, blockLabel(target), ra, rb)
    }

    private fun switch(insn: JvmInsn) {
        val p = valuePos(0)
        canonicalizeRange(0, p)
        val targets = insn.targets!!
        if (targets.isNotEmpty()) {
            val label = "P${payloads.size}"
            val key = w.src(regs[p], RegKind.NARROW, 8)
            val targetLabels = targets.map { blockLabel(it) }
            if (insn.opcode == Op.TABLESWITCH) {
                code.packedSwitch(key, label)
                payloads.add(SwitchPayload(label, true, insn.b, null, targetLabels))
            } else {
                code.sparseSwitch(key, label)
                payloads.add(SwitchPayload(label, false, 0, insn.keys, targetLabels))
            }
        }
        // Ни одна ветка не подошла — DEX проваливается дальше, JVM идёт на default.
        code.goto(blockLabel(insn.a))
    }

    private fun returnValue(op: Opcode, kind: RegKind) {
        val value = w.src(regs[valuePos(0)], kind, 8)
        exitMonitor()
        when (op) {
            Opcode.RETURN -> code.returnValue(value)
            Opcode.RETURN_WIDE -> code.returnWide(value)
            else -> code.returnObject(value)
        }
    }

    private fun exitMonitor() {
        if (isSynchronized) code.monitorExit(layout.lockReg)
    }

    // ---- поля ----

    private fun getStatic(index: Int) {
        val ref = pool.member(index)
        val kind = kindOfDescriptor(ref.descriptor)
        val dest = resultReg(frame.sp)
        val d = w.dst(dest, 8)
        code.emit(Ins21c(FieldOps.sget(ref.descriptor), d, ctx.fieldIndex(index)))
        w.commit(dest, d, kind)
        regs[frame.sp] = dest
    }

    private fun putStatic(index: Int) {
        val ref = pool.member(index)
        val p = valuePos(0)
        code.emit(Ins21c(FieldOps.sput(ref.descriptor), w.src(regs[p], kindAt(p), 8), ctx.fieldIndex(index)))
    }

    private fun getField(index: Int) {
        val ref = pool.member(index)
        val p = valuePos(0)
        val dest = resultReg(p)
        val obj = w.src(regs[p], RegKind.OBJECT, 4)
        val d = w.dst(dest, 4)
        code.emit(Ins22c(FieldOps.iget(ref.descriptor), d, obj, ctx.fieldIndex(index)))
        w.commit(dest, d, kindOfDescriptor(ref.descriptor))
        regs[p] = dest
    }

    private fun putField(index: Int) {
        val ref = pool.member(index)
        val value = valuePos(0)
        val obj = valuePos(1)
        val v = w.src(regs[value], kindAt(value), 4)
        val o = w.src(regs[obj], RegKind.OBJECT, 4)
        code.emit(Ins22c(FieldOps.iput(ref.descriptor), v, o, ctx.fieldIndex(index)))
    }

    // ---- вызовы ----

    private fun invoke(insn: JvmInsn) {
        val ref = pool.member(insn.a)
        // Маркеры встраивания Kotlin (`InlineMarker.*` — пустые статические методы в телах inline-функций)
        // не переводятся, как в d8: вызов между концом try synchronized-блока и monitorexit попадал бы
        // только во внешний обработчик, и ART отмечал бы метод «failed lock verification».
        if (insn.opcode == Op.INVOKESTATIC && ref.owner == "kotlin/jvm/internal/InlineMarker") return
        val callType = ref.methodType ?: throw IllegalArgumentException("#${insn.a}: вызов не метода, а поля ${ref.name}")
        if (insn.opcode == Op.INVOKESTATIC && ref.name == LambdaFactory.FACTORY) {
            val lambda = ctx.lambdas[ref.owner]
            if (lambda != null) return newLambda(ref.owner, lambda, callType)
        }
        val words = callType.paramWords + if (insn.opcode == Op.INVOKESTATIC) 0 else 1
        val base = frame.sp - words
        val polymorphic = if (insn.opcode == Op.INVOKEVIRTUAL) Polymorphic.declaredDescriptor(ref) else null
        val bridge = if (insn.opcode == Op.INVOKESTATIC || ref.name == "<init>") null
        else ctx.nest.bridgeFor(ctx.thisClass, ref.owner, ref.name, ref.descriptor)
        when {
            polymorphic != null -> {
                val methodIdx = ctx.methodIndex(ref.owner, ref.name, polymorphic)
                val protoIdx = ctx.protoIndex(ref.descriptor)
                val args = argumentRegisters(base)
                if (args != null) code.invokePolymorphic(methodIdx, protoIdx, *args)
                else code.invokePolymorphicRange(methodIdx, protoIdx, rangeStart(base), words)
            }
            // private-метод соседа по гнезду: ART не пускает к нему напрямую — зовём статический мост.
            bridge != null -> emitInvoke(Opcode.INVOKE_STATIC, ctx.methodIndex(bridge.owner, bridge.bridgeName, bridge.bridgeDescriptor), base, words)
            // invokespecial собственного виртуального метода — его private-копия (см. ClassContext.specialCopies).
            insn.opcode == Op.INVOKESPECIAL && ref.owner == ctx.thisClass && (ref.name + ref.descriptor) in ctx.specialCopies ->
                emitInvoke(Opcode.INVOKE_DIRECT, ctx.methodIndex(ctx.thisClass, ctx.specialCopies.getValue(ref.name + ref.descriptor), ref.descriptor), base, words)
            else -> {
                val op = when (insn.opcode) {
                    Op.INVOKESTATIC -> Opcode.INVOKE_STATIC
                    Op.INVOKESPECIAL -> if (ref.name == "<init>" || ref.owner == ctx.thisClass) Opcode.INVOKE_DIRECT else Opcode.INVOKE_SUPER
                    Op.INVOKEINTERFACE -> if (ctx.isOwnPrivateMethod(ref)) Opcode.INVOKE_DIRECT else Opcode.INVOKE_INTERFACE
                    else -> if (ctx.isOwnPrivateMethod(ref)) Opcode.INVOKE_DIRECT else Opcode.INVOKE_VIRTUAL
                }
                // Невиртуальный вызов метода интерфейса (static/private/super) ART допускает с DEX 037.
                if (ref.isInterface && op != Opcode.INVOKE_INTERFACE) ctx.asm.state.requireDexVersion(37)
                emitInvoke(op, ctx.methodIndex(insn.a), base, words)
            }
        }
        moveResult(callType.returnType, base)
    }

    /**
     * Фабрика класса лямбды из рассахаривания встраивается, как у d8: без захвата — `sget-object INSTANCE`,
     * с захватом — `new-instance` и `invoke-direct <init>(захваченные)`. Сама фабрика в DEX не пишется
     * ([com.tim.c2d.ClassConverter]); порядок создания объекта и вычисления аргументов не наблюдаем.
     */
    private fun newLambda(owner: String, lambda: LambdaFactory, callType: Descriptors.MethodType) {
        val desc = Descriptors.ofClass(owner)
        if (!lambda.capturing) {
            val field = ctx.asm.fields.add(desc, LambdaFactory.INSTANCE, desc)
            pushObject { d -> code.emit(Ins21c(Opcode.SGET_OBJECT, d, field)) }
            return
        }
        val words = callType.paramWords
        val base = frame.sp - words
        val ctor = ctx.methodIndex(owner, "<init>", lambda.constructorDescriptor)
        val dest = if (discardResult) layout.stack(base) else resultReg(base)
        // 35c: получатель и не больше четырёх слов аргументов, все ниже v16.
        val args = if (words <= 4) argumentRegisters(base) else null
        var receiver = -1
        if (args != null) {
            fun free(r: Int) = r < 16 && args.none { it == r }
            // Получатель — регистр стека: new-instance пишет в него до <init>, и локал (при записи сразу
            // в локал) не должен держать неинициализированный объект — его увидел бы обработчик.
            receiver = when {
                layout.isStackReg(dest) && free(dest) -> dest
                free(layout.stack(base)) -> layout.stack(base)
                free(layout.stack(frame.sp)) -> layout.stack(frame.sp)
                else -> -1
            }
        }
        if (receiver >= 0) {
            code.newInstance(receiver, desc)
            code.invoke(Opcode.INVOKE_DIRECT, ctor, receiver, *args!!)
            w.move(dest, receiver, RegKind.OBJECT)
        } else {
            // /range: получатель и копии аргументов подряд над вершиной стека (место зарезервировано).
            val r = layout.stack(frame.sp)
            val d = w.dst(r, 8)
            code.newInstance(d, desc)
            w.commit(r, d, RegKind.OBJECT)
            var k = 1
            forEachValue(base, frame.sp) { p ->
                w.move(r + k, regs[p], kindAt(p))
                k += frame.stack[p].width
            }
            code.invokeRange(Opcode.INVOKE_DIRECT_RANGE, ctor, r, words + 1)
            w.move(dest, r, RegKind.OBJECT)
        }
        regs[base] = dest
    }

    /** Слов стека сверх max_stack для встроенных фабрик лямбд с /range: получатель и копии аргументов. */
    private fun lambdaSlack(): Int {
        if (ctx.lambdas.isEmpty()) return 0
        var slack = 0
        for (insn in decoded.insns) {
            if (insn.opcode != Op.INVOKESTATIC) continue
            val ref = pool.member(insn.a)
            if (ref.name != LambdaFactory.FACTORY || ref.owner !in ctx.lambdas) continue
            slack = maxOf(slack, (ref.methodType?.paramWords ?: 0) + 1)
        }
        return slack
    }

    /** invoke-* в форме 35c, если аргументы помещаются, иначе /range по каноническим регистрам. */
    private fun emitInvoke(op: Opcode, methodIdx: Int, base: Int, words: Int) {
        val args = argumentRegisters(base)
        if (args != null) code.invoke(op, methodIdx, *args)
        else code.invokeRange(rangeOf(op), methodIdx, rangeStart(base), words)
    }

    private fun invokeDynamic(index: Int) {
        val indy = pool.invokeDynamic(index)
        val callType = Descriptors.method(indy.descriptor)
        val base = frame.sp - callType.paramWords
        val callSite = ctx.callSiteIndex(indy)
        val args = argumentRegisters(base)
        if (args != null) code.invokeCustom(callSite, *args)
        else code.invokeCustomRange(callSite, rangeStart(base), callType.paramWords)
        moveResult(callType.returnType, base)
    }

    /**
     * Регистры аргументов для формы 35c (не больше 5 слов, все < 16) или `null` — нужна форма /range.
     * Аргумент в старшем регистре (параметр, локал) копируется в регистр своего слова стека, если тот
     * ниже v16, иначе во временную пару: копии только таких аргументов вместо копий всех в диапазон.
     */
    private fun argumentRegisters(base: Int): IntArray? {
        val words = frame.sp - base
        if (words > 5) return null
        // Аргумент, у которого и слово стека выше v15, — во временную пару (если хватает). Копий
        // не больше, чем для /range (там копируются аргументы не на своих словах стека).
        var needTemps = 0
        var copies35c = 0
        var copiesRange = 0
        forEachValue(base, frame.sp) { p ->
            val width = frame.stack[p].width
            if (regs[p] + width - 1 >= 16) {
                copies35c++
                if (layout.stack(p) + width - 1 >= 16) needTemps++
            }
            if (regs[p] != layout.stack(p)) copiesRange++
        }
        if (needTemps > 0 && (needTemps > w.tempPairsLeft() || copies35c > copiesRange)) return null
        val out = IntArray(words)
        var n = 0
        forEachValue(base, frame.sp) { p ->
            val width = frame.stack[p].width
            var r = regs[p]
            if (r + width - 1 >= 16) {
                if (layout.stack(p) + width - 1 < 16) {
                    materialize(p)
                    r = regs[p]
                } else {
                    val t = w.takeTemp()
                    w.move(t, r, kindAt(p))
                    r = t
                }
            }
            out[n++] = r
            if (width == 2) out[n++] = r + 1
        }
        return out
    }

    /** Аргументы /range: значения в канонических регистрах подряд. */
    private fun rangeStart(base: Int): Int {
        canonicalizeRange(base, frame.sp)
        return layout.stack(base)
    }

    private fun moveResult(returnType: String, base: Int) {
        if (returnType == "V") return
        if (discardResult) {
            regs[base] = layout.stack(base)
            return
        }
        val kind = kindOfDescriptor(returnType)
        val dest = resultReg(base)
        val d = w.dst(dest, 8)
        when (kind) {
            RegKind.NARROW -> code.moveResult(d)
            RegKind.WIDE -> code.moveResultWide(d)
            RegKind.OBJECT -> code.moveResultObject(d)
        }
        w.commit(dest, d, kind)
        regs[base] = dest
    }

    // ---- объекты и массивы ----

    private fun newInstance(desc: String) = pushObject { d -> code.newInstance(d, desc) }

    private fun newArray(desc: String) {
        val p = valuePos(0)
        val dest = resultReg(p)
        val size = w.src(regs[p], RegKind.NARROW, 4)
        val d = w.dst(dest, 4)
        code.newArray(d, size, desc)
        w.commit(dest, d, RegKind.OBJECT)
        regs[p] = dest
    }

    /**
     * javac и kotlinc заполняют массив примитивов константами поэлементно: `dup; индекс; значение; xastore`
     * (в DEX — 4–7 кодовых единиц на элемент). Как d8, префикс с индексами 0, 1, 2, … переводится одной
     * `fill-array-data`, если так короче. Инструкции шаблона — в блоке `newarray` и под теми же
     * обработчиками; исключений в них нет: массив только что создан, его длина — константа не меньше
     * числа элементов. Вызывается сразу после перевода `newarray` (массив — на вершине стека).
     */
    private fun fillArray(element: Char) {
        val insns = decoded.insns
        val at = current
        val block = flow.blockOfInsn[at]
        val set = handlers.handlerSetOfInsn[at]
        if (at == 0 || flow.blockOfInsn[at - 1] != block) return
        val length = intConstant(insns[at - 1]) ?: return
        val store = when (element) {
            'I' -> Op.IASTORE
            'B', 'Z' -> Op.BASTORE
            'C' -> Op.CASTORE
            'S' -> Op.SASTORE
            'J' -> Op.LASTORE
            'F' -> Op.FASTORE
            else -> Op.DASTORE
        }
        val width = when (element) {
            'B', 'Z' -> 1
            'C', 'S' -> 2
            'I', 'F' -> 4
            else -> 8
        }
        val values = ArrayList<Long>()
        var saved = 0 // кодовые единицы поэлементного перевода
        var i = at + 1
        while (values.size < length && i + 3 < insns.size) {
            if ((i until i + 4).any { flow.blockOfInsn[it] != block || handlers.handlerSetOfInsn[it] != set }) break
            if (insns[i].opcode != Op.DUP || insns[i + 3].opcode != store) break
            val index = intConstant(insns[i + 1]) ?: break
            if (index != values.size) break
            val value = elementConstant(insns[i + 2], element) ?: break
            values.add(value)
            saved += constUnits(index.toLong(), false) + constUnits(value, width == 8) + 2
            i += 4
        }
        val count = values.size
        if (count == 0) return
        val dataUnits = (count * width + 1) / 2
        if (3 + 4 + dataUnits + 1 >= saved) return
        val data = ByteArray(count * width)
        for ((k, v) in values.withIndex()) {
            for (b in 0 until width) data[k * width + b] = (v ushr (8 * b)).toByte()
        }
        val label = "A${insns[at].pc}"
        w.resetTemps() // временные newarray уже не нужны: массив в своём регистре
        code.fillArrayData(w.src(regs[valuePos(0)], RegKind.OBJECT, 8), label)
        arrayPayloads.add(ArrayPayload(label, width, count, data))
        skipUntil = i
    }

    /** Значение `iconst`/`bipush`/`sipush`/`ldc` целого или `null`. */
    private fun intConstant(insn: JvmInsn): Int? = when (insn.opcode) {
        in Op.ICONST_M1..Op.ICONST_5, Op.BIPUSH, Op.SIPUSH -> insn.b
        Op.LDC -> (pool.constant(insn.a) as? PoolConstant.IntValue)?.value
        else -> null
    }

    /** Константа элемента массива [element] (биты float/double; boolean — `& 1`, как bastore в boolean[]). */
    private fun elementConstant(insn: JvmInsn, element: Char): Long? = when (element) {
        'I' -> intConstant(insn)?.toLong()
        'B' -> intConstant(insn)?.let { (it and 0xFF).toLong() }
        'Z' -> intConstant(insn)?.let { (it and 1).toLong() }
        'C', 'S' -> intConstant(insn)?.let { (it and 0xFFFF).toLong() }
        'F' -> when (insn.opcode) {
            in Op.FCONST_0..Op.FCONST_2 -> java.lang.Float.floatToRawIntBits((insn.opcode - Op.FCONST_0).toFloat()).toLong() and 0xFFFFFFFFL
            Op.LDC -> (pool.constant(insn.a) as? PoolConstant.FloatValue)?.let { it.bits.toLong() and 0xFFFFFFFFL }
            else -> null
        }
        'J' -> when (insn.opcode) {
            Op.LCONST_0, Op.LCONST_1 -> (insn.opcode - Op.LCONST_0).toLong()
            Op.LDC2_W -> (pool.constant(insn.a) as? PoolConstant.LongValue)?.value
            else -> null
        }
        else -> when (insn.opcode) {
            Op.DCONST_0, Op.DCONST_1 -> java.lang.Double.doubleToRawLongBits((insn.opcode - Op.DCONST_0).toDouble())
            Op.LDC2_W -> (pool.constant(insn.a) as? PoolConstant.DoubleValue)?.bits
            else -> null
        }
    }

    /** Кодовые единицы const-инструкции для значения (как в [DexCodeWriter.constInt]/[DexCodeWriter.constWide]). */
    private fun constUnits(value: Long, wide: Boolean): Int = if (!wide) {
        val v = value.toInt()
        when {
            v in -8..7 -> 1
            v in -32768..32767 || v and 0xFFFF == 0 -> 2
            else -> 3
        }
    } else when {
        value in -32768L..32767L -> 2
        value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> 3
        value and 0xFFFFFFFFFFFFL == 0L -> 2
        else -> 5
    }

    /**
     * check-cast меняет тип самого регистра (и сужая, и расширяя его), поэтому приводится только
     * собственная копия значения в регистре стека — регистр локала сохраняет свой тип.
     */
    private fun checkCast(desc: String) {
        val p = valuePos(0)
        val target = forwardTarget
        if (target >= 0) {
            // Следом xstore в локал: значение копируется в его регистр и приводится там (тип локала
            // и так станет типом приведения). Ссылки на этот регистр ниже по стеку уже скопированы.
            forwardTarget = -1
            w.move(target, regs[p], RegKind.OBJECT)
            regs[p] = target
            if (target < 256) {
                code.checkCast(target, desc)
            } else {
                val t = w.src(target, RegKind.OBJECT, 8)
                code.checkCast(t, desc)
                w.move(target, t, RegKind.OBJECT)
            }
            return
        }
        val src = regs[p]
        if (src < 256 && castInPlace(p, src)) {
            // Локал дальше не читается: приводится сам его регистр, без копии (на провале регистр не меняется).
            code.checkCast(src, desc)
            return
        }
        materialize(p)
        val reg = layout.stack(p)
        if (reg < 256) {
            code.checkCast(reg, desc)
        } else {
            val t = w.src(reg, RegKind.OBJECT, 8)
            code.checkCast(t, desc)
            w.move(reg, t, RegKind.OBJECT)
        }
    }

    /**
     * Значение на слове [p] — регистр локала [reg], который после этой инструкции никто не читает (ни сам
     * слот, ни копии в том же регистре), и других ссылок на регистр на стеке нет: check-cast можно делать
     * прямо в нём — сменится лишь тип мёртвого регистра.
     */
    private fun castInPlace(p: Int, reg: Int): Boolean {
        if (layout.isStackReg(reg) || reg < layout.temps) return false
        var shared = false
        forEachValue(0, frame.sp) { q -> if (q != p && regs[q] == reg) shared = true }
        if (shared) return false
        val live = liveness.liveAfter(current)
        for (slot in 0 until layout.maxLocals) {
            if (live[slot] && layout.hasRegister(slot) && layout.local(slot) == reg) return false
        }
        return true
    }

    private fun instanceOf(desc: String) {
        val p = valuePos(0)
        val dest = resultReg(p)
        val s = w.src(regs[p], RegKind.OBJECT, 4)
        val d = w.dst(dest, 4)
        code.instanceOf(d, s, desc)
        w.commit(dest, d, RegKind.NARROW)
        regs[p] = dest
    }

    /**
     * `multianewarray`: в DEX нет прямого аналога, поэтому как d8 —
     * `filled-new-array {размеры}, [I` + `java.lang.reflect.Array.newInstance(Class, int[])` + check-cast.
     */
    private fun multiNewArray(desc: String, dims: Int) {
        val base = frame.sp - dims
        if (dims == 1) {
            newArray(desc)
            return
        }
        canonicalizeRange(base, frame.sp)
        val first = layout.stack(base)
        if (dims <= 5 && first + dims - 1 < 16) code.filledNewArray("[I", *IntArray(dims) { first + it })
        else code.filledNewArrayRange("[I", first, dims)
        val dimsArray = layout.stack(base + 1)
        val classReg = first
        val d = w.dst(dimsArray, 8)
        code.moveResultObject(d)
        w.commit(dimsArray, d, RegKind.OBJECT)

        val component = desc.substring(dims)
        val c = w.dst(classReg, 8)
        if (component.length == 1) {
            val box = Polymorphic.boxOf(component[0])
            code.emit(Ins21c(Opcode.SGET_OBJECT, c, ctx.asm.fields.add(box, "TYPE", "Ljava/lang/Class;")))
        } else {
            code.constClass(c, component)
        }
        w.commit(classReg, c, RegKind.OBJECT)

        val newInstance = ctx.asm.methods.add(
            "Ljava/lang/reflect/Array;", "newInstance", listOf("Ljava/lang/Class;", "[I"), "Ljava/lang/Object;"
        )
        if (dimsArray < 16) code.invoke(Opcode.INVOKE_STATIC, newInstance, classReg, dimsArray)
        else code.invokeRange(Opcode.INVOKE_STATIC_RANGE, newInstance, classReg, 2)
        val r = w.dst(first, 8)
        code.moveResultObject(r)
        code.checkCast(r, desc)
        w.commit(first, r, RegKind.OBJECT)
        regs[base] = first
    }

    // ---- перестановки стека ----

    private class StackValue(val reg: Int, val kind: RegKind, val width: Int)

    /**
     * Семейство dup и swap: значения переставляются символически, затем всё, что лежит
     * в регистрах стека не на своём месте, переносится одним параллельным присваиванием.
     */
    private fun shuffle(op: Int) {
        fun value(k: Int): StackValue {
            val p = valuePos(k)
            val t = frame.stack[p]
            return StackValue(regs[p], RegKind.of(t), t.width)
        }
        val v1 = value(0)
        val out: List<StackValue>
        val consumed: Int
        when (op) {
            Op.DUP -> { consumed = 1; out = listOf(v1, v1) }
            Op.DUP_X1 -> { val v2 = value(1); consumed = 2; out = listOf(v1, v2, v1) }
            Op.DUP_X2 -> {
                val v2 = value(1)
                if (v2.width == 2) { consumed = 2; out = listOf(v1, v2, v1) }
                else { val v3 = value(2); consumed = 3; out = listOf(v1, v3, v2, v1) }
            }
            Op.DUP2 -> {
                if (v1.width == 2) { consumed = 1; out = listOf(v1, v1) }
                else { val v2 = value(1); consumed = 2; out = listOf(v2, v1, v2, v1) }
            }
            Op.DUP2_X1 -> {
                val v2 = value(1)
                if (v1.width == 2) { consumed = 2; out = listOf(v1, v2, v1) }
                else { val v3 = value(2); consumed = 3; out = listOf(v2, v1, v3, v2, v1) }
            }
            Op.DUP2_X2 -> {
                val v2 = value(1)
                if (v1.width == 2) {
                    if (v2.width == 2) { consumed = 2; out = listOf(v1, v2, v1) }
                    else { val v3 = value(2); consumed = 3; out = listOf(v1, v3, v2, v1) }
                } else {
                    val v3 = value(2)
                    if (v3.width == 2) { consumed = 3; out = listOf(v2, v1, v3, v2, v1) }
                    else { val v4 = value(3); consumed = 4; out = listOf(v2, v1, v4, v3, v2, v1) }
                }
            }
            else -> { val v2 = value(1); consumed = 2; out = listOf(v1, v2) }
        }
        val base = valuePos(consumed - 1)
        val moves = ArrayList<DexCodeWriter.Move>(out.size)
        val placed = IntArray(out.size)
        var pos = base
        for ((i, v) in out.withIndex()) {
            val target = layout.stack(pos)
            if (layout.isStackReg(v.reg)) {
                moves.add(DexCodeWriter.Move(target, v.reg, v.kind))
                placed[i] = target
            } else {
                placed[i] = v.reg // ссылка на локал остаётся виртуальной
            }
            pos += v.width
        }
        w.parallelMove(moves)
        pos = base
        for ((i, v) in out.withIndex()) {
            regs[pos] = placed[i]
            pos += v.width
        }
    }

    private fun kindOfDescriptor(desc: String): RegKind = RegKind.of(VType.ofDescriptor(desc))

    private fun rangeOf(op: Opcode): Opcode = when (op) {
        Opcode.INVOKE_VIRTUAL -> Opcode.INVOKE_VIRTUAL_RANGE
        Opcode.INVOKE_SUPER -> Opcode.INVOKE_SUPER_RANGE
        Opcode.INVOKE_DIRECT -> Opcode.INVOKE_DIRECT_RANGE
        Opcode.INVOKE_STATIC -> Opcode.INVOKE_STATIC_RANGE
        Opcode.INVOKE_INTERFACE -> Opcode.INVOKE_INTERFACE_RANGE
        else -> throw IllegalStateException("нет /range-формы у ${op.mnemonic}")
    }

    private companion object {
        const val SYNC_HANDLER = "SYNC"
    }
}
