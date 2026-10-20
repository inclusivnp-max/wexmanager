package org.wexclient.app.dex

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Мост DEX -> jar: основной перевод — d2c, не переведённое им полностью — у dex2jar. */
class DexJarCacheTest {

    private val good = """
        .class public Ltest/Good;
        .super Ljava/lang/Object;

        .method public static greet()Ljava/lang/String;
            .registers 1
            const-string v0, "fromD2c"
            return-object v0
        .end method
    """.trimIndent()

    // v0 читается, ни разу не записанный: ART такой метод отверг бы, d2c ставит заглушку и помечает класс
    private val broken = """
        .class public Ltest/Broken;
        .super Ljava/lang/Object;

        .method public static f()I
            .registers 1
            return v0
        .end method
    """.trimIndent()

    private fun dex(vararg smali: String): ByteArray {
        val tmp = Files.createTempDirectory("dexjar_test_").toFile()
        try {
            val files = smali.mapIndexed { i, text -> File(tmp, "C$i.smali").apply { writeText(text) } }
            val out = File(tmp, "classes.dex")
            assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = out.absolutePath }, files.map { it.absolutePath }))
            return out.readBytes()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `good class comes from d2c, degraded one from dex2jar`() {
        val cache = DexJarCache(listOf(DexSource("classes.dex", dex(good, broken))))
        try {
            val primary = cache.getJars()
            assertEquals(1, primary.size)
            assertTrue(primary[0].endsWith("classes_d2c.jar"), primary[0])

            assertEquals(primary, cache.jarsFor("test/Good.class"))
            assertNotNull(DexToJar.readFirst(cache.jarsFor("test/Good.class"), "test/Good.class"))
            // Второе мнение для класса от d2c — jar dex2jar
            val alt = cache.alternativeJarsFor("test/Good.class")
            assertNotNull(alt)
            assertTrue(alt.none { it.endsWith("classes_d2c.jar") })

            val brokenJars = cache.jarsFor("test/Broken.class")
            assertTrue(brokenJars.none { it.endsWith("classes_d2c.jar") }, brokenJars.toString())
            // Класс и так из dex2jar — второго мнения нет
            assertNull(cache.alternativeJarsFor("test/Broken.class"))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `second opinion wins only with fewer failed methods`() {
        val marker = arrayOf("has failed to decompile")
        val bad2 = "a has failed to decompile; b has failed to decompile"
        val bad1 = "a has failed to decompile"
        assertEquals(2, DexToJar.failures(bad2, marker))
        assertEquals(bad1, DexToJar.secondOpinion(bad2, marker) { bad1 })
        assertEquals(bad1, DexToJar.secondOpinion(bad1, marker) { bad2 })
        assertEquals(bad1, DexToJar.secondOpinion(bad1, marker) { "x has failed to decompile" })
        var asked = false
        assertEquals("ok", DexToJar.secondOpinion("ok", marker) { asked = true; "other" })
        assertTrue(!asked, "без сбоев второе мнение не спрашивается")
    }

    @Test
    fun `CFR decompiles class translated by d2c`() {
        val engine = CfrEngine(listOf(DexSource("classes.dex", dex(good))))
        try {
            val java = engine.decompileToJava("test.Good")
            assertTrue(java.contains("fromD2c"), java)
        } finally {
            engine.close()
        }
    }
}
