package com.tim.c2d

import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.*
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.Opcode
import com.tim.dex.model.CodeItem
import com.tim.dex.model.DexFile
import com.tim.dex.model.EncodedValue

/**
 * Интерпретатор байткода DEX для проверки смысла перевода: исполняет `main` программы
 * и возвращает её вывод, чтобы сравнить с `java`.
 *
 * Поддерживается то, что нужно тестовым программам: классы из самого DEX (поля, виртуальные
 * и default-методы, `<clinit>`), массивы, исключения, а из JDK — `Object`, `RuntimeException`,
 * `System.out.println`, `Array.newInstance` и примитивные `TYPE`. Регистры хранят «сырые»
 * значения как в Dalvik: 32 бита — `Int` (float — его биты), 64 бита — `Long` в первом регистре пары.
 */
class DexInterpreter(private val dex: DexFile) {

    /** Объект класса из DEX (или исключение JDK). */
    class Obj(val type: String) {
        val fields = HashMap<String, Any?>()
    }

    /** Массив с дескриптором. */
    class Arr(val desc: String, val data: Array<Any?>)

    /** Класс-литерал (`const-class`, `Integer.TYPE`). */
    class ClassRef(val desc: String)

    private object PrintStreamToken

    private class Thrown(val obj: Obj) : RuntimeException(obj.type)

    private class Method(val owner: String, val name: String, val params: List<String>, val ret: String,
                         val flags: Int, val code: CodeItem?) {
        val isStatic get() = flags and 0x8 != 0
        val key get() = name + params.joinToString("", "(", ")") + ret
    }

    private class ClassInfo(val desc: String, val superDesc: String?, val interfaces: List<String>,
                            val methods: Map<String, Method>, val staticValues: List<EncodedValue>,
                            val staticFields: List<Pair<String, String>>)

    private val classes = HashMap<String, ClassInfo>()
    private val statics = HashMap<String, Any?>()
    private val initialized = HashSet<String>()
    private val decodedCache = HashMap<CodeItem, DexDisassembler.Code>()
    val output = StringBuilder()
    private var steps = 0L

    init {
        for (c in dex.classDefs) {
            val desc = type(c.classIdx)
            val methods = HashMap<String, Method>()
            val staticFields = ArrayList<Pair<String, String>>()
            if (c.classDataIdx >= 0) {
                val data = dex.classDataItems[c.classDataIdx]
                for (m in data.directMethods + data.virtualMethods) {
                    val id = dex.methodIds[m.methodIdx]
                    val (params, ret) = proto(id.protoIdx)
                    val method = Method(desc, dex.strings[id.nameIdx], params, ret, m.accessFlags,
                        if (m.codeIdx >= 0) dex.codeItems[m.codeIdx] else null)
                    methods[method.key] = method
                }
                for (f in data.staticFields) staticFields.add(fieldKey(f.fieldIdx) to type(dex.fieldIds[f.fieldIdx].typeIdx))
            }
            val interfaces = if (c.interfacesTypeListIdx < 0) emptyList()
            else dex.typeLists[c.interfacesTypeListIdx].typeIdx.map { type(it) }
            val values = if (c.staticValuesIdx < 0) emptyList() else dex.encodedArrayItems[c.staticValuesIdx]
            classes[desc] = ClassInfo(desc, if (c.superclassIdx < 0) null else type(c.superclassIdx), interfaces, methods, values, staticFields)
        }
    }

    /** Исполнить `public static void main(String[])` класса [mainClass]; вывод — [output]. */
    fun runMain(mainClass: String): String {
        val cls = classes[mainClass] ?: throw IllegalArgumentException("нет класса $mainClass")
        val main = cls.methods["main([Ljava/lang/String;)V"] ?: throw IllegalArgumentException("нет main в $mainClass")
        ensureInit(mainClass)
        try {
            execute(main, arrayOf(Arr("[Ljava/lang/String;", arrayOf())))
        } catch (t: Thrown) {
            output.append("НЕПЕРЕХВАЧЕННОЕ ИСКЛЮЧЕНИЕ ${t.obj.type}: ${t.obj.fields["message"]}\n")
        }
        return output.toString()
    }

    // ---- пулы ----

