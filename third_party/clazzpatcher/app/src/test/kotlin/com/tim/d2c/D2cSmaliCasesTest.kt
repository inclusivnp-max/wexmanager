package com.tim.d2c

import com.tim.d2c.model.DexView
import com.tim.dex.model.DexFile
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Целевые случаи перевода на рукописном smali (собирается dsmali): схема копирования параметров,
 * слияние констант с разными типами, обработчик без move-exception, switch с крайними ключами,
 * fill-array-data, invoke-polymorphic, копия неинициализированной ссылки, LUB с checkcast.
 */
class D2cSmaliCasesTest {

    private fun dex(resource: String): DexFile {
        val text = javaClass.getResource(resource)!!.readText()
        val cls = SmaliParser.parseClass(text) ?: error("нет класса в $resource")
        return Smali.assemble(SmaliTree(mutableListOf(cls)))
    }

    @Test
    fun cases() {
        val jar = File(D2cTestSupport.tempDir("smali"), "t.jar")
        D2cTestSupport.writeJar(dex("/d2c/smali/T.smali"), jar)
        val run = D2cTestSupport.runMain(jar, "T")
        val expected = listOf(
            "7", "null", "0null1.01065353216", "1064", "8.0", "6.283185307179586", "101110",
            "100200-1120", "[1.5, -2.0, 0.25]ABЖ[true, false, true][1, 2, 3, 4, 5]", "42(int)String",
            "7", "copy", "-9", "123", "ABC", "9"
        )
        assertEquals(expected, run.out.trim().lines(), run.out)
        val v = D2cTestSupport.verify(jar)
        assertTrue(v.verifyErrors.isEmpty(), v.verifyErrors.joinToString("\n"))
    }

    @Test
    fun copyLayoutIsUsedForWideAcrossParams() {
        val d = dex("/d2c/smali/T.smali")
        val cf = DexToClass.convert(d).single()
        val m = cf.methods.first { cf.utf8(it.nameIndex) == "wideAcross" }
        val code = m.attributes.filterIsInstance<com.tim.clazz.model.attributes.Code>().single()
        // Схема с копированием: слоты ins (2) + регистры (4).
        assertEquals(6, code.maxLocals)
        assertEquals("LT;", DexView(d).type(d.classDefs[0].classIdx))
    }
}
