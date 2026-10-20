package com.tim.dsmali.io

import com.tim.dsmali.SmaliFormatter
import com.tim.dsmali.model.SmaliAnnotation
import com.tim.dsmali.model.SmaliCatchDirective
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliField
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliLabel
import com.tim.dsmali.model.SmaliMethod

/**
 * smali-модель → текст в раскладке baksmali 3.0.9 (секции, пустые строки, отступы,
 * `.param pN` с номерами регистров и комментарием-типом, `.catch` сразу после `:try_end_`).
 * Чистая функция от класса — потокобезопасна.
 */
object SmaliWriter {
    fun write(cls: SmaliClass): String {
        val sb = StringBuilder(estimateSize(cls))
        sb.append(".class ")
        appendFlags(sb, cls.accessFlags)
        SmaliFormatter.appendType(sb, cls.descriptor).append('\n')
        cls.superClass?.let { SmaliFormatter.appendType(sb.append(".super "), it).append('\n') }
        cls.sourceFile?.let { SmaliFormatter.appendQuoted(sb.append(".source "), it).append('\n') }
        if (cls.interfaces.isNotEmpty()) {
            sb.append("\n# interfaces\n")
            for (i in cls.interfaces) SmaliFormatter.appendType(sb.append(".implements "), i).append('\n')
        }
        if (cls.annotations.isNotEmpty()) {
            sb.append("\n\n# annotations\n")
            appendAnnotations(sb, cls.annotations, 0)
        }

        var header = false
        for (f in cls.fields) {
            if ("static" !in f.accessFlags) continue
            if (!header) { sb.append("\n\n# static fields"); header = true }
            sb.append('\n')
            appendField(sb, f)
        }
        header = false
        for (f in cls.fields) {
            if ("static" in f.accessFlags) continue
            if (!header) { sb.append("\n\n# instance fields"); header = true }
            sb.append('\n')
            appendField(sb, f)
        }
        header = false
        for (m in cls.methods) {
            if (!isDirect(m)) continue
            if (!header) { sb.append("\n\n# direct methods"); header = true }
            sb.append('\n')
            appendMethod(sb, m)
        }
        header = false
        for (m in cls.methods) {
            if (isDirect(m)) continue
            if (!header) { sb.append("\n\n# virtual methods"); header = true }
            sb.append('\n')
            appendMethod(sb, m)
        }
        return sb.toString()
    }

    private fun isDirect(m: SmaliMethod): Boolean =
        "static" in m.accessFlags || "private" in m.accessFlags || "constructor" in m.accessFlags

    private fun appendFlags(sb: StringBuilder, flags: List<String>) {
        for (f in flags) sb.append(f).append(' ')
    }

    private fun appendField(sb: StringBuilder, f: SmaliField) {
        sb.append(".field ")
        appendFlags(sb, f.accessFlags)
        SmaliFormatter.appendSimpleName(sb, f.name).append(':')
        SmaliFormatter.appendType(sb, f.type)
        f.initialValue?.let { appendIndented(sb.append(" = "), it, 0) }
        sb.append('\n')
        if (f.annotations.isNotEmpty()) {
            appendAnnotations(sb, f.annotations, 4)
            sb.append(".end field\n")
        }
    }

    private fun appendMethod(sb: StringBuilder, m: SmaliMethod) {
        sb.append(".method ")
        appendFlags(sb, m.accessFlags)
        SmaliFormatter.appendSimpleName(sb, m.name).append('(')
        for (p in m.paramTypes) SmaliFormatter.appendType(sb, p)
        sb.append(')')
        SmaliFormatter.appendType(sb, m.returnType).append('\n')

        val hasCode = m.registers >= 0 || m.locals >= 0 || m.body.isNotEmpty() || m.catches.isNotEmpty()
        if (m.registers >= 0) sb.append("    .registers ").append(m.registers).append('\n')
        else if (m.locals >= 0) sb.append("    .locals ").append(m.locals).append('\n')
        appendParams(sb, m)
        appendAnnotations(sb, m.annotations, 4)
        if (hasCode) {
            sb.append('\n')
            appendBody(sb, m)
        }
        sb.append(".end method\n")
    }

    private fun appendParams(sb: StringBuilder, m: SmaliMethod) {
        val count = m.paramTypes.size
        for (i in count until m.paramNames.size) {
            if (m.paramNames[i] != null) {
                throw IllegalStateException("Метод ${m.name}: имя для несуществующего параметра #$i (параметров $count)")
            }
        }
        for (i in m.parameterAnnotations.keys) {
            if ((i < 0 || i >= count) && !m.parameterAnnotations[i].isNullOrEmpty()) {
                throw IllegalStateException("Метод ${m.name}: аннотации для несуществующего параметра #$i (параметров $count)")
            }
        }
        var reg = if ("static" in m.accessFlags) 0 else 1
        for (i in 0 until count) {
            val type = m.paramTypes[i]
            val name = m.paramNames.getOrNull(i)
            val anns = m.parameterAnnotations[i]
            if (name != null || !anns.isNullOrEmpty()) {
                sb.append("    .param p").append(reg)
                if (name != null) SmaliFormatter.appendQuoted(sb.append(", "), name)
                SmaliFormatter.appendType(sb.append("    # "), type).append('\n')
                if (!anns.isNullOrEmpty()) {
                    appendAnnotations(sb, anns, 8)
                    sb.append("    .end param\n")
                }
            }
            reg += if (SmaliFormatter.isWide(type)) 2 else 1
        }
    }