    private fun type(idx: Int) = dex.strings[dex.typeIds[idx].descriptorIdx]

    private fun proto(idx: Int): Pair<List<String>, String> {
        val p = dex.protoIds[idx]
        val params = if (p.parametersTypeListIdx < 0) emptyList() else dex.typeLists[p.parametersTypeListIdx].typeIdx.map { type(it) }
        return params to type(p.returnTypeIdx)
    }

    private fun fieldKey(idx: Int): String {
        val f = dex.fieldIds[idx]
        return type(f.classIdx) + "->" + dex.strings[f.nameIdx]
    }

    // ---- классы ----

    private fun ensureInit(desc: String) {
        val cls = classes[desc] ?: return
        if (!initialized.add(desc)) return
        cls.superDesc?.let { ensureInit(it) }
        for ((i, field) in cls.staticFields.withIndex()) {
            statics[field.first] = cls.staticValues.getOrNull(i)?.let { staticValue(it) } ?: defaultOf(field.second)
        }
        cls.methods["<clinit>()V"]?.let { execute(it, emptyArray()) }
    }

    private fun staticValue(v: EncodedValue): Any? = when (v.type) {
        EncodedValueType.VALUE_BOOLEAN -> if (v.value as Boolean) 1 else 0
        EncodedValueType.VALUE_BYTE, EncodedValueType.VALUE_SHORT, EncodedValueType.VALUE_INT -> (v.value as Number).toInt()
        EncodedValueType.VALUE_CHAR -> (v.value as Char).code
        EncodedValueType.VALUE_LONG -> (v.value as Number).toLong()
        EncodedValueType.VALUE_FLOAT -> java.lang.Float.floatToRawIntBits((v.value as Number).toFloat())
        EncodedValueType.VALUE_DOUBLE -> java.lang.Double.doubleToRawLongBits((v.value as Number).toDouble())
        EncodedValueType.VALUE_STRING -> dex.strings[(v.value as Number).toInt()]
        EncodedValueType.VALUE_NULL -> null
        else -> throw IllegalStateException("static_values: тип 0x${Integer.toHexString(v.type)} не поддерживается")
    }

    private fun defaultOf(desc: String): Any? = when (desc[0]) {
        'J', 'D' -> 0L
        'L', '[' -> null
        else -> 0
    }

    private fun isSubtype(sub: String, sup: String): Boolean {
        if (sub == sup || sup == "Ljava/lang/Object;") return true
        if (sub.startsWith("[") || sup.startsWith("[")) {
            if (!sub.startsWith("[") || !sup.startsWith("[")) return false
            val a = sub.substring(1)
            val b = sup.substring(1)
            return if (a[0] == 'L' || a[0] == '[') (b[0] == 'L' || b[0] == '[') && isSubtype(a, b) else a == b
        }
        val cls = classes[sub]
        if (cls != null) {
            if (cls.superDesc != null && isSubtype(cls.superDesc, sup)) return true
            return cls.interfaces.any { isSubtype(it, sup) }
        }
        val parent = JDK_PARENTS[sub] ?: return false
        return isSubtype(parent, sup)
    }

    private fun typeOf(v: Any?): String = when (v) {
        is Obj -> v.type
        is Arr -> v.desc
        is String -> "Ljava/lang/String;"
        is ClassRef -> "Ljava/lang/Class;"
        else -> "Ljava/lang/Object;"
    }

    private fun exception(type: String, message: String? = null): Thrown {
        val o = Obj(type)
        o.fields["message"] = message
        return Thrown(o)
    }

    // ---- вызовы ----

    private fun findVirtual(desc: String, key: String): Method? {
        var c: String? = desc
        while (c != null) {
            val cls = classes[c] ?: break
            cls.methods[key]?.let { if (!it.isStatic && it.code != null) return it }
            c = cls.superDesc
        }
        // default-методы интерфейсов
        val queue = ArrayDeque<String>()
        c = desc
        while (c != null) { val cls = classes[c] ?: break; queue.addAll(cls.interfaces); c = cls.superDesc }
        while (queue.isNotEmpty()) {
            val i = classes[queue.removeFirst()] ?: continue
            i.methods[key]?.let { if (it.code != null) return it }
            queue.addAll(i.interfaces)
        }
        return null
    }

