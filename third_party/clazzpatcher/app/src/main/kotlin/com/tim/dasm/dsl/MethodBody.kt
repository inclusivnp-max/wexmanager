package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.assembler.CodeResolver
import com.tim.dasm.assembler.DebugInfoBuilder
import com.tim.dasm.assembler.TryListBuilder
import com.tim.dasm.model.Instruction
import com.tim.dasm.model.TryBlock
import com.tim.dex.model.CatchHandler
import com.tim.dex.model.DebugInfoItem
import com.tim.dex.model.TryItem

/** Собранное тело метода. */
internal class BuiltBody(
    val instructions: List<Instruction>,
    val tries: List<TryItem>,
    val catchHandlers: List<CatchHandler>,
    val debugInfo: DebugInfoItem?
)

/**
 * Общее для [MethodDsl] и [MethodPatchDsl]: один [InstructionsDsl] на весь метод,
 * отладочная информация (привязана к позициям инструкций) и try/catch по меткам.
 */
internal class MethodBody(private val asm: DexAsm) {
    val code = InstructionsDsl(asm)
    val debug = DebugInfoBuilder(asm) { code.instructions.size }
    val tryBlocks = ArrayList<TryBlock>()

    fun build(parameterCount: Int): BuiltBody {
        val resolved = CodeResolver.resolve(code)

        val tryList = TryListBuilder()
        for (t in tryBlocks) {
            val start = resolved.labelOffset(t.startLabel)
            val end = resolved.labelOffset(t.endLabel)
            val handler = resolved.labelOffset(t.handlerLabel)
            val typeIdx = if (t.exceptionType == null) -1 else asm.types.add(t.exceptionType)
            try {
                tryList.addHandler(start, end, typeIdx, handler)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException(
                    "try {${t.startLabel} .. ${t.endLabel}} ${t.handlerLabel}: ${e.message}", e
                )
            }
        }
        val (tries, handlers) = tryList.build()

        val debugInfo = if (debug.hasData()) debug.build(resolved.offsets, parameterCount) else null
        return BuiltBody(resolved.instructions, tries, handlers, debugInfo)
    }
}
