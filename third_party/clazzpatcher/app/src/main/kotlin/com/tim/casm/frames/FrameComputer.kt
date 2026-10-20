package com.tim.casm.frames

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.model.RawStackMapFrame
import com.tim.casm.model.RawVerificationType
import com.tim.clazz.model.ConstantPoolEntry
import com.tim.clazz.model.opcodes.Opcodes

/** Код метода после расчёта: возможно, с заменённым недостижимым кодом и урезанной таблицей исключений. */
class ComputedCode(
    val code: ByteArray,
    val maxStack: Int,
    val maxLocals: Int,
    /** Таблица исключений в исходном порядке (приоритет); недостижимые участки вырезаны. */
    val tries: List<ComputedTry>,
    /** Сжатые фреймы StackMapTable (пусто, если фреймы не нужны). */
    val frames: List<RawStackMapFrame>
)

class ComputedTry(val start: Int, val end: Int, val handler: Int, val catchType: String?)

/** Нужен ли StackMapTable: по версии класса (JVMS §4.10). */
enum class FrameMode {
    /** До версии 50: фреймов нет (старый верификатор). */
    NONE,
    /** Версия 50: фреймы строятся, но код с jsr/ret допустим — тогда без фреймов (откат верификатора). */
    OPTIONAL,
    /** С версии 51: фреймы обязательны, jsr/ret запрещены. */
    REQUIRED;

    companion object {
        fun forVersion(major: Int): FrameMode = when {
            major < 50 -> NONE
            major == 50 -> OPTIONAL
            else -> REQUIRED
        }
    }
}

/** Метод, для которого считаются фреймы. */
class MethodShape(
    /** Внутреннее имя класса-владельца. */
    val owner: String,
    val name: String,
    val descriptor: String,
    val isStatic: Boolean
)

/**
 * Расчёт max_stack/max_locals и StackMapTable для готового байткода.
 *
 * Недостижимый код (его нельзя проверить верификатором) при [withFrames] заменяется на
 * `nop … athrow` с фреймом `[] [java/lang/Throwable]`, а его участки исключаются из
 * диапазонов обработчиков — так же поступает ASM. Исключения — участки, проверяемые без анализа
 * (см. [keptDead]): они остаются как есть.
 */
object FrameComputer {

    fun compute(
        code: ByteArray,
        tries: List<ComputedTry>,
        pool: (Int) -> ConstantPoolEntry?,
        hierarchy: ClassHierarchy,
        method: MethodShape,
        withFrames: Boolean
    ): ComputedCode = compute(code, tries, pool, hierarchy, method, if (withFrames) FrameMode.REQUIRED else FrameMode.NONE)

    fun compute(
        code: ByteArray,
        tries: List<ComputedTry>,
        pool: (Int) -> ConstantPoolEntry?,
        hierarchy: ClassHierarchy,
        method: MethodShape,
        mode: FrameMode
    ): ComputedCode {
        val bytecode = Bytecode.decode(code)
        val hasJsr = (0 until bytecode.count).any {
            val op = bytecode.op[it]
            op == Opcodes.JSR || op == Opcodes.JSR_W || op == Opcodes.RET
        }
        val withFrames = when (mode) {
            FrameMode.NONE -> false
            FrameMode.OPTIONAL -> !hasJsr
            FrameMode.REQUIRED -> {
                if (hasJsr) {
                    throw AnalysisException("Метод ${method.owner}.${method.name}${method.descriptor}: jsr/ret запрещены с версии класса 51")
                }
                true
            }
        }
        val ranges = tries.map { TryRange(it.start, it.end, it.handler, it.catchType) }
        val result = FrameAnalyzer(
            bytecode, PoolAccess(pool), hierarchy, method.owner, method.name,
            method.descriptor, method.isStatic, ranges, strict = withFrames
        ).run()
        if (!withFrames) return ComputedCode(code, result.maxStack, result.maxLocals, tries, emptyList())

        val allDead = deadRuns(bytecode, result)
        val kept = keptDead(bytecode, result, tries, allDead)
        val dead = if (kept.isEmpty()) allDead else allDead.filter { it[0] !in kept }
        val outCode = if (dead.isEmpty()) code else replaceDead(code, dead)
        val outTries = if (dead.isEmpty()) tries else cutTries(tries, dead)
        val frames = encodeFrames(bytecode, result, ranges, dead, kept)
        // Фрейм замены и обработчик `astore; aload; athrow` держат на стеке исключение.
        val maxStack = if (dead.isEmpty() && kept.values.all { it.stack.isEmpty() }) result.maxStack
        else maxOf(result.maxStack, 1)
        return ComputedCode(outCode, maxStack, result.maxLocals, outTries, frames)
    }

