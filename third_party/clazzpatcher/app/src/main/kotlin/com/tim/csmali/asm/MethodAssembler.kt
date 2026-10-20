package com.tim.csmali.asm

import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.frames.MethodShape
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.csmali.SmaliFormatter
import com.tim.csmali.data.SmaliDebugKind
import com.tim.csmali.model.SmaliDebug
import com.tim.csmali.model.SmaliInsn
import com.tim.csmali.model.SmaliLabel
import com.tim.csmali.model.SmaliMethod

/** Тело [SmaliMethod] → байткод с таблицами строк, локалов и StackMapTable (через casm). */
internal class MethodAssembler(
    private val pool: ConstantPoolBuilder,
    private val refs: SmaliRefs,
    private val owner: String,
    private val options: (SmaliMethod) -> CodeAssembler.Options
) {

    fun assemble(m: SmaliMethod, isStatic: Boolean): CodeAssembler.Assembled {
        val code = CodeDsl()
        val paramSlots = (if (isStatic) 0 else 1) + m.paramTypes.size + m.paramTypes.count { SmaliFormatter.isWide(it) }
        val emitter = SmaliInsnEmitter(refs, code, paramSlots)
        val lineNumbers = ArrayList<Pair<String, Int>>()
        // Записи LocalVariableTable — в порядке директив .local (а не закрытия): у переменных, открытых на одном
        // pc, разборщик сохраняет порядок таблицы, и повторная разборка даёт тот же текст.
        val closed = ArrayList<Pair<Int, CodeAssembler.LocalVarSpec>>()
        var opened = 0
        val open = HashMap<Int, MutableList<OpenLocal>>()
        var debugLabels = 0
        fun debugLabel(): String = ":__dbg_${debugLabels++}".also { code.label(it) }

        for (el in m.body) {
            try {
                when (el) {
                    is SmaliLabel -> code.label(el.name)
                    is SmaliInsn -> emitter.emit(el.text)
                    is SmaliDebug -> when (el.kind) {
                        SmaliDebugKind.LINE -> lineNumbers.add(debugLabel() to parseLine(el.raw))
                        SmaliDebugKind.LOCAL -> {
                            val local = parseLocal(el.raw, paramSlots, debugLabel(), opened++)
                            open.getOrPut(local.slot) { ArrayList(1) }.add(local)
                        }
                        SmaliDebugKind.END_LOCAL -> {
                            val (slot, name) = parseEndLocal(el.raw, paramSlots)
                            val local = closeLocal(open, slot, name)
                            closed.add(local.order to local.spec(debugLabel()))
                        }
                        else -> throw IllegalArgumentException("${el.raw}: директива не поддерживается csmali")
                    }
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("${elementText(el)}: ${e.message}", e)
            }
        }
        if (open.values.any { it.isNotEmpty() }) {
            val end = debugLabel()
            for (local in open.values.flatten()) closed.add(local.order to local.spec(end))
        }
        val localVariables = closed.sortedBy { it.first }.map { it.second }
        val tries = m.catches.map { CodeAssembler.TrySpec(it.tryStart, it.tryEnd, it.handler, it.exceptionType?.let(SmaliRefs::internalName)) }
        val desc = "(${m.paramTypes.joinToString("")})${m.returnType}"
        return CodeAssembler.assemble(code, pool, MethodShape(owner, m.name, desc, isStatic), tries, lineNumbers, localVariables, options(m))
    }

    private class OpenLocal(val slot: Int, val name: String, val type: String, val signature: String?, val start: String, val order: Int) {
        fun spec(end: String) = CodeAssembler.LocalVarSpec(start, end, name, type, slot, signature)
    }

    /** Закрыть локал слота; при нескольких открытых на одном слоте нужно имя (`.end local v0, "x"`). */
    private fun closeLocal(open: MutableMap<Int, MutableList<OpenLocal>>, slot: Int, name: String?): OpenLocal {
        val list = open[slot]
        if (list.isNullOrEmpty()) throw IllegalArgumentException("нет открытой .local для этого регистра")
        val i = when {
            name != null -> list.indexOfFirst { it.name == name }.also {
                if (it < 0) throw IllegalArgumentException("нет открытой .local \"$name\" для этого регистра")
            }
            list.size == 1 -> 0
            else -> throw IllegalArgumentException("на регистре несколько открытых .local — укажите имя")
        }
        return list.removeAt(i)
    }

    private fun elementText(el: Any): String = when (el) {
        is SmaliInsn -> el.text.substringBefore('\n')
        is SmaliDebug -> el.raw
        is SmaliLabel -> el.name
        else -> el.toString()
    }

    private fun parseLine(raw: String): Int {
        val s = SmaliScanner(raw)
        if (!s.tryConsume(".line")) throw IllegalArgumentException("Ожидалось .line")
        val n = SmaliLiterals.parseInt(s.word())
        s.expectEnd()
        if (n !in 0..0xFFFF) throw IllegalArgumentException("Номер строки $n вне 0..65535")
        return n
    }

    /** `.local reg, "имя":тип[, "сигнатура"]`. */
    private fun parseLocal(raw: String, paramSlots: Int, start: String, order: Int): OpenLocal {
        val s = SmaliScanner(raw)
        if (!s.tryConsume(".local")) throw IllegalArgumentException("Ожидалось .local")
        val slot = s.register(paramSlots)
        s.expect(',')
        val name = s.stringLiteral()
        s.expect(':')
        val type = s.type()
        val signature = if (s.tryConsume(',')) s.stringLiteral() else null
        s.expectEnd()
        return OpenLocal(slot, name, type, signature, start, order)
    }

    /** `.end local reg[, "имя"]`. */
    private fun parseEndLocal(raw: String, paramSlots: Int): Pair<Int, String?> {
        val s = SmaliScanner(raw)
        if (!s.tryConsume(".end") || !s.tryConsume("local")) throw IllegalArgumentException("Ожидалось .end local")
        val slot = s.register(paramSlots)
        val name = if (s.tryConsume(',')) s.stringLiteral() else null
        s.expectEnd()
        return slot to name
    }
}