    private fun invoke(op: Opcode, methodIdx: Int, args: Array<Any?>): Any? {
        val id = dex.methodIds[methodIdx]
        val owner = type(id.classIdx)
        val name = dex.strings[id.nameIdx]
        val (params, ret) = proto(id.protoIdx)
        val key = name + params.joinToString("", "(", ")") + ret
        val m = op.mnemonic
        val target: Method? = when {
            m.startsWith("invoke-static") -> { ensureInit(owner); classes[owner]?.methods?.get(key) }
            m.startsWith("invoke-direct") -> classes[owner]?.methods?.get(key)
            m.startsWith("invoke-super") -> findVirtual(owner, key)
            else -> {
                val recv = args[0] ?: throw exception("Ljava/lang/NullPointerException;")
                findVirtual(typeOf(recv), key)
            }
        }
        if (target?.code != null) return execute(target, args)
        return jdk(owner, name, key, args)
    }

    private fun jdk(owner: String, name: String, key: String, args: Array<Any?>): Any? {
        if (owner == "Ljava/lang/StringBuilder;") {
            val o = args[0] as Obj
            when {
                key == "<init>()V" -> { o.fields["sb"] = StringBuilder(); return null }
                key == "<init>(Ljava/lang/String;)V" -> { o.fields["sb"] = StringBuilder(args[1] as String); return null }
                name == "append" -> {
                    val param = key.substring(key.indexOf('(') + 1, key.indexOf(')'))
                    (o.fields["sb"] as StringBuilder).append(text(param, args[1]))
                    return o
                }
                key == "toString()Ljava/lang/String;" -> return (o.fields["sb"] as StringBuilder).toString()
            }
        }
        when {
            name == "<init>" && key == "<init>()V" -> return null
            name == "<init>" && key == "<init>(Ljava/lang/String;)V" -> { (args[0] as Obj).fields["message"] = args[1]; return null }
            key == "getMessage()Ljava/lang/String;" -> return (args[0] as Obj).fields["message"]
            owner == "Ljava/lang/String;" && key == "length()I" -> return (args[0] as String).length
            owner == "Ljava/util/Objects;" && key == "requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;" ->
                return args[0] ?: throw exception("Ljava/lang/NullPointerException;")
            owner == "Ljava/io/PrintStream;" && name == "println" -> {
                output.append(render(key, args.getOrNull(1))).append('\n'); return null
            }
            key == "clone()Ljava/lang/Object;" && args[0] is Arr -> {
                val a = args[0] as Arr
                return Arr(a.desc, a.data.copyOf())
            }
            owner == "Ljava/lang/reflect/Array;" && key == "newInstance(Ljava/lang/Class;[I)Ljava/lang/Object;" -> {
                val component = (args[0] as ClassRef).desc
                val dims = (args[1] as Arr).data.map { it as Int }
                return multiArray("[".repeat(dims.size) + component, dims, 0)
            }
        }
        throw IllegalStateException("интерпретатор: нет реализации $owner->$key")
    }

    /** Строковое представление значения типа [param] (как String.valueOf). */
    private fun text(param: String, v: Any?): String = when (param) {
        "J" -> (v as Long).toString()
        "I", "S", "B" -> (v as Int).toString()
        "Z" -> ((v as Int) != 0).toString()
        "C" -> (v as Int).toChar().toString()
        "D" -> java.lang.Double.longBitsToDouble(v as Long).toString()
        "F" -> java.lang.Float.intBitsToFloat(v as Int).toString()
        else -> when (v) {
            null -> "null"
            is String -> v
            is Obj -> (v.fields["sb"] as? StringBuilder)?.toString() ?: v.type
            else -> v.toString()
        }
    }

    private fun render(key: String, v: Any?): String = when {
        key.startsWith("println(J)") -> (v as Long).toString()
        key.startsWith("println(I)") -> (v as Int).toString()
        key.startsWith("println(Z)") -> ((v as Int) != 0).toString()
        key.startsWith("println(C)") -> (v as Int).toChar().toString()
        key.startsWith("println(D)") -> java.lang.Double.longBitsToDouble(v as Long).toString()
        key.startsWith("println(F)") -> java.lang.Float.intBitsToFloat(v as Int).toString()
        key.startsWith("println(Ljava/lang/String;)") -> v as String? ?: "null"
        key.startsWith("println(Ljava/lang/Object;)") -> text("L", v)
        else -> throw IllegalStateException("println: $key")
    }

