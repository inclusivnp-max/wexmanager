package org.wexclient.app.apk

import kotlinx.coroutines.CancellationException
import java.io.IOException
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction

/**
 * Слепок класса: имя, набор методов и полей и общий отпечаток тела.
 * По отпечатку видно, что класс переписали, даже если список методов остался прежним.
 */
data class DexClassSignature(
    val name: String,
    val fingerprint: Long,
    val methods: Set<String>,
    val fields: Set<String>
)

/** Что случилось с одним классом между версиями. */
data class DexClassChange(
    val name: String,
    val kind: DiffKind,
    val addedMethods: List<String> = emptyList(),
    val removedMethods: List<String> = emptyList(),
    val addedFields: List<String> = emptyList(),
    val removedFields: List<String> = emptyList()
) {
    /** Изменения без добавления или удаления методов и полей. */
    val bodyOnly: Boolean
        get() = kind == DiffKind.CHANGED && addedMethods.isEmpty() && removedMethods.isEmpty() &&
            addedFields.isEmpty() && removedFields.isEmpty()
}

/** Итог сравнения кода двух APK. */
data class DexDiff(
    val classes: List<DexClassChange>,
    val oldClasses: Int,
    val newClasses: Int,
    val oldMethods: Int,
    val newMethods: Int
) {
    val added: Int get() = classes.count { it.kind == DiffKind.ADDED }
    val removed: Int get() = classes.count { it.kind == DiffKind.REMOVED }
    val changed: Int get() = classes.count { it.kind == DiffKind.CHANGED }
}

/**
 * Сравнение кода: классы, методы и поля из всех classes*.dex.
 * Отпечаток строится по инструкциям, ссылкам, полям и настройкам сравнения.
 */
object ApkDexDiff {

