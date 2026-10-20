package com.tim.testutil

import com.tim.c2d.C2dCalcPrograms
import com.tim.c2d.C2dSamples
import java.io.File

/**
 * Программы для сквозных проверок конвертеров: исходники Java с `main`, печатающим результаты.
 * Эталон — вывод исходных классов в HotSpot (`-Xverify:all`).
 */
object Programs {

    /** Эталон для запуска в ART: та же программа от d8 в ART или вывод HotSpot (там, где d8 отступает от JDK). */
    enum class ArtReference { D8, HOTSPOT }

    class Program(val name: String, val mainClass: String, val sources: Map<String, String>, val release: Int,
                  val extra: List<String> = emptyList(), val art: ArtReference = ArtReference.D8) {
        override fun toString() = name
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    /** Программы для --release 8 (лямбды в них рассахаривает c2d, как и в [indy]). */
    val plain: List<Program> by lazy {
        val d2c = File(root, "app/src/test/resources/d2c/programs").listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "indy" && it.name != "reflect" }
            .sortedBy { it.name }
            .map { dir -> Program("d2c/${dir.name}", "Main", mapOf("Main.java" to File(dir, "Main.java").readText()), 8) }
        listOf(
            Program("c2d/samples", "c2d.Samples", mapOf("c2d/Samples.java" to C2dSamples.SAMPLES), 8),
            Program("c2d/calc", "c2d.Calc", mapOf("c2d/Calc.java" to C2dCalcPrograms.CALC), 8),
            Program("c2d/bigcalc", "c2d.BigCalc", mapOf("c2d/BigCalc.java" to C2dCalcPrograms.bigCalc()), 8),
            Program("c2d/big", "c2d.Big", mapOf("c2d/Big.java" to C2dSamples.bigMethod(300)), 8),
            Program("c2d/nest", "c2d.Nest", mapOf("c2d/Nest.java" to C2dCalcPrograms.NEST), 17)
        ) + d2c
    }

    /** Программы с invokedynamic (лямбды, конкатенация строк, pattern switch, records). */
    val indy: List<Program> by lazy {
        listOf(
            Program("features", "fx.feat.Features",
                mapOf("fx/feat/Features.java" to File(root, "app/src/test/fixtures-src/java/features/fx/feat/Features.java").readText()),
                21, listOf("-parameters")),
            Program("d2c/indy", "Main", mapOf("Main.java" to File(root, "app/src/test/resources/d2c/programs/indy/Main.java").readText()), 8),
            Program("desugar", "fx.ds.Desugar",
                mapOf("fx/ds/Desugar.java" to File(root, "app/src/test/fixtures-src/java/desugar/fx/ds/Desugar.java").readText()), 21),
            Program("desugar-jdk", "fx.ds.DesugarJdk",
                mapOf("fx/ds/DesugarJdk.java" to File(root, "app/src/test/fixtures-src/java/desugar/fx/ds/DesugarJdk.java").readText()), 21,
                art = ArtReference.HOTSPOT),
            Program("reflect21", "fx.ds.Refl",
                mapOf("fx/ds/Refl.java" to File(root, "app/src/test/fixtures-src/java/desugar/fx/ds/Refl.java").readText()), 21,
                art = ArtReference.HOTSPOT)
        )
    }

    /** Компиляция в [dir]; возвращает каталог классов. */
    fun compile(p: Program, dir: File): File {
        val src = File(dir, "src")
        val files = p.sources.map { (path, text) -> File(src, path).also { it.parentFile.mkdirs(); it.writeText(text) } }
        val out = File(dir, "classes")
        JvmVerifier.compileJava(files, out, *(listOf("--release", p.release.toString(), "-g", "-nowarn", "-encoding", "UTF-8") + p.extra).toTypedArray())
        return out
    }

    /** Все .class каталога: внутреннее имя → байты. */
    fun classes(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }
            .associate { it.relativeTo(dir).path.replace(File.separatorChar, '/').removeSuffix(".class") to it.readBytes() }
}
