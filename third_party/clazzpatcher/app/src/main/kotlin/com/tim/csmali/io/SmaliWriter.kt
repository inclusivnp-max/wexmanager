package com.tim.csmali.io

import com.tim.csmali.SmaliFormatter
import com.tim.csmali.model.*
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.*

object SmaliWriter {

    fun write(cls: SmaliClass): String {
        val sb = StringBuilder(estimateSize(cls))
        appendHeader(sb, cls)
        appendAnnotations(sb, cls.annotations, 0)
        appendTypeAnnotations(sb, cls.typeAnnotations, 0)
        appendFields(sb, cls)
        appendMethods(sb, cls)
        appendExtra(sb, cls)
        return sb.toString()
    }

    private fun appendHeader(sb: StringBuilder, cls: SmaliClass) {
        sb.append(".class ")
        appendFlags(sb, cls.accessFlags)
        SmaliFormatter.appendType(sb, cls.descriptor).append('\n')
        cls.superClass?.let { SmaliFormatter.appendType(sb.append(".super "), it).append('\n') }
        cls.sourceFile?.let { SmaliFormatter.appendQuoted(sb.append(".source "), it).append('\n') }
        sb.append(".version ").append(cls.majorVersion)
        if (cls.minorVersion != 0) sb.append(' ').append(cls.minorVersion)
        sb.append('\n')
        for (i in cls.interfaces) SmaliFormatter.appendType(sb.append(".implements "), i).append('\n')
        cls.signature?.let { SmaliFormatter.appendQuoted(sb.append(".signature "), it).append('\n') }
        cls.sourceDebugExtension?.let { SmaliFormatter.appendQuoted(sb.append(".sourcedebug "), it).append('\n') }
        appendRawAttributes(sb, cls.rawAttributes, 0)
    }

