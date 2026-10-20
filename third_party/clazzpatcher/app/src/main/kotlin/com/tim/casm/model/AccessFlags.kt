package com.tim.casm.model

object AccessFlags {
    const val ACC_PUBLIC = 0x0001
    const val ACC_FINAL = 0x0010
    const val ACC_SUPER = 0x0020
    const val ACC_INTERFACE = 0x0200
    const val ACC_ABSTRACT = 0x0400
    const val ACC_SYNTHETIC = 0x1000
    const val ACC_ANNOTATION = 0x2000
    const val ACC_ENUM = 0x4000
    const val ACC_MODULE = 0x8000

    const val ACC_PRIVATE = 0x0002
    const val ACC_PROTECTED = 0x0004
    const val ACC_STATIC = 0x0008
    const val ACC_VOLATILE = 0x0040
    const val ACC_TRANSIENT = 0x0080

    const val ACC_SYNCHRONIZED = 0x0020
    const val ACC_BRIDGE = 0x0040
    const val ACC_VARARGS = 0x0080
    const val ACC_NATIVE = 0x0100
    const val ACC_STRICT = 0x0800

    fun isPublic(f: Int) = f and ACC_PUBLIC != 0
    fun isPrivate(f: Int) = f and ACC_PRIVATE != 0
    fun isProtected(f: Int) = f and ACC_PROTECTED != 0
    fun isStatic(f: Int) = f and ACC_STATIC != 0
    fun isFinal(f: Int) = f and ACC_FINAL != 0
    fun isAbstract(f: Int) = f and ACC_ABSTRACT != 0
    fun isInterface(f: Int) = f and ACC_INTERFACE != 0
    fun isNative(f: Int) = f and ACC_NATIVE != 0
    fun isSynthetic(f: Int) = f and ACC_SYNTHETIC != 0
    fun isAnnotation(f: Int) = f and ACC_ANNOTATION != 0
    fun isEnum(f: Int) = f and ACC_ENUM != 0
    fun isModule(f: Int) = f and ACC_MODULE != 0
    fun isBridge(f: Int) = f and ACC_BRIDGE != 0
    fun isVarargs(f: Int) = f and ACC_VARARGS != 0
}
