package com.tim.c2d.desugar

import com.tim.c2d.bytecode.Descriptors
import com.tim.c2d.bytecode.PoolConstant
import com.tim.casm.CasmState
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.clazz.model.MethodInfo

/**
 * Статические методы-заменители invokedynamic в классе-хозяине. Семантика — как у реализаций JDK 21:
 * StringConcatFactory (String.valueOf каждого аргумента по порядку), ObjectMethods (toString
 * `Имя[a=…, b=…]`; equals: тождество, isInstance, затем поля с последнего — float/double через
 * compare, ссылки через Objects.equals; hashCode: `h = h*31 + hash(поле)`), SwitchBootstraps
 * (null → -1, проверка restart, первая подходящая метка не раньше restart, иначе число меток).
 */
internal object Helpers {

    private const val SB = "java/lang/StringBuilder"

    // ---- Конкатенация строк ----

    /** [args] — рецепт и константы makeConcatWithConstants; null — makeConcat (все аргументы подряд). */
    fun concat(state: CasmState, h: ClassHierarchy, host: String, name: String, desc: String, flags: Int, args: List<PoolConstant>?): MethodInfo {
        val type = Descriptors.method(desc)
        val code = CodeDsl()
        val e = Emit(code, state)
        val recipe = if (args == null) "\u0001".repeat(type.params.size)
        else (args.firstOrNull() as? PoolConstant.StringValue)?.value ?: throw IllegalArgumentException("нет рецепта конкатенации")
        e.newObject(SB)
        e.invokespecial(SB, "<init>", "()V")
        val literal = StringBuilder()
        fun flush() {
            if (literal.isEmpty()) return
            e.ldcString(literal.toString())
            append(e, "Ljava/lang/String;")
            literal.setLength(0)
        }
        var arg = 0
        var slot = 0
        var constant = 1
        for (ch in recipe) {
            when (ch) {
                '\u0001' -> {
                    flush()
                    val t = type.params.getOrNull(arg++) ?: throw IllegalArgumentException("в рецепте больше аргументов, чем в $desc")
                    e.load(t, slot)
                    slot += Descriptors.width(t)
                    append(e, t)
                }
                '\u0002' -> {
                    val c = args?.getOrNull(constant++) ?: throw IllegalArgumentException("в рецепте больше констант, чем аргументов bootstrap")
                    if (c is PoolConstant.StringValue) literal.append(c.value) else {
                        flush()
                        append(e, pushConstant(e, c))
                    }
                }
                else -> literal.append(ch)
            }
        }
        flush()
        if (arg != type.params.size) throw IllegalArgumentException("в рецепте меньше аргументов, чем в $desc")
        e.invokevirtual(SB, "toString", "()Ljava/lang/String;")
        code.areturn()
        return assembleMethod(state, h, host, name, desc, flags, code)
    }

    /** `StringBuilder.append` для значения типа [t] (как String.valueOf: объекты и массивы — через Object). */
    private fun append(e: Emit, t: String) {
        val p = when (t[0]) {
            'Z' -> "Z"; 'C' -> "C"; 'B', 'S', 'I' -> "I"; 'J' -> "J"; 'F' -> "F"; 'D' -> "D"
            else -> if (t == "Ljava/lang/String;") t else "Ljava/lang/Object;"
        }
        e.invokevirtual(SB, "append", "($p)L$SB;")
    }

    /** Константа bootstrap на стек; её тип. */
    private fun pushConstant(e: Emit, c: PoolConstant): String {
        val pool = e.pool
        val code = e.code
        return when (c) {
            is PoolConstant.IntValue -> { e.pushInt(c.value); "I" }
            is PoolConstant.LongValue -> { code.ldc2W(pool.long(c.value)); "J" }
            is PoolConstant.FloatValue -> { code.ldc(pool.float(c.bits)); "F" }
            is PoolConstant.DoubleValue -> { code.ldc2W(pool.double(c.bits)); "D" }
            is PoolConstant.StringValue -> { code.ldc(pool.string(c.value)); "Ljava/lang/String;" }
            is PoolConstant.ClassValue -> { code.ldc(pool.classInfo(Emit.internal(c.descriptor))); "Ljava/lang/Class;" }
            is PoolConstant.MethodTypeValue -> { code.ldc(pool.methodType(c.descriptor)); "Ljava/lang/invoke/MethodType;" }
            is PoolConstant.MethodHandleValue -> throw IllegalArgumentException("константа MethodHandle в рецепте не поддерживается")
        }
    }

    // ---- Методы record ----

