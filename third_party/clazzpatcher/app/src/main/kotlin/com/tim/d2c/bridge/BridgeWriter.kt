package com.tim.d2c.bridge

import com.tim.casm.CasmState
import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.builder.CodeAttributes
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.frames.MethodShape
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.model.JvmType
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.MethodInfo
import com.tim.d2c.model.DexView
import com.tim.d2c.translate.JvmOps
import com.tim.d2c.types.Cat

/**
 * Вторая фаза: дописывает в уже переведённый класс синтетические методы — конструкторы-переходники
 * ([ConstructorBridges]) и методы доступа к protected-членам ([ProtectedAccessors]). Остальные
 * методы класса не трогаются; пул констант только дополняется.
 */
internal class BridgeWriter(
    private val bridges: ConstructorBridges,
    private val accessors: ProtectedAccessors,
    private val index: ClassIndex,
    private val hierarchy: ClassHierarchy
) {

    fun addTo(
        cf: ClassFile,
        type: String,
        constructors: List<ConstructorBridges.Target>,
        access: List<ProtectedAccessors.Accessor>
    ): ClassFile {
        val state = CasmState(cf).also { it.hierarchy = hierarchy }
        val added = ArrayList<MethodInfo>(constructors.size + access.size)
        for (t in constructors) added.add(constructor(state, type, t))
        for (a in access) added.add(accessor(state, type, a))
        return cf.copy(constantPool = state.pool.build(), methods = cf.methods + added)
    }

    /** `<init>(D…, M × n)`: параметры — следующему переходнику прямого суперкласса или A.<init>(D). */
    private fun constructor(state: CasmState, type: String, t: ConstructorBridges.Target): MethodInfo {
        val pool = state.pool
        val superType = index.superOf(type) ?: throw IllegalStateException("переходник без суперкласса")
        val descriptor = ConstructorBridges.descriptor(t.params, bridges.voids(type, bridges.distance(type, t.ancestor), t))
        val code = CodeDsl()
        code.aload(0)
        loadParams(code, t.params, 1)
        if (superType == t.ancestor) {
            code.invokespecial(pool.methodref(DexView.internalName(t.ancestor), "<init>", ConstructorBridges.descriptor(t.params, 0)))
        } else {
            val next = bridges.voids(superType, bridges.distance(superType, t.ancestor), t)
            repeat(next) { code.aconstNull() }
            code.invokespecial(pool.methodref(DexView.internalName(superType), "<init>", ConstructorBridges.descriptor(t.params, next)))
        }
        code.returnVoid()
        return method(state, type, "<init>", descriptor, isStatic = false, flags = ACC_PUBLIC or ACC_SYNTHETIC, code = code)
    }

    /** `static access$d2c$…(LD;…)`: то же обращение, но изнутри объявляющего класса. */
    private fun accessor(state: CasmState, type: String, a: ProtectedAccessors.Accessor): MethodInfo {
        val pool = state.pool
        val owner = DexView.internalName(type)
        val staticDescriptor = accessors.staticDescriptor(type, a)
        val name = accessors.accessorName(type, a, staticDescriptor)
        val code = CodeDsl()
        code.aload(0)
        when (a.kind) {
            ProtectedAccessors.Kind.INVOKE -> {
                val (params, ret) = JvmType.parseMethodDescriptor(a.memberDescriptor)
                loadParams(code, params, 1)
                code.invokevirtual(pool.methodref(owner, a.member, a.memberDescriptor))
                if (ret == "V") code.returnVoid() else JvmOps.returnOf(code, Cat.of(ret))
            }
            ProtectedAccessors.Kind.CLONE -> {
                // clone() массива публичен: выбор по типу. Не массив (ART вызвал бы Object.clone из чужого
                // класса, что в JVM невыразимо) — CloneNotSupportedException.
                for (array in listOf("[Ljava/lang/Object;", "[I", "[J", "[F", "[D", "[Z", "[B", "[C", "[S")) {
                    val next = "not$array"
                    code.instanceof(pool.classInfo(array))
                    code.ifeq(next)
                    code.aload(0)
                    code.checkcast(pool.classInfo(array))
                    code.invokevirtual(pool.methodref(array, "clone", "()Ljava/lang/Object;"))
                    code.areturn()
                    code.label(next)
                    code.aload(0)
                }
                code.pop()
                val ex = "java/lang/CloneNotSupportedException"
                code.new(pool.classInfo(ex))
                code.dup()
                code.ldc(pool.string("DEX → class: Object.clone() не массива из чужого класса"))
                code.invokespecial(pool.methodref(ex, "<init>", "(Ljava/lang/String;)V"))
                code.athrow()
            }
            ProtectedAccessors.Kind.SUPER -> {
                // Внутри интерфейса invokespecial его же метода — реализация, видимая из интерфейса.
                val (params, ret) = JvmType.parseMethodDescriptor(a.memberDescriptor)
                loadParams(code, params, 1)
                code.invokespecial(pool.interfaceMethodref(owner, a.member, a.memberDescriptor))
                if (ret == "V") code.returnVoid() else JvmOps.returnOf(code, Cat.of(ret))
            }
            ProtectedAccessors.Kind.GET -> {
                code.getfield(pool.fieldref(owner, a.member, a.memberDescriptor))
                JvmOps.returnOf(code, Cat.of(a.memberDescriptor))
            }
            ProtectedAccessors.Kind.PUT -> {
                JvmOps.load(code, Cat.of(a.memberDescriptor), 1)
                code.putfield(pool.fieldref(owner, a.member, a.memberDescriptor))
                code.returnVoid()
            }
        }
        return method(state, type, name, staticDescriptor, isStatic = true, flags = ACC_PUBLIC or ACC_STATIC or ACC_SYNTHETIC, code = code)
    }

    private fun loadParams(code: CodeDsl, params: List<String>, first: Int) {
        var slot = first
        for (p in params) {
            val c = Cat.of(p)
            JvmOps.load(code, c, slot)
            slot += if (c.isWide) 2 else 1
        }
    }

    private fun method(state: CasmState, type: String, name: String, descriptor: String, isStatic: Boolean, flags: Int, code: CodeDsl): MethodInfo {
        val pool = state.pool
        val assembled = CodeAssembler.assemble(
            code, pool, MethodShape(DexView.internalName(type), name, descriptor, isStatic),
            options = CodeAssembler.Options(frames = state.frameMode, hierarchy = hierarchy)
        )
        return MethodInfo(flags, pool.utf8(name), pool.utf8(descriptor), listOf(CodeAttributes.build(pool, assembled)))
    }

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_STATIC = 0x0008
        const val ACC_SYNTHETIC = 0x1000
    }
}
