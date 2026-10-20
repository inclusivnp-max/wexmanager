package com.tim.fuzz

import com.tim.c2d.ClassToDex
import com.tim.clazz.parser.ClassReader
import com.tim.d2c.D2cOptions
import com.tim.d2c.DexToClass
import com.tim.dex.model.DexFile
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import com.tim.testutil.Adb
import com.tim.testutil.JvmVerifier
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Дифференциальный фаззинг d2c и c2d на настоящем ART (opt-in):
 *   ./gradlew :app:test -Padb=<serial> -Pdifffuzz=<программ> [-Pfuzz.seed=N] --tests 'com.tim.fuzz.DiffFuzzTest'
 *
 * Случайные программы на smali ([SmaliProgramGen]) исполняются в ART — это эталон. Затем:
 * DEX → .class (d2c) → HotSpot `-Xverify:all` и DEX → .class → DEX (c2d) → ART; вывод каждой программы
 * обязан совпасть. Программы, которые отверг сам ART (ошибка генератора), не сравниваются.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffFuzzTest {
    private val tmp: File = Files.createTempDirectory("difffuzz").toFile()
    private var adb: Adb? = null

    @AfterAll
    fun cleanup() {
        adb?.cleanup()
        tmp.deleteRecursively()
    }

    private val batch = 40

    private fun mainClass(count: Int): String = buildString {
        append(".class public Lg/Main;\n.super Ljava/lang/Object;\n")
        append(".method public static main([Ljava/lang/String;)V\n    .registers 3\n")
        append("    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;\n")
        for (k in 0 until count) {
            append("    const-string v1, \"#$k\"\n    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V\n")
            append("    :s$k\n    invoke-static {}, Lg/G$k;->run()V\n    :e$k\n")
            append("    .catch Ljava/lang/Throwable; {:s$k .. :e$k} :c$k\n    goto :n$k\n")
            append("    :c$k\n    move-exception v1\n")
            append("    invoke-virtual {v1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;\n    move-result-object v1\n")
            append("    invoke-virtual {v1}, Ljava/lang/Class;->getName()Ljava/lang/String;\n    move-result-object v1\n")
            append("    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V\n")
            append("    :n$k\n")
        }
        append("    return-void\n.end method\n")
    }

    /** Вывод по программам: «#k» → строки. */
    private fun split(out: String): Map<Int, List<String>> {
        val map = LinkedHashMap<Int, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (line in out.lines()) {
            if (line.startsWith("#") && line.substring(1).toIntOrNull() != null) {
                cur = ArrayList<String>().also { map[line.substring(1).toInt()] = it }
            } else if (line.isNotBlank()) cur?.add(line)
        }
        return map
    }

    @Test
    fun smaliProgramsOnArt() {
        val device = Adb.configured()
        assumeTrue(device != null, "устройство не задано (-Padb=<serial>)")
        val total = System.getProperty("clazzpatcher.difffuzz")?.toIntOrNull()
        assumeTrue(total != null, "число программ не задано (-Pdifffuzz=N)")
        adb = device!!
        device.prepare()
        val findings = ArrayList<String>()
        var compared = 0
        var rejected = 0
        val batches = (total!! + batch - 1) / batch
        for (b in 0 until batches) {
            val seed = Fuzz.seed * 7919 + b
            val texts = (0 until batch).map { k -> SmaliProgramGen(seed * 1000 + k, "g/G$k").generate() }
            val classes = (texts + mainClass(batch)).map { SmaliParser.parseClass(it) ?: error("пустой класс") }
            val dex: DexFile = Smali.assemble(SmaliTree(classes.toMutableList()))
            val dir = File(tmp, "b$b").apply { mkdirs() }
            val dexFile = File(dir, "g$b.dex").also { it.writeBytes(DexWriter(dex).buildDex()) }
            val art = split(device.runDex(dexFile, "g.Main").second)

            // d2c → HotSpot
            val bytes = try {
                DexToClass.convertToBytes(dex, D2cOptions())
            } catch (e: Exception) {
                findings.add("пакет $b (seed $seed): d2c: ${generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: "" }}")
                File(File("build/difffuzz-findings").apply { mkdirs() }, "seed$seed-d2c.smali").writeText(texts.joinToString("\n\n"))
                continue
            }
            val classDir = File(dir, "classes").also { JvmVerifier.writeClasses(it, bytes) }
            // Куча как у dalvikvm (256 МБ по умолчанию): огромный массив (длина из битов float) не должен
            // создаваться в одной VM и падать с OutOfMemoryError в другой.
            val hot = split(JvmVerifier.runMain(listOf(classDir), "g.Main", "-Xverify:all", "-Xmx256m").second)

            // d2c → c2d → ART
            val back = File(dir, "back$b.dex").also {
                it.writeBytes(DexWriter(ClassToDex.convert(bytes.values.map { b2 -> ClassReader().read(b2) })).buildDex())
            }
            val art2 = split(device.runDex(back, "g.Main").second)

            for (k in 0 until batch) {
                val ref = art[k] ?: emptyList()
                if (ref.any { "VerifyError" in it }) { rejected++; continue }
                compared++
                val problems = ArrayList<String>()
                if (hot[k] != ref) problems.add("HotSpot после d2c: ${hot[k]} ≠ ART: $ref")
                if (art2[k] != ref) problems.add("ART после d2c → c2d: ${art2[k]} ≠ ART: $ref")
                if (problems.isNotEmpty()) {
                    val f = File(File("build/difffuzz-findings").apply { mkdirs() }, "seed$seed-G$k.smali").also { it.writeText(texts[k]) }
                    findings.add("пакет $b, программа $k (${f.path}):\n  " + problems.joinToString("\n  "))
                }
            }
        }
        println("difffuzz: сравнено $compared программ, отвергнуто ART (генератор) $rejected, находок ${findings.size}")
        assertTrue(rejected * 20 <= compared + rejected, "генератор слишком часто пишет неверный для ART код: $rejected")
        assertTrue(findings.isEmpty(), "находок: ${findings.size}\n" + findings.take(20).joinToString("\n"))
    }
}
