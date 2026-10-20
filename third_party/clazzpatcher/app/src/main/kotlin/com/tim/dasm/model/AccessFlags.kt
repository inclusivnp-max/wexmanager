package com.tim.dasm.model

object AccessFlags {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010
    const val SYNCHRONIZED = 0x0020
    const val VOLATILE = 0x0040
    const val BRIDGE = 0x0040
    const val TRANSIENT = 0x0080
    const val VARARGS = 0x0080
    const val NATIVE = 0x0100
    const val INTERFACE = 0x0200
    const val ABSTRACT = 0x0400
    const val STRICT = 0x0800
    const val SYNTHETIC = 0x1000
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000
    const val CONSTRUCTOR = 0x10000
    const val DECLARED_SYNCHRONIZED = 0x20000
    const val SUPER = 0x0020

    fun isStatic(flags: Int) = (flags and STATIC) != 0
    fun isPublic(flags: Int) = (flags and PUBLIC) != 0
    fun isPrivate(flags: Int) = (flags and PRIVATE) != 0
    fun isProtected(flags: Int) = (flags and PROTECTED) != 0
    fun isFinal(flags: Int) = (flags and FINAL) != 0
    fun isAbstract(flags: Int) = (flags and ABSTRACT) != 0
    fun isConstructor(flags: Int) = (flags and CONSTRUCTOR) != 0
    fun isNative(flags: Int) = (flags and NATIVE) != 0
    fun addFlag(flags: Int, flag: Int) = flags or flag
    fun removeFlag(flags: Int, flag: Int) = flags and flag.inv()
    fun hasFlag(flags: Int, flag: Int) = (flags and flag) != 0

    fun flagsOf(flags: List<String>): Int {
        var f = 0
        flags.forEach { f = f or flagOf(it) }
        return f
    }

    fun flagOf(name: String): Int = when (name) {
        "public" -> PUBLIC;
        "private" -> PRIVATE;
        "protected" -> PROTECTED
        "static" -> STATIC;
        "final" -> FINAL;
        "synchronized" -> SYNCHRONIZED
        "volatile" -> VOLATILE;
        "bridge" -> BRIDGE;
        "transient" -> TRANSIENT
        "varargs" -> VARARGS;
        "native" -> NATIVE;
        "interface" -> INTERFACE
        "abstract" -> ABSTRACT;
        "strictfp" -> STRICT;
        "synthetic" -> SYNTHETIC
        "annotation" -> ANNOTATION;
        "enum" -> ENUM;
        "constructor" -> CONSTRUCTOR
        "declared-synchronized" -> DECLARED_SYNCHRONIZED
        else -> throw IllegalArgumentException("Unknown access flag: $name")
    }

    fun classAccessFlags(flags: Int): MutableList<String> {
        val out = mutableListOf<String>()
        if (flags and PUBLIC != 0) out.add("public")
        if (flags and PRIVATE != 0) out.add("private")
        if (flags and PROTECTED != 0) out.add("protected")
        if (flags and STATIC != 0) out.add("static")
        if (flags and FINAL != 0) out.add("final")
        if (flags and INTERFACE != 0) out.add("interface")
        if (flags and ABSTRACT != 0) out.add("abstract")
        if (flags and ANNOTATION != 0) out.add("annotation")
        if (flags and ENUM != 0) out.add("enum")
        if (flags and SYNTHETIC != 0) out.add("synthetic")
        return out
    }

    fun fieldAccessFlags(flags: Int): MutableList<String> {
        val out = mutableListOf<String>()
        if (flags and PUBLIC != 0) out.add("public")
        if (flags and PRIVATE != 0) out.add("private")
        if (flags and PROTECTED != 0) out.add("protected")
        if (flags and STATIC != 0) out.add("static")
        if (flags and FINAL != 0) out.add("final")
        if (flags and VOLATILE != 0) out.add("volatile")
        if (flags and TRANSIENT != 0) out.add("transient")
        if (flags and SYNTHETIC != 0) out.add("synthetic")
        if (flags and ENUM != 0) out.add("enum")
        return out
    }

    fun methodAccessFlags(flags: Int): MutableList<String> {
        val out = mutableListOf<String>()
        if (flags and PUBLIC != 0) out.add("public")
        if (flags and PRIVATE != 0) out.add("private")
        if (flags and PROTECTED != 0) out.add("protected")
        if (flags and STATIC != 0) out.add("static")
        if (flags and FINAL != 0) out.add("final")
        if (flags and SYNCHRONIZED != 0) out.add("synchronized")
        if (flags and BRIDGE != 0) out.add("bridge")
        if (flags and VARARGS != 0) out.add("varargs")
        if (flags and NATIVE != 0) out.add("native")
        if (flags and ABSTRACT != 0) out.add("abstract")
        if (flags and STRICT != 0) out.add("strictfp")
        if (flags and CONSTRUCTOR != 0) out.add("constructor")
        if (flags and DECLARED_SYNCHRONIZED != 0) out.add("declared-synchronized")
        if (flags and SYNTHETIC != 0) out.add("synthetic")
        return out
    }
}
