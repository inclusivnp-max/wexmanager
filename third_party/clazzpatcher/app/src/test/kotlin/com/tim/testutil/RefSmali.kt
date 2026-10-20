package com.tim.testutil

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.util.SyntheticAccessorResolver
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.StringWriter
import java.nio.file.Files

/**
 * Эталонные baksmali/smali 3.0.9 в том же процессе (тестовая зависимость) — оракул синтаксиса smali
 * и семантического сравнения DEX (как скрипт dexdiff: baksmali с настройками по умолчанию).
 */
object RefSmali {
    private fun load(bytes: ByteArray): DexBackedDexFile = DexBackedDexFile(null, bytes)

    /** Настройки как у `baksmali d` по умолчанию. */
    private fun options(dex: DexBackedDexFile, debugInfo: Boolean) = BaksmaliOptions().apply {
        parameterRegisters = true
        localsDirective = false
        sequentialLabels = false
        this.debugInfo = debugInfo
        codeOffsets = false
        accessorComments = true
        implicitReferences = false
        normalizeVirtualMethods = false
        registerInfo = 0
        syntheticAccessorResolver = SyntheticAccessorResolver(dex.opcodes, dex.classes)
    }

    private fun render(options: BaksmaliOptions, cls: DexBackedClassDef): String {
        val sw = StringWriter()
        val writer = BaksmaliWriter(sw, null)
        ClassDefinition(options, cls).writeTo(writer)
        writer.flush()
        return sw.toString()
    }

    /** Smali-текст каждого класса: дескриптор -> текст. */
    fun disassemble(bytes: ByteArray, debugInfo: Boolean = true): Map<String, String> {
        val dex = load(bytes)
        val options = options(dex, debugInfo)
        val result = sortedMapOf<String, String>()
        for (cls in dex.classes) {
            if (result.put(cls.type, render(options, cls)) != null) throw IllegalStateException("дубликат класса ${cls.type}")
        }
        return result
    }

    /**
     * Сравнение двух DEX через тексты эталонного baksmali (как dexdiff). null — совпадают,
     * иначе отчёт: число различающихся классов и фрагменты первых [maxClasses].
     */
    fun diff(expected: ByteArray, actual: ByteArray, debugInfo: Boolean = true, maxClasses: Int = 3): String? {
        val a = load(expected)
        val b = try {
            load(actual)
        } catch (e: RuntimeException) {
            return "эталонный baksmali не читает результат: $e"
        }
        val oa = options(a, debugInfo)
        val ob = options(b, debugInfo)
        val bClasses = b.classes.associateBy { it.type }
        val aTypes = a.classes.map { it.type }.toSet()
        val report = StringBuilder()
        val missing = aTypes - bClasses.keys
        val extra = bClasses.keys - aTypes
        if (missing.isNotEmpty()) report.append("пропали классы (${missing.size}): ${missing.sorted().take(10)}\n")
        if (extra.isNotEmpty()) report.append("лишние классы (${extra.size}): ${extra.sorted().take(10)}\n")
        var differing = 0
        for (ca in a.classes) {
            val cb = bClasses[ca.type] ?: continue
            val ta = render(oa, ca)
            val tb = try {
                render(ob, cb)
            } catch (e: RuntimeException) {
                differing++
                report.append("класс ${ca.type}: эталонный baksmali падает на результате: $e\n")
                continue
            }
            if (ta != tb) {
                if (differing < maxClasses) report.append("класс ${ca.type} отличается:\n").append(TextDiff.snippet(ta, tb))
                differing++
            }
        }
        if (differing > 0) report.insert(0, "различаются классы: $differing из ${aTypes.size}\n")
        return if (report.isEmpty()) null else report.toString()
    }

    /** Уровень API для эталонного smali по версии DEX (чтобы допускались нужные опкоды и секции). */
    fun apiForVersion(version: String): Int = when (version) {
        "035" -> 15
        "037" -> 24
        "038" -> 26
        "039" -> 29
        else -> throw IllegalArgumentException("эталонный smali 3.0.9: неподдерживаемая версия DEX $version")
    }

    private val errLock = Any()

    /** Сборка эталонным smali из текстов классов. */
    fun assemble(texts: Collection<String>, apiLevel: Int): ByteArray {
        val dir = Files.createTempDirectory("refsmali").toFile()
        try {
            val src = File(dir, "src").apply { mkdirs() }
            texts.forEachIndexed { i, t -> File(src, "c$i.smali").writeText(t) }
            val out = File(dir, "out.dex")
            val options = SmaliOptions().apply {
                this.apiLevel = apiLevel
                outputDexFile = out.path
                jobs = 1
                verboseErrors = true
            }
            val captured = ByteArrayOutputStream()
            val ok = synchronized(errLock) {
                val old = System.err
                System.setErr(PrintStream(captured, true, "UTF-8"))
                try {
                    Smali.assemble(options, listOf(src.path))
                } finally {
                    System.setErr(old)
                }
            }
            if (!ok) {
                val errors = captured.toString("UTF-8").lines().filter { it.isNotBlank() }.take(20).joinToString("\n")
                throw AssertionError("эталонный smali не собрал текст:\n$errors")
            }
            return out.readBytes()
        } finally {
            dir.deleteRecursively()
        }
    }
}
