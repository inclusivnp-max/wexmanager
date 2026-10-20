package com.tim.d2c.translate

import com.tim.casm.CasmState
import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.frames.MethodShape
import com.tim.clazz.model.opcodes.Opcodes
import com.tim.d2c.bridge.ConstructorBridges
import com.tim.d2c.bridge.ProtectedAccessors
import com.tim.d2c.cfg.Insns
import com.tim.d2c.cfg.MethodCode
import com.tim.d2c.model.Descriptors
import com.tim.d2c.model.DexView
import com.tim.d2c.model.MethodRef
import com.tim.d2c.model.Proto
import com.tim.d2c.types.Cat
import com.tim.d2c.types.JType
import com.tim.d2c.types.MethodContext
import com.tim.d2c.types.Nodes
import com.tim.d2c.types.TypeOps
import com.tim.d2c.types.TypeResolver
import com.tim.d2c.types.ValueFlow
import com.tim.d2c.types.Results
import com.tim.dasm.model.*
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.Opcode
import com.tim.dex.model.CodeItem
import com.tim.dex.model.EncodedValue

/**
 * Перевод тела метода DEX в байткод JVM.
 *
 * Регистр → слот JVM ([SlotLayout]); каждая инструкция DEX переводится в
 * «загрузить операнды → операция → сохранить результат» при пустом стеке JVM на границах
 * инструкций. Константы без типа подставляются в месте использования в нужном типе.
 * Диапазоны исключений JVM охватывают только бросающие инструкции DEX (как проверяет ART).
 * Проход выполняется дважды: сначала «вхолостую» (сбор спроса на значения), затем с генерацией.
 */
