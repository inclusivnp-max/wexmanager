package com.tim.dsmali.asm

import com.tim.dasm.DexAsm
import com.tim.dasm.dsl.MethodDsl
import com.tim.dasm.model.AccessFlags
import com.tim.dasm.model.ClassInfo
import com.tim.dasm.model.Instruction
import com.tim.dasm.util.ClassTransfer
import com.tim.dasm.util.HierarchyOrder
import com.tim.dasm.util.OrderedParallel
import com.tim.dex.data.DexMagic
import com.tim.dex.model.DexFile
import com.tim.dex.model.DexFileBuilder
import com.tim.dex.model.DexHeader
import com.tim.dsmali.SmaliBuildException
import com.tim.dsmali.lineOf
import com.tim.dsmali.data.SmaliDebugKind
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.MethodContext
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliLabel
import com.tim.dsmali.model.SmaliMethod
import com.tim.dsmali.model.SmaliTree

/**
 * [SmaliTree] → [DexFile] через asm DSL.
 *
 * Перед сборкой строки из `const-string` добавляются в пул первыми: их предварительные
 * индексы гарантированно помещаются в 16 бит, даже если строк в DEX больше 65536
 * (writer затем переупорядочивает пул). Call site добавляются в порядке номеров
 * `call_site_N`, чтобы при сохранении порядка пула номера совпали с исходными.
 *
 * Классы собираются параллельно, каждый в собственных пулах, и переносятся в общий DexAsm по
 * порядку ([ClassTransfer]) — результат от числа потоков не зависит. Классы с `invoke-custom`
 * собираются прямо в общих пулах (номера call site задаёт предварительный проход).
 */
class TreeToDex(private val threads: Int = Runtime.getRuntime().availableProcessors()) {

    private class Built(val asm: DexAsm, val info: ClassInfo)

    fun build(tree: SmaliTree): DexFile {
        val asm = DexAsm.load(emptyDexFile())
        val refs = SmaliRefs(asm)
        prepass(tree, refs)
        val ordered = HierarchyOrder.sort(tree.classes, { it.descriptor }, { listOfNotNull(it.superClass) + it.interfaces })
        OrderedParallel.run(ordered, threads, produce = { cls ->
            if (usesCallSites(cls)) null
            else {
                val local = DexAsm.load(emptyDexFile())
                val localRefs = SmaliRefs(local)
                Built(local, context(cls) {
                    constStrings(cls, localRefs) // как в общем пуле: строки const-string — первыми
                    buildClass(local, localRefs, cls)
                })
            }
        }, consume = { cls, built ->
            val info = context(cls) {
                if (built == null) buildClass(asm, refs, cls)
                else try {
                    ClassTransfer(built.asm, asm).transfer(built.info)
                } catch (e: ClassTransfer.StringIndexOverflow) {
                    // const-string, чья строка в общем пуле дальше 65535, — сборка прямо в общих пулах.
                    buildClass(asm, refs, cls)
                }
            }
            context(cls) { asm.useDsl { addClass(info) } }
        })
        return asm.build()
    }