    /**
     * Слепки классов из всех DEX. Повреждённый DEX прерывает сравнение с ошибкой.
     * Отмена проверяется перед каждым DEX и между классами.
     */
    fun read(
        dexes: List<ByteArray>,
        options: DexCompareOptions = DexCompareOptions(),
        cancelled: () -> Boolean = { false }
    ): Map<String, DexClassSignature> {
        val result = LinkedHashMap<String, DexClassSignature>()
        for ((index, bytes) in dexes.withIndex()) {
            if (cancelled()) throw CancellationException("Сравнение отменено")
            try {
                val file = DexBackedDexFile(Opcodes.getDefault(), bytes)
                for (cls in file.classes) {
                    if (cancelled()) throw CancellationException("Сравнение отменено")
                    val sig = signatureOf(cls, options)
                    // При дублировании класса в нескольких DEX берётся первый.
                    result.putIfAbsent(sig.name, sig)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Не удалось прочитать DEX #${index + 1}: ${e.message}", e)
            }
        }
        return result
    }

    fun compare(old: Map<String, DexClassSignature>, new: Map<String, DexClassSignature>): DexDiff {
        val names = (old.keys + new.keys).toSortedSet()
        val changes = ArrayList<DexClassChange>()
        for (name in names) {
            val o = old[name]
            val n = new[name]
            when {
                o == null && n != null -> changes += DexClassChange(name, DiffKind.ADDED)
                n == null && o != null -> changes += DexClassChange(name, DiffKind.REMOVED)
                o != null && n != null && o.fingerprint != n.fingerprint -> changes += DexClassChange(
                    name = name,
                    kind = DiffKind.CHANGED,
                    addedMethods = (n.methods - o.methods).sorted(),
                    removedMethods = (o.methods - n.methods).sorted(),
                    addedFields = (n.fields - o.fields).sorted(),
                    removedFields = (o.fields - n.fields).sorted()
                )
            }
        }
        // Сначала изменённые (у них есть что смотреть), потом новые и пропавшие
        val sorted = changes.sortedWith(compareBy({ order(it.kind) }, { it.name }))
        return DexDiff(
            classes = sorted,
            oldClasses = old.size,
            newClasses = new.size,
            oldMethods = old.values.sumOf { it.methods.size },
            newMethods = new.values.sumOf { it.methods.size }
        )
    }

    private fun order(kind: DiffKind): Int = when (kind) {
        DiffKind.CHANGED -> 0
        DiffKind.ADDED -> 1
        DiffKind.REMOVED -> 2
        DiffKind.SAME -> 3
    }

    /** `Lcom/example/Foo;` → `com.example.Foo` */
    fun readableName(descriptor: String): String =
        descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun signatureOf(cls: ClassDef, options: DexCompareOptions): DexClassSignature {
        var hash = FNV_OFFSET
        hash = mix(hash, cls.type)
        hash = mix(hash, cls.superclass ?: "")
        cls.interfaces.sorted().forEach { hash = mix(hash, it) }
        hash = mix(hash, cls.accessFlags.toString())

        val methods = LinkedHashSet<String>()
        for (m in cls.methods) {
            val key = methodKey(m.name, m.parameterTypes.map { it.toString() }, m.returnType)
            methods += key
            hash = mix(hash, key)
            hash = mix(hash, m.accessFlags.toString())

            val impl = m.implementation ?: continue
            // Тело метода: каждая инструкция приводится к виду, заданному настройками сравнения
            val body = impl.instructions.map { instructionKey(it, options) }
            val ordered = if (options.ignoreInstructionOrder) body.sorted() else body
            ordered.forEach { hash = mix(hash, it) }
            if (!options.ignoreDebugInfo) {
                impl.debugItems.forEach { hash = mix(hash, it.debugItemType.toString()) }
            }
        }

        val fields = LinkedHashSet<String>()
        for (f in cls.fields) {
            val key = "${f.name}: ${prettyType(f.type)}"
            fields += key
            hash = mix(hash, key)
            hash = mix(hash, f.accessFlags.toString())
            hash = mix(hash, f.initialValue?.toString() ?: "")
        }

        return DexClassSignature(readableName(cls.type), hash, methods, fields)
    }

    /**
     * Одна инструкция в виде строки: опкод, при необходимости регистры, число и ссылка.
     * Настройки решают, что из этого попадёт в отпечаток.
     */
    fun instructionKey(inst: Instruction, options: DexCompareOptions): String {
        val sb = StringBuilder(32)
        val name = inst.opcode.name ?: "?"
        sb.append(if (options.ignoreCompilerOptimization) SmaliNormalizer.normalizeOpcode(name) else name)

        if (!options.ignoreRegisters) {
            when (inst) {
                is FiveRegisterInstruction -> sb.append(" v").append(inst.registerC)
                    .append(",v").append(inst.registerD).append(",v").append(inst.registerE)
                    .append(",v").append(inst.registerF).append(",v").append(inst.registerG)
                is RegisterRangeInstruction -> sb.append(" v").append(inst.startRegister)
                    .append("..").append(inst.registerCount)
                is ThreeRegisterInstruction -> sb.append(" v").append(inst.registerA)
                    .append(",v").append(inst.registerB).append(",v").append(inst.registerC)
                is TwoRegisterInstruction -> sb.append(" v").append(inst.registerA)
                    .append(",v").append(inst.registerB)
                is OneRegisterInstruction -> sb.append(" v").append(inst.registerA)
                else -> Unit
            }
        }

        if (inst is WideLiteralInstruction) sb.append(' ').append(literalKey(inst.wideLiteral, options))
        if (inst is OffsetInstruction && !options.ignoreInstructionOrder) {
            sb.append(" @").append(inst.codeOffset)
        }
        if (inst is ArrayPayload) {
            sb.append(" width=").append(inst.elementWidth)
            inst.arrayElements.forEach { sb.append(' ').append(literalKey(it.toLong(), options)) }
        }
        if (inst is SwitchPayload) {
            inst.switchElements.forEach { element ->
                sb.append(" case=").append(element.key)
                if (!options.ignoreInstructionOrder) sb.append('@').append(element.offset)
            }
        }
        if (inst is ReferenceInstruction) sb.append(' ').append(inst.reference.toString())
        return sb.toString()
    }

    private fun literalKey(value: Long, options: DexCompareOptions): String =
        if (options.ignoreResourceIds && SmaliNormalizer.isResourceId(value)) "#res" else value.toString()

    /** `foo(java.lang.String, int): void` — по такому ключу метод узнаётся между версиями. */
    fun methodKey(name: String, params: List<String>, returnType: String): String =
        name + "(" + params.joinToString(", ") { prettyType(it) } + "): " + prettyType(returnType)

    /** Читаемый тип: `Ljava/lang/String;` → `java.lang.String`, `[I` → `int[]`. */
    fun prettyType(descriptor: String): String {
        var depth = 0
        var d = descriptor
        while (d.startsWith("[")) {
            depth++
            d = d.substring(1)
        }
        val base = when (d) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> readableName(d)
        }
        return base + "[]".repeat(depth)
    }

    private fun mix(hash: Long, text: String): Long {
        var h = hash
        for (ch in text) {
            h = h xor ch.code.toLong()
            h *= FNV_PRIME
        }
        return h
    }

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L
}
