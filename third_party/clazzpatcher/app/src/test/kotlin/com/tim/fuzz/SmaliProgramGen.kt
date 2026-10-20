package com.tim.fuzz

import kotlin.random.Random

/**
 * Генератор случайных программ на smali, корректных для верификатора ART, — для дифференциального
 * фаззинга: вывод ART (эталон) против вывода HotSpot после DEX → .class (и обратно в DEX).
 *
 * Типы регистров моделируются как у верификатора ART: константа без типа ([T.C32]) годится и как
 * int, и как float, широкая ([T.C64]) — как long и double; при слиянии путей константа принимает
 * тип другой стороны, несовместимые типы дают [T.NONE] (регистр больше не читается). Так в коде
 * возникают ровно те случаи, что порождает R8: один регистр под разные типы на разных путях,
 * пары long/double внахлёст, константы в φ, try-диапазоны с обработчиками без move-exception.
 *
 * Управление структурное: ветвления, циклы с ограниченным счётчиком (тип на входе цикла — неподвижная
 * точка: тело перегенерируется из того же зерна, пока слияние не перестанет меняться), switch,
 * try/catch вокруг бросающих инструкций. В конце печатаются все регистры с известным типом.
 */
class SmaliProgramGen(seed: Long, private val className: String) {

    enum class T { NONE, INT, FLOAT, C32, LONG, DOUBLE, C64, HIGH, IARR, STR }

    private val rootSeed = seed
    private var r = Random(seed)
    private val out = StringBuilder()
    private val payloads = StringBuilder()
    private var label = 0
    private var budget = 0
    private val protectedRegs = HashSet<Int>()
    private var inTry = false

    /** Параметры тела (регистры v24…): long/double — пара. Пусто — тело без параметров. */
    private val params = ArrayList<T>()
    private val ins: Int get() = params.size + params.count { it == T.LONG || it == T.DOUBLE }
    private val frame: Int get() = TOTAL + ins

    companion object {
        const val WORK = 16          // v0..v15 — рабочие (доступны всем форматам)
        const val TOTAL = 24         // v16..v23 — временные (печать, range-вызовы, move-exception)
        const val TMP = 16

        private fun isInt(t: T) = t == T.INT || t == T.C32
        private fun isFloat(t: T) = t == T.FLOAT || t == T.C32
        private fun isLong(t: T) = t == T.LONG || t == T.C64
        private fun isDouble(t: T) = t == T.DOUBLE || t == T.C64
        private fun isWideLow(t: T) = t == T.LONG || t == T.DOUBLE || t == T.C64

        fun merge(a: T, b: T): T = when {
            a == b -> a
            a == T.C32 && (b == T.INT || b == T.FLOAT) -> b
            b == T.C32 && (a == T.INT || a == T.FLOAT) -> a
            a == T.C64 && (b == T.LONG || b == T.DOUBLE) -> b
            b == T.C64 && (a == T.LONG || a == T.DOUBLE) -> a
            else -> T.NONE
        }

        /** Слияние состояний с согласованием пар: HIGH остаётся, только если слита широкая нижняя половина. */
        fun merge(a: Array<T>, b: Array<T>): Array<T> {
            val m = Array(a.size) { merge(a[it], b[it]) }
            for (i in m.indices) {
                if (m[i] == T.HIGH && (i == 0 || !isWideLow(m[i - 1]))) m[i] = T.NONE
                if (isWideLow(m[i]) && (i + 1 >= m.size || m[i + 1] != T.HIGH)) m[i] = T.NONE
            }
            return m
        }

        private val INTS = intArrayOf(0, 1, -1, 2, 7, -8, 127, -128, 255, 32767, -32768, 65535, Int.MAX_VALUE, Int.MIN_VALUE,
            0x3f800000, 0x7fc00000, 0x7f800000, -0x800000, 0x80000000.toInt(), 0x40490fdb, 12345, -99999)
        private val LONGS = longArrayOf(0, 1, -1, 5, Long.MAX_VALUE, Long.MIN_VALUE, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
            0x3ff0000000000000L, 0x7ff8000000000000L, 0x7ff0000000000000L, -0x10000000000000L, 0x400921fb54442d18L, 1L shl 40, 123456789012L)
    }

    private fun emit(s: String) { out.append("    ").append(s).append('\n'); budget-- }
    private fun newLabel(p: String) = ":${p}_${label++}"
    private fun place(l: String) { out.append("    ").append(l).append('\n') }

    private fun hex(v: Long): String = if (v < 0) "-0x" + java.lang.Long.toHexString(-v).let { if (v == Long.MIN_VALUE) "8000000000000000" else it } else "0x" + java.lang.Long.toHexString(v)
    private fun hexI(v: Int): String = if (v < 0) "-0x" + Integer.toHexString(-v).let { if (v == Int.MIN_VALUE) "80000000" else it } else "0x" + Integer.toHexString(v)