internal class MethodTranslator(
    private val view: DexView,
    private val state: CasmState,
    private val ops: TypeOps,
    private val debugInfo: Boolean,
    /** Переходники для `<init>` в обход конструкторов (null — не строить). */
    private val bridges: ConstructorBridges? = null,
    /** Методы доступа к protected-членам суперкласса из другого пакета (null — не строить). */
    private val accessors: ProtectedAccessors? = null,
    /** Нестрогий режим: предупреждение вместо исключения (null — строгий). */
    private val lenient: ((String) -> Unit)? = null,
    /** Предупреждения о точных, но заметных преобразованиях (в любом режиме). */
    private val warn: (String) -> Unit = {}
) {
    private val pool get() = state.pool

    /**
     * Интерфейсы, которые нужно добавить классу в прямые суперинтерфейсы (внутренние имена): invoke-super
     * к default-методу интерфейса вне переводимых DEX, который класс реализует через предков.
     */
    val extraInterfaces = LinkedHashSet<String>()

    /** Типы элементов компактного fill-array-data: классу нужны методы [FillHelpers]. */
    val fillHelpers = LinkedHashSet<Char>()

    fun translate(ctx: MethodContext, item: CodeItem): CodeAssembler.Assembled {
        val plain = translate(ctx, item, null)
        if (plain.code.size <= MAX_CODE) return plain
        // JVMS §4.7.3: code_length < 65536. Регистровый код на стеке длиннее; главный источник —
        // fill-array-data (по элементу): данные payload переносятся строкой в пул констант.
        var size = plain.code.size
        if (MethodCode(item) { view.type(it) }.fillData.any { it != null }) {
            val helpers = LinkedHashSet<Char>()
            val compact = translate(ctx, item, helpers)
            if (compact.code.size <= MAX_CODE) {
                fillHelpers.addAll(helpers)
                return compact
            }
            size = compact.code.size
        }
        throw IllegalStateException("код метода в JVM — $size байт, больше предела $MAX_CODE")
    }

    /** [compactFill] — не null: fill-array-data через методы [FillHelpers] (нужные типы — в набор). */
    private fun translate(ctx: MethodContext, item: CodeItem, compactFill: MutableSet<Char>?): CodeAssembler.Assembled {
        val code = MethodCode(item) { view.type(it) }
        val flow = ValueFlow(code, ctx) { view.method(it) }
        flow.run()
        val res = TypeResolver(flow, view, ops)
        res.resolve()
        val layout = SlotLayout(code.registers, code.ins, flow.wideAcrossParams || flow.thisOverwritten)
        val debug = if (debugInfo && item.debugInfoIdx >= 0) {
            DebugInfo.read(view.dex.debugInfoItems[item.debugInfoIdx], code, view)
        } else null

        val needed = neededLabels(code, debug)
        val uses = IntArray(flow.nodes.size)
        Pass(ctx, code, flow, res, layout, null, needed, uses, debug, compactFill).run()
        res.finishDemand()
        val dsl = CodeDsl()
        val pass = Pass(ctx, code, flow, res, layout, dsl, needed, uses, debug, compactFill)
        pass.run()

        val assembled = CodeAssembler.assemble(
            dsl, pool,
            MethodShape(DexView.internalName(ctx.owner), ctx.name, ctx.proto.descriptor, ctx.isStatic),
            pass.tries, pass.lines(debug), pass.locals(debug, ctx),
            // max_locals — весь кадр: отладочные локалы могут ссылаться на регистры, которых нет в коде JVM.
            CodeAssembler.Options(maxLocals = layout.size, frames = state.frameMode, hierarchy = state.hierarchy)
        )
        return assembled
    }

    /**
     * Инструкции, на которые заранее известны ссылки по меткам `aN`: цели переходов и switch,
     * продолжение после switch, обработчики, отладка. Начало try-диапазона помечается позже.
     */
    private fun neededLabels(code: MethodCode, debug: DebugInfo?): BooleanArray {
        val need = BooleanArray(code.count + 1)
        need[0] = true
        for (i in 0 until code.count) {
            if (code.isJumpTarget[i] || code.isHandlerEntry[i]) need[i] = true
            if (code.switchTargets[i] != null) need[i + 1] = true
        }
        if (debug != null) {
            for (l in debug.lines) need[l.index] = true
            for (l in debug.locals) { need[l.start] = true; if (l.end < code.count) need[l.end] = true }
        }
        return need
    }

    /**
     * Тело-заглушка `throw new VerifyError(причина)` (нестрогий режим): почти всегда такой метод отверг бы
     * и верификатор ART — то же исключение, только при вызове, а не при загрузке класса.
     */
    fun unsupported(ctx: MethodContext, reason: String): CodeAssembler.Assembled {
        val dsl = CodeDsl()
        val ex = "java/lang/VerifyError"
        dsl.new(pool.classInfo(ex))
        dsl.dup()
        dsl.ldc(pool.string("DEX → class: $reason"))
        dsl.invokespecial(pool.methodref(ex, "<init>", "(Ljava/lang/String;)V"))
        dsl.athrow()
        return CodeAssembler.assemble(
            dsl, pool,
            MethodShape(DexView.internalName(ctx.owner), ctx.name, ctx.proto.descriptor, ctx.isStatic),
            options = CodeAssembler.Options(frames = state.frameMode, hierarchy = state.hierarchy)
        )
    }

    /** Один проход по коду; [out] = null — холостой проход (только спрос). */
    private inner class Pass(
        private val ctx: MethodContext,
        private val code: MethodCode,
        private val flow: ValueFlow,
        private val res: TypeResolver,
        private val layout: SlotLayout,
        private val out: CodeDsl?,
        private val needed: BooleanArray,
        /** Число загрузок каждого узла инструкциями (считает холостой проход). */
        private val uses: IntArray,
        private val debug: DebugInfo?,
        /** Компактный fill-array-data ([FillHelpers]); не null — нужные типы элементов. */
        private val compactFill: MutableSet<Char>? = null
    ) {
        /**
         * Последняя запись результата в слот: если следующая же загрузка — единственное использование
         * значения, пара store/load снимается и значение остаётся на стеке (код короче и быстрее).
         */
        private var pendingNode = -1
        private var pendingReg = -1
        private var pendingBytes: ByteArray? = null
        private var current = -1
        /** Позиция в CodeDsl перед каждой инструкцией — для меток начала try-диапазона. */
        private val position = IntArray(code.count)
        private val placed = BooleanArray(code.count)

        private val nodes = flow.nodes
        private val dry = out == null
        private var emitted = 0

        val tries = ArrayList<CodeAssembler.TrySpec>()
        private val stubs = ArrayList<Int>()

        // Текущий диапазон исключений: покрытие, первая инструкция, метка конца, отметка позиции.
        private var spanCoverage = -1
        private var spanStart = -1
        private var spanEnd = ""
        private var spanMark = -1

        private inline fun e(block: CodeDsl.() -> Unit) {
            val o = out ?: return
            o.block()
            emitted++
        }

        fun run() {
            prologue()
            for (b in 0 until flow.blockCount) {
                val start = flow.blockStart[b]
                val end = flow.blockEnd(b)
                val entry = flow.entry[b]
                if (entry == null) {
                    for (i in start until end) mark(i)
                    continue
                }
                val st = entry.copyOf()
                for (i in start until end) {
                    mark(i)
                    try {
                        instruction(i, st)
                    } catch (ex: Exception) {
                        throw IllegalStateException("${code.error(i, ex.message ?: ex.toString()).message}", ex)
                    }
                    flow.step(i, st)
                }
            }
            closeSpan()
            label("end")
            for (h in stubs) {
                label("h$h")
                e { pop() }
                e { goto(Labels.a(h)) }
            }
        }

        private fun label(name: String) { out?.label(name) }

        /** Позиция инструкции [i]; метка `aN` — сразу, если на неё есть известные ссылки. */
        private fun mark(i: Int) {
            val o = out ?: return
            position[i] = o.size
            if (needed[i]) { o.label(Labels.a(i)); placed[i] = true }
        }

        /** Метка `aN` задним числом на позицию начала инструкции [i]. */
        private fun ensureLabel(i: Int) {
            val o = out ?: return
            if (placed[i]) return
            o.labelAt(Labels.a(i), position[i])
            placed[i] = true
        }

        // ---- Пролог и отладка ----

        /** Схема с копированием: параметры из слотов 0..ins-1 переносятся в слоты своих регистров. */
        private fun prologue() {
            if (dry) return
            var reg = code.registers - code.ins
            var slot = 0
            if (!ctx.isStatic) {
                if (layout.copyParams) e { aload(0); astore(layout.slot(reg)) }
                reg++; slot++
            }
            for (p in ctx.proto.params) {
                val c = Cat.of(p)
                // Параметр float/double в паутине, хранящей биты: слот сразу переводится в int/long.
                val node = nodes.def(Nodes.Kind.PARAM, -1, reg)
                val bits = res.isDemanded(node) && res.isBits(node) && res.cat(node) != c
                if (layout.copyParams || bits) {
                    e { JvmOps.load(this, c, slot) }
                    val stored = if (bits && toBits(c, res.cat(node))) res.cat(node) else c
                    e { JvmOps.store(this, stored, layout.slot(reg)) }
                }
                val w = if (c.isWide) 2 else 1
                reg += w; slot += w
            }
        }

        /** Номера строк JVM — u2: строки вне 0..65535 в class-файле невыразимы и не переносятся. */
        fun lines(debug: DebugInfo?): List<Pair<String, Int>> =
            debug?.lines?.filter { it.line in 0..0xFFFF }?.map { Labels.a(it.index) to it.line } ?: emptyList()

        fun locals(debug: DebugInfo?, ctx: MethodContext): List<CodeAssembler.LocalVarSpec> {
            if (debug == null) return emptyList()
            val out = ArrayList<CodeAssembler.LocalVarSpec>()
            var reg = code.registers - code.ins
            if (!ctx.isStatic) {
                out.add(CodeAssembler.LocalVarSpec("a0", "end", "this", ctx.owner, layout.slot(reg)))
                reg++
            }
            for ((k, p) in ctx.proto.params.withIndex()) {
                val name = debug.parameterNames.getOrNull(k)
                if (name != null) out.add(CodeAssembler.LocalVarSpec("a0", "end", name, p, layout.slot(reg)))
                reg += if (DexView.isWide(p)) 2 else 1
            }
            for (l in debug.locals) {
                if (!validLocal(l.reg, l.name, l.type)) continue
                val end = if (l.end >= code.count) "end" else Labels.a(l.end)
                out.add(CodeAssembler.LocalVarSpec(Labels.a(l.start), end, l.name, l.type, layout.slot(l.reg), l.signature))
            }
            // HotSpot отвергает повторы (начало, конец, имя, слот): this/параметры могут быть и в debug_info.
            return out.distinctBy { listOf(it.startLabel, it.endLabel, it.name, it.index) }
        }

        /**
         * Отладочный локал выразим в LocalVariableTable: ART debug_info не проверяет, а HotSpot отвергает
         * весь класс (ClassFormatError) при слоте вне кадра, long/double на двух несмежных слотах
         * (пара через границу параметров) и недопустимых имени или дескрипторе.
         */
        private fun validLocal(reg: Int, name: String, type: String): Boolean {
            if (reg < 0 || reg >= code.registers) return false
            if (DexView.isWide(type) && (reg + 1 >= code.registers || layout.slot(reg + 1) != layout.slot(reg) + 1)) return false
            if (name.isEmpty() || name.any { it == '.' || it == ';' || it == '[' || it == '/' }) return false
            return Descriptors.isFieldType(type)
        }

        // ---- Диапазоны исключений ----

        private fun beforeThrowing(i: Int) {
            val cov = code.coverage[i]
            if (cov == spanCoverage && emitted == spanMark) return
            closeSpan()
            spanCoverage = cov
            spanStart = i
            ensureLabel(i)
        }

        private fun afterThrowing(i: Int) {
            spanEnd = "e$i"
            label(spanEnd)
            spanMark = emitted
        }

        private fun closeSpan() {
            if (spanCoverage < 0 || dry) { spanCoverage = -1; return }
            for (h in code.handlers[spanCoverage]) {
                if (h.catchType != null && missingCatchType(h.catchType)) continue
                val handler = if (isInlineHandler(h.target)) Labels.a(h.target) else "h${h.target}"
                tries.add(CodeAssembler.TrySpec(Labels.a(spanStart), spanEnd, handler, h.catchType?.let { DexView.internalName(it) }))
            }
            spanCoverage = -1
        }

        private val missingTypes = HashMap<String, Boolean>()

        /**
         * Тип обработчика точно не существует: класс из пакета JDK, которого нет в JDK (иерархия JDK
         * доступна). ART такой обработчик пропускает, а HotSpot при проверке класса бросил бы
         * NoClassDefFoundError — обработчик не переносится.
         */
        private fun missingCatchType(type: String): Boolean = missingTypes.getOrPut(type) {
            val name = DexView.internalName(type)
            val h = state.hierarchy
            val missing = name.startsWith("java/") && h.isInterface(name) == null && h.isInterface("java/lang/Object") != null
            if (missing) warn("$ctx: тип обработчика $type не существует — обработчик пропущен (как в ART)")
            missing
        }

        /**
         * Обработчик можно начать прямо в коде: обычным потоком он не достижим. Инструкция 0 —
         * вход в метод (стек пуст), поэтому обработчик на ней всегда идёт через переходник `hN`.
         */
        private fun isInlineHandler(h: Int): Boolean =
            h != 0 && !code.isJumpTarget[h] && (!flow.isReachable(h - 1) || code.isPayload(h - 1) || !code.fallsThrough(h - 1))

        // ---- Операнды ----

        private fun slot(reg: Int) = layout.slot(reg)

        /** Загрузка регистра в категории [cat]; для ссылок при необходимости — checkcast к [need]. */
        private fun ld(st: IntArray, reg: Int, cat: Cat, need: String? = null) {
            val node = st[reg]
            if (node == Nodes.UNDEF || node == Nodes.HIGH) throw IllegalStateException("регистр v$reg не определён")
            if (cat.isWide && (reg + 1 >= st.size || st[reg + 1] != Nodes.HIGH)) {
                throw IllegalStateException("регистр v$reg не содержит пару long/double")
            }
            if (res.isConst(node)) {
                if (!dry) pushConst(res.constBits(node), res.constWide(node), cat)
                return
            }
            if (dry) { res.demand(node, cat); uses[node]++; return }
            val c = res.cat(node)
            if (c != cat) {
                // Слияние констант, используемое и как целое, и как вещественное, хранит биты (см. TypeResolver).
                if (res.isBits(node) && c == Cat.I && cat == Cat.F) {
                    e { JvmOps.load(this, Cat.I, slot(reg)); invokestatic(pool.methodref("java/lang/Float", "intBitsToFloat", "(I)F")) }
                    return
                }
                if (res.isBits(node) && c == Cat.J && cat == Cat.D) {
                    e { JvmOps.load(this, Cat.J, slot(reg)); invokestatic(pool.methodref("java/lang/Double", "longBitsToDouble", "(J)D")) }
                    return
                }
                throw IllegalStateException("регистр v$reg: ожидалось ${cat.name}, а значение ${c.name} (${res.type(node)})")
            }
            if (!elideLoad(node, reg)) e { JvmOps.load(this, cat, slot(reg)) }
            if (cat == Cat.A && need != null) checkcast(res.type(node), need)
        }

        /** Загрузка без заданной категории (if-eqz и т. п.); возвращает выбранную категорию. */
        private fun ldAny(st: IntArray, reg: Int): Cat {
            val node = st[reg]
            if (node == Nodes.UNDEF || node == Nodes.HIGH) throw IllegalStateException("регистр v$reg не определён")
            if (res.isConst(node)) {
                if (!dry) pushConst(res.constBits(node), res.constWide(node), Cat.I)
                return Cat.I
            }
            if (dry) { res.demand(node, null); uses[node]++; return Cat.NONE }
            val c = res.cat(node)
            if (!elideLoad(node, reg)) e { JvmOps.load(this, c, slot(reg)) }
            return c
        }

        private fun checkcast(from: JType, need: String) {
            if (from is JType.Uninit || from === JType.UninitThis) return
            if (ops.assignable(from, need)) return
            e { checkcast(pool.classInfo(DexView.internalName(need))) }
        }

        /**
         * Сохранение результата инструкции [i] (на стеке JVM — значение категории [stackCat]) в регистр [reg].
         * Если значение кому-то нужно, его категория обязана совпадать с выведенной.
         */
        private fun storeDef(i: Int, reg: Int, stackCat: Cat) {
            if (dry) return
            val node = nodes.def(Nodes.Kind.TYPED, i, reg)
            rememberStore(node, reg, if (res.isDemanded(node)) res.cat(node) else stackCat)
            if (res.isDemanded(node) && res.cat(node) != stackCat) {
                val c = res.cat(node)
                // Слот хранит биты (см. TypeResolver.isBits): вещественное пишется своим битовым образом.
                if (res.isBits(node) && toBits(stackCat, c)) {
                    e { JvmOps.store(this, c, slot(reg)) }
                    return
                }
                throw IllegalStateException("результат в v$reg: на стеке ${stackCat.name}, а нужен ${res.cat(node).name}")
            }
            e { JvmOps.store(this, stackCat, slot(reg)) }
        }

        private fun rememberStore(node: Int, reg: Int, cat: Cat) {
            pendingNode = node
            pendingReg = reg
            pendingBytes = CodeDsl().also { JvmOps.store(it, cat, slot(reg)) }.singleRawBytes()
        }

        /** Значение [node] в [reg] только что записано и больше нигде не читается: снять запись, не загружать. */
        private fun elideLoad(node: Int, reg: Int): Boolean {
            val bytes = pendingBytes ?: return false
            if (pendingNode != node || pendingReg != reg) return false
            pendingBytes = null
            if (uses[node] != 1 || res.hasSlotUsers(node) || debugCovers(reg, current)) return false
            return out!!.dropLastIf(bytes)
        }

        /** Отладочный локал на регистре [reg] в инструкции [i] (снятая запись исказила бы его значение). */
        private fun debugCovers(reg: Int, i: Int): Boolean =
            debug != null && debug.locals.any { (it.reg == reg || it.reg + 1 == reg) && i >= it.start && i <= it.end }

        /** float → int / double → long тем же битовым образом (на стеке); false — не тот случай. */
        private fun toBits(from: Cat, to: Cat): Boolean {
            when {
                from == Cat.F && to == Cat.I -> e { invokestatic(pool.methodref("java/lang/Float", "floatToRawIntBits", "(F)I")) }
                from == Cat.D && to == Cat.J -> e { invokestatic(pool.methodref("java/lang/Double", "doubleToRawLongBits", "(D)J")) }
                else -> return false
            }
            return true
        }

        private fun pushConst(bits: Long, wide: Boolean, cat: Cat) {
            if (cat.isWide != wide) throw IllegalStateException("константа ${java.lang.Long.toHexString(bits)} не той ширины для ${cat.name}")
            when (cat) {
                Cat.I -> {
                    val v = bits.toInt()
                    if (v in Short.MIN_VALUE..Short.MAX_VALUE) e { pushInt(v) } else e { ldc(pool.integer(v)) }
                }
                Cat.F -> when (val b = bits.toInt()) {
                    0 -> e { fconst(0f) }
                    0x3f800000 -> e { fconst(1f) }
                    0x40000000 -> e { fconst(2f) }
                    else -> e { ldc(pool.float(b)) }
                }
                Cat.J -> when (bits) {
                    0L -> e { lconst(0) }
                    1L -> e { lconst(1) }
                    else -> e { ldc2W(pool.long(bits)) }
                }
                Cat.D -> when (bits) {
                    0L -> e { dconst(0.0) }
                    0x3ff0000000000000L -> e { dconst(1.0) }
                    else -> e { ldc2W(pool.double(bits)) }
                }
                Cat.A -> {
                    if (bits != 0L) throw IllegalStateException("ненулевая константа 0x${java.lang.Long.toHexString(bits)} используется как ссылка")
                    e { aconstNull() }
                }
                else -> throw IllegalStateException("константа в категории $cat")
            }
        }

        /** Константа: запись в слот, только если её значение нужно φ (иначе подставляется при использовании). */
        private fun materializeConst(i: Int, reg: Int, st: IntArray, wide: Boolean) {
            if (dry) return
            val node = nodes.def(Nodes.Kind.CONST, i, reg)
            val c = res.storeCat(node)
            if (c == Cat.NONE) return releaseRefs(st, reg, wide)
            pushConst(res.constBits(node), res.constWide(node), c)
            e { JvmOps.store(this, c, slot(reg)) }
        }

        /**
         * Значение [reg] (и [reg]+1 у широких) заменяется, но в слот JVM ничего не пишется: если там была
         * ссылка, она очищается. В ART перезаписанный регистр — не корень GC (объект может быть собран,
         * WeakReference очищена), а слот JVM держал бы объект до конца метода в интерпретаторе.
         */
        private fun releaseRefs(st: IntArray, reg: Int, wide: Boolean) {
            for (r in if (wide) intArrayOf(reg, reg + 1) else intArrayOf(reg)) {
                if (r >= st.size) continue
                val prev = st[r]
                if (prev == Nodes.UNDEF || prev == Nodes.HIGH || res.isConst(prev)) continue
                val inSlot = when (nodes.kind(prev)) {
                    Nodes.Kind.TYPED, Nodes.Kind.PARAM, Nodes.Kind.INIT -> true
                    else -> res.isDemanded(prev)
                }
                val t = res.type(prev)
                if (inSlot && (t is JType.Ref || t === JType.Null || t is JType.Uninit || t === JType.UninitThis)) {
                    e { aconstNull(); astore(slot(r)) }
                }
            }
        }

        /** Код, который ART считает недостижимым, а JVM проверяет: throw VerifyError с причиной. */
        private fun unreachable(reason: String) {
            e {
                new(pool.classInfo("java/lang/VerifyError"))
                dup()
                ldc(pool.string("DEX → class: недостижимый в ART код: $reason"))
                invokespecial(pool.methodref("java/lang/VerifyError", "<init>", "(Ljava/lang/String;)V"))
                athrow()
            }
        }

        /** move vA, vB: копия слота, только если значение vA кому-то нужно. */
        private fun materializeMove(i: Int, a: Int, b: Int, st: IntArray, wide: Boolean) {
            if (dry) return
            val node = nodes.def(Nodes.Kind.MOVE, i, a)
            if (res.isConst(node)) {
                val c = res.storeCat(node)
                if (c == Cat.NONE) return releaseRefs(st, a, wide)
                pushConst(res.constBits(node), res.constWide(node), c)
                e { JvmOps.store(this, c, slot(a)) }
            } else if (res.isDemanded(node)) {
                val c = res.cat(node)
                e { JvmOps.load(this, c, slot(b)); JvmOps.store(this, c, slot(a)) }
            } else {
                releaseRefs(st, a, wide)
            }
        }

        // ---- Инструкции ----

        private fun instruction(i: Int, st: IntArray) {
            current = i
            val insn = code.insns[i]
            val op = insn.opcode
            val throwing = Insns.canThrow(op) && code.coverage[i] >= 0
            if (code.isHandlerEntry[i]) handlerEntry(i, insn)
            if (throwing) beforeThrowing(i)
            translate(i, insn, st)
            if (throwing) afterThrowing(i)
        }

        private fun handlerEntry(i: Int, insn: Instruction) {
            if (isInlineHandler(i)) {
                if (insn.opcode != Opcode.MOVE_EXCEPTION) e { pop() }
            } else {
                if (insn.opcode == Opcode.MOVE_EXCEPTION) throw IllegalStateException("move-exception достижим обычным потоком управления")
                if (!dry) stubs.add(i)
            }
        }

        private fun translate(i: Int, insn: Instruction, st: IntArray) {
            val op = insn.opcode
            val v = op.value
            when {
                op == Opcode.NOP -> {}
                v in Opcode.MOVE.value..Opcode.MOVE_OBJECT_16.value -> {
                    val (a, b) = ValueFlow.twoRegs(insn)
                    materializeMove(i, a, b, st, wide = v in Opcode.MOVE_WIDE.value..Opcode.MOVE_WIDE_16.value)
                }
                Insns.isMoveResult(op) -> {} // сохраняется вместе с invoke / filled-new-array
                op == Opcode.MOVE_EXCEPTION -> storeDef(i, (insn as Ins11x).regA, Cat.A)
                op == Opcode.RETURN_VOID || op == Opcode.RETURN || op == Opcode.RETURN_WIDE || op == Opcode.RETURN_OBJECT -> {
                    val ret = ctx.proto.returnType
                    val fits = when (op) {
                        Opcode.RETURN_VOID -> ret == "V"
                        Opcode.RETURN_WIDE -> ret == "J" || ret == "D"
                        Opcode.RETURN_OBJECT -> ret[0] == 'L' || ret[0] == '['
                        else -> ret != "V" && ret != "J" && ret != "D" && ret[0] != 'L' && ret[0] != '['
                    }
                    when {
                        // Возврат не того вида ART допускает только в недостижимом коде (после вызова, который
                        // всегда бросает: неразрешимый метод и т. п.) — в JVM его место занимает throw.
                        !fits -> unreachable("${op.mnemonic} в методе, возвращающем $ret")
                        op == Opcode.RETURN_VOID -> e { returnVoid() }
                        else -> {
                            val c = Cat.of(ret)
                            ld(st, (insn as Ins11x).regA, c, if (c == Cat.A) ret else null)
                            e { JvmOps.returnOf(this, c) }
                        }
                    }
                }
                v in Opcode.CONST_4.value..Opcode.CONST_WIDE_HIGH16.value ->
                    materializeConst(i, constReg(insn), st, wide = v >= Opcode.CONST_WIDE_16.value)
                op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO -> {
                    val (reg, idx) = if (insn is Ins21c) insn.regA to insn.index else (insn as Ins31c).regA to insn.index
                    e { ldc(pool.string(view.string(idx))) }
                    storeDef(i, reg, Cat.A)
                }
                op == Opcode.CONST_CLASS -> {
                    insn as Ins21c
                    e { ldc(pool.classInfo(DexView.internalName(view.type(insn.index)))) }
                    storeDef(i, insn.regA, Cat.A)
                }
                op == Opcode.CONST_METHOD_HANDLE -> {
                    insn as Ins21c
                    e { ldc(methodHandle(insn.index)) }
                    storeDef(i, insn.regA, Cat.A)
                }
                op == Opcode.CONST_METHOD_TYPE -> {
                    insn as Ins21c
                    e { ldc(methodType(insn.index)) }
                    storeDef(i, insn.regA, Cat.A)
                }
                op == Opcode.MONITOR_ENTER || op == Opcode.MONITOR_EXIT -> {
                    ld(st, (insn as Ins11x).regA, Cat.A)
                    if (op == Opcode.MONITOR_ENTER) e { monitorenter() } else e { monitorexit() }
                }
                op == Opcode.CHECK_CAST -> {
                    insn as Ins21c
                    ld(st, insn.regA, Cat.A)
                    e { checkcast(pool.classInfo(DexView.internalName(view.type(insn.index)))) }
                    storeDef(i, insn.regA, Cat.A)
                }
                op == Opcode.INSTANCE_OF -> {
                    insn as Ins22c
                    ld(st, insn.regB, Cat.A)
                    e { instanceof(pool.classInfo(DexView.internalName(view.type(insn.index)))) }
                    storeDef(i, insn.regA, Cat.I)
                }
                op == Opcode.ARRAY_LENGTH -> {
                    insn as Ins12x
                    ld(st, insn.regB, Cat.A)
                    e { arraylength() }
                    storeDef(i, insn.regA, Cat.I)
                }
                op == Opcode.NEW_INSTANCE -> {
                    insn as Ins21c
                    e { new(pool.classInfo(DexView.internalName(view.type(insn.index)))) }
                    storeDef(i, insn.regA, Cat.A)
                }
                op == Opcode.NEW_ARRAY -> {
                    insn as Ins22c
                    ld(st, insn.regB, Cat.I)
                    newArray(view.type(insn.index))
                    storeDef(i, insn.regA, Cat.A)
                }
                op == Opcode.FILLED_NEW_ARRAY || op == Opcode.FILLED_NEW_ARRAY_RANGE -> filledNewArray(i, insn, st)
                op == Opcode.FILL_ARRAY_DATA -> fillArrayData(i, insn as Ins31t, st)
                op == Opcode.THROW -> {
                    ld(st, (insn as Ins11x).regA, Cat.A, "Ljava/lang/Throwable;")
                    e { athrow() }
                }
                Insns.isGoto(op) -> e { goto(Labels.a(code.jumpTarget[i])) }
                Insns.isSwitch(op) -> switch(i, insn as Ins31t, st)
                v in Opcode.CMPL_FLOAT.value..Opcode.CMP_LONG.value -> {
                    insn as Ins23x
                    val c = when (op) { Opcode.CMPL_FLOAT, Opcode.CMPG_FLOAT -> Cat.F; Opcode.CMP_LONG -> Cat.J; else -> Cat.D }
                    ld(st, insn.regB, c); ld(st, insn.regC, c)
                    when (op) {
                        Opcode.CMPL_FLOAT -> e { fcmpl() }
                        Opcode.CMPG_FLOAT -> e { fcmpg() }
                        Opcode.CMPL_DOUBLE -> e { dcmpl() }
                        Opcode.CMPG_DOUBLE -> e { dcmpg() }
                        else -> e { lcmp() }
                    }
                    storeDef(i, insn.regA, Cat.I)
                }
                Insns.isIf(op) -> ifInsn(i, insn, st)
                v in Opcode.AGET.value..Opcode.AGET_SHORT.value -> arrayGet(i, insn as Ins23x, st)
                v in Opcode.APUT.value..Opcode.APUT_SHORT.value -> arrayPut(insn as Ins23x, st)
                v in Opcode.IGET.value..Opcode.IGET_SHORT.value -> {
                    insn as Ins22c
                    val f = view.field(insn.index)
                    val access = protectedAccess(st, insn.regB, f.owner, ProtectedAccessors.Kind.GET, f.name, f.type)
                    if (access != null) {
                        ld(st, insn.regB, Cat.A, access.owner)
                        e { invokestatic(pool.methodref(DexView.internalName(access.owner), access.name, access.descriptor)) }
                    } else {
                        ld(st, insn.regB, Cat.A, receiverNeed(st, insn.regB, f.owner))
                        e { getfield(pool.fieldref(DexView.internalName(f.owner), f.name, f.type)) }
                    }
                    storeDef(i, insn.regA, Cat.of(f.type))
                }
                v in Opcode.IPUT.value..Opcode.IPUT_SHORT.value -> {
                    insn as Ins22c
                    val f = view.field(insn.index)
                    val c = Cat.of(f.type)
                    val access = protectedAccess(st, insn.regB, f.owner, ProtectedAccessors.Kind.PUT, f.name, f.type)
                    ld(st, insn.regB, Cat.A, access?.owner ?: receiverNeed(st, insn.regB, f.owner))
                    ld(st, insn.regA, c, if (c == Cat.A) f.type else null)
                    if (access != null) e { invokestatic(pool.methodref(DexView.internalName(access.owner), access.name, access.descriptor)) }
                    else e { putfield(pool.fieldref(DexView.internalName(f.owner), f.name, f.type)) }
                }
                v in Opcode.SGET.value..Opcode.SGET_SHORT.value -> {
                    insn as Ins21c
                    val f = view.field(insn.index)
                    e { getstatic(pool.fieldref(DexView.internalName(f.owner), f.name, f.type)) }
                    storeDef(i, insn.regA, Cat.of(f.type))
                }
                v in Opcode.SPUT.value..Opcode.SPUT_SHORT.value -> {
                    insn as Ins21c
                    val f = view.field(insn.index)
                    val c = Cat.of(f.type)
                    ld(st, insn.regA, c, if (c == Cat.A) f.type else null)
                    e { putstatic(pool.fieldref(DexView.internalName(f.owner), f.name, f.type)) }
                }
                Insns.isInvoke(op) -> invoke(i, insn, st)
                v in Opcode.NEG_INT.value..Opcode.INT_TO_SHORT.value -> unary(i, insn as Ins12x, st)
                v in Opcode.ADD_INT.value..Opcode.REM_DOUBLE.value -> {
                    insn as Ins23x
                    binary(i, op, insn.regA, insn.regB, insn.regC, st)
                }
                v in Opcode.ADD_INT_2ADDR.value..Opcode.REM_DOUBLE_2ADDR.value -> {
                    insn as Ins12x
                    binary(i, op, insn.regA, insn.regA, insn.regB, st)
                }
                v in Opcode.ADD_INT_LIT16.value..Opcode.XOR_INT_LIT16.value -> {
                    insn as Ins22s
                    literal(i, v - Opcode.ADD_INT_LIT16.value, insn.regA, insn.regB, insn.value, st)
                }
                v in Opcode.ADD_INT_LIT8.value..Opcode.USHR_INT_LIT8.value -> {
                    insn as Ins22b
                    literal(i, v - Opcode.ADD_INT_LIT8.value, insn.regA, insn.regB, insn.value, st)
                }
                code.isPayload(i) -> throw IllegalStateException("управление попадает в payload")
                else -> throw IllegalStateException("неподдерживаемая инструкция")
            }
        }

        private fun constReg(insn: Instruction): Int = when (insn) {
            is Ins11n -> insn.regA
            is Ins21s -> insn.regA
            is Ins31i -> insn.regA
            is Ins21h -> insn.regA
            is Ins51l -> insn.regA
            else -> throw IllegalStateException("неожиданный формат константы ${insn.format}")
        }

        /** Требуемый тип получателя поля: неинициализированный this проверять нельзя. */
        private fun receiverNeed(st: IntArray, reg: Int, owner: String): String? {
            val node = st[reg]
            if (dry || res.isConst(node)) return owner
            val t = res.type(node)
            return if (t === JType.UninitThis || t is JType.Uninit) null else owner
        }

        private fun newArray(type: String) {
            val element = type.substring(1)
            if (element[0] == 'L' || element[0] == '[') e { anewarray(pool.classInfo(DexView.internalName(element))) }
            else e { newarray(JvmOps.primitiveArrayType(element[0])) }
        }

        private fun ifInsn(i: Int, insn: Instruction, st: IntArray) {
            val op = insn.opcode
            val target = Labels.a(code.jumpTarget[i])
            if (insn is Ins21t) {
                if (op == Opcode.IF_EQZ || op == Opcode.IF_NEZ) {
                    val c = ldBits(st, insn.regA)
                    val jvm = if (c == Cat.A) (if (op == Opcode.IF_EQZ) Opcodes.IFNULL else Opcodes.IFNONNULL)
                    else Opcodes.IFEQ + (op.value - Opcode.IF_EQZ.value)
                    e { jump(jvm, target) }
                } else {
                    ldBits(st, insn.regA, reference = false)
                    e { jump(Opcodes.IFEQ + (op.value - Opcode.IF_EQZ.value), target) }
                }
                return
            }
            insn as Ins22t
            if (op == Opcode.IF_EQ || op == Opcode.IF_NE) {
                val c = comparisonCat(st, insn.regA, insn.regB)
                if (c == null) { ldAny(st, insn.regA); ldAny(st, insn.regB) }
                else if (c == Cat.A) { ld(st, insn.regA, c); ld(st, insn.regB, c) }
                else { ldBits(st, insn.regA, reference = false); ldBits(st, insn.regB, reference = false) }
                val jvm = if (c == Cat.A) (if (op == Opcode.IF_EQ) Opcodes.IF_ACMPEQ else Opcodes.IF_ACMPNE)
                else Opcodes.IF_ICMPEQ + (op.value - Opcode.IF_EQ.value)
                e { jump(jvm, target) }
            } else {
                ldBits(st, insn.regA, reference = false); ldBits(st, insn.regB, reference = false)
                e { jump(Opcodes.IF_ICMPEQ + (op.value - Opcode.IF_EQ.value), target) }
            }
        }

        /**
         * Операнд if-*: 32-битное содержимое регистра. Слияние одних констант может получить категорию F
         * (его используют и как float), а if-* в DEX сравнивает биты — тогда `Float.floatToRawIntBits`.
         * Возвращает категорию на стеке (I или, если [reference], A; NONE — холостой проход).
         */
        private fun ldBits(st: IntArray, reg: Int, reference: Boolean = true): Cat {
            val c = ldAny(st, reg)
            return when (c) {
                Cat.NONE, Cat.I -> c
                Cat.A -> if (reference) c else throw IllegalStateException("регистр v$reg: ожидалось I, а значение A")
                Cat.F -> {
                    e { invokestatic(pool.methodref("java/lang/Float", "floatToRawIntBits", "(F)I")) }
                    Cat.I
                }
                else -> throw IllegalStateException("регистр v$reg: ожидалось 32-битное значение, а значение ${c.name}")
            }
        }

        /** Категория сравнения if-eq/if-ne: по первому неконстантному операнду (null — холостой проход). */
        private fun comparisonCat(st: IntArray, a: Int, b: Int): Cat? {
            for (reg in intArrayOf(a, b)) {
                val node = st[reg]
                if (node == Nodes.UNDEF || node == Nodes.HIGH) throw IllegalStateException("регистр v$reg не определён")
                if (res.isConst(node)) continue
                if (dry) return null
                return res.cat(node)
            }
            return Cat.I
        }

        private fun switch(i: Int, insn: Ins31t, st: IntArray) {
            ld(st, insn.regA, Cat.I)
            if (i + 1 >= code.count) throw IllegalStateException("switch в конце кода")
            val keys = code.switchKeys[i]!!
            val targets = code.switchTargets[i]!!.map { Labels.a(it) }
            val default = Labels.a(i + 1)
            val ascending = (1 until keys.size).all { keys[it - 1] < keys[it] }
            if (!ascending) throw IllegalStateException("ключи switch не упорядочены по возрастанию")
            val packed = insn.opcode == Opcode.PACKED_SWITCH && keys.isNotEmpty()
            if (packed) e { tableswitch(keys[0], keys[keys.size - 1], default, targets) }
            else e { lookupswitch(keys, default, targets) }
        }

        private fun arrayGet(i: Int, insn: Ins23x, st: IntArray) {
            val op = insn.opcode
            val arrayType = arrayTypeAt(st, insn.regB)
            // Массив null: aget только бросит NPE, тип результата (int/float, long/double) — по использованию.
            val used = if (dry || arrayType != null) null
            else nodes.def(Nodes.Kind.TYPED, i, insn.regA).let { if (res.isDemanded(it)) res.cat(it) else null }
            val (element, need) = when (op) {
                Opcode.AGET -> if (arrayType == "[F" || used == Cat.F) 'F' to "[F" else 'I' to "[I"
                Opcode.AGET_WIDE -> if (arrayType == "[D" || used == Cat.D) 'D' to "[D" else 'J' to "[J"
                Opcode.AGET_OBJECT -> 'L' to (arrayType?.takeIf { it.length > 1 && (it[1] == 'L' || it[1] == '[') } ?: "[Ljava/lang/Object;")
                Opcode.AGET_BOOLEAN -> 'Z' to "[Z"
                Opcode.AGET_BYTE -> 'B' to "[B"
                Opcode.AGET_CHAR -> 'C' to "[C"
                else -> 'S' to "[S"
            }
            ld(st, insn.regB, Cat.A, need)
            ld(st, insn.regC, Cat.I)
            e { JvmOps.arrayLoad(this, element) }
            storeDef(i, insn.regA, when (element) { 'L' -> Cat.A; 'J' -> Cat.J; 'D' -> Cat.D; 'F' -> Cat.F; else -> Cat.I })
        }

        private fun arrayPut(insn: Ins23x, st: IntArray) {
            val op = insn.opcode
            val arrayType = arrayTypeAt(st, insn.regB)
            val (element, need) = when (op) {
                Opcode.APUT -> if (arrayType == "[F") 'F' to "[F" else 'I' to "[I"
                Opcode.APUT_WIDE -> if (arrayType == "[D") 'D' to "[D" else 'J' to "[J"
                Opcode.APUT_OBJECT -> 'L' to (arrayType?.takeIf { it.length > 1 && (it[1] == 'L' || it[1] == '[') } ?: "[Ljava/lang/Object;")
                Opcode.APUT_BOOLEAN -> 'Z' to "[Z"
                Opcode.APUT_BYTE -> 'B' to "[B"
                Opcode.APUT_CHAR -> 'C' to "[C"
                else -> 'S' to "[S"
            }
            ld(st, insn.regB, Cat.A, need)
            ld(st, insn.regC, Cat.I)
            ld(st, insn.regA, when (element) { 'L' -> Cat.A; 'J' -> Cat.J; 'D' -> Cat.D; 'F' -> Cat.F; else -> Cat.I })
            e { JvmOps.arrayStore(this, element) }
        }

        /** Дескриптор массива в регистре (null — неизвестен, константа null или холостой проход). */
        private fun arrayTypeAt(st: IntArray, reg: Int): String? {
            val node = st[reg]
            if (node == Nodes.UNDEF || node == Nodes.HIGH) return null
            return (res.arrayTypeOf(node) as? JType.Ref)?.descriptor
        }

        private fun filledNewArray(i: Int, insn: Instruction, st: IntArray) {
            val type = view.type(ValueFlow.indexOf(insn))
            val regs = ValueFlow.argRegs(insn)
            val element = type.substring(1)
            val c = Cat.of(element)
            if (c.isWide) throw IllegalStateException("filled-new-array с элементами long/double")
            e { pushInt(regs.size) }
            newArray(type)
            for ((k, reg) in regs.withIndex()) {
                e { dup(); pushLiteral(k) }
                ld(st, reg, c)
                e { JvmOps.arrayStore(this, element[0]) }
            }
            result(i, Cat.A)
        }

        private fun fillArrayData(i: Int, insn: Ins31t, st: IntArray) {
            val data = code.fillData[i]!!
            val arrayType = arrayTypeAt(st, insn.regA)
            val element = when (data.elementSize) {
                1 -> if (arrayType == "[Z") 'Z' else 'B'
                2 -> if (arrayType == "[C") 'C' else 'S'
                4 -> if (arrayType == "[F") 'F' else 'I'
                8 -> if (arrayType == "[D") 'D' else 'J'
                else -> throw IllegalStateException("ширина элемента ${data.elementSize}")
            }
            val need = "[$element"
            val bytes = data.data
            val w = data.elementSize
            // Как в ART: null → NPE, массив короче данных → AIOOBE, и в обоих случаях до первой записи.
            ld(st, insn.regA, Cat.A, need)
            if (data.size == 0) {
                e { arraylength(); pop() }
            } else {
                e { pushLiteral(data.size - 1) }
                e { JvmOps.arrayLoad(this, element); JvmOps.pop(this, Cat.of(element.toString())) }
            }
            if (compactFill != null && data.size > COMPACT_FILL_MIN) {
                compactFill.add(element)
                val owner = DexView.internalName(ctx.owner)
                val isInterface = ops.isInterface(ctx.owner)
                if (isInterface) state.requireVersion(52)
                for ((first, chunk) in FillHelpers.chunks(bytes, w, data.size, element)) {
                    ld(st, insn.regA, Cat.A, need)
                    e { pushLiteral(first) }
                    e { ldc(pool.string(chunk)) }
                    e {
                        invokestatic(
                            if (isInterface) pool.interfaceMethodref(owner, FillHelpers.name(element), FillHelpers.descriptor(element))
                            else pool.methodref(owner, FillHelpers.name(element), FillHelpers.descriptor(element))
                        )
                    }
                }
                return
            }
            for (k in 0 until data.size) {
                var raw = 0L
                for (b in 0 until w) raw = raw or ((bytes[k * w + b].toLong() and 0xFF) shl (8 * b))
                ld(st, insn.regA, Cat.A, need)
                e { pushLiteral(k) }
                if (!dry) when (element) {
                    'Z', 'B' -> pushConst(raw.toByte().toLong(), false, Cat.I)
                    'S' -> pushConst(raw.toShort().toLong(), false, Cat.I)
                    'C' -> pushConst(raw, false, Cat.I)
                    'I' -> pushConst(raw.toInt().toLong(), false, Cat.I)
                    'F' -> pushConst(raw, false, Cat.F)
                    'J' -> pushConst(raw, true, Cat.J)
                    else -> pushConst(raw, true, Cat.D)
                }
                e { JvmOps.arrayStore(this, element) }
            }
        }

        /** Результат invoke/filled-new-array: в приёмник следующего move-result или со стека. */
        private fun result(i: Int, cat: Cat) {
            val next = i + 1
            if (next < code.count && Insns.isMoveResult(code.insns[next].opcode)) {
                storeDef(next, (code.insns[next] as Ins11x).regA, cat)
            } else {
                e { JvmOps.pop(this, cat) }
            }
        }

        private fun unary(i: Int, insn: Ins12x, st: IntArray) {
            val op = insn.opcode
            ld(st, insn.regB, Results.unarySourceCat(op))
            when (op) {
                Opcode.NEG_INT -> e { ineg() }
                Opcode.NOT_INT -> e { iconst(-1); ixor() }
                Opcode.NEG_LONG -> e { lneg() }
                Opcode.NOT_LONG -> e { ldc2W(pool.long(-1L)); lxor() }
                Opcode.NEG_FLOAT -> e { fneg() }
                Opcode.NEG_DOUBLE -> e { dneg() }
                Opcode.INT_TO_LONG -> e { i2l() }
                Opcode.INT_TO_FLOAT -> e { i2f() }
                Opcode.INT_TO_DOUBLE -> e { i2d() }
                Opcode.LONG_TO_INT -> e { l2i() }
                Opcode.LONG_TO_FLOAT -> e { l2f() }
                Opcode.LONG_TO_DOUBLE -> e { l2d() }
                Opcode.FLOAT_TO_INT -> e { f2i() }
                Opcode.FLOAT_TO_LONG -> e { f2l() }
                Opcode.FLOAT_TO_DOUBLE -> e { f2d() }
                Opcode.DOUBLE_TO_INT -> e { d2i() }
                Opcode.DOUBLE_TO_LONG -> e { d2l() }
                Opcode.DOUBLE_TO_FLOAT -> e { d2f() }
                Opcode.INT_TO_BYTE -> e { i2b() }
                Opcode.INT_TO_CHAR -> e { i2c() }
                Opcode.INT_TO_SHORT -> e { i2s() }
                else -> throw IllegalStateException("неизвестная унарная операция")
            }
            storeDef(i, insn.regA, Results.resultCat(op))
        }

        private fun binary(i: Int, op: Opcode, dest: Int, a: Int, b: Int, st: IntArray) {
            val c = Results.binaryCat(op)
            val index = binaryIndex(op)
            ld(st, a, c)
            ld(st, b, if (Results.isLongShift(op)) Cat.I else c)
            e { JvmOps.binary(this, c, index) }
            storeDef(i, dest, c)
        }

        private fun binaryIndex(op: Opcode): Int {
            val v = op.value
            val base = if (v >= Opcode.ADD_INT_2ADDR.value) v - Opcode.ADD_INT_2ADDR.value else v - Opcode.ADD_INT.value
            return when {
                base <= 10 -> base
                base <= 21 -> base - 11
                base <= 26 -> base - 22
                else -> base - 27
            }
        }

        /** lit16/lit8: [k] — порядок операции (add, rsub, mul, div, rem, and, or, xor, shl, shr, ushr). */
        private fun literal(i: Int, k: Int, dest: Int, src: Int, value: Int, st: IntArray) {
            if (k == 0 && dest == src && !dry && incrementsInPlace(i, st[src], dest)) {
                // add-int/lit vA, vA — iinc: значение уже в слоте регистра, тот же слот получает результат.
                e { iinc(slot(dest), value) }
                pendingBytes = null
                return
            }
            if (k == 1) {
                e { pushLiteral(value) }
                ld(st, src, Cat.I)
                e { isub() }
            } else {
                ld(st, src, Cat.I)
                e { pushLiteral(value) }
                e { JvmOps.binary(this, Cat.I, k) }
            }
            storeDef(i, dest, Cat.I)
        }

        /**
         * `add-int/lit` на месте переводится в iinc, если слагаемое лежит в слоте как int (не константа,
         * подставляемая в месте использования) и результат нужен как int. Спрос и число чтений уже
         * посчитаны холостым проходом по обычной схеме — iinc лишь не загружает значение.
         */
        private fun incrementsInPlace(i: Int, srcNode: Int, reg: Int): Boolean {
            if (srcNode == Nodes.UNDEF || srcNode == Nodes.HIGH || res.isConst(srcNode) || res.cat(srcNode) != Cat.I) return false
            val node = nodes.def(Nodes.Kind.TYPED, i, reg)
            return !res.isDemanded(node) || res.cat(node) == Cat.I
        }

        private fun CodeDsl.pushLiteral(value: Int) {
            if (value in Short.MIN_VALUE..Short.MAX_VALUE) pushInt(value) else ldc(pool.integer(value))
        }

        // ---- Вызовы ----

        private fun invoke(i: Int, insn: Instruction, st: IntArray) {
            val op = insn.opcode
            val regs = ValueFlow.argRegs(insn)
            when (op) {
                Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> {
                    val csIdx = ValueFlow.indexOf(insn)
                    val proto = res.callSiteProto(csIdx)
                    loadArgs(st, regs, 0, proto.params)
                    e { invokedynamic(callSite(csIdx, proto)) }
                    if (proto.returnType != "V") result(i, Cat.of(proto.returnType))
                    return
                }
                Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE -> {
                    val ref = view.method(ValueFlow.indexOf(insn))
                    val proto = view.proto(res.protoIndexOf(insn))
                    ld(st, regs[0], Cat.A, ref.owner)
                    loadArgs(st, regs, 1, proto.params)
                    e { invokevirtual(pool.methodref(DexView.internalName(ref.owner), ref.name, proto.descriptor)) }
                    if (proto.returnType != "V") result(i, Cat.of(proto.returnType))
                    return
                }
                else -> {}
            }
            val ref = view.method(ValueFlow.indexOf(insn))
            if ((op == Opcode.INVOKE_DIRECT || op == Opcode.INVOKE_DIRECT_RANGE) && ref.name == "<init>") {
                val bridge = constructorBridge(st, regs, ref)
                if (bridge != null) {
                    ld(st, regs[0], Cat.A)
                    loadArgs(st, regs, 1, ref.proto.params)
                    e {
                        repeat(bridge.nulls) { aconstNull() }
                        invokespecial(pool.methodref(DexView.internalName(bridge.owner), "<init>", bridge.descriptor))
                    }
                    return
                }
            }
            val isSuper = op == Opcode.INVOKE_SUPER || op == Opcode.INVOKE_SUPER_RANGE
            val ownerIsInterface = ops.isInterface(ref.owner)
            if (isSuper && !ownerIsInterface && notSuperclassOfCurrent(ref.owner)) {
                // ART: класс в ссылке invoke-super не предок вызывающего — NoSuchMethodError (FindSuperMethodToCall).
                e {
                    new(pool.classInfo("java/lang/NoSuchMethodError"))
                    dup()
                    ldc(pool.string("${ref.owner}->${ref.name}${ref.proto.descriptor} (invoke-super из ${ctx.owner})"))
                    invokespecial(pool.methodref("java/lang/NoSuchMethodError", "<init>", "(Ljava/lang/String;)V"))
                    athrow()
                }
                return
            }
            if (isSuper && ownerIsInterface) {
                val planner = accessors
                if (planner != null && !planner.isDirectSuperinterface(ctx.owner, ref.owner)) {
                    val access = planner.planSuper(ref.owner, ref.name, ref.proto.descriptor, record = !dry)
                    if (access != null) {
                        ld(st, regs[0], Cat.A, access.owner)
                        loadArgs(st, regs, 1, ref.proto.params)
                        state.requireVersion(52)
                        e { invokestatic(pool.interfaceMethodref(DexView.internalName(access.owner), access.name, access.descriptor)) }
                        if (ref.proto.returnType != "V") result(i, Cat.of(ref.proto.returnType))
                        return
                    }
                    // Интерфейс вне переводимых DEX (JDK, android.jar), который класс реализует через предков:
                    // он становится прямым суперинтерфейсом (иерархия и выбор default-методов не меняются),
                    // и invokespecial к нему допустим. ART требует того же: интерфейс — суперинтерфейс класса.
                    val iface = DexView.internalName(ref.owner)
                    if (state.hierarchy.implements(DexView.internalName(ctx.owner), iface) == true) {
                        extraInterfaces.add(iface)
                    } else {
                        val message = "invoke-super ${ref.owner}->${ref.name}${ref.proto.descriptor}: интерфейс не прямой суперинтерфейс ${ctx.owner} и не из переводимых DEX"
                        val warn = lenient ?: throw IllegalStateException(message)
                        if (!dry) warn("$ctx: $message — вызов оставлен как есть, верификатор JVM его отвергнет")
                    }
                }
            }
            if ((op == Opcode.INVOKE_VIRTUAL || op == Opcode.INVOKE_VIRTUAL_RANGE) && ref.owner == OBJECT &&
                ref.name == "clone" && ref.proto.descriptor == "()Ljava/lang/Object;" && objectClone(i, st, regs)) return
            if (op == Opcode.INVOKE_VIRTUAL || op == Opcode.INVOKE_VIRTUAL_RANGE) {
                val access = protectedAccess(st, regs.firstOrNull() ?: -1, ref.owner, ProtectedAccessors.Kind.INVOKE, ref.name, ref.proto.descriptor)
                if (access != null) {
                    ld(st, regs[0], Cat.A, access.owner)
                    loadArgs(st, regs, 1, ref.proto.params)
                    e { invokestatic(pool.methodref(DexView.internalName(access.owner), access.name, access.descriptor)) }
                    if (ref.proto.returnType != "V") result(i, Cat.of(ref.proto.returnType))
                    return
                }
            }
            val isStatic = op == Opcode.INVOKE_STATIC || op == Opcode.INVOKE_STATIC_RANGE
            var k = 0
            if (!isStatic) {
                val need = when (op) {
                    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> null
                    Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> ctx.owner
                    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> if (ref.name == "<init>") null else ref.owner
                    else -> ref.owner
                }
                if (regs.isEmpty()) throw IllegalStateException("вызов без получателя")
                ld(st, regs[0], Cat.A, need)
                k = 1
            }
            loadArgs(st, regs, k, ref.proto.params)
            // invoke-super метода класса: ART берёт реализацию из таблицы прямого суперкласса вызывающего
            // (при любом владельце в ссылке, в том числе своём классе) — в JVM это invokespecial суперкласса.
            val callOwner = when {
                isSuper && !ownerIsInterface -> ctx.superName ?: ref.owner
                (op == Opcode.INVOKE_DIRECT || op == Opcode.INVOKE_DIRECT_RANGE) && ref.name == "<init>" ->
                    bridges?.declaringConstructor(ref.owner, ref.proto.params) ?: ref.owner
                else -> ref.owner
            }
            val owner = DexView.internalName(callOwner)
            val desc = ref.proto.descriptor
            when (op) {
                Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE ->
                    e { invokevirtual(pool.methodref(owner, ref.name, desc)) }
                Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE ->
                    e { invokespecial(methodRef(owner, ref, ownerIsInterface)) }
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
                    e { invokestatic(methodRef(owner, ref, ownerIsInterface)) }
                else -> e { invokeinterface(pool.interfaceMethodref(owner, ref.name, desc), 1 + ref.proto.paramWords) }
            }
            if (ref.proto.returnType != "V") result(i, Cat.of(ref.proto.returnType))
        }

        /**
         * `Object.clone()` (protected) на значении не текущего класса: ART вызывает его без проверки типа
         * получателя, верификатор JVM — нет. Массив — clone() массива (публичный); класс из DEX — метод
         * доступа в нём; значение типа Object (так d8 выносит clone() массива) — выбор по типу массива.
         * false — ограничения нет (получатель — текущий класс), вызов обычный.
         */
        private fun objectClone(i: Int, st: IntArray, regs: IntArray): Boolean {
            if (regs.isEmpty()) return false
            val node = st[regs[0]]
            if (node == Nodes.UNDEF || node == Nodes.HIGH || res.isConst(node)) return false
            val t = res.type(node) as? JType.Ref ?: return false
            if (t.descriptor[0] != '[' && ops.assignable(t, ctx.owner)) return false
            val planner = accessors
            when {
                t.descriptor[0] == '[' -> {
                    ld(st, regs[0], Cat.A, t.descriptor)
                    e { invokevirtual(pool.methodref(t.descriptor, "clone", "()Ljava/lang/Object;")) }
                }
                planner != null && (t.descriptor == OBJECT || ops.isInterface(t.descriptor)) -> {
                    val access = planner.planIn(ctx.owner, ProtectedAccessors.Kind.CLONE, "", "", record = !dry)!!
                    ld(st, regs[0], Cat.A)
                    e { invokestatic(pool.methodref(DexView.internalName(access.owner), access.name, access.descriptor)) }
                }
                planner != null && planner.planIn(t.descriptor, ProtectedAccessors.Kind.INVOKE, "clone", "()Ljava/lang/Object;", record = false) != null -> {
                    val access = planner.planIn(t.descriptor, ProtectedAccessors.Kind.INVOKE, "clone", "()Ljava/lang/Object;", record = !dry)!!
                    ld(st, regs[0], Cat.A, access.owner)
                    e { invokestatic(pool.methodref(DexView.internalName(access.owner), access.name, access.descriptor)) }
                }
                else -> {
                    // Класс вне DEX: его clone() (если публичный — проверка доступа JVM пройдёт).
                    ld(st, regs[0], Cat.A, t.descriptor)
                    e { invokevirtual(pool.methodref(DexView.internalName(t.descriptor), "clone", "()Ljava/lang/Object;")) }
                }
            }
            result(i, Cat.A)
            return true
        }

        /**
         * `<init>`, который JVM не примет: A.<init> на `new C` (A ≠ C) или в конструкторе C на this,
         * где A — не C и не прямой суперкласс. Тогда вызов идёт через переходник ([ConstructorBridges]).
         */
        private fun constructorBridge(st: IntArray, regs: IntArray, ref: MethodRef): ConstructorBridges.Plan.Call? {
            val planner = bridges ?: return null
            if (regs.isEmpty()) return null
            val node = st[regs[0]]
            if (node == Nodes.UNDEF || node == Nodes.HIGH || res.isConst(node)) return null
            // ART разрешает C.<init>(D) поиском вверх по суперклассам: исполняется конструктор предка A.
            val declaring = planner.declaringConstructor(ref.owner, ref.proto.params)
            val receiver = when (val t = res.type(node)) {
                is JType.Uninit -> {
                    val created = view.type((code.insns[t.newIndex] as Ins21c).index)
                    if (created == declaring) return null
                    created
                }
                JType.UninitThis -> {
                    if (declaring == ctx.owner || declaring == ctx.superName) return null
                    ctx.superName ?: return null
                }
                else -> return null
            }
            return when (val plan = planner.plan(receiver, declaring, ref.proto.params, record = !dry)) {
                is ConstructorBridges.Plan.Call -> plan
                is ConstructorBridges.Plan.Impossible -> {
                    val message = "вызов ${ref.owner}-><init>${ref.proto.descriptor} (конструктор $declaring) для $receiver: ${plan.reason}"
                    val warn = lenient ?: throw IllegalStateException(message)
                    if (!dry) warn("$ctx: $message — вызов оставлен как есть, верификатор JVM его отвергнет")
                    null
                }
            }
        }

        /**
         * Обращение к protected-члену суперкласса из другого пакета, которое JVM отвергнет (объект не
         * доказуемо текущего класса или ссылка через «соседний» класс), — через метод доступа ([ProtectedAccessors]).
         */
        private fun protectedAccess(
            st: IntArray, reg: Int, referenced: String, kind: ProtectedAccessors.Kind, name: String, descriptor: String
        ): ProtectedAccessors.Call? {
            val planner = accessors ?: return null
            if (reg < 0) return null
            val node = st[reg]
            if (node == Nodes.UNDEF || node == Nodes.HIGH || res.isConst(node)) return null
            val t = res.type(node) as? JType.Ref ?: return null
            return planner.plan(ctx.owner, referenced, kind, name, descriptor, ops.assignable(t, ctx.owner), record = !dry)
        }

        /**
         * Класс [type] точно не суперкласс текущего класса (и не он сам): цепочка суперклассов известна
         * до java/lang/Object, и [type] в ней нет. Неизвестная цепочка — false.
         */
        private fun notSuperclassOfCurrent(type: String): Boolean {
            if (type == ctx.owner || type[0] != 'L') return false
            val target = DexView.internalName(type)
            var cur: String? = DexView.internalName(ctx.owner)
            var guard = 0
            while (cur != null && guard++ < 256) {
                if (cur == target) return false
                if (cur == "java/lang/Object") return true
                val next = state.hierarchy.superClass(cur)
                if (next == null && state.hierarchy.isInterface(cur) == null) return false // иерархия неизвестна
                cur = next
            }
            return false
        }

        /** Ссылка для invokestatic/invokespecial; InterfaceMethodref в них допустим только с версии 52. */
        private fun methodRef(owner: String, ref: MethodRef, isInterface: Boolean): Int {
            if (!isInterface) return pool.methodref(owner, ref.name, ref.proto.descriptor)
            state.requireVersion(52)
            return pool.interfaceMethodref(owner, ref.name, ref.proto.descriptor)
        }

        /** Аргументы по параметрам прототипа, начиная с регистра №[from] списка. */
        private fun loadArgs(st: IntArray, regs: IntArray, from: Int, params: List<String>) {
            var k = from
            for (p in params) {
                if (k >= regs.size) throw IllegalStateException("регистров меньше, чем параметров")
                val c = Cat.of(p)
                ld(st, regs[k], c, if (c == Cat.A) p else null)
                k += if (c.isWide) 2 else 1
            }
            if (k != regs.size) throw IllegalStateException("число регистров (${regs.size}) не совпадает с параметрами")
        }

        // ---- Handles и call sites (CONSTANT_MethodHandle/MethodType/InvokeDynamic — с версии 51) ----

        private fun methodType(protoIdx: Int): Int {
            state.requireVersion(51)
            return pool.methodType(view.proto(protoIdx).descriptor)
        }

        private fun methodHandle(dexIdx: Int): Int {
            state.requireVersion(51)
            val mh = view.dex.methodHandles.getOrNull(dexIdx) ?: throw IllegalStateException("нет method handle #$dexIdx")
            val t = mh.methodHandleType
            if (t in 0..3) {
                val f = view.field(mh.fieldOrMethodId)
                val kind = when (t) { 0 -> 4; 1 -> 2; 2 -> 3; else -> 1 }
                return pool.methodHandle(kind, pool.fieldref(DexView.internalName(f.owner), f.name, f.type))
            }
            val m = view.method(mh.fieldOrMethodId)
            val owner = DexView.internalName(m.owner)
            val kind = when (t) {
                4 -> 6; 5 -> 5; 6 -> 8; 7 -> 7; 8 -> 9
                else -> throw IllegalStateException("неизвестный тип method handle $t")
            }
            val ref = if (kind == 9 || ((kind == 6 || kind == 7) && ops.isInterface(m.owner))) {
                // invokestatic/invokespecial-handle на метод интерфейса — только с версии 52 (JVMS §4.4.8).
                if (kind != 9) state.requireVersion(52)
                pool.interfaceMethodref(owner, m.name, m.proto.descriptor)
            } else pool.methodref(owner, m.name, m.proto.descriptor)
            return pool.methodHandle(kind, ref)
        }

        private fun callSite(csIdx: Int, proto: Proto): Int {
            val cs = view.dex.callSiteItems[view.dex.callSiteIds[csIdx].callSiteIdx]
            val handle = methodHandle(cs.methodHandle.value as Int)
            val name = view.string(cs.arguments[0].value as Int)
            val args = cs.arguments.drop(2).map { bootstrapArgument(it) }
            val bsm = state.bootstrapPool.add(handle, args)
            return pool.invokeDynamic(bsm, pool.nameAndType(name, proto.descriptor))
        }

        private fun bootstrapArgument(v: EncodedValue): Int = when (v.type) {
            EncodedValueType.VALUE_INT -> pool.integer(v.value as Int)
            EncodedValueType.VALUE_BYTE -> pool.integer((v.value as Byte).toInt())
            EncodedValueType.VALUE_SHORT -> pool.integer((v.value as Short).toInt())
            EncodedValueType.VALUE_CHAR -> pool.integer((v.value as Char).code)
            EncodedValueType.VALUE_BOOLEAN -> pool.integer(if (v.value as Boolean) 1 else 0)
            EncodedValueType.VALUE_LONG -> pool.long(v.value as Long)
            EncodedValueType.VALUE_FLOAT -> pool.float((v.value as Float).toRawBits())
            EncodedValueType.VALUE_DOUBLE -> pool.double((v.value as Double).toRawBits())
            EncodedValueType.VALUE_STRING -> pool.string(view.string(v.value as Int))
            EncodedValueType.VALUE_TYPE -> pool.classInfo(DexView.internalName(view.type(v.value as Int)))
            EncodedValueType.VALUE_METHOD_TYPE -> methodType(v.value as Int)
            EncodedValueType.VALUE_METHOD_HANDLE -> methodHandle(v.value as Int)
            else -> throw IllegalStateException("аргумент bootstrap-метода типа 0x${Integer.toHexString(v.type)} не поддерживается в JVM")
        }
    }
}

/** Предел длины кода метода (JVMS §4.7.3). */
private const val MAX_CODE = 0xFFFF

/** Короткие таблицы и в компактном режиме пишутся по элементу. */
private const val COMPACT_FILL_MIN = 4

private const val OBJECT = "Ljava/lang/Object;"

/**
 * Регистр DEX → слот JVM. Поворот: параметры (последние ins регистров) — в слоты 0..ins-1,
 * остальные регистры — следом. [copyParams]: все регистры r → слот ins+r, параметры копируются в прологе.
 */
internal class SlotLayout(private val registers: Int, private val ins: Int, val copyParams: Boolean) {
    /** Число слотов JVM под кадр. */
    val size: Int get() = if (copyParams) ins + registers else registers

    fun slot(r: Int): Int = when {
        copyParams -> ins + r
        r >= registers - ins -> r - (registers - ins)
        else -> r + ins
    }
}