    fun recordMethod(state: CasmState, h: ClassHierarchy, host: String, name: String, desc: String, flags: Int,
                     method: String, args: List<PoolConstant>): MethodInfo {
        val record = (args.getOrNull(0) as? PoolConstant.ClassValue)?.descriptor ?: throw IllegalArgumentException("ObjectMethods: нет класса record")
        val names = (args.getOrNull(1) as? PoolConstant.StringValue)?.value?.let { if (it.isEmpty()) emptyList() else it.split(';') }
            ?: throw IllegalArgumentException("ObjectMethods: нет имён компонент")
        val getters = args.drop(2).map { it as? PoolConstant.MethodHandleValue ?: throw IllegalArgumentException("ObjectMethods: геттер — не MethodHandle") }
        if (names.size != getters.size) throw IllegalArgumentException("ObjectMethods: ${names.size} имён и ${getters.size} геттеров")
        val code = CodeDsl()
        val e = Emit(code, state)
        when (method) {
            "toString" -> {
                expect(desc, "($record)Ljava/lang/String;")
                e.newObject(SB)
                e.ldcClass(record)
                e.invokevirtual("java/lang/Class", "getSimpleName", "()Ljava/lang/String;")
                e.invokespecial(SB, "<init>", "(Ljava/lang/String;)V")
                var prefix = "["
                for ((k, g) in getters.withIndex()) {
                    e.ldcString(prefix + names[k] + "=")
                    append(e, "Ljava/lang/String;")
                    code.aload(0)
                    append(e, get(e, g))
                    prefix = ", "
                }
                e.ldcString(if (getters.isEmpty()) "[]" else "]")
                append(e, "Ljava/lang/String;")
                e.invokevirtual(SB, "toString", "()Ljava/lang/String;")
                code.areturn()
            }
            "equals" -> {
                expect(desc, "(${record}Ljava/lang/Object;)Z")
                code.aload(0); code.aload(1); code.ifAcmpne("notSame")
                code.iconst(1); code.ireturn()
                code.label("notSame")
                code.aload(1); e.instanceOf(record); code.ifne("same")
                code.iconst(0); code.ireturn()
                code.label("same")
                code.aload(1); e.checkcast(record); code.astore(2)
                // guardWithTest в JDK вкладывает проверки так, что первой идёт последняя компонента.
                for (g in getters.reversed()) {
                    code.aload(0)
                    val t = get(e, g)
                    code.aload(2)
                    get(e, g)
                    when (t[0]) {
                        'Z', 'B', 'C', 'S', 'I' -> code.ifIcmpne("false")
                        'J' -> { code.lcmp(); code.ifne("false") }
                        'F' -> { e.invokestatic("java/lang/Float", "compare", "(FF)I"); code.ifne("false") }
                        'D' -> { e.invokestatic("java/lang/Double", "compare", "(DD)I"); code.ifne("false") }
                        else -> { e.invokestatic("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z"); code.ifeq("false") }
                    }
                }
                code.iconst(1); code.ireturn()
                code.label("false")
                code.iconst(0); code.ireturn()
            }
            "hashCode" -> {
                expect(desc, "($record)I")
                code.iconst(0)
                for (g in getters) {
                    code.bipush(31); code.imul()
                    code.aload(0)
                    val t = get(e, g)
                    when (t[0]) {
                        'Z' -> e.invokestatic("java/lang/Boolean", "hashCode", "(Z)I")
                        'B' -> e.invokestatic("java/lang/Byte", "hashCode", "(B)I")
                        'S' -> e.invokestatic("java/lang/Short", "hashCode", "(S)I")
                        'C' -> e.invokestatic("java/lang/Character", "hashCode", "(C)I")
                        'I' -> e.invokestatic("java/lang/Integer", "hashCode", "(I)I")
                        'J' -> e.invokestatic("java/lang/Long", "hashCode", "(J)I")
                        'F' -> e.invokestatic("java/lang/Float", "hashCode", "(F)I")
                        'D' -> e.invokestatic("java/lang/Double", "hashCode", "(D)I")
                        else -> e.invokestatic("java/util/Objects", "hashCode", "(Ljava/lang/Object;)I")
                    }
                    code.iadd()
                }
                code.ireturn()
            }
            else -> throw IllegalArgumentException("ObjectMethods: неизвестный метод $method")
        }
        return assembleMethod(state, h, host, name, desc, flags, code)
    }

    private fun expect(desc: String, want: String) {
        if (desc != want) throw IllegalArgumentException("ObjectMethods: дескриптор $desc, ожидался $want")
    }