    private fun appendRawAttributes(sb: StringBuilder, attrs: List<SmaliRawAttribute>, indent: Int, directive: String = ".attribute ") {
        for (a in attrs) {
            appendIndent(sb, indent)
            SmaliFormatter.appendQuoted(sb.append(directive), a.name).append(", \"")
            for (b in a.data) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            }
            sb.append("\"\n")
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun appendAnnotations(sb: StringBuilder, annotations: List<SmaliAnnotation>, indent: Int) {
        for (a in annotations) {
            sb.append('\n')
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

    private fun appendTypeAnnotations(sb: StringBuilder, annotations: List<SmaliTypeAnnotation>, indent: Int) {
        for (ta in annotations) {
            sb.append('\n')
            appendIndent(sb, indent)
            sb.append(".typeannotation 0x").append(Integer.toHexString(ta.targetType)).append(' ')
            appendTargetInfo(sb, ta)
            for (e in ta.typePath.entries) {
                sb.append(' ').append(e.typePathKind).append(':').append(e.typeArgumentIndex)
            }
            sb.append('\n')
            appendIndent(sb, indent + 4)
            sb.append(".annotation ").append(ta.annotation.visibility).append(' ')
            SmaliFormatter.appendType(sb, ta.annotation.type).append('\n')
            for (e in ta.annotation.elements) {
                appendIndent(sb, indent + 8)
                SmaliFormatter.appendSimpleName(sb, e.name).append(" = ")
                appendIndented(sb, e.value, indent + 8)
                sb.append('\n')
            }
            appendIndent(sb, indent + 4)
            sb.append(".end annotation\n")
            appendIndent(sb, indent)
            sb.append(".end typeannotation\n")
        }
    }

    private fun appendTargetInfo(sb: StringBuilder, ta: SmaliTypeAnnotation) {
        when (val ti = ta.targetInfo) {
            is EmptyTarget -> sb.append("empty")
            is TypeParameterTarget -> sb.append("typeparam ").append(ti.typeParameterIndex)
            is SuperTypeTarget -> sb.append("supertype ").append(ti.supertypeIndex)
            is TypeParameterBoundTarget -> sb.append("typeparambound ")
                .append(ti.typeParameterIndex).append(' ').append(ti.boundIndex)
            is FormalParameterTarget -> sb.append("formalparam ").append(ti.formalParameterIndex)
            is ThrowsTarget -> sb.append("throws ").append(ti.throwsTypeIndex)
            is LocalvarTarget -> {
                sb.append("localvar")
                for (e in ti.entries) sb.append(' ').append(e.startPc).append(':').append(e.length)
                    .append(':').append(e.index)
            }
            is CatchTarget -> sb.append("catch ").append(ti.exceptionTableIndex)
            is OffsetTarget -> sb.append("offset ").append(ti.offset)
            is TypeArgumentTarget -> sb.append("typearg ").append(ti.offset).append(' ').append(ti.typeArgumentIndex)
            null -> sb.append("empty")
        }
    }

    private fun appendFields(sb: StringBuilder, cls: SmaliClass) {
        for (f in cls.fields) {
            sb.append('\n')
            sb.append(".field ")
            appendFlags(sb, f.accessFlags)
            SmaliFormatter.appendSimpleName(sb, f.name).append(':')
            SmaliFormatter.appendType(sb, f.type)
            f.initialValue?.let { appendIndented(sb.append(" = "), it, 0) }
            sb.append('\n')
            f.signature?.let {
                SmaliFormatter.appendQuoted(sb.append("    .signature "), it).append('\n')
            }
            appendRawAttributes(sb, f.rawAttributes, 4)
            appendAnnotations(sb, f.annotations, 4)
            appendTypeAnnotations(sb, f.typeAnnotations, 4)
            if (f.annotations.isNotEmpty() || f.typeAnnotations.isNotEmpty() || f.signature != null || f.rawAttributes.isNotEmpty()) {
                sb.append(".end field\n")
            }
        }
    }

    private fun appendMethods(sb: StringBuilder, cls: SmaliClass) {
        for (m in cls.methods) {
            sb.append('\n')
            sb.append(".method ")
            appendFlags(sb, m.accessFlags)
            SmaliFormatter.appendSimpleName(sb, m.name).append('(')
            for (p in m.paramTypes) SmaliFormatter.appendType(sb, p)
            sb.append(')')
            SmaliFormatter.appendType(sb, m.returnType).append('\n')

            if (m.maxStack >= 0) sb.append("    .limit stack ").append(m.maxStack).append('\n')
            if (m.maxLocals >= 0) sb.append("    .limit locals ").append(m.maxLocals).append('\n')

            m.signature?.let {
                SmaliFormatter.appendQuoted(sb.append("    .signature "), it).append('\n')
            }

            if (m.exceptions.isNotEmpty()) {
                sb.append("    .throws ")
                for ((i, e) in m.exceptions.withIndex()) {
                    if (i > 0) sb.append(", ")
                    SmaliFormatter.appendType(sb, e)
                }
                sb.append('\n')
            }

            appendRawAttributes(sb, m.rawAttributes, 4)
            appendRawAttributes(sb, m.rawCodeAttributes, 4, ".codeattribute ")
            appendAnnotations(sb, m.annotations, 4)
            appendParams(sb, m)
            appendTypeAnnotations(sb, m.typeAnnotations, 4)

            m.annotationDefault?.let {
                sb.append("    .annotation-default ")
                appendIndented(sb, it, 4)
                sb.append('\n')
            }

            if (m.body.isNotEmpty() || m.catches.isNotEmpty()) {
                sb.append('\n')
                appendBody(sb, m)
            }
            sb.append(".end method\n")
        }
    }

    /**
     * `.param pN[, "имя"|null][, 0xфлаги]`: запись MethodParameters (если есть) и аннотации параметра.
     * Регистр — слот JVM (у нестатического метода p0 — this).
     */
    private fun appendParams(sb: StringBuilder, m: SmaliMethod) {
        var slot = if ("static" in m.accessFlags) 0 else 1
        for ((i, type) in m.paramTypes.withIndex()) {
            val entry = m.methodParameters.getOrNull(i)
            val anns = m.parameterAnnotations[i]
            if (entry != null || !anns.isNullOrEmpty()) {
                sb.append("    .param p").append(slot)
                if (entry != null) {
                    sb.append(", ")
                    if (entry.name == null) sb.append("null") else SmaliFormatter.appendQuoted(sb, entry.name)
                    if (entry.flags != 0) sb.append(", 0x").append(Integer.toHexString(entry.flags))
                }
                SmaliFormatter.appendType(sb.append("    # "), type).append('\n')
                if (!anns.isNullOrEmpty()) {
                    appendAnnotations(sb, anns, 8)
                    sb.append("    .end param\n")
                }
            }
            slot += if (SmaliFormatter.isWide(type)) 2 else 1
        }
        if (m.methodParameters.size > m.paramTypes.size) {
            throw IllegalStateException("Метод ${m.name}: записей MethodParameters (${m.methodParameters.size}) больше, чем параметров")
        }
    }

    private fun appendBody(sb: StringBuilder, m: SmaliMethod) {
        var pendingBlank = false
        for (el in m.body) {
            when (el) {
                is SmaliInsn -> {
                    if (pendingBlank) sb.append('\n')
                    appendInsn(sb, el.text)
                    pendingBlank = true
                }
                is SmaliLabel -> {
                    if (pendingBlank) {
                        sb.append('\n')
                        pendingBlank = false
                    }
                    sb.append("    ").append(el.name).append('\n')
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
        // Порядок .catch = порядок таблицы исключений (приоритет обработчиков).
        if (m.catches.isNotEmpty()) sb.append('\n')
        for (c in m.catches) appendCatch(sb, c)
    }

    /** Инструкция; у switch — строки целей с отступом и `.end tableswitch`/`.end lookupswitch`. */
    private fun appendInsn(sb: StringBuilder, text: String) {
        var nl = text.indexOf('\n')
        if (nl < 0) {
            sb.append("    ").append(text).append('\n')
            return
        }
        sb.append("    ").append(text, 0, nl).append('\n')
        while (nl >= 0) {
            val start = nl + 1
            nl = text.indexOf('\n', start)
            val line = if (nl < 0) text.substring(start) else text.substring(start, nl)
            sb.append(if (line.startsWith(".end")) "    " else "        ").append(line).append('\n')
        }
    }

    private fun appendCatch(sb: StringBuilder, c: SmaliCatchDirective) {
        if (c.exceptionType == null) {
            sb.append("    .catchall {")
        } else {
            SmaliFormatter.appendType(sb.append("    .catch "), c.exceptionType).append(" {")
        }
        sb.append(c.tryStart).append(" .. ").append(c.tryEnd).append("} ").append(c.handler).append('\n')
    }

    private fun appendExtra(sb: StringBuilder, cls: SmaliClass) {
        if (cls.innerClasses.isNotEmpty()) {
            sb.append("\n# inner classes\n")
            for (ic in cls.innerClasses) {
                sb.append(".inner ")
                for (f in ic.flags) sb.append(f).append(' ')
                SmaliFormatter.appendType(sb, ic.innerInfo)
                if (ic.outerInfo != null || ic.innerName != null) {
                    sb.append(" of ")
                    ic.outerInfo?.let { SmaliFormatter.appendType(sb, it) } ?: sb.append("null")
                    if (ic.innerName != null) sb.append(" as ").append(ic.innerName)
                }
                sb.append('\n')
            }
        }
        cls.nestHost?.let { SmaliFormatter.appendType(sb.append("\n.nesthost "), it).append('\n') }
        if (cls.nestMembers.isNotEmpty()) {
            sb.append("\n# nest members\n")
            for (n in cls.nestMembers) SmaliFormatter.appendType(sb.append(".nestmember "), n).append('\n')
        }
        if (cls.permittedSubclasses.isNotEmpty()) {
            sb.append("\n# permitted subclasses\n")
            for (p in cls.permittedSubclasses) SmaliFormatter.appendType(sb.append(".permits "), p).append('\n')
        }
        if (cls.isRecord && cls.recordComponents.isEmpty()) sb.append("\n# record without components\n.record\n")
        if (cls.recordComponents.isNotEmpty()) {
            sb.append("\n# record components\n")
            for (rc in cls.recordComponents) {
                sb.append(".record-component ")
                SmaliFormatter.appendSimpleName(sb, rc.name).append(':')
                SmaliFormatter.appendType(sb, rc.descriptor)
                sb.append('\n')
                rc.signature?.let {
                    SmaliFormatter.appendQuoted(sb.append("    .signature "), it).append('\n')
                }
                appendAnnotations(sb, rc.annotations, 4)
                appendTypeAnnotations(sb, rc.typeAnnotations, 4)
                sb.append(".end record-component\n")
            }
        }
        cls.module?.let { appendModule(sb, it) }
        cls.enclosingMethod?.let { (outer, method) ->
            sb.append('\n')
            SmaliFormatter.appendType(sb.append(".enclosing "), outer)
            if (method != null) sb.append("->").append(method)
            sb.append('\n')
        }
    }

    private fun appendModule(sb: StringBuilder, m: SmaliModule) {
        sb.append("\n.module ").append(m.name).append('\n')
        m.version?.let { SmaliFormatter.appendQuoted(sb.append("    .version "), it).append('\n') }
        for (p in m.packages) sb.append("    .package ").append(p).append('\n')
        m.mainClass?.let { SmaliFormatter.appendType(sb.append("    .mainclass "), it).append('\n') }
        if (m.flags != 0) sb.append("    .flag 0x").append(Integer.toHexString(m.flags)).append('\n')
        for (r in m.requires) {
            sb.append("    .requires ").append(r.module)
            if (r.flags != 0) sb.append(" flag 0x").append(Integer.toHexString(r.flags))
            r.version?.let { sb.append(" version ").append(it) }
            sb.append('\n')
        }
        for (e in m.exports) {
            sb.append("    .exports ").append(e.pkg)
            if (e.flags != 0) sb.append(" flag 0x").append(Integer.toHexString(e.flags))
            if (e.to.isNotEmpty()) sb.append(" to ").append(e.to.joinToString(" "))
            sb.append('\n')
        }
        for (o in m.opens) {
            sb.append("    .opens ").append(o.pkg)
            if (o.flags != 0) sb.append(" flag 0x").append(Integer.toHexString(o.flags))
            if (o.to.isNotEmpty()) sb.append(" to ").append(o.to.joinToString(" "))
            sb.append('\n')
        }
        for (u in m.uses) sb.append("    .uses ").append(u).append('\n')
        for (p in m.provides) {
            sb.append("    .provides ").append(p.service).append(" with ")
                .append(p.with.joinToString(" ")).append('\n')
        }
        sb.append(".end module\n")
    }

    private fun appendFlags(sb: StringBuilder, flags: List<String>) {
        for (f in flags) sb.append(f).append(' ')
    }

    private fun appendIndent(sb: StringBuilder, n: Int) {
        for (i in 0 until n) sb.append(' ')
    }

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
        line.endsWith("{") ||
            line.startsWith(".subannotation ") ||
            SUBANNOTATION_ELEMENT.containsMatchIn(line)

    private fun closesBlock(line: String): Boolean =
        line.startsWith("}") || line.startsWith(".end subannotation")

    private val SUBANNOTATION_ELEMENT = Regex("""^[^"\s]+ = \.subannotation """)

    private fun estimateSize(cls: SmaliClass): Int {
        var size = 512
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
        return size + cls.fields.size * 96
    }
}