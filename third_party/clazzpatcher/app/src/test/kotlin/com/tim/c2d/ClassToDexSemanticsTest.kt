package com.tim.c2d

import com.tim.dex.parser.DexReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Смысл перевода: программа, исполненная на JVM (`java -Xverify:all`), и она же после
 * .class → DEX, исполненная [DexInterpreter], печатают одно и то же.
 */
class ClassToDexSemanticsTest {

    private fun sameOutput(source: String, className: String, options: C2dOptions = C2dOptions(), release: Int = 8) {
        val (expected, classes) = C2dTestKit.compileAndRun(mapOf("${className.replace('.', '/')}.java" to source), className, release)
        val dex = C2dTestKit.convert(classes, options)
        C2dTestKit.assertValidDex(dex, className, expectedClasses = classes.size)
        val actual = DexInterpreter(DexReader().read(dex)).runMain("L${className.replace('.', '/')};")
        assertEquals(expected, actual, "вывод $className на JVM и после преобразования в DEX")
    }

    @Test
    fun calc() = sameOutput(C2dCalcPrograms.CALC, "c2d.Calc")

    @Test
    fun calcWithoutDebugInfo() = sameOutput(C2dCalcPrograms.CALC, "c2d.Calc", C2dOptions(debugInfo = false))

    @Test
    fun bigRegisters() = sameOutput(C2dCalcPrograms.bigCalc(), "c2d.BigCalc")

    /** Java 17: доступ к private-членам соседей по гнезду (верификатор тестов отвергает прямой доступ). */
    @Test
    fun nestmates() = sameOutput(C2dCalcPrograms.NEST, "c2d.Nest", release = 17)
}