    private fun newArray(desc: String, size: Int): Arr {
        if (size < 0) throw exception("Ljava/lang/NegativeArraySizeException;")
        val zero: Any? = when (desc[1]) { 'J', 'D' -> 0L; 'L', '[' -> null; else -> 0 }
        return Arr(desc, arrayOfNulls<Any?>(size).also { a -> if (zero != null) a.fill(zero) })
    }

    private fun multiArray(desc: String, dims: List<Int>, level: Int): Arr {
        val a = newArray(desc, dims[level])
        if (level + 1 < dims.size) for (i in a.data.indices) a.data[i] = multiArray(desc.substring(1), dims, level + 1)
        return a
    }

    // ---- исполнение метода ----

    private fun execute(m: Method, args: Array<Any?>): Any? {
        val code = m.code ?: throw IllegalStateException("у ${m.owner}->${m.key} нет кода")
        val decoded = decodedCache.getOrPut(code) { DexDisassembler().decode(code.insns) }
        val insns = decoded.instructions
        val regs = arrayOfNulls<Any?>(code.registersSize)
        var r = code.registersSize - code.insSize
        var a = 0
        if (!m.isStatic) regs[r++] = args[a++]
        for (p in m.params) {
            regs[r] = args[a++]
            r += if (p == "J" || p == "D") 2 else 1
        }
        var result: Any? = null
        var exception: Obj? = null
        var i = 0
        while (true) {
            if (++steps > 50_000_000) throw IllegalStateException("слишком долго: зацикливание?")
            val insn = insns[i]
            val addr = decoded.offsets[i]
            var next = i + 1
            try {
                val op = insn.opcode
                fun jump(offset: Int) { next = decoded.indexAt(addr + offset) }
                when (insn) {
                    is Ins10x -> if (op == Opcode.RETURN_VOID) return null
                    is Ins12x -> regs12x(op, insn.regA, insn.regB, regs)
                    is Ins22x -> regs[insn.regA] = regs[insn.regB]
                    is Ins32x -> regs[insn.regA] = regs[insn.regB]
                    is Ins11n -> regs[insn.regA] = insn.value
                    is Ins21s -> regs[insn.regA] = if (op == Opcode.CONST_16) insn.value else insn.value.toLong()
                    is Ins21h -> regs[insn.regA] = if (op == Opcode.CONST_HIGH16) insn.value shl 16 else insn.value.toLong() shl 48
                    is Ins31i -> regs[insn.regA] = if (op == Opcode.CONST) insn.value else insn.value.toLong()
                    is Ins51l -> regs[insn.regA] = insn.value
                    is Ins11x -> when (op) {
                        Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT -> regs[insn.regA] = result
                        Opcode.MOVE_EXCEPTION -> regs[insn.regA] = exception
                        Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT -> return regs[insn.regA]
                        Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT -> if (regs[insn.regA] == null) throw exception("Ljava/lang/NullPointerException;")
                        Opcode.THROW -> throw Thrown(regs[insn.regA] as Obj? ?: throw exception("Ljava/lang/NullPointerException;"))
                        else -> throw IllegalStateException("${op.mnemonic}")
                    }
                    is Ins21c -> regs21c(op, insn.regA, insn.index, regs)
                    is Ins31c -> regs[insn.regA] = dex.strings[insn.index]
                    is Ins22c -> regs22c(op, insn.regA, insn.regB, insn.index, regs)
                    is Ins23x -> regs23x(op, insn.regA, insn.regB, insn.regC, regs)
                    is Ins22b -> regs[insn.regA] = litOp(op, regs[insn.regB] as Int, insn.value)
                    is Ins22s -> regs[insn.regA] = litOp(op, regs[insn.regB] as Int, insn.value)
                    is Ins10t -> jump(insn.offset)
                    is Ins20t -> jump(insn.offset)
                    is Ins30t -> jump(insn.offset)
                    is Ins21t -> if (testZero(op, regs[insn.regA])) jump(insn.offset)
                    is Ins22t -> if (test(op, regs[insn.regA], regs[insn.regB])) jump(insn.offset)
                    is Ins31t -> {
                        val payload = insns[decoded.payloadIndex[i]]
                        when (payload) {
                            is PackedSwitchData -> {
                                val k = (regs[insn.regA] as Int).toLong() - payload.firstKey
                                if (k >= 0 && k < payload.size) next = decoded.indexAt(payload.targets[k.toInt()])
                            }
                            is SparseSwitchData -> {
                                val k = payload.keys.indexOf(regs[insn.regA] as Int)
                                if (k >= 0) next = decoded.indexAt(payload.targets[k])
                            }
                            is FillArrayData -> {
                                val arr = regs[insn.regA] as Arr? ?: throw exception("Ljava/lang/NullPointerException;")
                                if (payload.size > arr.data.size) throw exception("Ljava/lang/ArrayIndexOutOfBoundsException;")
                                for (k in 0 until payload.size) {
                                    var bits = 0L
                                    for (b in 0 until payload.elementSize) {
                                        bits = bits or ((payload.data[k * payload.elementSize + b].toLong() and 0xFF) shl (8 * b))
                                    }
                                    arr.data[k] = when (arr.desc[1]) {
                                        'J', 'D' -> bits
                                        'B' -> bits.toByte().toInt()
                                        'S' -> bits.toShort().toInt()
                                        else -> bits.toInt()
                                    }
                                }
                            }
                            else -> throw IllegalStateException("неожиданный payload: $payload")
                        }
                    }
                    is Ins35c -> result = call(op, insn.index, insn.getRegisters(), regs)
                    is Ins3rc -> result = call(op, insn.index, (insn.regStart until insn.regStart + insn.regCount).toList(), regs)
                    else -> throw IllegalStateException("инструкция ${insn.opcode.mnemonic} не поддерживается")
                }
            } catch (t: Thrown) {
                val handler = findHandler(code, decoded, addr, t.obj) ?: throw t
                exception = t.obj
                next = handler
            }
            i = next
        }
    }