    // ---- Регистры ----

    private fun readable(st: Array<T>, p: (T) -> Boolean) = (0 until WORK).filter { p(st[it]) }
    private fun readableWide(st: Array<T>, p: (T) -> Boolean) = (0 until WORK - 1).filter { p(st[it]) && st[it + 1] == T.HIGH }

    private fun dest32(): Int? = (0 until WORK).filter { it !in protectedRegs }.randomOrNull(r)

    private fun destWide(): Int? = (0 until WORK - 1).filter { it !in protectedRegs && (it + 1) !in protectedRegs }.randomOrNull(r)

    private fun write32(st: Array<T>, reg: Int, t: T) {
        if (st[reg] == T.HIGH && reg > 0) st[reg - 1] = T.NONE
        if (isWideLow(st[reg]) && reg + 1 < st.size) st[reg + 1] = T.NONE
        st[reg] = t
    }

    private fun writeWide(st: Array<T>, reg: Int, t: T) {
        if (st[reg] == T.HIGH && reg > 0) st[reg - 1] = T.NONE
        if (isWideLow(st[reg + 1]) && reg + 2 < st.size) st[reg + 2] = T.NONE
        st[reg] = t
        st[reg + 1] = T.HIGH
    }

    // ---- Программа ----

    fun generate(size: Int = 60): String {
        val cls = StringBuilder()
        cls.append(".class public L$className;\n.super Ljava/lang/Object;\n\n")
        // Тело с параметрами (как у R8: регистры параметров переписываются значениями других типов,
        // цикл со счётчиком-параметром с первой инструкции) — через обёртку run()V с константами.
        if (r.nextInt(10) < 7) {
            var words = 0
            val want = 1 + r.nextInt(4)
            while (words < want) {
                val t = listOf(T.INT, T.INT, T.FLOAT, T.LONG, T.DOUBLE).random(r)
                val w = if (t == T.LONG || t == T.DOUBLE) 2 else 1
                if (words + w > 4) break
                params.add(t)
                words += w
            }
        }
        if (params.isNotEmpty()) cls.append(wrapper())
        val desc = "(" + params.joinToString("") { when (it) { T.INT -> "I"; T.FLOAT -> "F"; T.LONG -> "J"; else -> "D" } } + ")V"
        cls.append(if (params.isEmpty()) ".method public static run()V\n" else ".method static body$desc\n")
        cls.append("    .registers $frame\n")
        budget = size
        val st = Array(frame) { T.NONE }
        var reg = TOTAL
        for (t in params) {
            st[reg] = t
            if (t == T.LONG || t == T.DOUBLE) st[++reg] = T.HIGH
            reg++
        }
        // Цикл по параметру-счётчику с первой инструкции: вход блока 0 — голова цикла.
        if (params.firstOrNull() == T.INT && r.nextBoolean()) loop(st, 0, counterReg = TOTAL)
        block(st, 0)
        printAll(st)
        emit("return-void")
        cls.append(out).append(payloads).append(".end method\n\n")
        cls.append(helpers())
        return cls.toString()
    }

    /** `run()V`: константы параметров (int — 0…4: счётчик цикла) и вызов тела. */
    private fun wrapper(): String = buildString {
        append(".method public static run()V\n    .registers ${ins + 1}\n")
        var reg = 0
        val desc = StringBuilder("(")
        for (t in params) {
            when (t) {
                T.INT -> { append("    const/4 v$reg, ${hexI(r.nextInt(0, 5))}\n"); desc.append('I') }
                T.FLOAT -> { append("    const v$reg, ${hexI(INTS.random(r))}\n"); desc.append('F') }
                T.LONG -> { append("    const-wide v$reg, ${hex(LONGS.random(r))}L\n"); desc.append('J') }
                else -> { append("    const-wide v$reg, ${hex(LONGS.random(r))}L\n"); desc.append('D') }
            }
            reg += if (t == T.LONG || t == T.DOUBLE) 2 else 1
        }
        desc.append(")V")
        append("    invoke-static/range {v0 .. v${ins - 1}}, L$className;->body$desc\n")
        append("    return-void\n.end method\n\n")
    }

    private fun block(st: Array<T>, depth: Int) {
        val n = 1 + r.nextInt(if (depth == 0) 12 else 5)
        repeat(n) { if (budget > 0) statement(st, depth) }
    }

