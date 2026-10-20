package com.tim.fuzz

import com.tim.c2d.ConversionException
import com.tim.clazz.util.ClassFormatException
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Мутационный фаззинг разборщиков и конвейеров на детерминированных seed'ах.
 *
 * Правило проекта: некорректный вход — исключение с сообщением (IllegalArgumentException,
 * IllegalStateException, ClassFormatException, ConversionException), а не внутренняя ошибка.
 * Находкой считается: любое другое исключение где-либо в цепочке причин (IndexOutOfBounds, NPE,
 * ClassCast, NegativeArraySize, NoSuchElement, Arithmetic, NumberFormat без места…), Error
 * (StackOverflow, OutOfMemory), пустое сообщение и превышение времени.
 *
 * Объём: `-Pfuzz=N` — итераций на seed (по умолчанию короткий прогон), `-Pfuzz.seed=S`.
 * Входы находок сохраняются в build/fuzz-findings для воспроизведения.
 */
object Fuzz {
    val iterations: Int = System.getProperty("clazzpatcher.fuzz")?.toIntOrNull() ?: 40
    val seed: Long = System.getProperty("clazzpatcher.fuzz.seed")?.toLongOrNull() ?: 20260929L
    private const val TIMEOUT_MS = 5_000L

    class Finding(val target: String, val problem: String, val file: File?) {
        override fun toString() = "$target: $problem" + (file?.let { " (вход: ${it.path})" } ?: "")
    }

    /**
     * Разрешённый отказ: цепочка причин из «чистых» исключений с сообщением. NumberFormatException
     * допустим как причина обёртки с местом ошибки; выход за массив — только если обёртка объясняет
     * отказ сама, не повторяя текст причины («чтение за концом файла» у DexReader).
     */
    fun problemOf(e: Throwable): String? {
        var t: Throwable? = e
        var wrapper: Throwable? = null
        var depth = 0
        while (t != null && depth++ < 32) {
            val clean = (t is IllegalArgumentException && t !is NumberFormatException) || t is IllegalStateException ||
                t is ClassFormatException || t is ConversionException
            if (!clean) {
                // NumberFormat — законный отказ «не число», обёртка добавляет место. Выход за массив — только
                // если обёртка объясняет его сама (иначе это непроверенный вход: «Index 32 out of bounds…»).
                val explained = wrapper != null && (t is NumberFormatException ||
                    (t is IndexOutOfBoundsException && (t.message == null || t.message!! !in wrapper.message!!)))
                return if (explained) null else describe(t, e)
            }
            if (t.message.isNullOrBlank()) return "пустое сообщение: ${describe(t, e)}"
            wrapper = t
            t = t.cause
        }
        return null
    }