    private inline fun <T> context(cls: SmaliClass, block: () -> T): T =
        try {
            block()
        } catch (e: ClassTransfer.StringIndexOverflow) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("TreeToDex: класс ${cls.descriptor}: ${e.message}", e)
        }

    internal fun usesCallSites(cls: SmaliClass): Boolean =
        cls.methods.any { m -> m.body.any { it is SmaliInsn && it.text.startsWith("invoke-custom") } }

    internal fun emptyDexFile(): DexFile {
        val b = DexFileBuilder()
        b.header = DexHeader(magic = DexMagic.DEX_035.copyOf())
        return b.build()
    }

    // ---- Предварительный проход ----

    private fun prepass(tree: SmaliTree, refs: SmaliRefs) {
        val callSites = ArrayList<Pair<Long, String>>()
        for (cls in tree.classes) {
            constStrings(cls, refs)
            for (m in cls.methods) {
                for (el in m.body) {
                    if (el !is SmaliInsn || !el.text.startsWith("invoke-custom")) continue
                    val text = callSiteText(el.text) ?: continue
                    callSites.add(callSiteOrder(text, callSites.size) to text)
                }
            }
        }
        callSites.sortBy { it.first }
        for ((_, text) in callSites) {
            try {
                refs.callSite(text)
            } catch (e: Exception) {
                throw IllegalStateException("TreeToDex: call site $text: ${e.message}", e)
            }
        }
    }

    /** Строки `const-string` класса — в пул (раньше прочих строк: индексы помещаются в 16 бит). */
    internal fun constStrings(cls: SmaliClass, refs: SmaliRefs) {
        for (m in cls.methods) {
            for (el in m.body) {
                if (el !is SmaliInsn) continue
                val t = el.text
                if (!t.startsWith("const-string ") && !t.startsWith("const-string\t")) continue
                try {
                    val q = t.indexOf('"')
                    if (q > 0) refs.string(SmaliScanner(t, q).stringLiteralRaw())
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("TreeToDex: класс ${cls.descriptor}: метод ${m.name}: \"$t\": ${e.message}", e)
                }
            }
        }
    }

    /** Текст call site из `invoke-custom {..}, call_site_N(...)@...` (ошибки разбора — при сборке метода). */
    private fun callSiteText(t: String): String? {
        val close = t.indexOf('}')
        if (close < 0) return null
        val comma = t.indexOf(',', close)
        if (comma < 0) return null
        return SmaliParser.stripComment(t.substring(comma + 1)).ifEmpty { null }
    }

    /** Порядок: номер N из `call_site_N`, прочие имена — после, в порядке появления. */
    private fun callSiteOrder(text: String, seq: Int): Long {
        val paren = text.indexOf('(')
        val name = if (paren > 0) text.substring(0, paren) else text
        val n = if (name.startsWith("call_site_")) name.substring(10).toIntOrNull() else null
        return if (n != null && n >= 0) n.toLong() else (1L shl 32) + seq
    }

    // ---- Классы ----

    /**
     * Ошибка сборки кода метода (метки, переходы, try) возникает уже после DSL — в [DexAsm.buildClass], без
     * строки: её место — строка `.method` того метода, чья сигнатура в сообщении.
     */
    internal fun buildClass(asm: DexAsm, refs: SmaliRefs, cls: SmaliClass): ClassInfo =
        try {
            buildClassUnchecked(asm, refs, cls)
        } catch (e: ClassTransfer.StringIndexOverflow) {
            throw e
        } catch (e: Exception) {
            if (lineOf(e) != 0) throw e
            val msg = e.message ?: throw e
            val m = cls.methods.firstOrNull { it.line > 0 && msg.contains("Метод ${it.name}(${it.paramTypes.joinToString("")})${it.returnType}") }
                ?: throw e
            throw SmaliBuildException(m.line, msg, e)
        }

    private fun buildClassUnchecked(asm: DexAsm, refs: SmaliRefs, cls: SmaliClass): ClassInfo {
        val values = refs.values
        return asm.buildClass(cls.descriptor) {
            accessFlags(flagsOf(cls.accessFlags))
            superClass(cls.superClass)
            cls.sourceFile?.let { sourceFile(it) }
            cls.interfaces.forEach { implements(it) }

            cls.annotations.forEach { a ->
                annotation(values.buildAnnotation(a.visibility, a.type, a.elements.map { it.name to it.value }))
            }

            for (f in cls.fields) {
                try {
                    val annotations = f.annotations.map { a ->
                        values.buildAnnotation(a.visibility, a.type, a.elements.map { it.name to it.value })
                    }
                    val initialValue = f.initialValue?.let { values.build(it) }
                    field(f.name, f.type, flagsOf(f.accessFlags), annotations, initialValue, hiddenApiOf(f.accessFlags))
                } catch (e: Exception) {
                    throw SmaliBuildException(f.line, "поле ${f.name}:${f.type}: ${e.message}", e)
                }
            }

            // Повторное объявление метода: как эталонный smali (dexlib2), действует первое.
            val declared = HashSet<String>()
            for (m in cls.methods) {
                val signature = "${m.name}(${m.paramTypes.joinToString("")})${m.returnType}"
                if (!declared.add(signature)) continue
                try {
                    method(m.name) { emitMethod(refs, this, m) }
                } catch (e: Exception) {
                    throw SmaliBuildException(m.line, "метод $signature: ${e.message}", e)
                }
            }
        }
    }

    private fun emitMethod(refs: SmaliRefs, dsl: MethodDsl, m: SmaliMethod) {
        dsl.returnType(m.returnType)
        m.paramTypes.forEach { dsl.param(it) }
        dsl.accessFlags(flagsOf(m.accessFlags))
        dsl.hiddenApi(hiddenApiOf(m.accessFlags))
        val isStatic = "static" in m.accessFlags
        val insSize = insSizeOf(m, isStatic)

        val registers = when {
            m.registers >= 0 -> m.registers
            m.locals >= 0 -> m.locals + insSize
            else -> -1
        }
        if (registers >= 0) dsl.registers(registers)

        m.paramNames.forEachIndexed { i, n -> if (n != null) dsl.paramName(i, n) }

        val values = refs.values
        m.annotations.forEach { a ->
            dsl.annotation(values.buildAnnotation(a.visibility, a.type, a.elements.map { it.name to it.value }))
        }
        for ((i, anns) in m.parameterAnnotations) {
            for (a in anns) {
                dsl.paramAnnotation(i, values.buildAnnotation(a.visibility, a.type, a.elements.map { it.name to it.value }))
            }
        }

        if (m.body.isEmpty() && m.catches.isEmpty()) return
        if (registers < 0) throw IllegalStateException("у метода с кодом нет .registers/.locals")
        if (registers < insSize) throw IllegalStateException("регистров ($registers) меньше, чем занимают параметры ($insSize)")

        val ctx = MethodContext(registers, insSize, isStatic, m.paramTypes)
        val code = dsl.code
        val emitter = SmaliInsnEmitter(refs, code, ctx)
        val paramBase = registers - insSize
        for ((i, el) in m.body.withIndex()) {
            try {
                when (el) {
                    is SmaliLabel -> code.label(el.name)
                    is SmaliInsn -> {
                        val from = code.instructions.size
                        emitter.emit(el.text)
                        checkRegisters(code.instructions, from, registers)
                    }
                    is SmaliDebug -> debug(dsl, el, paramBase, insSize)
                }
            } catch (e: Exception) {
                val text = when (el) {
                    is SmaliLabel -> el.name
                    is SmaliInsn -> el.text.lineSequence().first()
                    is SmaliDebug -> el.raw
                }
                throw SmaliBuildException(el.line, "элемент #$i \"$text\": ${e.message}", e)
            }
        }

        for (c in m.catches) {
            if (c.exceptionType == null) dsl.catchAll(c.tryStart, c.tryEnd, c.handler)
            else dsl.tryCatch(c.tryStart, c.tryEnd, c.handler, c.exceptionType)
        }
    }

    /**
     * Регистры только что добавленных инструкций — в кадре метода. Та же проверка есть при сборке кода, но
     * там место — только метод; здесь ошибка приходится на строку инструкции.
     */
    private fun checkRegisters(list: List<Instruction>, from: Int, registers: Int) {
        for (k in from until list.size) {
            for (r in list[k].getWideRegisters()) {
                if (r >= registers) {
                    throw IllegalArgumentException("регистр v$r вне кадра метода: регистров $registers (v0…v${registers - 1})")
                }
            }
        }
    }

    private fun debug(dsl: MethodDsl, el: SmaliDebug, paramBase: Int, insSize: Int) {
        val s = SmaliScanner(el.raw)
        fun directive(d: String) {
            if (!s.tryConsume(d)) s.error("Ожидалась директива $d")
        }
        when (el.kind) {
            SmaliDebugKind.LINE -> {
                directive(".line")
                val n = SmaliLiterals.parseInt(s.word())
                dsl.lineNumber(n)
            }
            SmaliDebugKind.LOCAL -> {
                directive(".local")
                val reg = s.register(paramBase, insSize)
                var name: String? = null
                var type: String? = null
                var sig: String? = null
                if (s.tryConsume(',')) {
                    name = if (s.tryConsume("null")) null else s.stringLiteral()
                    s.expect(':')
                    type = s.type().takeIf { it != "V" }
                    if (s.tryConsume(',')) sig = s.stringLiteral()
                }
                dsl.startLocal(reg, name, type, sig)
            }
            SmaliDebugKind.END_LOCAL -> {
                directive(".end"); directive("local")
                dsl.endLocal(s.register(paramBase, insSize))
            }
            SmaliDebugKind.RESTART_LOCAL -> {
                directive(".restart"); directive("local")
                dsl.restartLocal(s.register(paramBase, insSize))
            }
            SmaliDebugKind.PROLOGUE -> { directive(".prologue"); dsl.prologueEnd() }
            SmaliDebugKind.EPILOGUE -> { directive(".epilogue"); dsl.epilogueBegin() }
            SmaliDebugKind.SOURCE -> {
                directive(".source")
                dsl.setFile(if (s.atEnd()) null else s.stringLiteral())
            }
        }
        s.expectEnd()
    }

    private fun insSizeOf(m: SmaliMethod, isStatic: Boolean): Int {
        var n = if (isStatic) 0 else 1
        m.paramTypes.forEach { n += if (it == "J" || it == "D") 2 else 1 }
        return n
    }

    companion object {
        /** Значение hidden API из слов списка флагов; `-1`, если ограничений нет. */
        fun hiddenApiOf(words: List<String>): Int = com.tim.dsmali.HiddenApi.parse(words)

        fun flagsOf(words: List<String>): Int {
            var f = 0
            for (w in words) {
                if (com.tim.dsmali.HiddenApi.isWord(w)) continue
                f = f or AccessFlags.flagOf(w)
            }
            return f
        }
    }
}
