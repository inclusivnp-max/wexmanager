package com.tim.casm.pool

import com.tim.casm.model.BootstrapArgument
import com.tim.casm.model.BootstrapMethodSpec
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.BootstrapMethods

/** Таблица BootstrapMethods с дедупликацией: одинаковые (handle, аргументы) получают один индекс. */
class BootstrapMethodPool(private val pool: ConstantPoolBuilder) {
    private val index = HashMap<BootstrapMethods.BootstrapMethod, Int>()
    private val methods = ArrayList<BootstrapMethods.BootstrapMethod>()

    fun loadExisting(cf: ClassFile) {
        val attr = cf.attributes.filterIsInstance<BootstrapMethods>().firstOrNull() ?: return
        for (m in attr.bootstrapMethods) {
            index.putIfAbsent(m, methods.size)
            methods.add(m)
        }
    }

    /** [methodHandleIdx] — CONSTANT_MethodHandle, [arguments] — индексы загружаемых констант пула. */
    fun add(methodHandleIdx: Int, arguments: List<Int>): Int {
        val m = BootstrapMethods.BootstrapMethod(methodHandleIdx, arguments.toList())
        index[m]?.let { return it }
        val idx = methods.size
        methods.add(m)
        index[m] = idx
        return idx
    }

    fun add(spec: BootstrapMethodSpec): Int = add(spec.methodHandleIdx, spec.arguments.map { arg ->
        when (arg) {
            is BootstrapArgument.IntConst -> pool.integer(arg.value)
            is BootstrapArgument.LongConst -> pool.long(arg.value)
            is BootstrapArgument.FloatConst -> pool.float(arg.bits)
            is BootstrapArgument.DoubleConst -> pool.double(arg.bits)
            is BootstrapArgument.StringConst -> pool.string(arg.value)
            is BootstrapArgument.ClassConst -> pool.classInfo(
                if (arg.descriptor.startsWith("L")) arg.descriptor.substring(1, arg.descriptor.length - 1) else arg.descriptor
            )
            is BootstrapArgument.MethodHandle -> pool.methodHandle(arg.kind, arg.refIndex)
            is BootstrapArgument.MethodType -> pool.methodType(arg.descriptor)
            is BootstrapArgument.Dynamic -> pool.dynamic(arg.bsmIndex, arg.nameAndTypeIndex)
        }
    })

    fun isEmpty(): Boolean = methods.isEmpty()

    fun build(nameIndex: Int): BootstrapMethods? =
        if (methods.isEmpty()) null else BootstrapMethods(nameIndex, 0, methods.toList())
}