    private fun describe(t: Throwable, top: Throwable): String {
        val at = t.stackTrace.firstOrNull { it.className.startsWith("com.tim.") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?"
        val chain = if (t === top) "" else " (верхнее: ${top.javaClass.simpleName}: ${top.message?.take(160)})"
        return "${t.javaClass.name}: ${t.message?.take(200)} @ $at$chain"
    }

    private var pool: ExecutorService = newPool()
    private fun newPool(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(null, r, "fuzz", 64L shl 20).apply { isDaemon = true } }

    /** Выполнить [work] над входом; null — корректный исход, иначе описание проблемы. */
    fun check(work: () -> Unit): String? {
        val f = pool.submit(Callable { try { work(); null } catch (e: Throwable) { e } })
        val thrown = try {
            f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            pool.shutdownNow()
            pool = newPool() // зависший поток остаётся демоном
            return "превышено время ${TIMEOUT_MS} мс"
        } catch (e: ExecutionException) {
            e.cause
        } ?: return null
        return if (thrown is Error) describe(thrown, thrown) else problemOf(thrown)
    }

    /** Сохранить вход находки. */
    fun save(target: String, input: ByteArray, ext: String): File {
        val dir = File("build/fuzz-findings").apply { mkdirs() }
        val f = File(dir, "$target-${Integer.toHexString(input.contentHashCode())}.$ext")
        f.writeBytes(input)
        return f
    }

    // ---- Мутации ----

    private val interesting8 = byteArrayOf(0, 1, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x10, 0x20, 0x40)
    private val interesting32 = intArrayOf(0, 1, -1, 0x7FFFFFFF, 0x80000000.toInt(), 0xFFFF, 0x10000, 0x7FFF, 0x8000, 0x100, 0xFFFFFFF0.toInt(), 0x70)

    /**
     * Двоичная мутация: инверсия битов, «интересные» байты и слова (в порядке [littleEndian]),
     * усечение, вырезка, вставка и перенос кусков.
     */
    fun mutate(src: ByteArray, r: Random, littleEndian: Boolean): ByteArray {
        var b = src.copyOf()
        repeat(1 + r.nextInt(3)) {
            if (b.isEmpty()) return b
            when (r.nextInt(8)) {
                0 -> repeat(1 + r.nextInt(4)) { val i = r.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl r.nextInt(8))).toByte() }
                1 -> repeat(1 + r.nextInt(4)) { b[r.nextInt(b.size)] = interesting8[r.nextInt(interesting8.size)] }
                2, 3 -> if (b.size >= 4) {
                    val i = if (r.nextBoolean()) r.nextInt(b.size / 4) * 4 else r.nextInt(b.size - 3)
                    val v = interesting32[r.nextInt(interesting32.size)] + (if (r.nextInt(4) == 0) r.nextInt(-4, 5) else 0)
                    for (k in 0 until 4) b[i + k] = (v ushr (8 * (if (littleEndian) k else 3 - k))).toByte()
                }
                4 -> b = b.copyOf(r.nextInt(b.size))
                5 -> { val i = r.nextInt(b.size); val n = r.nextInt(1, minOf(64, b.size - i) + 1); b = b.copyOfRange(0, i) + b.copyOfRange(i + n, b.size) }
                6 -> { val i = r.nextInt(b.size + 1); b = b.copyOfRange(0, i) + ByteArray(r.nextInt(1, 32)) { r.nextInt(256).toByte() } + b.copyOfRange(i, b.size) }
                else -> { val n = r.nextInt(1, minOf(32, b.size) + 1); val from = r.nextInt(b.size - n + 1); val to = r.nextInt(b.size - n + 1); b.copyInto(b, to, from, from + n) }
            }
        }
        return b
    }

    private val tokens = listOf("0", "-1", "0x7fffffff", "-0x80000000", "0xffffffffffffffffL", "v65535", "p9", "v0", "{v0 .. v300}",
        ":cond_0", ":nope", "L;", "[", "[[[[I", "Ljava/lang/Object;", "LA;->b()V", "\"\\u", "\"", "'", ".end method", ".method", ".catch",
        ".registers 0", ".registers 70000", ".locals 3", "0x1t", "1.5f", "NaN", "-Infinity", "#", "{", "}", "..", "(", ")", "->", ":", "=")

    /** Текстовая мутация smali: строки (удалить, повторить, переставить) и лексемы (заменить, вставить). */
    fun mutateText(src: String, r: Random): String {
        val lines = src.lines().toMutableList()
        repeat(1 + r.nextInt(3)) {
            if (lines.isEmpty()) return ""
            val i = r.nextInt(lines.size)
            when (r.nextInt(7)) {
                0 -> lines.removeAt(i)
                1 -> lines.add(r.nextInt(lines.size + 1), lines[i])
                2 -> { val j = r.nextInt(lines.size); val t = lines[i]; lines[i] = lines[j]; lines[j] = t }
                3, 4 -> {
                    val parts = lines[i].split(' ').toMutableList()
                    val k = r.nextInt(parts.size)
                    parts[k] = tokens[r.nextInt(tokens.size)]
                    lines[i] = parts.joinToString(" ")
                }
                5 -> { val s = lines[i]; val p = r.nextInt(s.length + 1); lines[i] = s.substring(0, p) + tokens[r.nextInt(tokens.size)] + s.substring(p) }
                else -> { val s = lines[i]; if (s.isNotEmpty()) lines[i] = s.substring(0, r.nextInt(s.length)) }
            }
        }
        return lines.joinToString("\n")
    }

    /** Исправить file_size заголовка DEX (иначе почти каждая мутация отвергается уже на нём). */
    fun fixDexSize(b: ByteArray) {
        if (b.size < 0x24) return
        val n = b.size
        for (k in 0 until 4) b[0x20 + k] = (n ushr (8 * k)).toByte()
    }

    /** Прогон [iterations] мутаций каждого seed; все находки (без повторов по месту возникновения). */
    fun run(
        target: String, seeds: List<Pair<String, ByteArray>>, ext: String,
        mutation: (ByteArray, Random) -> ByteArray, work: (ByteArray) -> Unit
    ): List<Finding> {
        val findings = LinkedHashMap<String, Finding>()
        val r = Random(seed xor target.hashCode().toLong())
        for ((name, seedBytes) in seeds) {
            // Сам seed обязан обрабатываться без ошибок.
            check { work(seedBytes) }?.let { findings.getOrPut("seed:$name") { Finding(target, "seed $name: $it", null) } }
            repeat(iterations) {
                val input = mutation(seedBytes, r)
                val problem = check { work(input) } ?: return@repeat
                val key = problem.substringAfter(" @ ", problem).substringBefore(" (верхнее") + "|" + problem.substringBefore(":")
                if (key !in findings) findings[key] = Finding(target, problem, save(target, input, ext))
            }
        }
        return findings.values.toList()
    }
}
