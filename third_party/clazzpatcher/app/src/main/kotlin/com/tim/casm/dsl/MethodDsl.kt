package com.tim.casm.dsl

import com.tim.casm.CasmState
import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.frames.MethodShape
import com.tim.casm.model.*

class MethodDsl(private val name: String) {
    var returnType: String = "V"
    var descriptor: String? = null
    var accessFlags: Int = AccessFlags.ACC_PUBLIC
    var signature: String? = null
    var maxStack: Int = -1
    var maxLocals: Int = -1
    var exceptions: List<String> = emptyList()
    var annotationDefault: AnnotationValue? = null
    private val paramTypes = ArrayList<String>()
    private val annotations = ArrayList<AnnotationInfo>()
    private val parameterAnnotations = ArrayList<MutableList<AnnotationInfo>>()
    private val typeAnnotations = ArrayList<TypeAnnotationInfo>()
    private val trySpecs = ArrayList<CodeAssembler.TrySpec>()
    private val lineNumbers = ArrayList<Pair<String, Int>>()
    private val localVariables = ArrayList<CodeAssembler.LocalVarSpec>()
    private val methodParams = ArrayList<MethodParameterSpec>()

    val code = CodeDsl()

    fun param(type: String) { paramTypes.add(type) }
    fun params(vararg types: String) = types.forEach { param(it) }
    fun returnType(t: String) { returnType = t }
    fun descriptor(d: String) { descriptor = d }

    /** Видимость взаимоисключающая: новая заменяет прежнюю. */
    fun public() = visibility(AccessFlags.ACC_PUBLIC)
    fun private() = visibility(AccessFlags.ACC_PRIVATE)
    fun protected() = visibility(AccessFlags.ACC_PROTECTED)
    fun packagePrivate() = visibility(0)

    private fun visibility(flag: Int) {
        accessFlags = (accessFlags and VISIBILITY.inv()) or flag
    }
    fun static() { accessFlags = accessFlags or AccessFlags.ACC_STATIC }
    fun final() { accessFlags = accessFlags or AccessFlags.ACC_FINAL }
    fun synchronized() { accessFlags = accessFlags or AccessFlags.ACC_SYNCHRONIZED }
    fun bridge() { accessFlags = accessFlags or AccessFlags.ACC_BRIDGE }
    fun varargs() { accessFlags = accessFlags or AccessFlags.ACC_VARARGS }
    fun native() { accessFlags = accessFlags or AccessFlags.ACC_NATIVE }
    fun abstract() { accessFlags = accessFlags or AccessFlags.ACC_ABSTRACT }
    fun strict() { accessFlags = accessFlags or AccessFlags.ACC_STRICT }
    fun synthetic() { accessFlags = accessFlags or AccessFlags.ACC_SYNTHETIC }
    fun accessFlags(f: Int) { accessFlags = f }

    fun constructor() { returnType = "V" }

    fun signature(s: String) { signature = s }
    fun throwsException(internalName: String) { exceptions = exceptions + internalName }
    fun maxStack(v: Int) { maxStack = v }
    fun maxLocals(v: Int) { maxLocals = v }
    fun annotationDefault(value: AnnotationValue) { annotationDefault = value }
    fun methodParameter(name: String?, flags: Int = 0) { methodParams.add(MethodParameterSpec(name, flags)) }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(); dsl.block(); annotations.add(dsl.buildInfo())
    }

    fun paramAnnotation(index: Int, block: AnnotationDsl.() -> Unit) {
        while (parameterAnnotations.size <= index) parameterAnnotations.add(ArrayList())
        val dsl = AnnotationDsl(); dsl.block(); parameterAnnotations[index].add(dsl.buildInfo())
    }

    fun typeAnnotation(targetType: Int, block: TypeAnnotationDsl.() -> Unit) {
        val dsl = TypeAnnotationDsl(targetType); dsl.block(); typeAnnotations.add(dsl.build())
    }

    fun code(block: CodeDsl.() -> Unit) { code.block() }

    fun tryCatch(start: String, end: String, handler: String, exceptionType: String?) {
        trySpecs.add(CodeAssembler.TrySpec(start, end, handler, exceptionType))
    }

    fun lineNumber(label: String, line: Int) { lineNumbers.add(label to line) }

    fun localVariable(start: String, end: String, name: String, descriptor: String, index: Int, signature: String? = null) {
        localVariables.add(CodeAssembler.LocalVarSpec(start, end, name, descriptor, index, signature))
    }

    internal fun build(state: CasmState, owner: String): MethodInfo {
        val desc = descriptor ?: "(" + paramTypes.joinToString("") + ")" + returnType
        JvmType.parseMethodDescriptor(desc)
        if (code.isEmpty) {
            require(trySpecs.isEmpty()) { "Метод $name$desc: try/catch без кода" }
            return MethodInfo(
                name = name, descriptor = desc, accessFlags = accessFlags,
                annotations = annotations.toList(),
                parameterAnnotations = parameterAnnotations.map { it.toList() },
                typeAnnotations = typeAnnotations.toList(),
                signature = signature, exceptions = exceptions,
                annotationDefault = annotationDefault, methodParameters = methodParams.toList(),
                hasCode = false
            )
        }
        val assembled = CodeAssembler.assemble(
            code, state.pool, MethodShape(owner, name, desc, AccessFlags.isStatic(accessFlags)),
            trySpecs, lineNumbers, localVariables,
            CodeAssembler.Options(maxStack, maxLocals, state.frameMode, state.hierarchy)
        )
        return MethodInfo(
            name = name, descriptor = desc, accessFlags = accessFlags,
            code = assembled.code, maxStack = assembled.maxStack, maxLocals = assembled.maxLocals,
            exceptionTable = assembled.exceptionTable,
            lineNumbers = assembled.lineNumbers, localVariables = assembled.localVariables,
            annotations = annotations.toList(),
            parameterAnnotations = parameterAnnotations.map { it.toList() },
            typeAnnotations = typeAnnotations.toList(),
            signature = signature, exceptions = exceptions, annotationDefault = annotationDefault,
            stackMapFrames = assembled.stackMapFrames, methodParameters = methodParams.toList(),
            hasCode = true
        )
    }

    private companion object {
        const val VISIBILITY = AccessFlags.ACC_PUBLIC or AccessFlags.ACC_PRIVATE or AccessFlags.ACC_PROTECTED
    }
}
