package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.assembler.CodeItemBuilder
import com.tim.dex.model.EncodedMethod

class ClassPatchDsl(
    private val className: String,
    private val asm: DexAsm
) {
    private val methodPatches = mutableListOf<MethodPatchDsl>()

    /** Заменить тело метода по имени (имя должно быть однозначным среди методов класса). */
    fun method(name: String, block: MethodPatchDsl.() -> Unit) {
        val dsl = MethodPatchDsl(name, asm)
        dsl.block()
        methodPatches.add(dsl)
    }

    /** Заменить тело метода по полной сигнатуре: имя + типы параметров + тип результата. */
    fun method(name: String, paramTypes: List<String>, returnType: String, block: MethodPatchDsl.() -> Unit) {
        val dsl = MethodPatchDsl(name, asm, paramTypes.toList(), returnType)
        dsl.block()
        methodPatches.add(dsl)
    }

    internal fun apply() {
        val classDefIdx = asm.classes.find(className)
            ?: throw IllegalStateException("Класс не найден: $className")

        for (patch in methodPatches) {
            val classDef = asm.state.classDefs[classDefIdx]
            val cd = asm.classData.get(classDef.classDataIdx)
                ?: throw IllegalStateException("У класса $className нет class_data")

            val all = cd.directMethods + cd.virtualMethods
            val name = patch.methodNameValue
            val candidates = all.filter { m ->
                val id = asm.state.methodIds[m.methodIdx]
                if (asm.strings.get(id.nameIdx) != name) return@filter false
                if (patch.paramTypes == null) return@filter true
                asm.protos.getParamTypes(id.protoIdx) == patch.paramTypes &&
                    asm.protos.getReturnType(id.protoIdx) == patch.returnType
            }
            val signature = describe(name, patch.paramTypes, patch.returnType)
            val encoded: EncodedMethod = when (candidates.size) {
                0 -> throw IllegalStateException("Метод не найден: $className->$signature")
                1 -> candidates[0]
                else -> throw IllegalStateException(
                    "Метод $className->$name неоднозначен (${candidates.size} перегрузки): укажите сигнатуру"
                )
            }
            val oldCode = asm.codes.get(encoded.codeIdx)
                ?: throw IllegalStateException("У метода $className->$signature нет кода (abstract/native)")

            val methodId = asm.state.methodIds[encoded.methodIdx]
            val paramCount = asm.protos.getParamTypes(methodId.protoIdx).size
            val oldNames = asm.debugInfo.get(oldCode.debugInfoIdx)?.parameterNames
                ?.map { if (it < 0) null else asm.strings.get(it) } ?: emptyList()

            val (built, debugInfo) = try {
                patch.buildBody(paramCount, oldNames)
            } catch (e: Exception) {
                throw IllegalStateException("Патч $className->$signature: ${e.message}", e)
            }
            val debugInfoIdx = debugInfo?.let { asm.debugInfo.add(it) } ?: -1

            val rebuilt = CodeItemBuilder.rebuild(
                old = oldCode,
                instructions = built.instructions,
                registers = built.registers,
                debugInfoIdx = debugInfoIdx,
                tries = built.tries,
                catchHandlers = built.catchHandlers
            )
            asm.codes.replace(encoded.codeIdx, rebuilt)
        }
    }

    private fun describe(name: String, params: List<String>?, ret: String?): String =
        if (params == null) name else "$name(${params.joinToString("")})$ret"
}
