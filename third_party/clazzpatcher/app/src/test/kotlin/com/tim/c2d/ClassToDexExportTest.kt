package com.tim.c2d

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Выгрузка для проверки на ART: при заданной переменной `C2D_EXPORT=<каталог>` пишет туда
 * `<имя>.dex` и `<имя>.expected.txt` (вывод `java`) для программ тестов. Запуск на устройстве:
 *
 * ```
 * adb push <каталог> /data/local/tmp/c2d
 * adb shell dalvikvm64 -cp /data/local/tmp/c2d/calc.dex c2d.Calc
 * ```
 * и сравнение вывода с `calc.expected.txt`. Без переменной тест ничего не делает.
 */
class ClassToDexExportTest {

    @Test
    fun export() {
        val dir = System.getenv("C2D_EXPORT")?.let { File(it) } ?: return
        dir.mkdirs()
        val programs = listOf(
            Triple("samples", "c2d.Samples", C2dSamples.SAMPLES to 8),
            Triple("calc", "c2d.Calc", C2dCalcPrograms.CALC to 8),
            Triple("bigcalc", "c2d.BigCalc", C2dCalcPrograms.bigCalc() to 8),
            Triple("big", "c2d.Big", C2dSamples.bigMethod(300) to 8),
            Triple("nest", "c2d.Nest", C2dCalcPrograms.NEST to 17)
        )
        for ((file, className, program) in programs) {
            val (source, release) = program
            val (expected, classes) = C2dTestKit.compileAndRun(mapOf("${className.replace('.', '/')}.java" to source), className, release)
            val dex = C2dTestKit.convert(classes)
            C2dTestKit.assertValidDex(dex, className)
            File(dir, "$file.dex").writeBytes(dex)
            File(dir, "$file.expected.txt").writeText(expected)
            println("c2d: $file.dex — запуск: dalvikvm64 -cp $file.dex $className")
        }
    }
}