    private fun findHandler(code: CodeItem, decoded: DexDisassembler.Code, addr: Int, e: Obj): Int? {
        for (t in code.tries) {
            if (addr < t.startAddr || addr >= t.startAddr + t.insnCount) continue
            val h = code.catchHandlers[t.handlerIdx]
            for (p in h.handlers) if (isSubtype(e.type, type(p.typeIdx))) return decoded.indexAt(p.addr)
            if (h.catchAllAddr >= 0) return decoded.indexAt(h.catchAllAddr)
        }
        return null
    }

    private fun call(op: Opcode, index: Int, regList: List<Int>, regs: Array<Any?>): Any? {
        if (op == Opcode.FILLED_NEW_ARRAY || op == Opcode.FILLED_NEW_ARRAY_RANGE) {
            return Arr(type(index), Array(regList.size) { regs[regList[it]] })
        }
        val id = dex.methodIds[index]
        val (params, _) = proto(id.protoIdx)
        val args = ArrayList<Any?>()
        var k = 0
        val isStatic = op == Opcode.INVOKE_STATIC || op == Opcode.INVOKE_STATIC_RANGE
        if (!isStatic) args.add(regs[regList[k++]])
        for (p in params) {
            args.add(regs[regList[k]])
            k += if (p == "J" || p == "D") 2 else 1
        }
        return invoke(op, index, args.toTypedArray())
    }

