package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.model.MethodPatch
import com.tim.dasm.model.TryBlock
import com.tim.dex.model.DebugInfoItem

/**
 * Новое тело существующего метода.
 *
 * Метод ищется по имени ([paramTypes]/[returnType] = `null`) или по полной сигнатуре.
 * Прежние позиции/локальные переменные отладочной информации относятся к старому коду
 * и отбрасываются; имена параметров сохраняются, если не заданы новые.
 */
class MethodPatchDsl(
    private val methodName: String,
    private val asm: DexAsm,
    internal val paramTypes: List<String>? = null,
    internal val returnType: String? = null
) {
    private val body = MethodBody(asm)
    /** Число регистров; -1 — вычислить по использованным. */
    var registers: Int = -1
        private set

    val instructions get() = body.code.instructions

    internal val methodNameValue: String get() = methodName

    /** Число регистров; если не задано — вычисляется по использованным (не меньше ins_size). */
    fun registers(count: Int) {
        require(count >= 0) { "Отрицательное число регистров: $count" }
        registers = count
    }

    fun code(block: InstructionsDsl.() -> Unit) {
        body.code.block()
    }

    // ---- debug ----

    fun paramNames(vararg names: String?) = body.debug.paramNames(*names)
    fun paramName(index: Int, name: String?) = body.debug.setParamName(index, name)
    fun prologueEnd() = body.debug.prologueEnd()
    fun epilogueBegin() = body.debug.epilogueBegin()
    fun lineNumber(line: Int) = body.debug.lineNumber(line)
    fun startLocal(reg: Int, name: String?, type: String?, sig: String? = null) =
        body.debug.startLocal(reg, name, type, sig)
    fun endLocal(reg: Int) = body.debug.endLocal(reg)
    fun restartLocal(reg: Int) = body.debug.restartLocal(reg)

    // ---- try / catch ----

    fun tryCatch(start: String, end: String, handler: String, exceptionType: String) {
        body.tryBlocks.add(TryBlock(start, end, handler, exceptionType))
    }

    fun catchAll(start: String, end: String, handler: String) {
        body.tryBlocks.add(TryBlock(start, end, handler, null))
    }

    /**
     * @param oldParamNames имена параметров из прежней отладочной информации
     *                      (используются, если патч не задаёт своих).
     */
    internal fun buildBody(parameterCount: Int, oldParamNames: List<String?>): Pair<MethodPatch, DebugInfoItem?> {
        require(body.code.instructions.isNotEmpty()) { "$methodName: пустое тело метода" }
        if (!body.debug.hasParamNames()) {
            oldParamNames.forEachIndexed { i, n -> if (i < parameterCount) body.debug.setParamName(i, n) }
        }
        val built = body.build(parameterCount)
        return MethodPatch(
            name = methodName,
            instructions = built.instructions,
            registers = registers,
            paramTypes = paramTypes,
            returnType = returnType,
            tries = built.tries,
            catchHandlers = built.catchHandlers
        ) to built.debugInfo
    }
}
