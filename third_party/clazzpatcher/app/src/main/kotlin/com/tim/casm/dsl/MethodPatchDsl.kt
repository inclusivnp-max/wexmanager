package com.tim.casm.dsl

import com.tim.casm.Casm
import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.builder.CodeAttributes
import com.tim.casm.frames.MethodShape
import com.tim.casm.model.AccessFlags
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.Code

/**
 * Замена тела существующего метода. Старые атрибуты кода (строки, локалы, фреймы,
 * type-аннотации) ссылаются на старые pc и отбрасываются; фреймы считаются заново.
 */
class MethodPatchDsl(
    private val name: String,
    private val casm: Casm,
    private val descriptor: String? = null
) {
    private val code = CodeDsl()
    private var maxStack: Int = -1
    private var maxLocals: Int = -1
    private val trySpecs = ArrayList<CodeAssembler.TrySpec>()
    private val lineNumbers = ArrayList<Pair<String, Int>>()
    private val localVariables = ArrayList<CodeAssembler.LocalVarSpec>()

    fun maxStack(v: Int) { maxStack = v }
    fun maxLocals(v: Int) { maxLocals = v }

    fun code(block: CodeDsl.() -> Unit) { code.block() }

    fun tryCatch(start: String, end: String, handler: String, exceptionType: String?) {
        trySpecs.add(CodeAssembler.TrySpec(start, end, handler, exceptionType))
    }

    fun lineNumber(label: String, line: Int) { lineNumbers.add(label to line) }

    fun localVariable(start: String, end: String, name: String, descriptor: String, index: Int, signature: String? = null) {
        localVariables.add(CodeAssembler.LocalVarSpec(start, end, name, descriptor, index, signature))
    }

    internal fun apply(cf: ClassFile): ClassFile {
        val candidates = cf.methods.withIndex().filter { (_, m) ->
            cf.utf8(m.nameIndex) == name && (descriptor == null || cf.utf8(m.descriptorIndex) == descriptor)
        }
        require(candidates.size == 1) {
            "Метод $name${descriptor ?: ""}: " + if (candidates.isEmpty()) "не найден" else "неоднозначен (${candidates.size}), укажите дескриптор"
        }
        val (methodIdx, method) = candidates[0]
        val methodDesc = cf.utf8(method.descriptorIndex)
        val codeIdx = method.attributes.indexOfFirst { it is Code }
        require(codeIdx >= 0) { "У метода $name$methodDesc нет кода (abstract/native)" }

        val state = casm.state
        val pool = state.pool
        val assembled = CodeAssembler.assemble(
            code, pool, MethodShape(cf.thisClassName, name, methodDesc, AccessFlags.isStatic(method.accessFlags)),
            trySpecs, lineNumbers, localVariables,
            CodeAssembler.Options(maxStack, maxLocals, state.frameMode, state.hierarchy)
        )
        val newCode = CodeAttributes.build(pool, assembled)
        val newAttrs = method.attributes.toMutableList().also { it[codeIdx] = newCode }
        val newMethods = cf.methods.toMutableList().also { it[methodIdx] = method.copy(attributes = newAttrs) }
        return cf.copy(methods = newMethods, constantPool = pool.build())
    }
}