    private fun statement(st: Array<T>, depth: Int) {
        val control = depth < 3 && budget > 8
        if (ins > 0 && r.nextInt(5) == 0) return if (r.nextBoolean()) paramRead(st) else paramWrite(st)
        when (r.nextInt(if (control) 22 else 16)) {
            0, 1, 2 -> const(st)
            3, 4 -> binop32(st)
            5, 6 -> binop64(st)
            7 -> unop(st)
            8 -> cmp(st)
            9 -> move(st)
            10, 11 -> array(st)
            12 -> string(st)
            13, 14 -> invoke(st)
            15 -> lit(st)
            16, 17 -> diamond(st, depth)
            18 -> loop(st, depth)
            19 -> switch(st, depth)
            else -> tryCatch(st, depth)
        }
    }

    private fun const(st: Array<T>) {
        if (r.nextInt(3) != 0) {
            val d = dest32() ?: return
            val v = if (r.nextBoolean()) INTS.random(r) else r.nextInt(-40000, 40000)
            when {
                v in -8..7 && r.nextBoolean() -> emit("const/4 v$d, ${hexI(v)}")
                v in Short.MIN_VALUE..Short.MAX_VALUE && r.nextBoolean() -> emit("const/16 v$d, ${hexI(v)}")
                v and 0xFFFF == 0 && r.nextBoolean() -> emit("const/high16 v$d, ${hexI(v)}")
                else -> emit("const v$d, ${hexI(v)}")
            }
            write32(st, d, T.C32)
        } else {
            val d = destWide() ?: return
            val v = if (r.nextBoolean()) LONGS.random(r) else r.nextLong(-100000, 100000)
            when {
                v in Short.MIN_VALUE..Short.MAX_VALUE && r.nextBoolean() -> emit("const-wide/16 v$d, ${hex(v)}L")
                v in Int.MIN_VALUE..Int.MAX_VALUE && r.nextBoolean() -> emit("const-wide/32 v$d, ${hex(v)}L")
                v and 0xFFFFFFFFFFFFL == 0L && r.nextBoolean() -> emit("const-wide/high16 v$d, ${hex(v)}L")
                else -> emit("const-wide v$d, ${hex(v)}L")
            }
            writeWide(st, d, T.C64)
        }
    }

    private fun binop32(st: Array<T>) {
        val float = r.nextInt(3) == 0
        val src = readable(st, if (float) ::isFloat else ::isInt)
        if (src.isEmpty()) return const(st)
        val ops = if (float) listOf("add", "sub", "mul", "div", "rem")
        else listOf("add", "sub", "mul", "and", "or", "xor", "shl", "shr", "ushr") + if (inTry || r.nextInt(4) == 0) listOf("div", "rem") else emptyList()
        val op = ops.random(r) + if (float) "-float" else "-int"
        val a = src.random(r)
        val b = src.random(r)
        if (r.nextBoolean() && a !in protectedRegs) {
            emit("$op/2addr v$a, v$b")
            write32(st, a, if (float) T.FLOAT else T.INT)
        } else {
            val d = dest32() ?: return
            emit("$op v$d, v$a, v$b")
            write32(st, d, if (float) T.FLOAT else T.INT)
        }
    }

    private fun binop64(st: Array<T>) {
        val dbl = r.nextInt(3) == 0
        val src = readableWide(st, if (dbl) ::isDouble else ::isLong)
        if (src.isEmpty()) return const(st)
        if (!dbl && r.nextInt(4) == 0) {
            // Сдвиг long: счётчик — int.
            val cnt = readable(st, ::isInt)
            if (cnt.isNotEmpty()) {
                val d = destWide() ?: return
                emit("${listOf("shl", "shr", "ushr").random(r)}-long v$d, v${src.random(r)}, v${cnt.random(r)}")
                writeWide(st, d, T.LONG)
                return
            }
        }
        val ops = if (dbl) listOf("add", "sub", "mul", "div", "rem")
        else listOf("add", "sub", "mul", "and", "or", "xor") + if (inTry || r.nextInt(4) == 0) listOf("div", "rem") else emptyList()
        val op = ops.random(r) + if (dbl) "-double" else "-long"
        val a = src.random(r)
        val b = src.random(r)
        if (r.nextBoolean() && a !in protectedRegs && (a + 1) !in protectedRegs) {
            emit("$op/2addr v$a, v$b")
            writeWide(st, a, if (dbl) T.DOUBLE else T.LONG)
        } else {
            val d = destWide() ?: return
            emit("$op v$d, v$a, v$b")
            writeWide(st, d, if (dbl) T.DOUBLE else T.LONG)
        }
    }

