package org.wexclient.app.dex

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.jf.dexlib2.ReferenceType
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.VariableRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload
import org.jf.dexlib2.iface.instruction.formats.PackedSwitchPayload
import org.jf.dexlib2.iface.instruction.formats.SparseSwitchPayload
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodProtoReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.util.concurrent.ConcurrentHashMap

// --- Модель --------------------------------------------------------------------

enum class GhidraLineKind { METHOD_HEADER, TRY_HEADER, INSTRUCTION, NOTE }

sealed interface GhidraRefTarget {
    /** Переход внутри метода (ветвление) — адрес в code units. */
    data class Address(val address: Int) : GhidraRefTarget

    /** Вызов метода — имя класса (точки) + ключ метода. */
    data class Method(val className: String, val methodKey: String) : GhidraRefTarget
}

data class GhidraLine(
    /** Адрес в code units (16-бит). Байтовый = address * 2. */
    val address: Int,
    val bytesHex: String,
    val mnemonic: String,
    val operands: String,
    val comment: String,
    val kind: GhidraLineKind = GhidraLineKind.INSTRUCTION,
    val refTarget: GhidraRefTarget? = null
)

data class GhidraMethodListing(
    val className: String,
    val methodKey: String,
    val methodSignature: String,
    val lines: List<GhidraLine>,
    /** адрес (units) -> индекс строки, для прокрутки к меткам. */
    val addrToIndex: Map<Int, Int>
)

/** Кто вызывает метод: класс, ключ метода, адрес вызова (units). */
data class GhidraXref(val callerClass: String, val callerMethodKey: String, val address: Int)

// --- Построение ----------------------------------------------------------------

internal fun prettyType(descriptor: String): String {
    var arr = 0
    var s = descriptor
    while (s.startsWith("[")) {
        arr++
        s = s.substring(1)
    }
    val base = if (s.startsWith("L") && s.endsWith(";")) {
        s.substring(1, s.length - 1).replace('/', '.')
    } else when (s) {
        "Z" -> "boolean"
        "B" -> "byte"
        "S" -> "short"
        "C" -> "char"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        "V" -> "void"
        else -> s
    }
    return base + "[]".repeat(arr)
}

internal fun accessString(flags: Int): String {
    val parts = ArrayList<String>()
    if (flags and 0x1 != 0) parts.add("public")
    if (flags and 0x2 != 0) parts.add("private")
    if (flags and 0x4 != 0) parts.add("protected")
    if (flags and 0x8 != 0) parts.add("static")
    if (flags and 0x10 != 0) parts.add("final")
    if (flags and 0x20 != 0) parts.add("synchronized")
    if (flags and 0x40 != 0) parts.add("bridge")
    if (flags and 0x80 != 0) parts.add("varargs")
    if (flags and 0x100 != 0) parts.add("native")
    if (flags and 0x400 != 0) parts.add("abstract")
    if (flags and 0x800 != 0) parts.add("strictfp")
    if (flags and 0x1000 != 0) parts.add("synthetic")
    if (flags and 0x10000 != 0) parts.add("constructor")
    return parts.joinToString(" ")
}

internal fun methodKeyOf(method: Method): String {
    val params = method.parameters.joinToString(",") { it.type }
    return "${method.name}($params)${method.returnType}"
}

internal fun methodKeyOf(ref: MethodReference): String {
    val params = ref.parameterTypes.joinToString(",") { it.toString() }
    return "${ref.name}($params)${ref.returnType}"
}

internal fun methodReadable(ref: MethodReference): String {
    val cls = prettyType(ref.definingClass)
    val params = ref.parameterTypes.joinToString(", ") { prettyType(it.toString()) }
    return "$cls->${ref.name}($params)${prettyType(ref.returnType)}"
}

private fun regsOf(insn: Instruction): String? = when (insn) {
    is FiveRegisterInstruction -> {
        val all = listOf(
            insn.registerC, insn.registerD, insn.registerE,
            insn.registerF, insn.registerG
        )
        "{" + all.take(insn.registerCount).joinToString(", ") { "v$it" } + "}"
    }
    is RegisterRangeInstruction -> {
        val s = insn.startRegister
        val e = s + insn.registerCount - 1
        if (insn.registerCount <= 1) "{v$s}" else "{v$s .. v$e}"
    }
    is ThreeRegisterInstruction -> "v${insn.registerA}, v${insn.registerB}, v${insn.registerC}"
    is TwoRegisterInstruction -> "v${insn.registerA}, v${insn.registerB}"
    is OneRegisterInstruction -> "v${insn.registerA}"
    else -> null
}

private fun literalOf(insn: Instruction): String? = when (insn) {
    is NarrowLiteralInstruction -> insn.wideLiteral.toInt().toString()
    is WideLiteralInstruction -> insn.wideLiteral.toString()
    else -> null
}

