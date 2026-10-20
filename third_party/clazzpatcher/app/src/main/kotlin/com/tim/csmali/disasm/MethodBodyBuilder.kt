package com.tim.csmali.disasm

import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.LineNumberTable
import com.tim.clazz.model.attributes.LocalVariableTable
import com.tim.clazz.model.attributes.LocalVariableTypeTable
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations
import com.tim.clazz.model.attributes.StackMapTable
import com.tim.clazz.model.attributes.UnknownAttribute
import com.tim.csmali.SmaliFormatter
import com.tim.csmali.data.SmaliDebugKind
import com.tim.csmali.model.SmaliCatchDirective
import com.tim.csmali.model.SmaliDebug
import com.tim.csmali.model.SmaliInsn
import com.tim.csmali.model.SmaliLabel
import com.tim.csmali.model.SmaliMethod
import com.tim.csmali.model.SmaliRawAttribute
import com.tim.csmali.model.SmaliTypeAnnotation

/** Тело метода: инструкции, метки переходов и try, `.line`/`.local`, `.catch` в порядке таблицы исключений. */
internal class MethodBodyBuilder(
    private val cf: ClassFile,
    private val names: ClassNames,
    private val code: Code,
    paramTypes: List<String>,
    isStatic: Boolean
) {
    private val disasm = JvmDisassembler(code.code, names, paramTypes, isStatic)

    fun build(method: SmaliMethod, typeAnnotation: (RuntimeVisibleTypeAnnotations) -> List<SmaliTypeAnnotation>) {
        method.maxStack = code.maxStack
        method.maxLocals = code.maxLocals
        for (a in code.attributes) {
            when (a) {
                is LineNumberTable, is LocalVariableTable, is LocalVariableTypeTable -> {} // → .line/.local
                is StackMapTable -> {} // пересчитывается при сборке
                // Цели 0x40–0x4B ссылаются на pc: при неизменном коде сборка даёт те же pc.
                is RuntimeVisibleTypeAnnotations -> method.typeAnnotations.addAll(typeAnnotation(a))
                is UnknownAttribute -> method.rawCodeAttributes.add(SmaliRawAttribute(cf.utf8(a.attributeNameIndex), a.data))
                else -> throw IllegalStateException("атрибут ${cf.attributeName(a)} внутри Code не поддерживается csmali")
            }
        }

        val decoded = disasm.decode()
        val tryLabels = collectTryLabels()
        val debug = collectDebug()

        val body = method.body
        var d = 0
        for (insn in decoded.insns) {
            while (d < debug.size && debug[d].pc <= insn.pc) body.add(debug[d++].element)
            tryLabels[insn.pc]?.forEach { body.add(SmaliLabel(it)) }
            decoded.labels[insn.pc]?.forEach { body.add(SmaliLabel(it)) }
            body.add(SmaliInsn(insn.text))
        }
        while (d < debug.size) body.add(debug[d++].element)
        val end = code.code.size
        tryLabels[end]?.forEach { body.add(SmaliLabel(it)) }
        decoded.labels[end]?.forEach { body.add(SmaliLabel(it)) }

        for (e in code.exceptionTable) {
            method.catches.add(SmaliCatchDirective(
                exceptionType = if (e.catchType == 0) null else names.typeText(e.catchType),
                tryStart = label("try_start_", e.startPc),
                tryEnd = label("try_end_", e.endPc),
                handler = label(if (e.catchType == 0) "catchall_" else "catch_", e.handlerPc)
            ))
        }
    }

    private fun collectTryLabels(): Map<Int, List<String>> {
        if (code.exceptionTable.isEmpty()) return emptyMap()
        val out = HashMap<Int, MutableList<String>>()
        fun add(pc: Int, name: String) {
            val list = out.getOrPut(pc) { ArrayList(2) }
            if (name !in list) list.add(name)
        }
        for (e in code.exceptionTable) {
            add(e.startPc, label("try_start_", e.startPc))
            add(e.endPc, label("try_end_", e.endPc))
            add(e.handlerPc, label(if (e.catchType == 0) "catchall_" else "catch_", e.handlerPc))
        }
        return out
    }

    private class DebugEntry(val pc: Int, val element: SmaliDebug)

    /** Событие отладки: строка или переменная ([length] = 0 — пустой диапазон, выводится парой подряд). */
    private class Event(val pc: Int, val order: Int, val seq: Int, val line: Int, val local: LocalVariableTable.LocalVariableTableEntry?, val end: Boolean)

    /**
     * `.line`, `.local` и `.end local` по pc. На одном pc сначала закрываются старые переменные,
     * затем строки, затем открываются новые (переменные одного pc — по номеру слота). Открытые переменные моделируются по слотам: если
     * на слоте их несколько, `.end local` получает имя — иначе сборка не поймёт, какую закрыть.
     */
    private fun collectDebug(): List<DebugEntry> {
        val signatures = HashMap<Long, String>()
        for (a in code.attributes) {
            if (a !is LocalVariableTypeTable) continue
            for (e in a.entries) signatures[key(e.startPc, e.length, e.index)] = cf.utf8(e.signatureIndex)
        }
        val events = ArrayList<Event>()
        var seq = 0
        for (a in code.attributes) {
            when (a) {
                is LineNumberTable -> for (e in a.entries) events.add(Event(e.startPc, ORDER_LINE, seq++, e.lineNumber, null, false))
                is LocalVariableTable -> for (e in a.entries) {
                    events.add(Event(e.startPc, ORDER_OPEN, seq++, 0, e, false))
                    if (e.length > 0) events.add(Event(e.startPc + e.length, ORDER_CLOSE, seq++, 0, e, true))
                }
                else -> {}
            }
        }
        // На одном pc переменные — по слоту (порядок записей в таблице смысла не несёт и после сборки
        // другой): так текст не зависит от порядка LocalVariableTable и повторная разборка совпадает.
        events.sortWith(compareBy<Event>({ it.pc }, { it.order }, { it.local?.index ?: 0 }, { it.seq }))

        val out = ArrayList<DebugEntry>(events.size + 8)
        val open = HashMap<Int, ArrayList<LocalVariableTable.LocalVariableTableEntry>>()
        fun close(pc: Int, e: LocalVariableTable.LocalVariableTableEntry) {
            val list = open[e.index]
            val reg = disasm.regName(e.index)
            // Имя нужно, если открыто несколько; сборка закрывает первую открытую с этим именем.
            val text = if (list != null && list.size > 1) ".end local $reg, ${names.quotedString(e.nameIndex)}" else ".end local $reg"
            list?.let { l -> l.removeAt(l.indexOfFirst { it === e || it.nameIndex == e.nameIndex }.coerceAtLeast(0)) }
            out.add(DebugEntry(pc, SmaliDebug(SmaliDebugKind.END_LOCAL, text)))
        }
        for (ev in events) {
            val e = ev.local
            when {
                e == null -> out.add(DebugEntry(ev.pc, SmaliDebug(SmaliDebugKind.LINE, ".line ${ev.line}")))
                ev.end -> close(ev.pc, e)
                else -> {
                    val sb = StringBuilder(64).append(".local ").append(disasm.regName(e.index)).append(", ")
                        .append(names.quotedString(e.nameIndex)).append(':').append(names.descriptorText(e.descriptorIndex))
                    signatures[key(e.startPc, e.length, e.index)]?.let { SmaliFormatter.appendQuoted(sb.append(", "), it) }
                    out.add(DebugEntry(ev.pc, SmaliDebug(SmaliDebugKind.LOCAL, sb.toString())))
                    open.getOrPut(e.index) { ArrayList(2) }.add(e)
                    if (e.length == 0) close(ev.pc, e) // пустой диапазон: .end local сразу за .local
                }
            }
        }
        return out
    }

    private companion object {
        const val ORDER_CLOSE = 0
        const val ORDER_LINE = 1
        const val ORDER_OPEN = 2
    }

    private fun key(start: Int, length: Int, index: Int): Long =
        (start.toLong() shl 40) or (length.toLong() shl 20) or index.toLong()

    private fun label(prefix: String, pc: Int): String = ":$prefix${Integer.toHexString(pc)}"
}
