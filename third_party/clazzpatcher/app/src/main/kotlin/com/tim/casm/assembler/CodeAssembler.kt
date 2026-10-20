package com.tim.casm.assembler

import com.tim.casm.dsl.CodeDsl
import com.tim.casm.frames.ComputedTry
import com.tim.casm.frames.FrameComputer
import com.tim.casm.frames.FrameMode
import com.tim.casm.frames.MethodShape
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.model.ExceptionTableEntry
import com.tim.casm.model.LineNumberEntry
import com.tim.casm.model.LocalVariableEntry
import com.tim.casm.model.RawStackMapFrame
import com.tim.casm.pool.ConstantPoolBuilder

/**
 * Сборка тела метода: метки → pc, затем расчёт max_stack/max_locals и StackMapTable
 * по готовому байткоду ([FrameComputer]).
 */
object CodeAssembler {

    class Assembled(
        val code: ByteArray,
        val maxStack: Int,
        val maxLocals: Int,
        val exceptionTable: List<ExceptionTableEntry>,
        val lineNumbers: List<LineNumberEntry>,
        val localVariables: List<LocalVariableEntry>,
        val stackMapFrames: List<RawStackMapFrame>
    )

    /** [exceptionType] — внутреннее имя класса, `null` — любое исключение. */
    class TrySpec(val startLabel: String, val endLabel: String, val handlerLabel: String, val exceptionType: String?)

    /** [signature] — для LocalVariableTypeTable (обобщённый тип), `null` — нет. */
    class LocalVarSpec(
        val startLabel: String,
        val endLabel: String,
        val name: String,
        val descriptor: String,
        val index: Int,
        val signature: String? = null
    )

    /** Параметры сборки: запрошенные лимиты (-1 — вычислить), режим фреймов по версии класса. */
    class Options(
        val maxStack: Int = -1,
        val maxLocals: Int = -1,
        val frames: FrameMode = FrameMode.REQUIRED,
        val hierarchy: ClassHierarchy = ClassHierarchy.EMPTY
    ) {
        /** [withFrames]: `true` — фреймы обязательны (версия ≥ 51), `false` — не строятся. */
        constructor(maxStack: Int, maxLocals: Int, withFrames: Boolean, hierarchy: ClassHierarchy = ClassHierarchy.EMPTY) :
            this(maxStack, maxLocals, if (withFrames) FrameMode.REQUIRED else FrameMode.NONE, hierarchy)
    }

    fun assemble(
        dsl: CodeDsl,
        pool: ConstantPoolBuilder,
        method: MethodShape,
        trySpecs: List<TrySpec> = emptyList(),
        lineNumbers: List<Pair<String, Int>> = emptyList(),
        localVariables: List<LocalVarSpec> = emptyList(),
        options: Options = Options()
    ): Assembled {
        val where = "Метод ${method.owner}.${method.name}${method.descriptor}"
        val resolved = try {
            LabelResolver.resolve(dsl)
        } catch (e: Exception) {
            throw IllegalStateException("$where: ${e.message}", e)
        }
        fun pc(label: String): Int = resolved.pc(label)
            ?: throw IllegalStateException("$where: неизвестная метка $label")

        val tries = trySpecs.map {
            val start = pc(it.startLabel)
            val end = pc(it.endLabel)
            if (end <= start) throw IllegalStateException("$where: пустой диапазон try {${it.startLabel} .. ${it.endLabel}}")
            ComputedTry(start, end, pc(it.handlerLabel), it.exceptionType)
        }

        val computed = FrameComputer.compute(
            resolved.code, tries, pool::get, options.hierarchy, method, options.frames
        )
        val codeSize = computed.code.size
        // Строка после последней инструкции ни к чему не относится (start_pc < code_length).
        val lines = lineNumbers.mapNotNull { (label, line) ->
            val at = pc(label)
            if (at < codeSize) LineNumberEntry(at, line) else null
        }
        val locals = localVariables.map {
            val start = pc(it.startLabel)
            LocalVariableEntry(start, pc(it.endLabel) - start, it.name, it.descriptor, it.index, it.signature)
        }
        return Assembled(
            code = computed.code,
            maxStack = maxOf(computed.maxStack, options.maxStack),
            maxLocals = maxOf(computed.maxLocals, options.maxLocals),
            exceptionTable = computed.tries.map { ExceptionTableEntry(it.start, it.end, it.handler, it.catchType) },
            lineNumbers = lines,
            localVariables = locals,
            stackMapFrames = computed.frames
        )
    }
}