    private fun appendBody(sb: StringBuilder, m: SmaliMethod) {
        // .catch выводятся сразу после своей метки :try_end_ (как в baksmali).
        val catchesByEnd = LinkedHashMap<String, MutableList<SmaliCatchDirective>>()
        for (c in m.catches) catchesByEnd.getOrPut(c.tryEnd) { ArrayList(2) }.add(c)

        var pendingBlank = false
        for (el in m.body) {
            when (el) {
                is SmaliInsn -> {
                    if (pendingBlank) sb.append('\n')
                    val text = el.text
                    // Подсказки baksmali у литералов, похожих на float/double (только для чтения — разбор их отбросит)
                    if (text.startsWith(".array-data")) {
                        appendIndented(sb.append("    "), LiteralHints.arrayData(text), 4)
                    } else {
                        appendIndented(sb.append("    "), text, 4)
                        if (text.startsWith("const")) LiteralHints.forInsn(text)?.let { sb.append("    # ").append(it) }
                    }
                    sb.append('\n')
                    pendingBlank = true
                }
                is SmaliLabel -> {
                    val catches = catchesByEnd.remove(el.name)
                    if (pendingBlank && !(catches != null && el.name.startsWith(":try_end_"))) {
                        sb.append('\n')
                        pendingBlank = false
                    }
                    sb.append("    ").append(el.name).append('\n')
                    if (catches != null) for (c in catches) appendCatch(sb, c)
                }
                is SmaliDebug -> {
                    if (pendingBlank) {
                        sb.append('\n')
                        pendingBlank = false
                    }
                    sb.append("    ").append(el.raw).append('\n')
                }
            }
        }
        // Директивы, чья метка конца не найдена в теле, — в конец метода.
        for (list in catchesByEnd.values) for (c in list) appendCatch(sb, c)
    }

    private fun appendCatch(sb: StringBuilder, c: SmaliCatchDirective) {
        if (c.exceptionType == null) {
            sb.append("    .catchall {")
        } else {
            SmaliFormatter.appendType(sb.append("    .catch "), c.exceptionType).append(" {")
        }
        sb.append(c.tryStart).append(" .. ").append(c.tryEnd).append("} ").append(c.handler).append('\n')
    }

    private fun appendAnnotations(sb: StringBuilder, annotations: List<SmaliAnnotation>, indent: Int) {
        var first = true
        for (a in annotations) {
            if (!first) sb.append('\n')
            first = false
            appendIndent(sb, indent)
            if (a.isSub) sb.append(".subannotation ") else sb.append(".annotation ").append(a.visibility).append(' ')
            SmaliFormatter.appendType(sb, a.type).append('\n')
            for (e in a.elements) {
                appendIndent(sb, indent + 4)
                SmaliFormatter.appendSimpleName(sb, e.name).append(" = ")
                appendIndented(sb, e.value, indent + 4)
                sb.append('\n')
            }
            appendIndent(sb, indent)
            sb.append(if (a.isSub) ".end subannotation\n" else ".end annotation\n")
        }
    }

    /**
     * Многострочное значение (payload, массив, subannotation). Отступы строк продолжения
     * расставляются по структуре (`{`/`}`, `.subannotation`/`.end subannotation`, `.end <payload>`),
     * а не берутся из текста: так вывод одинаков и для дерева из DexToTree, и для дерева из SmaliParser.
     */
    private fun appendIndented(sb: StringBuilder, text: String, indent: Int) {
        var nl = text.indexOf('\n')
        if (nl < 0) {
            sb.append(text)
            return
        }
        sb.append(text, 0, nl)
        var depth = if (opensBlock(text.substring(0, nl).trim())) 1 else 0
        while (nl >= 0) {
            val start = nl + 1
            nl = text.indexOf('\n', start)
            val line = (if (nl < 0) text.substring(start) else text.substring(start, nl)).trim()
            sb.append('\n')
            if (line.isEmpty()) continue
            if (closesBlock(line) && depth > 0) depth--
            appendIndent(sb, indent + 4 * depth)
            sb.append(line)
            if (opensBlock(line)) depth++
        }
    }

    private fun opensBlock(line: String): Boolean =
        line.endsWith("{") || line.startsWith(".subannotation ") || SUBANNOTATION_ELEMENT.containsMatchIn(line) ||
            line.startsWith(".packed-switch") || line.startsWith(".sparse-switch") || line.startsWith(".array-data")

    private fun closesBlock(line: String): Boolean =
        line.startsWith("}") || line.startsWith(".end subannotation") ||
            line.startsWith(".end packed-switch") || line.startsWith(".end sparse-switch") || line.startsWith(".end array-data")

    /** `name = .subannotation Ltype;` — элемент, значение которого — вложенная аннотация. */
    private val SUBANNOTATION_ELEMENT = Regex("""^[^"\s]+ = \.subannotation """)

    private fun appendIndent(sb: StringBuilder, n: Int) {
        for (i in 0 until n) sb.append(' ')
    }

    private fun estimateSize(cls: SmaliClass): Int {
        var size = 256
        for (m in cls.methods) {
            size += 128
            for (el in m.body) {
                size += when (el) {
                    is SmaliInsn -> el.text.length + 10
                    is SmaliLabel -> el.name.length + 6
                    is SmaliDebug -> el.raw.length + 6
                }
            }
        }
        return size + cls.fields.size * 64
    }
}