    private fun regs12x(op: Opcode, a: Int, b: Int, regs: Array<Any?>) {
        val v = regs[b]
        regs[a] = when (op) {
            Opcode.MOVE, Opcode.MOVE_WIDE, Opcode.MOVE_OBJECT -> v
            Opcode.ARRAY_LENGTH -> (v as Arr? ?: throw exception("Ljava/lang/NullPointerException;")).data.size
            Opcode.NEG_INT -> -(v as Int)
            Opcode.NOT_INT -> (v as Int).inv()
            Opcode.NEG_LONG -> -(v as Long)
            Opcode.NOT_LONG -> (v as Long).inv()
            Opcode.NEG_FLOAT -> fb(-f(v))
            Opcode.NEG_DOUBLE -> db(-d(v))
            Opcode.INT_TO_LONG -> (v as Int).toLong()
            Opcode.INT_TO_FLOAT -> fb((v as Int).toFloat())
            Opcode.INT_TO_DOUBLE -> db((v as Int).toDouble())
            Opcode.LONG_TO_INT -> (v as Long).toInt()
            Opcode.LONG_TO_FLOAT -> fb((v as Long).toFloat())
            Opcode.LONG_TO_DOUBLE -> db((v as Long).toDouble())
            Opcode.FLOAT_TO_INT -> f(v).toInt()
            Opcode.FLOAT_TO_LONG -> f(v).toLong()
            Opcode.FLOAT_TO_DOUBLE -> db(f(v).toDouble())
            Opcode.DOUBLE_TO_INT -> d(v).toInt()
            Opcode.DOUBLE_TO_LONG -> d(v).toLong()
            Opcode.DOUBLE_TO_FLOAT -> fb(d(v).toFloat())
            Opcode.INT_TO_BYTE -> (v as Int).toByte().toInt()
            Opcode.INT_TO_CHAR -> (v as Int).toChar().code
            Opcode.INT_TO_SHORT -> (v as Int).toShort().toInt()
            else -> {
                val base = Opcode.valueOf(op.name.removeSuffix("_2ADDR"))
                arith(base, regs[a], v)
            }
        }
    }

    private fun regs21c(op: Opcode, a: Int, index: Int, regs: Array<Any?>) {
        when (op) {
            Opcode.CONST_STRING -> regs[a] = dex.strings[index]
            Opcode.CONST_CLASS -> regs[a] = ClassRef(type(index))
            Opcode.CHECK_CAST -> {
                val v = regs[a]
                if (v != null && !isSubtype(typeOf(v), type(index))) throw exception("Ljava/lang/ClassCastException;")
            }
            Opcode.NEW_INSTANCE -> { val t = type(index); ensureInit(t); regs[a] = Obj(t) }
            else -> {
                val f = dex.fieldIds[index]
                val owner = type(f.classIdx)
                val name = dex.strings[f.nameIdx]
                if (owner == "Ljava/lang/System;" && name == "out") { regs[a] = PrintStreamToken; return }
                if (name == "TYPE" && owner.startsWith("Ljava/lang/")) {
                    regs[a] = ClassRef(PRIMITIVE_BOXES[owner] ?: throw IllegalStateException("TYPE у $owner")); return
                }
                ensureInit(owner)
                val key = "$owner->$name"
                if (op.mnemonic.startsWith("sget")) regs[a] = statics[key] else statics[key] = regs[a]
            }
        }
    }

    private fun regs22c(op: Opcode, a: Int, b: Int, index: Int, regs: Array<Any?>) {
        when (op) {
            Opcode.INSTANCE_OF -> regs[a] = regs[b].let { if (it != null && isSubtype(typeOf(it), type(index))) 1 else 0 }
            Opcode.NEW_ARRAY -> regs[a] = newArray(type(index), regs[b] as Int)
            else -> {
                val obj = regs[b] as Obj? ?: throw exception("Ljava/lang/NullPointerException;")
                val f = dex.fieldIds[index]
                val key = dex.strings[f.nameIdx]
                if (op.mnemonic.startsWith("iget")) {
                    regs[a] = obj.fields[key] ?: defaultOf(type(f.typeIdx))
                } else {
                    obj.fields[key] = regs[a]
                }
            }
        }
    }