    // ---- Недостижимый код ----

    /**
     * Участки [начало, конец) из подряд идущих недостижимых инструкций; участок кончается и на инструкции
     * без проваливания (athrow, return, goto, switch): уже заменённый код (ASM, прошлая сборка csmali —
     * блоки `nop…athrow`) после замены остаётся тем же.
     */
    private fun deadRuns(code: Bytecode, r: FrameAnalyzer.Result): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < code.count) {
            if (r.reached[i]) { i++; continue }
            val start = code.pc[i]
            while (i < code.count && !r.reached[i]) {
                val ends = endsBlock(code.op[i]) || code.switchTargets[i] != null
                i++
                if (ends) break
            }
            out.add(intArrayOf(start, if (i < code.count) code.pc[i] else code.code.size))
        }
        return out
    }

    /**
     * Недостижимый код, который проверяем и без анализа, остаётся как есть (иначе после сборки менялся бы
     * текст: `goto` → `nop nop athrow`):
     * - одиночный `goto` к достижимому коду (ECJ ставит его после `return`) — с фреймом цели: всякий
     *   покрывающий его try-диапазон покрывает и цель, так что её локалы подходят обработчику;
     * - `astore k; aload k; athrow` (обработчик пустого `finally` у javac, лексеры ANTLR 3) — с фреймом
     *   `[] [java/lang/Throwable]`: покрывать его могут только диапазоны, у которых он сам обработчик.
     */
    private fun keptDead(
        code: Bytecode,
        r: FrameAnalyzer.Result,
        tries: List<ComputedTry>,
        dead: List<IntArray>
    ): Map<Int, Frame> {
        var out: HashMap<Int, Frame>? = null
        for (d in dead) {
            val i = code.indexAt(d[0])
            val f = when (code.op[i]) {
                Opcodes.GOTO, Opcodes.GOTO_W -> {
                    val targetPc = code.a[i]
                    val target = code.indexAt(targetPc)
                    val f = if (target >= 0 && r.reached[target]) r.entry[target] else null
                    if (f == null || f.locals.any { it.isUninit } || f.stack.any { it.isUninit } ||
                        tries.any { it.start < d[1] && d[0] < it.end && (targetPc < it.start || targetPc >= it.end) }
                    ) null else f
                }
                Opcodes.ASTORE -> {
                    val rethrow = i + 2 < code.count && code.op[i + 1] == Opcodes.ALOAD && code.a[i + 1] == code.a[i] &&
                        code.op[i + 2] == Opcodes.ATHROW && code.end(i + 2) == d[1]
                    if (!rethrow || tries.any { it.start < d[1] && d[0] < it.end && it.handler != d[0] }) null
                    else Frame(Array(r.initial.locals.size) { VerType.Top }, arrayOf(VerType.THROWABLE))
                }
                else -> null
            } ?: continue
            (out ?: HashMap<Int, Frame>().also { out = it })[d[0]] = f
        }
        return out ?: emptyMap()
    }

    private val VerType.isUninit: Boolean get() = this is VerType.Uninit || this === VerType.UninitThis

    private fun endsBlock(op: Int): Boolean = when (op) {
        Opcodes.ATHROW, Opcodes.GOTO, Opcodes.GOTO_W, Opcodes.RET, in Opcodes.IRETURN..Opcodes.RETURN -> true
        else -> false
    }

    private fun replaceDead(code: ByteArray, dead: List<IntArray>): ByteArray {
        val out = code.copyOf()
        for (d in dead) {
            for (p in d[0] until d[1] - 1) out[p] = Opcodes.NOP.toByte()
            out[d[1] - 1] = Opcodes.ATHROW.toByte()
        }
        return out
    }

    private fun cutTries(tries: List<ComputedTry>, dead: List<IntArray>): List<ComputedTry> {
        val out = ArrayList<ComputedTry>(tries.size)
        for (t in tries) {
            var start = t.start
            for (d in dead) {
                if (d[1] <= start || d[0] >= t.end) continue
                if (d[0] > start) out.add(ComputedTry(start, d[0], t.handler, t.catchType))
                start = maxOf(start, d[1])
            }
            if (start < t.end) out.add(ComputedTry(start, t.end, t.handler, t.catchType))
        }
        return out
    }

    // ---- Кодирование фреймов ----

    private fun encodeFrames(
        code: Bytecode,
        r: FrameAnalyzer.Result,
        tries: List<TryRange>,
        dead: List<IntArray>,
        kept: Map<Int, Frame>
    ): List<RawStackMapFrame> {
        val targets = java.util.TreeMap<Int, Frame?>()
        for (i in 0 until code.count) {
            if (!r.reached[i]) continue
            val sw = code.switchTargets[i]
            if (sw != null) {
                for (t in sw) targets[t] = r.entry[code.indexAt(t)]
                continue
            }
            when (code.op[i]) {
                in Opcodes.IFEQ..Opcodes.GOTO, Opcodes.GOTO_W, Opcodes.IFNULL, Opcodes.IFNONNULL ->
                    targets[code.a[i]] = r.entry[code.indexAt(code.a[i])]
            }
        }
        for (t in tries) {
            // Обработчики: достижимые — со своим состоянием.
            val f = r.entry[code.indexAt(t.handler)] ?: continue
            targets[t.handler] = f
        }
        for (d in dead) targets[d[0]] = null
        for ((pc, f) in kept) {
            targets[pc] = f
            // Цель сохранённого goto могла быть достижима только проваливанием — ей тоже нужен фрейм.
            val i = code.indexAt(pc)
            if (code.op[i] == Opcodes.GOTO || code.op[i] == Opcodes.GOTO_W) targets[code.a[i]] = r.entry[code.indexAt(code.a[i])]
        }

        val out = ArrayList<RawStackMapFrame>(targets.size)
        var prevLocals = localsList(r.initial.locals)
        var prevPc = -1
        for ((pc, frame) in targets) {
            val locals = if (frame == null) emptyList() else localsList(frame.locals)
            val stack = if (frame == null) listOf(raw(VerType.THROWABLE)) else frame.stack.map { raw(it) }
            out.add(compress(pc - prevPc - 1, prevLocals, locals, stack))
            prevLocals = locals
            prevPc = pc
        }
        return out
    }

    /** Локалы фрейма: long/double — один элемент, хвостовые top отбрасываются. */
    private fun localsList(locals: Array<VerType>): List<RawVerificationType> {
        val out = ArrayList<RawVerificationType>(locals.size)
        var k = 0
        var lastNonTop = 0
        while (k < locals.size) {
            val t = locals[k]
            out.add(raw(t))
            if (t != VerType.Top) lastNonTop = out.size
            k += if (t.size == 2) 2 else 1
        }
        return if (lastNonTop == out.size) out else out.subList(0, lastNonTop).toList()
    }

    private fun raw(t: VerType): RawVerificationType = when (t) {
        is VerType.Ref -> RawVerificationType(t.tag, descriptor = t.descriptor)
        is VerType.Uninit -> RawVerificationType(t.tag, value = t.newPc)
        else -> RawVerificationType(t.tag)
    }

    private fun compress(
        delta: Int,
        prev: List<RawVerificationType>,
        locals: List<RawVerificationType>,
        stack: List<RawVerificationType>
    ): RawStackMapFrame {
        val sameLocals = prev == locals
        if (sameLocals && stack.isEmpty()) {
            return if (delta < 64) RawStackMapFrame(delta, delta, emptyList(), emptyList())
            else RawStackMapFrame(251, delta, emptyList(), emptyList())
        }
        if (sameLocals && stack.size == 1) {
            return if (delta < 64) RawStackMapFrame(64 + delta, delta, emptyList(), stack)
            else RawStackMapFrame(247, delta, emptyList(), stack)
        }
        if (stack.isEmpty()) {
            val diff = locals.size - prev.size
            if (diff in 1..3 && locals.subList(0, prev.size) == prev) {
                return RawStackMapFrame(251 + diff, delta, locals.subList(prev.size, locals.size).toList(), emptyList())
            }
            if (diff in -3..-1 && prev.subList(0, locals.size) == locals) {
                return RawStackMapFrame(251 + diff, delta, emptyList(), emptyList())
            }
        }
        return RawStackMapFrame(255, delta, locals, stack)
    }
}