    private fun lit(st: Array<T>) {
        val src = readable(st, ::isInt)
        if (src.isEmpty()) return const(st)
        val d = dest32() ?: return
        val a = src.random(r)
        if (r.nextBoolean()) {
            val ops = listOf("add", "rsub", "mul", "and", "or", "xor", "shl", "shr", "ushr", "div", "rem")
            val op = ops.random(r)
            var v = r.nextInt(-128, 128)
            if ((op == "div" || op == "rem") && v == 0 && !inTry) v = 3
            emit("$op-int/lit8 v$d, v$a, ${hexI(v)}")
        } else {
            val ops = listOf("add", "rsub", "mul", "and", "or", "xor", "div", "rem")
            val op = ops.random(r)
            var v = r.nextInt(-32768, 32768)
            if ((op == "div" || op == "rem") && v == 0 && !inTry) v = -7
            val opName = if (op == "rsub") "rsub-int" else "$op-int/lit16"
            emit("$opName v$d, v$a, ${hexI(v)}")
        }
        write32(st, d, T.INT)
    }

    private fun unop(st: Array<T>) {
        data class U(val op: String, val from: (T) -> Boolean, val wideFrom: Boolean, val to: T)
        val all = listOf(
            U("neg-int", ::isInt, false, T.INT), U("not-int", ::isInt, false, T.INT), U("neg-float", ::isFloat, false, T.FLOAT),
            U("int-to-long", ::isInt, false, T.LONG), U("int-to-float", ::isInt, false, T.FLOAT), U("int-to-double", ::isInt, false, T.DOUBLE),
            U("float-to-int", ::isFloat, false, T.INT), U("float-to-long", ::isFloat, false, T.LONG), U("float-to-double", ::isFloat, false, T.DOUBLE),
            U("int-to-byte", ::isInt, false, T.INT), U("int-to-char", ::isInt, false, T.INT), U("int-to-short", ::isInt, false, T.INT),
            U("neg-long", ::isLong, true, T.LONG), U("not-long", ::isLong, true, T.LONG), U("neg-double", ::isDouble, true, T.DOUBLE),
            U("long-to-int", ::isLong, true, T.INT), U("long-to-float", ::isLong, true, T.FLOAT), U("long-to-double", ::isLong, true, T.DOUBLE),
            U("double-to-int", ::isDouble, true, T.INT), U("double-to-long", ::isDouble, true, T.LONG), U("double-to-float", ::isDouble, true, T.FLOAT)
        )
        val u = all.random(r)
        val src = if (u.wideFrom) readableWide(st, u.from) else readable(st, u.from)
        if (src.isEmpty()) return const(st)
        val wideTo = u.to == T.LONG || u.to == T.DOUBLE
        val d = (if (wideTo) destWide() else dest32()) ?: return
        emit("${u.op} v$d, v${src.random(r)}")
        if (wideTo) writeWide(st, d, u.to) else write32(st, d, u.to)
    }

    private fun cmp(st: Array<T>) {
        val (op, src) = when (r.nextInt(3)) {
            0 -> (if (r.nextBoolean()) "cmpl-float" else "cmpg-float") to readable(st, ::isFloat)
            1 -> (if (r.nextBoolean()) "cmpl-double" else "cmpg-double") to readableWide(st, ::isDouble)
            else -> "cmp-long" to readableWide(st, ::isLong)
        }
        if (src.isEmpty()) return const(st)
        val d = dest32() ?: return
        emit("$op v$d, v${src.random(r)}, v${src.random(r)}")
        write32(st, d, T.INT)
    }

    private fun move(st: Array<T>) {
        when (r.nextInt(3)) {
            0 -> {
                val src = readable(st) { it == T.INT || it == T.FLOAT || it == T.C32 }
                if (src.isEmpty()) return const(st)
                val s = src.random(r)
                val d = dest32() ?: return
                emit("move v$d, v$s")
                write32(st, d, st[s])
            }
            1 -> {
                val src = readableWide(st, ::isWideLow)
                if (src.isEmpty()) return const(st)
                val s = src.random(r)
                val d = destWide() ?: return
                val t = st[s]
                emit("move-wide v$d, v$s") // пары могут перекрываться (vN ← vN±1) — это разрешено
                writeWide(st, d, t)
            }
            else -> {
                val src = readable(st) { it == T.IARR || it == T.STR }
                if (src.isEmpty()) return string(st)
                val s = src.random(r)
                val d = dest32() ?: return
                emit("move-object v$d, v$s")
                write32(st, d, st[s])
            }
        }
    }

