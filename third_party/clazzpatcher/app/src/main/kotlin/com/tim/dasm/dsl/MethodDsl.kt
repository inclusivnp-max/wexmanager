package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.model.*
import com.tim.dex.model.AnnotationItem

class MethodDsl(
    private val methodName: String,
    private val asm: DexAsm
) {
    var returnType: String = "V"
    private val paramTypes = mutableListOf<String>()
    private var accessFlags: Int = AccessFlags.PUBLIC
    private var registers: Int = -1 // -1 — вычислить по использованным регистрам
    private val annotations = mutableListOf<AnnotationItem>()
    private val parameterAnnotations = LinkedHashMap<Int, MutableList<AnnotationItem>>()

    private val body = MethodBody(asm)

    /** Построитель кода метода (один на весь метод). */
    val code: InstructionsDsl get() = body.code

    // ---- параметры ----

    fun param(type: String) { paramTypes.add(type) }
    fun params(vararg types: String) = types.forEach { param(it) }

    // ---- access ----

    fun public() { accessFlags = accessFlags or AccessFlags.PUBLIC }
    fun private() { accessFlags = accessFlags or AccessFlags.PRIVATE }
    fun protected() { accessFlags = accessFlags or AccessFlags.PROTECTED }
    fun static() { accessFlags = accessFlags or AccessFlags.STATIC }
    fun final() { accessFlags = accessFlags or AccessFlags.FINAL }
    fun synchronized() { accessFlags = accessFlags or AccessFlags.SYNCHRONIZED }
    fun native() { accessFlags = accessFlags or AccessFlags.NATIVE }
    fun abstract() { accessFlags = accessFlags or AccessFlags.ABSTRACT }
    fun synthetic() { accessFlags = accessFlags or AccessFlags.SYNTHETIC }
    fun varargs() { accessFlags = accessFlags or AccessFlags.VARARGS }

    fun constructor() { accessFlags = accessFlags or AccessFlags.CONSTRUCTOR }

    /**
     * `<clinit>` — статический конструктор класса.
     *
     * По спецификации DEX валидные флаги: `ACC_STATIC | ACC_CONSTRUCTOR`.
     * Флаги доступа (PUBLIC/PRIVATE/PROTECTED) сбрасываются.
     */
    fun staticConstructor() {
        accessFlags = AccessFlags.STATIC or AccessFlags.CONSTRUCTOR
    }

    fun accessFlags(flags: Int) { this.accessFlags = flags }

    /** Флаги ограничений hidden API (hiddenapi_class_data), `-1` — не заданы. */
    fun hiddenApi(flags: Int) { hiddenApi = flags }
    private var hiddenApi: Int = -1
    fun flags(flags: Int) { this.accessFlags = flags }

    // ---- настройки ----

    fun returnType(type: String) { returnType = type }

    /** Число регистров метода; если не задано — вычисляется по использованным регистрам. */
    fun registers(count: Int) {
        require(count >= 0) { "Отрицательное число регистров: $count" }
        registers = count
    }

    // ---- регистры параметров ----

    fun paramRegister(index: Int): Int {
        require(registers >= 0) { "registers() нужно задать до paramRegister()" }
        require(index in paramTypes.indices) { "Нет параметра с индексом $index" }
        val isStatic = AccessFlags.isStatic(accessFlags)
        var reg = registers - insSize() + (if (isStatic) 0 else 1)
        for (i in 0 until index) reg += widthOf(paramTypes[i])
        return reg
    }

    fun thisRegister(): Int {
        require(registers >= 0) { "registers() нужно задать до thisRegister()" }
        require(!AccessFlags.isStatic(accessFlags)) { "У статического метода нет this" }
        return registers - insSize()
    }

    fun insSize(): Int {
        var size = if (AccessFlags.isStatic(accessFlags)) 0 else 1
        for (t in paramTypes) size += widthOf(t)
        return size
    }

    private fun widthOf(type: String) = if (type == "J" || type == "D") 2 else 1

    // ---- аннотации ----

    fun annotation(item: AnnotationItem) { annotations.add(item) }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(asm)
        dsl.block()
        annotations.add(dsl.build())
    }

    /** Аннотация параметра [index] (0 — первый объявленный параметр, без `this`). */
    fun paramAnnotation(index: Int, item: AnnotationItem) {
        require(index >= 0) { "Некорректный индекс параметра: $index" }
        parameterAnnotations.getOrPut(index) { mutableListOf() }.add(item)
    }

    fun paramAnnotation(index: Int, block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(asm)
        dsl.block()
        paramAnnotation(index, dsl.build())
    }

    fun signature(vararg parts: String) {
        val dsl = AnnotationDsl(asm)
        dsl.type("Ldalvik/annotation/Signature;")
        dsl.systemVisibility()
        dsl.stringArray("value", parts.toList())
        annotations.add(dsl.build())
    }

    // ---- debug ----

    /** Имена параметров по порядку (`null` — без имени). */
    fun paramNames(vararg names: String?) = body.debug.paramNames(*names)
    /** Имя параметра [index] (0 — первый объявленный параметр, без `this`). */
    fun paramName(index: Int, name: String?) = body.debug.setParamName(index, name)
    fun prologueEnd() = body.debug.prologueEnd()
    fun epilogueBegin() = body.debug.epilogueBegin()
    fun lineNumber(line: Int) = body.debug.lineNumber(line)
    fun startLocal(reg: Int, name: String?, type: String?, sig: String? = null) =
        body.debug.startLocal(reg, name, type, sig)
    fun endLocal(reg: Int) = body.debug.endLocal(reg)
    fun restartLocal(reg: Int) = body.debug.restartLocal(reg)
    fun setFile(name: String?) = body.debug.setFile(name)
    fun lineStart(line: Int) = body.debug.lineStart(line)

    // ---- инструкции ----

    fun instructions(block: InstructionsDsl.() -> Unit) {
        body.code.block()
    }

    // ---- try / catch ----

    fun tryCatch(start: String, end: String, handler: String, exceptionType: String) {
        body.tryBlocks.add(TryBlock(start, end, handler, exceptionType))
    }

    fun catchAll(start: String, end: String, handler: String) {
        body.tryBlocks.add(TryBlock(start, end, handler, null))
    }

    // ---- build ----

    internal fun build(): MethodInfo {
        for (idx in parameterAnnotations.keys) {
            require(idx < paramTypes.size) { "$methodName: аннотация параметра $idx, а параметров ${paramTypes.size}" }
        }
        val paramAnns = parameterAnnotations.mapValues { it.value.toList() }

        if (body.code.instructions.isEmpty()) {
            require(body.tryBlocks.isEmpty() && body.code.labels.isEmpty()) {
                "$methodName: try/catch и метки без инструкций"
            }
            require(!body.debug.hasData()) {
                "$methodName: отладочная информация (имена параметров, .line, .local) без кода метода не сохраняется в DEX"
            }
            return MethodInfo(
                name = methodName,
                returnType = returnType,
                paramTypes = paramTypes.toList(),
                accessFlags = accessFlags,
                instructions = emptyList(),
                registers = registers,
                annotations = annotations.toList(),
                parameterAnnotations = paramAnns,
                hasCode = false,
                hiddenApi = hiddenApi
            )
        }

        val built = body.build(paramTypes.size)
        val debugInfoIdx = built.debugInfo?.let { asm.debugInfo.add(it) } ?: -1

        return MethodInfo(
            name = methodName,
            returnType = returnType,
            paramTypes = paramTypes.toList(),
            accessFlags = accessFlags,
            instructions = built.instructions,
            registers = registers,
            annotations = annotations.toList(),
            debugInfoIdx = debugInfoIdx,
            tries = built.tries,
            catchHandlers = built.catchHandlers,
            parameterAnnotations = paramAnns,
            hasCode = true,
            hiddenApi = hiddenApi
        )
    }
}
