package com.tim.c2d.desugar

import com.tim.c2d.bytecode.Descriptors
import com.tim.casm.CasmState
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.FieldInfo
import com.tim.clazz.model.MethodInfo

/**
 * Класс лямбды вместо LambdaMetafactory — то же, что генерирует JDK (InnerClassLambdaMetafactory):
 * поля захваченных значений, конструктор, метод интерфейса и мосты (altMetafactory FLAG_BRIDGES)
 * с преобразованием аргументов и результата. Без захвата — один экземпляр в `INSTANCE` (как у JDK:
 * такой call site возвращает всегда один объект). Статическая фабрика [FACTORY] заменяет invokedynamic.
 */
internal class LambdaClass(private val hierarchy: ClassHierarchy) {

    /** Как вызвать реализацию: вид MethodHandle ([IndyDesugar].REF_*), владелец, имя, дескриптор. */
    class Call(val kind: Int, val owner: String, val name: String, val desc: String, val isInterface: Boolean)

    class Spec(
        val name: String,
        val version: Int,
        val interfaces: List<String>,
        val samName: String,
        val samType: String,
        val bridges: List<String>,
        val instantiated: String,
        /** Типы захваченных значений (параметры invokedynamic). */
        val captures: List<String>,
        val factoryDesc: String,
        /** MethodHandle.type() реализации: получатель — первым параметром у методов экземпляра. */
        val implParams: List<String>,
        val implReturn: String,
        val call: Call
    )

    fun build(s: Spec): ClassFile {
        val state = CasmState(emptyClass(s.version))
        val pool = state.pool
        val fields = ArrayList<FieldInfo>()
        val methods = ArrayList<MethodInfo>()
        val capturing = s.captures.isNotEmpty()
        val self = "L${s.name};"
        val fi = s.interfaces.first()

        s.captures.forEachIndexed { k, t ->
            fields.add(FieldInfo(IndyDesugar.ACC_PRIVATE or IndyDesugar.ACC_FINAL, pool.utf8("f$$k"), pool.utf8(t), emptyList()))
        }
        if (!capturing) {
            fields.add(FieldInfo(IndyDesugar.ACC_PUBLIC or IndyDesugar.ACC_STATIC or IndyDesugar.ACC_FINAL, pool.utf8(INSTANCE), pool.utf8(self), emptyList()))
        }

        // Конструктор: поля по порядку. Пакетный: в DEX место вызова создаёт объект само (фабрика встраивается).
        val ctorDesc = constructorDescriptor(s.captures)
        methods.add(method(state, s.name, "<init>", ctorDesc, IndyDesugar.ACC_SYNTHETIC) { e ->
            e.code.aload(0)
            e.invokespecial("java/lang/Object", "<init>", "()V")
            var slot = 1
            s.captures.forEachIndexed { k, t ->
                e.code.aload(0)
                e.load(t, slot)
                e.code.putfield(e.field(s.name, "f$$k", t))
                slot += Descriptors.width(t)
            }
            e.code.returnVoid()
        })

        // Фабрика: новый объект или единственный экземпляр.
        methods.add(method(state, s.name, FACTORY, s.factoryDesc, IndyDesugar.ACC_STATIC or IndyDesugar.ACC_SYNTHETIC) { e ->
            if (capturing) {
                e.newObject(s.name)
                e.loadAll(s.captures, 0)
                e.invokespecial(s.name, "<init>", ctorDesc)
            } else {
                e.code.getstatic(e.field(s.name, INSTANCE, self))
            }
            e.code.areturn()
        })
        if (!capturing) {
            methods.add(method(state, s.name, "<clinit>", "()V", IndyDesugar.ACC_STATIC) { e ->
                e.newObject(s.name)
                e.invokespecial(s.name, "<init>", "()V")
                e.code.putstatic(e.field(s.name, INSTANCE, self))
                e.code.returnVoid()
            })
        }

        // Метод интерфейса и мосты.
        for (type in listOf(s.samType) + s.bridges) {
            methods.add(method(state, s.name, s.samName, type, IndyDesugar.ACC_PUBLIC) { e -> forward(e, s, type) })
        }

        return ClassFile(
            magic = 0xCAFEBABEL, minorVersion = 0, majorVersion = state.majorVersion,
            constantPool = emptyList(), // заменяется ниже: пул дописывается при сборке методов
            accessFlags = IndyDesugar.ACC_FINAL or IndyDesugar.ACC_SUPER or IndyDesugar.ACC_SYNTHETIC,
            thisClass = pool.classInfo(s.name), superClass = pool.classInfo("java/lang/Object"),
            interfaces = s.interfaces.map { pool.classInfo(it) },
            fields = fields, methods = methods, attributes = emptyList()
        ).copy(constantPool = pool.build())
    }