    private fun regs23x(op: Opcode, a: Int, b: Int, c: Int, regs: Array<Any?>) {
        val m = op.mnemonic
        if (m.startsWith("aget") || m.startsWith("aput")) {
            val arr = regs[b] as Arr? ?: throw exception("Ljava/lang/NullPointerException;")
            val idx = regs[c] as Int
            if (idx < 0 || idx >= arr.data.size) throw exception("Ljava/lang/ArrayIndexOutOfBoundsException;")
            if (m.startsWith("aget")) {
                regs[a] = arr.data[idx]
            } else {
                val v = regs[a]
                arr.data[idx] = when (op) {
                    Opcode.APUT_BOOLEAN -> (v as Int) and 1
                    Opcode.APUT_BYTE -> (v as Int).toByte().toInt()
                    Opcode.APUT_CHAR -> (v as Int).toChar().code
                    Opcode.APUT_SHORT -> (v as Int).toShort().toInt()
                    Opcode.APUT_OBJECT -> {
                        if (v != null && !isSubtype(typeOf(v), arr.desc.substring(1))) throw exception("Ljava/lang/ArrayStoreException;")
                        v
                    }
                    else -> v
                }
            }
            return
        }
        regs[a] = when (op) {
            Opcode.CMP_LONG -> (regs[b] as Long).compareTo(regs[c] as Long).coerceIn(-1, 1)
            Opcode.CMPL_FLOAT -> fcmp(f(regs[b]).toDouble(), f(regs[c]).toDouble(), -1)
            Opcode.CMPG_FLOAT -> fcmp(f(regs[b]).toDouble(), f(regs[c]).toDouble(), 1)
            Opcode.CMPL_DOUBLE -> fcmp(d(regs[b]), d(regs[c]), -1)
            Opcode.CMPG_DOUBLE -> fcmp(d(regs[b]), d(regs[c]), 1)
            else -> arith(op, regs[b], regs[c])
        }
    }

    private fun fcmp(x: Double, y: Double, nan: Int): Int = when {
        x.isNaN() || y.isNaN() -> nan
        x < y -> -1
        x > y -> 1
        else -> 0
    }

    private fun arith(op: Opcode, x: Any?, y: Any?): Any = when (op) {
        Opcode.ADD_INT -> (x as Int) + (y as Int)
        Opcode.SUB_INT -> (x as Int) - (y as Int)
        Opcode.MUL_INT -> (x as Int) * (y as Int)
        Opcode.DIV_INT -> { if (y as Int == 0) throw exception("Ljava/lang/ArithmeticException;", "/ by zero"); (x as Int) / y }
        Opcode.REM_INT -> { if (y as Int == 0) throw exception("Ljava/lang/ArithmeticException;", "/ by zero"); (x as Int) % y }
        Opcode.AND_INT -> (x as Int) and (y as Int)
        Opcode.OR_INT -> (x as Int) or (y as Int)
        Opcode.XOR_INT -> (x as Int) xor (y as Int)
        Opcode.SHL_INT -> (x as Int) shl (y as Int)
        Opcode.SHR_INT -> (x as Int) shr (y as Int)
        Opcode.USHR_INT -> (x as Int) ushr (y as Int)
        Opcode.ADD_LONG -> (x as Long) + (y as Long)
        Opcode.SUB_LONG -> (x as Long) - (y as Long)
        Opcode.MUL_LONG -> (x as Long) * (y as Long)
        Opcode.DIV_LONG -> { if (y as Long == 0L) throw exception("Ljava/lang/ArithmeticException;", "/ by zero"); (x as Long) / y }
        Opcode.REM_LONG -> { if (y as Long == 0L) throw exception("Ljava/lang/ArithmeticException;", "/ by zero"); (x as Long) % y }
        Opcode.AND_LONG -> (x as Long) and (y as Long)
        Opcode.OR_LONG -> (x as Long) or (y as Long)
        Opcode.XOR_LONG -> (x as Long) xor (y as Long)
        Opcode.SHL_LONG -> (x as Long) shl (y as Int)
        Opcode.SHR_LONG -> (x as Long) shr (y as Int)
        Opcode.USHR_LONG -> (x as Long) ushr (y as Int)
        Opcode.ADD_FLOAT -> fb(f(x) + f(y))
        Opcode.SUB_FLOAT -> fb(f(x) - f(y))
        Opcode.MUL_FLOAT -> fb(f(x) * f(y))
        Opcode.DIV_FLOAT -> fb(f(x) / f(y))
        Opcode.REM_FLOAT -> fb(f(x) % f(y))
        Opcode.ADD_DOUBLE -> db(d(x) + d(y))
        Opcode.SUB_DOUBLE -> db(d(x) - d(y))
        Opcode.MUL_DOUBLE -> db(d(x) * d(y))
        Opcode.DIV_DOUBLE -> db(d(x) / d(y))
        Opcode.REM_DOUBLE -> db(d(x) % d(y))
        else -> throw IllegalStateException("арифметика ${op.mnemonic}")
    }