    /** Значение компоненты (получатель на стеке) по геттеру; тип значения. */
    private fun get(e: Emit, g: PoolConstant.MethodHandleValue): String {
        val m = g.member
        return when (g.referenceKind) {
            IndyDesugar.REF_GET_FIELD -> { e.code.getfield(e.field(m.owner, m.name, m.descriptor)); m.descriptor }
            IndyDesugar.REF_INVOKE_VIRTUAL -> { e.invokevirtual(m.owner, m.name, m.descriptor); Descriptors.method(m.descriptor).returnType }
            else -> throw IllegalArgumentException("ObjectMethods: геттер вида ${g.referenceKind}")
        }
    }

    // ---- Switch ----

    fun switchMethod(state: CasmState, h: ClassHierarchy, host: String, name: String, desc: String, flags: Int,
                     isEnum: Boolean, labels: List<SwitchLabel>): MethodInfo {
        val type = Descriptors.method(desc)
        if (type.params.size != 2 || type.params[1] != "I" || type.returnType != "I" || !Descriptors.isReference(type.params[0])) {
            throw IllegalArgumentException("switch: дескриптор $desc")
        }
        val n = labels.size
        val code = CodeDsl()
        val e = Emit(code, state)
        // Objects.checkIndex(restart, n + 1) — до проверки null, как в JDK.
        code.iload(1); code.iflt("oob")
        code.iload(1); e.pushInt(n); code.ifIcmpgt("oob")
        code.aload(0); code.ifnonnull("start")
        code.iconst(-1); code.ireturn()
        code.label("start")
        for ((i, label) in labels.withIndex()) {
            val next = "next$i"
            code.iload(1); e.pushInt(i); code.ifIcmpgt(next)
            when (label) {
                is SwitchLabel.Type -> { code.aload(0); e.instanceOf(label.descriptor); code.ifeq(next) }
                is SwitchLabel.Str -> if (isEnum) enumName(e, label.value, null, next) else {
                    e.ldcString(label.value)
                    code.aload(0)
                    e.invokevirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
                    code.ifeq(next)
                }
                is SwitchLabel.Int -> {
                    val chr = "char$i"
                    val match = "match$i"
                    code.aload(0); e.instanceOf("Ljava/lang/Number;"); code.ifeq(chr)
                    code.aload(0); e.checkcast("Ljava/lang/Number;")
                    e.invokevirtual("java/lang/Number", "intValue", "()I")
                    e.pushInt(label.value); code.ifIcmpne(next)
                    code.goto(match)
                    code.label(chr)
                    code.aload(0); e.instanceOf("Ljava/lang/Character;"); code.ifeq(next)
                    code.aload(0); e.checkcast("Ljava/lang/Character;")
                    e.invokevirtual("java/lang/Character", "charValue", "()C")
                    e.pushInt(label.value); code.ifIcmpne(next)
                    code.label(match)
                }
                is SwitchLabel.EnumConst -> enumName(e, label.name, label.enumClass, next)
            }
            e.pushInt(i); code.ireturn()
            code.label(next)
        }
        e.pushInt(n); code.ireturn()
        code.label("oob")
        e.newObject("java/lang/IndexOutOfBoundsException")
        e.newObject(SB)
        e.invokespecial(SB, "<init>", "()V")
        e.ldcString("Index "); append(e, "Ljava/lang/String;")
        code.iload(1); append(e, "I")
        e.ldcString(" out of bounds for length ${n + 1}"); append(e, "Ljava/lang/String;")
        e.invokevirtual(SB, "toString", "()Ljava/lang/String;")
        e.invokespecial("java/lang/IndexOutOfBoundsException", "<init>", "(Ljava/lang/String;)V")
        code.athrow()
        return assembleMethod(state, h, host, name, desc, flags, code)
    }

    /**
     * Константа enum: объект — Enum с именем [constant] и (если [enumClass] задан) объявляющим классом
     * [enumClass] — то же, что сравнение с разрешённым EnumDesc (нет такой константы — нет совпадения).
     */
    private fun enumName(e: Emit, constant: String, enumClass: String?, next: String) {
        val code = e.code
        code.aload(0); e.instanceOf("Ljava/lang/Enum;"); code.ifeq(next)
        if (enumClass != null) {
            code.aload(0); e.checkcast("Ljava/lang/Enum;")
            e.invokevirtual("java/lang/Enum", "getDeclaringClass", "()Ljava/lang/Class;")
            e.ldcClass(enumClass)
            code.ifAcmpne(next)
        }
        e.ldcString(constant)
        code.aload(0); e.checkcast("Ljava/lang/Enum;")
        e.invokevirtual("java/lang/Enum", "name", "()Ljava/lang/String;")
        e.invokevirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
        code.ifeq(next)
    }
}