    /** Тело метода интерфейса типа [type] (ForwardingMethodGenerator JDK). */
    private fun forward(e: Emit, s: Spec, type: String) {
        val sam = Descriptors.method(type)
        val instantiated = Descriptors.method(s.instantiated)
        val c = s.call
        if (c.kind == IndyDesugar.REF_NEW_INVOKE_SPECIAL) e.newObject(c.owner)
        s.captures.forEachIndexed { k, t ->
            e.code.aload(0)
            e.code.getfield(e.field(s.name, "f$$k", t))
        }
        var slot = 1
        sam.params.forEachIndexed { i, arg ->
            e.load(arg, slot)
            slot += Descriptors.width(arg)
            val target = s.implParams.getOrNull(s.captures.size + i)
                ?: throw IllegalArgumentException("у реализации меньше параметров, чем у ${s.samName}$type")
            e.convert(arg, target, instantiated.params.getOrNull(i) ?: arg)
        }
        when (c.kind) {
            IndyDesugar.REF_INVOKE_STATIC -> e.invokestatic(c.owner, c.name, c.desc, c.isInterface)
            IndyDesugar.REF_INVOKE_VIRTUAL -> e.invokevirtual(c.owner, c.name, c.desc)
            IndyDesugar.REF_INVOKE_INTERFACE -> e.invokeinterface(c.owner, c.name, c.desc)
            IndyDesugar.REF_NEW_INVOKE_SPECIAL -> e.invokespecial(c.owner, "<init>", c.desc)
            else -> throw IllegalArgumentException("вызов реализации вида ${c.kind}")
        }
        if (sam.returnType == "V") e.pop(s.implReturn) else e.convert(s.implReturn, sam.returnType, sam.returnType)
        e.ret(sam.returnType)
    }

    private fun method(state: CasmState, owner: String, name: String, desc: String, flags: Int, body: (Emit) -> Unit): MethodInfo {
        val code = CodeDsl()
        body(Emit(code, state))
        return assembleMethod(state, hierarchy, owner, name, desc, flags, code)
    }

    private fun emptyClass(major: Int) = ClassFile(
        magic = 0xCAFEBABEL, minorVersion = 0, majorVersion = major, constantPool = listOf(null),
        accessFlags = 0, thisClass = 0, superClass = 0, interfaces = emptyList(),
        fields = emptyList(), methods = emptyList(), attributes = emptyList()
    )

    companion object {
        /** Статическая фабрика (имя недопустимо в Java/Kotlin — не совпадёт с методом интерфейса). */
        const val FACTORY = "-\$\$create"
        const val INSTANCE = "INSTANCE"

        fun constructorDescriptor(captures: List<String>): String = "(" + captures.joinToString("") + ")V"
    }
}

/**
 * Класс лямбды из рассахаривания: c2d встраивает его фабрику в место вызова (как d8 — `new-instance`
 * и `invoke-direct <init>`, без захвата — `sget-object INSTANCE`) и не пишет саму фабрику в DEX.
 */
class LambdaFactory(val capturing: Boolean, val constructorDescriptor: String) {
    companion object {
        const val FACTORY = LambdaClass.FACTORY
        const val INSTANCE = LambdaClass.INSTANCE
    }
}