    private fun litOp(op: Opcode, x: Int, lit: Int): Int = when (op) {
        Opcode.ADD_INT_LIT8, Opcode.ADD_INT_LIT16 -> x + lit
        Opcode.RSUB_INT_LIT8, Opcode.RSUB_INT -> lit - x
        Opcode.MUL_INT_LIT8, Opcode.MUL_INT_LIT16 -> x * lit
        Opcode.DIV_INT_LIT8, Opcode.DIV_INT_LIT16 -> { if (lit == 0) throw exception("Ljava/lang/ArithmeticException;", "/ by zero"); x / lit }
        Opcode.REM_INT_LIT8, Opcode.REM_INT_LIT16 -> { if (lit == 0) throw exception("Ljava/lang/ArithmeticException;", "/ by zero"); x % lit }
        Opcode.AND_INT_LIT8, Opcode.AND_INT_LIT16 -> x and lit
        Opcode.OR_INT_LIT8, Opcode.OR_INT_LIT16 -> x or lit
        Opcode.XOR_INT_LIT8, Opcode.XOR_INT_LIT16 -> x xor lit
        Opcode.SHL_INT_LIT8 -> x shl lit
        Opcode.SHR_INT_LIT8 -> x shr lit
        Opcode.USHR_INT_LIT8 -> x ushr lit
        else -> throw IllegalStateException("литерал ${op.mnemonic}")
    }

    private fun testZero(op: Opcode, v: Any?): Boolean {
        val x: Int = when (v) { null -> 0; is Int -> v; else -> 1 }
        return when (op) {
            Opcode.IF_EQZ -> x == 0
            Opcode.IF_NEZ -> x != 0
            Opcode.IF_LTZ -> x < 0
            Opcode.IF_GEZ -> x >= 0
            Opcode.IF_GTZ -> x > 0
            else -> x <= 0
        }
    }

    private fun test(op: Opcode, a: Any?, b: Any?): Boolean {
        if (a !is Int || b !is Int) {
            val same = if (a is Int || b is Int) (a ?: 0) == (b ?: 0) else a === b
            return if (op == Opcode.IF_EQ) same else !same
        }
        return when (op) {
            Opcode.IF_EQ -> a == b
            Opcode.IF_NE -> a != b
            Opcode.IF_LT -> a < b
            Opcode.IF_GE -> a >= b
            Opcode.IF_GT -> a > b
            else -> a <= b
        }
    }

    private fun f(v: Any?) = java.lang.Float.intBitsToFloat(v as Int)
    private fun d(v: Any?) = java.lang.Double.longBitsToDouble(v as Long)
    private fun fb(x: Float) = java.lang.Float.floatToRawIntBits(x)
    private fun db(x: Double) = java.lang.Double.doubleToRawLongBits(x)

    private companion object {
        val JDK_PARENTS = mapOf(
            "Ljava/lang/ArithmeticException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/ArrayIndexOutOfBoundsException;" to "Ljava/lang/IndexOutOfBoundsException;",
            "Ljava/lang/IndexOutOfBoundsException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/NegativeArraySizeException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/NullPointerException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/ClassCastException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/ArrayStoreException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/IllegalStateException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/IllegalArgumentException;" to "Ljava/lang/RuntimeException;",
            "Ljava/lang/RuntimeException;" to "Ljava/lang/Exception;",
            "Ljava/lang/Exception;" to "Ljava/lang/Throwable;",
            "Ljava/lang/Throwable;" to "Ljava/lang/Object;"
        )
        val PRIMITIVE_BOXES = mapOf(
            "Ljava/lang/Integer;" to "I", "Ljava/lang/Long;" to "J", "Ljava/lang/Boolean;" to "Z",
            "Ljava/lang/Byte;" to "B", "Ljava/lang/Short;" to "S", "Ljava/lang/Character;" to "C",
            "Ljava/lang/Float;" to "F", "Ljava/lang/Double;" to "D"
        )
    }
}
