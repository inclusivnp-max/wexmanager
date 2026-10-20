package com.tim.c2d.desugar

import com.tim.casm.CasmState
import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.builder.CodeAttributes
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.frames.MethodShape
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.c2d.bytecode.Descriptors
import com.tim.clazz.model.MethodInfo

/**
 * Байткод сгенерированных методов рассахаривания: загрузка/возврат по типу, упаковка, распаковка,
 * расширение и приведения — ровно как InnerClassLambdaMetafactory (TypeConvertingMethodAdapter).
 */
internal class Emit(val code: CodeDsl, private val state: CasmState) {
    val pool get() = state.pool

    fun load(type: String, slot: Int) = when (type[0]) {
        'Z', 'B', 'C', 'S', 'I' -> code.iload(slot)
        'J' -> code.lload(slot)
        'F' -> code.fload(slot)
        'D' -> code.dload(slot)
        else -> code.aload(slot)
    }

    fun ret(type: String) = when (type[0]) {
        'V' -> code.returnVoid()
        'Z', 'B', 'C', 'S', 'I' -> code.ireturn()
        'J' -> code.lreturn()
        'F' -> code.freturn()
        'D' -> code.dreturn()
        else -> code.areturn()
    }

    fun pop(type: String) = when (type[0]) {
        'V' -> {}
        'J', 'D' -> code.pop2()
        else -> code.pop()
    }

    /** Загрузить параметры [params] начиная со слота [first]. */
    fun loadAll(params: List<String>, first: Int) {
        var slot = first
        for (p in params) {
            load(p, slot)
            slot += Descriptors.width(p)
        }
    }

    fun invokestatic(owner: String, name: String, desc: String, isInterface: Boolean = false) =
        code.invokestatic(ref(owner, name, desc, isInterface))

    fun invokevirtual(owner: String, name: String, desc: String) = code.invokevirtual(pool.methodref(owner, name, desc))

    fun invokespecial(owner: String, name: String, desc: String, isInterface: Boolean = false) =
        code.invokespecial(ref(owner, name, desc, isInterface))

    fun invokeinterface(owner: String, name: String, desc: String) =
        code.invokeinterface(pool.interfaceMethodref(owner, name, desc), 1 + Descriptors.method(desc).paramWords)

    fun ref(owner: String, name: String, desc: String, isInterface: Boolean): Int =
        if (isInterface) pool.interfaceMethodref(owner, name, desc) else pool.methodref(owner, name, desc)

    fun field(owner: String, name: String, desc: String): Int = pool.fieldref(owner, name, desc)

    /** Целая константа: вне диапазона sipush — через ldc. */
    fun pushInt(v: Int) = if (v in Short.MIN_VALUE..Short.MAX_VALUE) code.pushInt(v) else code.ldc(pool.integer(v))

    fun ldcString(s: String) = code.ldc(pool.string(s))

    fun ldcClass(descriptor: String) = code.ldc(pool.classInfo(internal(descriptor)))

    fun checkcast(descriptor: String) = code.checkcast(pool.classInfo(internal(descriptor)))

    fun instanceOf(descriptor: String) = code.instanceof(pool.classInfo(internal(descriptor)))

    fun newObject(internalName: String) {
        code.new(pool.classInfo(internalName))
        code.dup()
    }

    // ---- Преобразования типов (JDK TypeConvertingMethodAdapter.convertType) ----

    /**
     * Приведение значения типа [arg] на стеке к [target]; [functional] — тип из instantiatedMethodType
     * (для ссылок сначала приводится к нему: CCE на неверном аргументе, как у JDK).
     */
    fun convert(arg: String, target: String, functional: String) {
        if (arg == target && arg == functional) return
        if (arg == "V" || target == "V") return
        if (isPrimitive(arg)) {
            if (isPrimitive(target)) {
                widen(arg[0], target[0])
            } else {
                val boxedTarget = primitiveOfWrapper(target)
                if (boxedTarget != null) {
                    widen(arg[0], boxedTarget)
                    box(boxedTarget)
                } else {
                    box(arg[0])
                    cast(wrapperDescriptor(arg[0]), target)
                }
            }
        } else {
            val src = if (isPrimitive(functional)) arg else {
                cast(arg, functional)
                functional
            }
            if (isPrimitive(target)) {
                val w = primitiveOfWrapper(src)
                if (w != null) {
                    if (isSignedOrFloating(w)) {
                        unbox(wrapperName(w), target[0])
                    } else {
                        unbox(wrapperName(w), w)
                        widen(w, target[0])
                    }
                } else {
                    // Не обёртка: предполагается супертип обёртки (Object, Number, Comparable…).
                    val intermediate = if (isSignedOrFloating(target[0])) "java/lang/Number" else wrapperName(target[0])
                    cast(src, "L$intermediate;")
                    unbox(intermediate, target[0])
                }
            } else {
                cast(src, target)
            }
        }
    }