    /** Регистр параметра → рабочий: `move…/from16` (8-битный приёмник, 16-битный источник). */
    private fun paramRead(st: Array<T>) {
        val narrow = (TOTAL until frame).filter { st[it] == T.INT || st[it] == T.FLOAT || st[it] == T.C32 || st[it] == T.IARR || st[it] == T.STR }
        val wide = (TOTAL until frame - 1).filter { isWideLow(st[it]) && st[it + 1] == T.HIGH }
        if (wide.isNotEmpty() && (narrow.isEmpty() || r.nextBoolean())) {
            val p = wide.random(r)
            val d = destWide() ?: return
            emit("move-wide/from16 v$d, v$p")
            writeWide(st, d, st[p])
        } else if (narrow.isNotEmpty()) {
            val p = narrow.random(r)
            val d = dest32() ?: return
            emit((if (st[p] == T.IARR || st[p] == T.STR) "move-object/from16" else "move/from16") + " v$d, v$p")
            write32(st, d, st[p])
        }
    }

    /** Рабочий регистр → регистр параметра, в том числе значением другого типа (R8 так переиспользует параметры). */
    private fun paramWrite(st: Array<T>) {
        val free = (TOTAL until frame).filter { it !in protectedRegs }
        if (free.isEmpty()) return
        val src = (0 until WORK).filter { st[it] != T.NONE && st[it] != T.HIGH && (!isWideLow(st[it]) || (it + 1 < WORK && st[it + 1] == T.HIGH)) }
        if (src.isEmpty()) return
        val s = src.random(r)
        val t = st[s]
        if (isWideLow(t)) {
            val p = free.filter { it + 1 < frame && (it + 1) !in protectedRegs }.randomOrNull(r) ?: return
            emit("move-wide/from16 v$p, v$s")
            writeWide(st, p, t)
        } else {
            val p = free.random(r)
            emit((if (t == T.IARR || t == T.STR) "move-object/from16" else "move/from16") + " v$p, v$s")
            write32(st, p, t)
        }
    }

    private fun array(st: Array<T>) {
        val arrays = readable(st) { it == T.IARR }
        val ints = readable(st, ::isInt)
        when {
            arrays.isEmpty() || r.nextInt(4) == 0 -> {
                val d = dest32() ?: return
                if (r.nextBoolean() && ints.size >= 2) {
                    val regs = List(1 + r.nextInt(3)) { ints.random(r) }
                    emit("filled-new-array {${regs.joinToString(", ") { "v$it" }}}, [I")
                    emit("move-result-object v$d")
                    write32(st, d, T.IARR)
                } else {
                    val len = dest32() ?: return
                    if (len == d) return
                    emit("const/4 v$len, ${hexI(r.nextInt(0, 6))}")
                    write32(st, len, T.C32)
                    emit("new-array v$d, v$len, [I")
                    write32(st, d, T.IARR)
                    if (r.nextBoolean()) {
                        val data = newLabel("array")
                        emit("fill-array-data v$d, $data")
                        payloads.append("    $data\n    .array-data 4\n")
                        repeat(r.nextInt(0, 3)) { payloads.append("        ${hexI(INTS.random(r))}\n") }
                        payloads.append("    .end array-data\n")
                    }
                }
            }
            else -> {
                val a = arrays.random(r)
                if (ints.isEmpty()) {
                    val d = dest32() ?: return
                    emit("array-length v$d, v$a")
                    write32(st, d, T.INT)
                    return
                }
                val idx = ints.random(r)
                when (r.nextInt(3)) {
                    0 -> { val d = dest32() ?: return; emit("aget v$d, v$a, v$idx"); write32(st, d, T.INT) }
                    1 -> emit("aput v${ints.random(r)}, v$a, v$idx")
                    else -> { val d = dest32() ?: return; emit("array-length v$d, v$a"); write32(st, d, T.INT) }
                }
            }
        }
    }

    private fun string(st: Array<T>) {
        val strs = readable(st) { it == T.STR }
        val d = dest32() ?: return
        if (strs.isEmpty() || r.nextInt(3) == 0) {
            val ints = readable(st, ::isInt)
            if (ints.isNotEmpty() && r.nextBoolean()) {
                emit("invoke-static {v${ints.random(r)}}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;")
                emit("move-result-object v$d")
            } else {
                emit("const-string v$d, \"${listOf("", "a", "hello", "Ж", "12", "-7").random(r)}\"")
            }
            write32(st, d, T.STR)
        } else {
            val s = strs.random(r)
            emit("invoke-virtual {v$s}, Ljava/lang/String;->${if (r.nextBoolean()) "length" else "hashCode"}()I")
            emit("move-result v$d")
            write32(st, d, T.INT)
        }
    }