private data class Formatted(val operands: String, val comment: String, val target: GhidraRefTarget?)

private fun formatReference(ref: Any, typeNames: Boolean = true): Triple<String, String, GhidraRefTarget?> {
    return when (ref) {
        is StringReference -> {
            val raw = ref.string
            val shown = raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .take(80)
            Triple("\"$shown\"", "", null)
        }
        is TypeReference -> Triple(prettyType(ref.type), "", null)
        is FieldReference -> Triple(
            "${prettyType(ref.definingClass)}->${ref.name}:${prettyType(ref.type)}",
            "",
            null
        )
        is MethodReference -> Triple(
            methodReadable(ref),
            "",
            GhidraRefTarget.Method(DexEditorEngine.cleanDescriptor(ref.definingClass), methodKeyOf(ref))
        )
        is MethodProtoReference -> {
            val params = ref.parameterTypes.joinToString(", ") { prettyType(it.toString()) }
            Triple("($params)${prettyType(ref.returnType)}", "", null)
        }
        else -> Triple(ref.toString(), "", null)
    }
}

private fun mnemonicOf(opcodeName: String): String = when (opcodeName.lowercase()) {
    "array-payload" -> "array-data"
    "packed-switch-payload" -> "packed-switch-payload"
    "sparse-switch-payload" -> "sparse-switch-payload"
    else -> opcodeName.lowercase().replace('_', '-')
}

private fun formatInstruction(insn: Instruction, address: Int, lastSwitchAddr: Int): Formatted {
    // Псевдо-инструкции полезной нагрузки — отдельно
    if (insn is ArrayPayload) {
        val els = insn.arrayElements.take(8).joinToString(", ")
        val more = if (insn.arrayElements.size > 8) " …(+${insn.arrayElements.size - 8})" else ""
        return Formatted(
            "width=${insn.elementWidth} [$els$more]",
            "${insn.arrayElements.size} элементов",
            null
        )
    }
    if (insn is PackedSwitchPayload || insn is SparseSwitchPayload) {
        val els: List<Pair<Int, Int>> = when (insn) {
            is PackedSwitchPayload -> insn.switchElements.map { it.key to it.offset }
            is SparseSwitchPayload -> insn.switchElements.map { it.key to it.offset }
            else -> emptyList()
        }
        val shown = els.take(4).joinToString(", ") { (k, o) ->
            "$k -> 0x${Integer.toHexString((lastSwitchAddr + o) * 2).uppercase()}"
        }
        val more = if (els.size > 4) " …(+${els.size - 4})" else ""
        return Formatted("{$shown$more}", "${els.size} веток", null)
    }

    val regs = regsOf(insn)
    var secondary: String? = null
    var comment = ""
    var target: GhidraRefTarget? = null

    if (insn is ReferenceInstruction) {
        val ref = try {
            insn.reference
        } catch (_: Exception) {
            null
        }
        if (ref != null) {
            val (display, _, refTarget) = formatReference(ref)
            secondary = display
            // Навигация только для invoke-* на методы; у остальных просто текст
            // (Opcode.name уже в нижнем регистре: "invoke-static")
            val isInvoke = insn.opcode.name.startsWith("invoke-")
            target = if (isInvoke && refTarget is GhidraRefTarget.Method &&
                insn.referenceType == ReferenceType.METHOD
            ) {
                refTarget
            } else null
        }
    }
    if (secondary == null) {
        literalOf(insn)?.let { lit ->
            secondary = lit
            lit.toLongOrNull()?.let { v ->
                if (v > 9 || v < 0) comment = "0x" + java.lang.Long.toHexString(v).uppercase()
            }
        }
    }
    if (secondary == null && insn is OffsetInstruction) {
        val dest = address + insn.codeOffset
        secondary = "0x" + Integer.toHexString(dest * 2).uppercase().padStart(4, '0')
        comment = "переход"
        target = GhidraRefTarget.Address(dest)
    }

    val operands = listOfNotNull(regs, secondary).joinToString(", ")
    return Formatted(operands, comment, target)
}

private fun bytesHexOf(insn: Instruction): String {
    return try {
        val backed = insn as? DexBackedInstruction ?: return "${insn.codeUnits * 2}B"
        val buf = backed.dexFile.buffer.buf
        val start = backed.instructionStart
        val total = insn.codeUnits * 2
        val show = minOf(total, 16)
        if (start < 0 || start + show > buf.size) return "${total}B"
        val hex = (0 until show).joinToString(" ") { "%02X".format(buf[start + it]) }
        if (total > show) "$hex …" else hex
    } catch (_: Exception) {
        "${insn.codeUnits * 2}B"
    }
}

