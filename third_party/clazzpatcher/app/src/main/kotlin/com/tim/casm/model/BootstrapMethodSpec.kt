package com.tim.casm.model

data class BootstrapMethodSpec(
    val methodHandleIdx: Int,
    val arguments: List<BootstrapArgument>
)

sealed class BootstrapArgument {
    data class IntConst(val value: Int) : BootstrapArgument()
    data class LongConst(val value: Long) : BootstrapArgument()
    data class FloatConst(val bits: Int) : BootstrapArgument()
    data class DoubleConst(val bits: Long) : BootstrapArgument()
    data class StringConst(val value: String) : BootstrapArgument()
    data class ClassConst(val descriptor: String) : BootstrapArgument()
    data class MethodHandle(val kind: Int, val refIndex: Int) : BootstrapArgument()
    data class MethodType(val descriptor: String) : BootstrapArgument()
    data class Dynamic(val bsmIndex: Int, val nameAndTypeIndex: Int) : BootstrapArgument()
}