    private fun invoke(st: Array<T>) {
        val ints = readable(st, ::isInt)
        val floats = readable(st, ::isFloat)
        val longs = readableWide(st, ::isLong)
        val doubles = readableWide(st, ::isDouble)
        when (r.nextInt(7)) {
            0 -> if (ints.isNotEmpty() && longs.isNotEmpty()) {
                val d = destWide() ?: return
                emit("invoke-static {v${ints.random(r)}, v${longs.random(r).let { "$it, v${it + 1}" }}}, L$className;->h1(IJ)J")
                emit("move-result-wide v$d")
                writeWide(st, d, T.LONG)
            } else const(st)
            1 -> if (floats.isNotEmpty() && doubles.isNotEmpty()) {
                val d = destWide() ?: return
                emit("invoke-static {v${floats.random(r)}, v${doubles.random(r).let { "$it, v${it + 1}" }}}, L$className;->h2(FD)D")
                emit("move-result-wide v$d")
                writeWide(st, d, T.DOUBLE)
            } else const(st)
            2 -> if (ints.isNotEmpty()) {
                val d = dest32() ?: return
                val (m, sig) = listOf("Math" to "max", "Math" to "min", "Integer" to "rotateLeft", "Integer" to "compare").random(r)
                emit("invoke-static {v${ints.random(r)}, v${ints.random(r)}}, Ljava/lang/$m;->$sig(II)I")
                emit("move-result v$d")
                write32(st, d, T.INT)
            } else const(st)
            3 -> if (floats.isNotEmpty()) {
                val d = dest32() ?: return
                emit("invoke-static {v${floats.random(r)}}, Ljava/lang/Float;->floatToIntBits(F)I")
                emit("move-result v$d")
                write32(st, d, T.INT)
            } else const(st)
            4 -> if (doubles.isNotEmpty()) {
                val d = destWide() ?: return
                emit("invoke-static {v${doubles.random(r).let { "$it, v${it + 1}" }}}, Ljava/lang/Math;->abs(D)D")
                emit("move-result-wide v$d")
                writeWide(st, d, T.DOUBLE)
            } else const(st)
            5 -> if (ints.size >= 1) {
                // range-вызов: аргументы — во временных регистрах подряд.
                val n = 3 + r.nextInt(4)
                repeat(n) { k -> emit("move/from16 v${TMP + k}, v${ints.random(r)}") }
                val d = dest32() ?: return
                emit("invoke-static/range {v$TMP .. v${TMP + n - 1}}, L$className;->h$n(${"I".repeat(n)})I")
                emit("move-result v$d")
                write32(st, d, T.INT)
            } else const(st)
            else -> if (longs.isNotEmpty()) {
                val d = dest32() ?: return
                emit("invoke-static {v${longs.random(r).let { "$it, v${it + 1}" }}}, Ljava/lang/Long;->bitCount(J)I")
                emit("move-result v$d")
                write32(st, d, T.INT)
            } else const(st)
        }
    }

    // ---- Управление ----

    private fun condition(st: Array<T>, target: String): Boolean {
        val ints = readable(st, ::isInt)
        val refs = readable(st) { it == T.IARR || it == T.STR }
        when {
            ints.isNotEmpty() && r.nextInt(3) != 0 -> if (r.nextBoolean()) {
                emit("if-${listOf("eqz", "nez", "ltz", "gez", "gtz", "lez").random(r)} v${ints.random(r)}, $target")
            } else {
                emit("if-${listOf("eq", "ne", "lt", "ge", "gt", "le").random(r)} v${ints.random(r)}, v${ints.random(r)}, $target")
            }
            refs.isNotEmpty() -> if (r.nextBoolean()) {
                emit("if-${listOf("eqz", "nez").random(r)} v${refs.random(r)}, $target")
            } else {
                val a = refs.random(r)
                val b = refs.random(r)
                emit("if-${listOf("eq", "ne").random(r)} v$a, v$b, $target")
            }
            else -> return false
        }
        return true
    }

    private fun diamond(st: Array<T>, depth: Int) {
        val elseL = newLabel("else")
        val endL = newLabel("end")
        if (!condition(st, elseL)) return const(st)
        val thenSt = st.copyOf()
        block(thenSt, depth + 1)
        val hasElse = r.nextBoolean()
        if (hasElse) emit("goto $endL")
        place(elseL)
        val elseSt = st.copyOf()
        if (hasElse) block(elseSt, depth + 1)
        place(endL)
        merge(thenSt, elseSt).copyInto(st)
    }