internal fun buildMethodListing(
    className: String,
    method: Method
): GhidraMethodListing {
    val mkey = methodKeyOf(method)
    val lines = ArrayList<GhidraLine>()
    val addrToIndex = HashMap<Int, Int>()
    val params = method.parameters.joinToString(", ") { prettyType(it.type) }
    val sig = "${method.name}($params)${prettyType(method.returnType)}"
    val access = accessString(method.accessFlags)

    fun header(text: String) {
        lines.add(GhidraLine(-1, "", "", text, "", GhidraLineKind.METHOD_HEADER, null))
    }

    header(".method $access $sig".trim())
    val impl = try {
        method.implementation
    } catch (_: Exception) {
        null
    }
    if (impl == null) {
        header("<без кода: abstract / native / interface>")
        return GhidraMethodListing(className, mkey, sig, lines, addrToIndex)
    }
    header(".registers ${impl.registerCount}")

    try {
        for (tb in impl.tryBlocks) {
            val s = tb.startCodeAddress
            val e = s + tb.codeUnitCount
            for (h in tb.exceptionHandlers) {
                val t = h.exceptionType?.let { prettyType(it) } ?: "all"
                val ha = h.handlerCodeAddress
                lines.add(
                    GhidraLine(
                        -1, "", "try",
                        "{0x${Integer.toHexString(s * 2).uppercase()}..0x${Integer.toHexString(e * 2).uppercase()}}",
                        "catch $t -> 0x${Integer.toHexString(ha * 2).uppercase()}",
                        GhidraLineKind.TRY_HEADER, null
                    )
                )
            }
        }
    } catch (_: Exception) {
        // try-блоки не критичны
    }

    var addr = 0
    var lastSwitchAddr = -1
    val insns = try {
        impl.instructions.toList()
    } catch (_: Exception) {
        emptyList()
    }
    for (insn in insns) {
        val opName = try {
            insn.opcode.name
        } catch (_: Exception) {
            "UNKNOWN"
        }
        if (opName == "packed-switch" || opName == "sparse-switch") lastSwitchAddr = addr
        val units = try {
            insn.codeUnits
        } catch (_: Exception) {
            1
        }
        val f = try {
            formatInstruction(insn, addr, lastSwitchAddr)
        } catch (_: Exception) {
            Formatted("", "!? ошибка формата", null)
        }
        addrToIndex[addr] = lines.size
        lines.add(
            GhidraLine(
                address = addr,
                bytesHex = try {
                    bytesHexOf(insn)
                } catch (_: Exception) {
                    ""
                },
                mnemonic = mnemonicOf(opName),
                operands = f.operands,
                comment = f.comment,
                kind = GhidraLineKind.INSTRUCTION,
                refTarget = f.target
            )
        )
        addr += if (units <= 0) 1 else units
    }
    return GhidraMethodListing(className, mkey, sig, lines, addrToIndex)
}

// --- Движок: навигация и XREF ---------------------------------------------------

/** Ключ метода для навигации и XREF. Публичный — нужен UI. */
fun DexEditorEngine.methodKey(method: Method): String = methodKeyOf(method)

/**
 * Кто вызывает метод. Полный скан DEX — дорого на больших файлах,
 * поэтому suspend (отменяется при смене метода) + кэш на движок.
 */
suspend fun DexEditorEngine.findCallers(
    targetClass: String,
    targetMethodKey: String
): List<GhidraXref> {
    val cacheKey = "$targetClass#$targetMethodKey"
    ghidraXrefCache[cacheKey]?.let { return it }
    // Один скан за раз: два параллельных прохода по 30k классов ни к чему
    return ghidraXrefMutex.withLock {
        ghidraXrefCache[cacheKey]?.let { return it }
        val out = ArrayList<GhidraXref>()
        var scanned = 0
        for (c in classes) {
            for (m in c.classDef.methods) {
                val impl = try {
                    m.implementation
                } catch (_: Exception) {
                    continue
                } ?: continue
                var addr = 0
                try {
                    for (insn in impl.instructions) {
                        if (insn is ReferenceInstruction &&
                            insn.opcode.name.startsWith("invoke-")
                        ) {
                            val ref = try {
                                insn.reference
                            } catch (_: Exception) {
                                null
                            }
                            if (ref is MethodReference &&
                                insn.referenceType == ReferenceType.METHOD &&
                                DexEditorEngine.cleanDescriptor(ref.definingClass) == targetClass &&
                                methodKeyOf(ref) == targetMethodKey
                            ) {
                                out.add(GhidraXref(c.fullName, methodKeyOf(m), addr))
                            }
                        }
                        addr += try {
                            insn.codeUnits
                        } catch (_: Exception) {
                            1
                        }
                    }
                } catch (_: Exception) {
                    // битый метод пропускаем
                }
                if (++scanned % 500 == 0) yield()
            }
        }
        ghidraXrefCache[cacheKey] = out
        out
    }
}

/** Листинг одного метода класса. */
fun DexEditorEngine.buildGhidraListing(
    classNode: DexClassNode,
    method: Method
): GhidraMethodListing = buildMethodListing(classNode.fullName, method)