    /** checkcast, если типы различаются и цель — не Object. */
    fun cast(from: String, to: String) {
        if (from == to || to == "Ljava/lang/Object;") return
        checkcast(to)
    }

    fun box(p: Char) = invokestatic(wrapperName(p), "valueOf", "($p)${wrapperDescriptor(p)}")

    /** `owner.xxxValue()` — распаковка к примитиву [p] (owner — обёртка или Number). */
    fun unbox(owner: String, p: Char) = invokevirtual(owner, primitiveName(p) + "Value", "()$p")

    /** Расширяющее преобразование примитивов (JLS §5.1.2). */
    fun widen(from: Char, to: Char) {
        if (from == to) return
        val f = if (from == 'B' || from == 'S' || from == 'C') 'I' else from
        val ok = when (to) {
            'S' -> from == 'B'
            'I' -> f == 'I'
            'J' -> (f == 'I').also { if (it) code.i2l() }
            'F' -> when (f) { 'I' -> { code.i2f(); true }; 'J' -> { code.l2f(); true }; else -> false }
            'D' -> when (f) { 'I' -> { code.i2d(); true }; 'J' -> { code.l2d(); true }; 'F' -> { code.f2d(); true }; else -> false }
            else -> false
        }
        if (!ok) throw IllegalArgumentException("нерасширяющее преобразование $from → $to")
    }

    companion object {
        fun isPrimitive(t: String) = t.length == 1 && t[0] != 'V'

        fun internal(descriptor: String): String =
            if (descriptor[0] == 'L') descriptor.substring(1, descriptor.length - 1) else descriptor

        fun wrapperName(p: Char): String = when (p) {
            'Z' -> "java/lang/Boolean"; 'B' -> "java/lang/Byte"; 'S' -> "java/lang/Short"; 'C' -> "java/lang/Character"
            'I' -> "java/lang/Integer"; 'J' -> "java/lang/Long"; 'F' -> "java/lang/Float"; 'D' -> "java/lang/Double"
            else -> throw IllegalArgumentException("не примитив: $p")
        }

        fun wrapperDescriptor(p: Char) = "L${wrapperName(p)};"

        fun primitiveName(p: Char): String = when (p) {
            'Z' -> "boolean"; 'B' -> "byte"; 'S' -> "short"; 'C' -> "char"
            'I' -> "int"; 'J' -> "long"; 'F' -> "float"; 'D' -> "double"
            else -> throw IllegalArgumentException("не примитив: $p")
        }

        /** Примитив обёртки по дескриптору (`Ljava/lang/Integer;` → 'I'); null — не обёртка. */
        fun primitiveOfWrapper(descriptor: String): Char? = when (descriptor) {
            "Ljava/lang/Boolean;" -> 'Z'; "Ljava/lang/Byte;" -> 'B'; "Ljava/lang/Short;" -> 'S'
            "Ljava/lang/Character;" -> 'C'; "Ljava/lang/Integer;" -> 'I'; "Ljava/lang/Long;" -> 'J'
            "Ljava/lang/Float;" -> 'F'; "Ljava/lang/Double;" -> 'D'
            else -> null
        }

        fun isSignedOrFloating(p: Char) = p == 'B' || p == 'S' || p == 'I' || p == 'J' || p == 'F' || p == 'D'
    }
}

/** Сборка метода класса-носителя [state] из [code]: фреймы — по версии класса и иерархии. */
internal fun assembleMethod(
    state: CasmState, hierarchy: ClassHierarchy, owner: String, name: String, desc: String,
    flags: Int, code: CodeDsl
): MethodInfo {
    val pool = state.pool
    val a = CodeAssembler.assemble(
        code, pool, MethodShape(owner, name, desc, flags and 0x0008 != 0),
        options = CodeAssembler.Options(frames = state.frameMode, hierarchy = hierarchy)
    )
    return MethodInfo(flags, pool.utf8(name), pool.utf8(desc), listOf(CodeAttributes.build(pool, a)))
}