    private fun loop(st: Array<T>, depth: Int, counterReg: Int? = null) {
        val counter = counterReg ?: dest32() ?: return
        val entry = st.copyOf()
        if (counterReg == null) emit("const/4 v$counter, ${hexI(r.nextInt(0, 5))}")
        write32(entry, counter, T.INT) // на входе цикла счётчик сливается с результатом add-int
        val bodySeed = r.nextLong()
        val savedOut = out.length
        val savedPayloads = payloads.length
        val savedBudget = budget
        val savedLabel = label
        var head = entry
        protectedRegs.add(counter)
        try {
            for (attempt in 0 until 8) {
                out.setLength(savedOut)
                payloads.setLength(savedPayloads)
                budget = savedBudget
                label = savedLabel
                val headL = newLabel("loop")
                val exitL = newLabel("exit")
                val saved = r
                r = Random(bodySeed)
                place(headL)
                emit("if-lez v$counter, $exitL")
                val body = head.copyOf()
                // Цикл по параметру: в теле другой параметр получает значение другого типа (на входе
                // блока 0 его слот после слияния — top).
                if (counterReg != null && r.nextBoolean()) { const(body); paramWrite(body) }
                block(body, depth + 1)
                emit("add-int/lit8 v$counter, v$counter, -0x1")
                emit("goto $headL")
                place(exitL)
                r = saved
                val next = merge(entry, body)
                if (next.contentEquals(head)) break
                head = merge(head, next)
                if (attempt == 7) head = Array(frame) { if (it == counter) T.INT else T.NONE }
            }
        } finally {
            protectedRegs.remove(counter)
        }
        head.copyInto(st)
    }

    private fun switch(st: Array<T>, depth: Int) {
        val ints = readable(st, ::isInt)
        if (ints.isEmpty()) return const(st)
        val v = ints.random(r)
        val n = 1 + r.nextInt(4)
        val data = newLabel("sw")
        val endL = newLabel("swend")
        val cases = List(n) { newLabel("case") }
        val packed = r.nextBoolean()
        emit("${if (packed) "packed" else "sparse"}-switch v$v, $data")
        var merged = st.copyOf()
        val defSt = st.copyOf()
        block(defSt, depth + 1)
        merged = defSt
        emit("goto $endL")
        for (c in cases) {
            place(c)
            val cs = st.copyOf()
            block(cs, depth + 1)
            emit("goto $endL")
            merged = merge(merged, cs)
        }
        place(endL)
        merged.copyInto(st)
        if (packed) {
            payloads.append("    $data\n    .packed-switch ${hexI(r.nextInt(-3, 3))}\n")
            for (c in cases) payloads.append("        $c\n")
            payloads.append("    .end packed-switch\n")
        } else {
            val keys = generateSequence { INTS.random(r) }.distinct().take(n).sorted().toList()
            payloads.append("    $data\n    .sparse-switch\n")
            for ((k, c) in keys.zip(cases)) payloads.append("        ${hexI(k)} -> $c\n")
            payloads.append("    .end sparse-switch\n")
        }
    }

    private fun tryCatch(st: Array<T>, depth: Int) {
        // Бросающим инструкциям нужно целое: константа — до try (иначе она попала бы в диапазон
        // и изменила бы состояние на входе обработчика между бросающими инструкциями).
        if (readable(st, ::isInt).isEmpty()) {
            val d = dest32() ?: return
            emit("const/4 v$d, ${hexI(r.nextInt(-2, 3))}")
            write32(st, d, T.C32)
        }
        val start = newLabel("try_start")
        val end = newLabel("try_end")
        val handler = newLabel("catch")
        val after = newLabel("try_after")
        place(start)
        var atHandler: Array<T>? = null
        val was = inTry
        inTry = true
        repeat(1 + r.nextInt(3)) {
            atHandler = atHandler?.let { merge(it, st) } ?: st.copyOf()
            throwing(st)
        }
        inTry = was
        place(end)
        emit("goto $after")
        val type = listOf("Ljava/lang/ArithmeticException;", "Ljava/lang/ArrayIndexOutOfBoundsException;",
            "Ljava/lang/RuntimeException;", "Ljava/lang/Throwable;", null).random(r)
        out.append("    ").append(if (type == null) ".catchall" else ".catch $type").append(" {$start .. $end} $handler\n")
        place(handler)
        val h = atHandler!!
        if (r.nextBoolean()) {
            emit("move-exception v$TMP")
        }
        block(h, depth + 1)
        place(after)
        merge(st, h).copyInto(st)
    }

