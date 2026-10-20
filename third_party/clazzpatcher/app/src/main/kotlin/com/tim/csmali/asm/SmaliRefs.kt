package com.tim.csmali.asm

import com.tim.casm.pool.BootstrapMethodPool
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.csmali.disasm.ClassNames

/**
 * Разбор ссылок csmali в записи пула констант (синтаксис — см. [ClassNames]).
 * Типы-массивы (`[I`) — внутренние имена CONSTANT_Class как есть; ссылка с префиксом
 * `interface` — CONSTANT_InterfaceMethodref.
 */
class SmaliRefs(private val pool: ConstantPoolBuilder, private val bootstrap: BootstrapMethodPool) {

    class IndexedMethod(val index: Int, val descriptor: String)

    private val typeCache = HashMap<String, Int>()

    /** Индекс CONSTANT_Class по дескриптору `Lcls;` или `[..`. */
    fun type(desc: String): Int = typeCache.getOrPut(desc) {
        val s = SmaliScanner(desc)
        s.type()
        s.expectEnd()
        pool.classInfo(internalName(desc))
    }

    fun typeRef(s: SmaliScanner): Int {
        val t = s.type()
        if (t[0] != 'L' && t[0] != '[') s.error("Ожидался тип-класс или массив, получен $t")
        return type(t)
    }

    fun fieldRef(s: SmaliScanner): Int {
        val owner = s.type()
        if (!s.tryConsume("->")) s.error("Ожидалось '->'")
        val name = s.memberName()
        s.expect(':')
        return pool.fieldref(internalName(owner), name, s.type())
    }

    /** Ссылка на метод; [forceInterface] — у invokeinterface ссылка всегда на метод интерфейса. */
    fun methodRef(s: SmaliScanner, forceInterface: Boolean = false): IndexedMethod {
        val isInterface = s.tryConsume("interface ") || forceInterface
        val owner = s.type()
        if (!s.tryConsume("->")) s.error("Ожидалось '->'")
        val name = s.memberName()
        val (params, ret) = s.proto()
        val desc = "(${params.joinToString("")})$ret"
        val cls = internalName(owner)
        val idx = if (isInterface) pool.interfaceMethodref(cls, name, desc) else pool.methodref(cls, name, desc)
        return IndexedMethod(idx, desc)
    }

    /** `kind@ссылка` → CONSTANT_MethodHandle. */
    fun methodHandle(s: SmaliScanner): Int {
        s.skipWs()
        val at = s.text.indexOf('@', s.pos)
        if (at < 0) s.error("Ожидался method handle вида kind@ссылка")
        val kindName = s.text.substring(s.pos, at).trim()
        val kind = ClassNames.METHOD_HANDLE_KINDS.indexOf(kindName)
        if (kind <= 0) s.error("Неизвестный тип method handle: $kindName")
        s.pos = at + 1
        val ref = if (kind in 1..4) fieldRef(s) else methodRef(s).index
        return pool.methodHandle(kind, ref)
    }

    /** `имя(дескриптор), handle, { аргументы }` → CONSTANT_InvokeDynamic. */
    fun invokeDynamic(s: SmaliScanner): Int {
        s.skipWs()
        val name = s.memberName()
        val (params, ret) = s.proto()
        val nat = pool.nameAndType(name, "(${params.joinToString("")})$ret")
        s.expect(',')
        return pool.invokeDynamic(bootstrapMethod(s), nat)
    }

    /** `handle, { аргументы }` → индекс в BootstrapMethods. */
    private fun bootstrapMethod(s: SmaliScanner): Int {
        val handle = methodHandle(s)
        s.expect(',')
        s.expect('{')
        val args = ArrayList<Int>()
        if (!s.tryConsume('}')) {
            while (true) {
                args.add(constant(s, allowLong = true))
                if (s.tryConsume('}')) break
                s.expect(',')
            }
        }
        return bootstrap.add(handle, args)
    }

    /**
     * Загружаемая константа: число, строка, тип, method type `(..)R`, handle, `.dynamic`.
     * [allowLong] — допускаются long/double (ldc2_w и аргументы bootstrap).
     */
    fun constant(s: SmaliScanner, allowLong: Boolean): Int {
        s.skipWs()
        if (s.atEnd()) s.error("Ожидалась константа")
        val c = s.text[s.pos]
        return when {
            c == '"' -> pool.string(s.stringLiteral())
            c == '(' -> {
                val (params, ret) = s.proto()
                pool.methodType("(${params.joinToString("")})$ret")
            }
            c == 'L' || c == '[' -> type(s.type())
            s.startsWithAt(".dynamic") -> dynamic(s)
            isHandleStart(s) -> methodHandle(s)
            else -> literal(s.word(), allowLong, s)
        }
    }

    private fun isHandleStart(s: SmaliScanner): Boolean {
        var i = s.pos
        while (i < s.text.length && (s.text[i].isLetter() || s.text[i] == '-')) i++
        return i < s.text.length && s.text[i] == '@'
    }

    /** `.dynamic имя:тип, handle, { аргументы }` → CONSTANT_Dynamic. */
    private fun dynamic(s: SmaliScanner) : Int {
        if (!s.tryConsume(".dynamic")) s.error("Ожидалось .dynamic")
        s.skipWs()
        val name = s.memberName()
        s.expect(':')
        val nat = pool.nameAndType(name, s.type())
        s.expect(',')
        return pool.dynamic(bootstrapMethod(s), nat)
    }

    private fun literal(text: String, allowLong: Boolean, s: SmaliScanner): Int {
        val lit = try { SmaliLiterals.parse(text) } catch (e: IllegalArgumentException) { s.error("Некорректная константа $text: ${e.message}") }
        return when (lit.kind) {
            SmaliLiterals.Kind.INT, SmaliLiterals.Kind.SHORT, SmaliLiterals.Kind.BYTE, SmaliLiterals.Kind.CHAR ->
                pool.integer(lit.intValue)
            SmaliLiterals.Kind.BOOLEAN -> pool.integer(lit.intValue)
            SmaliLiterals.Kind.FLOAT -> pool.float(lit.bits.toInt())
            SmaliLiterals.Kind.LONG -> if (allowLong) pool.long(lit.bits) else s.error("long-константа $text требует ldc2_w")
            SmaliLiterals.Kind.DOUBLE -> if (allowLong) pool.double(lit.bits) else s.error("double-константа $text требует ldc2_w")
        }
    }

    companion object {
        /** Дескриптор класса/массива → внутреннее имя CONSTANT_Class. */
        fun internalName(desc: String): String = when {
            desc.startsWith("[") -> desc
            desc.startsWith("L") && desc.endsWith(";") -> desc.substring(1, desc.length - 1)
            else -> throw IllegalArgumentException("Ожидался тип Lcls; или массив, получен $desc")
        }
    }
}
