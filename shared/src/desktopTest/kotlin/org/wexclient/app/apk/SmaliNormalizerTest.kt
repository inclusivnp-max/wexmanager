package org.wexclient.app.apk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Приведение smali к виду, где мелочи пересборки не считаются изменением. */
class SmaliNormalizerTest {

    private val all = DexCompareOptions(
        ignoreDebugInfo = true,
        ignoreCompilerOptimization = true,
        ignoreRegisters = true,
        ignoreInstructionOrder = true,
        ignoreResourceIds = true
    )
    private val strict = DexCompareOptions(false, false, false, false, false)

    @Test
    fun `отладочные строки выбрасываются`() {
        assertNull(SmaliNormalizer.line("    .line 42", all))
        assertNull(SmaliNormalizer.line(".source \"Foo.java\"", all))
        assertNull(SmaliNormalizer.line("    .local v0, \"x\":I", all))
        // При выключенной настройке строка остаётся
        assertEquals(".line 42", SmaliNormalizer.line("    .line 42", strict))
    }

    @Test
    fun `регистры обезличиваются`() {
        assertEquals(
            "invoke-virtual {v#, p#}, Lcom/A;->b()V",
            SmaliNormalizer.line("    invoke-virtual {v3, p1}, Lcom/A;->b()V", all)
        )
        assertEquals(
            "invoke-virtual {v3, p1}, Lcom/A;->b()V",
            SmaliNormalizer.line("    invoke-virtual {v3, p1}, Lcom/A;->b()V", strict)
        )
    }

    @Test
    fun `разрядность опкода не учитывается`() {
        assertEquals("const v#, 0x1", SmaliNormalizer.line("    const/16 v0, 0x1", all))
        assertEquals("goto :label", SmaliNormalizer.line("    goto/16 :label", all))
        assertEquals("const/16 v0, 0x1", SmaliNormalizer.line("    const/16 v0, 0x1", strict))
    }

    @Test
    fun `идентификаторы ресурсов сводятся к одному`() {
        assertEquals("const v#, 0x7fRES", SmaliNormalizer.line("    const v0, 0x7f0a0123", all))
        // Обычное число не трогаем
        assertEquals("const v#, 0x1f0a0123", SmaliNormalizer.line("    const v0, 0x1f0a0123", all))
        assertTrue(SmaliNormalizer.isResourceId(0x7f0a0123))
        assertTrue(!SmaliNormalizer.isResourceId(0x1f0a0123))
    }

    @Test
    fun `объявления и метки не ломаются`() {
        assertEquals(".method public a()V", SmaliNormalizer.line("    .method public a()V", all))
        assertEquals(":cond_0", SmaliNormalizer.line("    :cond_0", all))
        assertEquals("# direct methods", SmaliNormalizer.line("# direct methods", all))
    }

    @Test
    fun `выброшенные строки не сдвигают нумерацию`() {
        val text = ".class public LA;\n    .line 5\n    return-void\n"
        assertEquals(listOf(".class public LA;", "", "return-void", ""), SmaliNormalizer.lines(text, all))
    }

    @Test
    fun `подпись настроек читается`() {
        assertEquals("отладка, оптимизация, регистры, порядок, id ресурсов", all.label)
        assertEquals("всё учитывается", strict.label)
    }
}