    /** Одна бросающая инструкция (деление, индекс массива, отрицательная длина, вызов). */
    private fun throwing(st: Array<T>) {
        val ints = readable(st, ::isInt)
        val longs = readableWide(st, ::isLong)
        val arrays = readable(st) { it == T.IARR }
        when {
            arrays.isNotEmpty() && ints.isNotEmpty() && r.nextInt(3) == 0 -> {
                val d = dest32() ?: return
                emit("aget v$d, v${arrays.random(r)}, v${ints.random(r)}")
                write32(st, d, T.INT)
            }
            longs.isNotEmpty() && r.nextInt(3) == 0 -> {
                val d = destWide() ?: return
                emit("${if (r.nextBoolean()) "div" else "rem"}-long v$d, v${longs.random(r)}, v${longs.random(r)}")
                writeWide(st, d, T.LONG)
            }
            ints.isNotEmpty() && r.nextInt(3) == 0 -> {
                val d = dest32() ?: return
                if (r.nextBoolean()) emit("invoke-static {v${ints.random(r)}, v${ints.random(r)}}, Ljava/lang/Math;->floorMod(II)I")
                else emit("invoke-static {v${ints.random(r)}, v${ints.random(r)}}, Ljava/lang/Math;->multiplyExact(II)I")
                emit("move-result v$d")
                write32(st, d, T.INT)
            }
            ints.isNotEmpty() && r.nextInt(4) == 0 -> {
                // Длина массива может быть отрицательной (NegativeArraySizeException).
                val a = dest32() ?: return
                emit("new-array v$a, v${ints.random(r)}, [I")
                write32(st, a, T.IARR)
            }
            ints.isNotEmpty() -> {
                val d = dest32() ?: return
                emit("${if (r.nextBoolean()) "div" else "rem"}-int v$d, v${ints.random(r)}, v${ints.random(r)}")
                write32(st, d, T.INT)
            }
            else -> {}
        }
    }

    // ---- Печать ----

    private fun printAll(st: Array<T>) {
        for (k in (0 until WORK) + (TOTAL until frame)) {
            val t = st[k]
            val end = if (k < WORK) WORK else frame
            val sig = when (t) {
                T.INT, T.C32 -> "p(II)V"
                T.FLOAT -> "pf(IF)V"
                T.LONG, T.C64 -> if (k + 1 < end && st[k + 1] == T.HIGH) "pj(IJ)V" else null
                T.DOUBLE -> if (k + 1 < end && st[k + 1] == T.HIGH) "pd(ID)V" else null
                T.IARR -> "pa(I[I)V"
                T.STR -> "ps(ILjava/lang/String;)V"
                else -> null
            } ?: continue
            emit("const/16 v$TMP, ${hexI(k)}")
            val wide = sig.startsWith("pj") || sig.startsWith("pd")
            when {
                wide -> emit("move-wide/from16 v${TMP + 1}, v$k")
                t == T.IARR || t == T.STR -> emit("move-object/from16 v${TMP + 1}, v$k")
                else -> emit("move/from16 v${TMP + 1}, v$k")
            }
            emit("invoke-static/range {v$TMP .. v${TMP + if (wide) 2 else 1}}, L$className;->$sig")
        }
    }

    private fun helpers(): String = buildString {
        val out = "sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;"
        fun printer(name: String, desc: String, regs: Int, body: String) {
            append(".method static $name$desc\n    .registers $regs\n    $out\n$body    return-void\n.end method\n\n")
        }
        // p(k, v): "k:v"
        printer("p", "(II)V", 4, """    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v2, ":i "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
""")
        printer("pf", "(IF)V", 4, """    invoke-static {p1}, Ljava/lang/Float;->floatToIntBits(F)I
    move-result v1
    invoke-static {p0, v1}, L$className;->p(II)V
""")
        printer("pj", "(IJ)V", 5, """    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v2, ":j "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p1, p2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
""")
        printer("pd", "(ID)V", 6, """    invoke-static {p1, p2}, Ljava/lang/Double;->doubleToLongBits(D)J
    move-result-wide v1
    invoke-static {p0, v1, v2}, L$className;->pj(IJ)V
""")
        printer("pa", "(I[I)V", 4, """    invoke-static {p1}, Ljava/util/Arrays;->hashCode([I)I
    move-result v1
    invoke-static {p0, v1}, L$className;->p(II)V
""")
        printer("ps", "(ILjava/lang/String;)V", 4, """    invoke-virtual {p1}, Ljava/lang/String;->hashCode()I
    move-result v1
    invoke-static {p0, v1}, L$className;->p(II)V
""")
        append(".method static h1(IJ)J\n    .registers 5\n    int-to-long v0, p0\n    mul-long/2addr v0, p1\n    const-wide/16 v2, 0x1fL\n    add-long/2addr v0, v2\n    return-wide v0\n.end method\n\n")
        append(".method static h2(FD)D\n    .registers 5\n    float-to-double v0, p0\n    sub-double/2addr v0, p1\n    return-wide v0\n.end method\n\n")
        for (n in 3..6) {
            append(".method static h$n(${"I".repeat(n)})I\n    .registers ${n + 1}\n    const/4 v0, 0x0\n")
            for (k in 0 until n) append("    mul-int/lit8 v0, v0, 0x1f\n    add-int/2addr v0, p$k\n")
            append("    return v0\n.end method\n\n")
        }
    }
}
