package org.wexclient.app.dex

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Сквозной тест Vineflower-движка без APK: smali -> dex (smali-lib) -> jar (dex2jar) -> Java (Vineflower).
 */
class VineflowerEngineTest {

    @Test
    fun `dex to java via Vineflower end to end`() {
        val tmp = Files.createTempDirectory("vf_test_").toFile()
        try {
            val smaliFile = File(tmp, "Hello.smali").apply {
                writeText(
                    """
                    .class public Ltest/Hello;
                    .super Ljava/lang/Object;

                    .method public static greet()Ljava/lang/String;
                        .registers 1
                        const-string v0, "HelloVF"
                        return-object v0
                    .end method
                    """.trimIndent()
                )
            }
            val dexFile = File(tmp, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dexFile.absolutePath }
            assertTrue(Smali.assemble(options, smaliFile.absolutePath), "smali assembly failed")
            assertTrue(dexFile.exists() && dexFile.length() > 0, "dex not produced")

            val engine = VineflowerEngine(listOf(DexSource("classes.dex", dexFile.readBytes())))
            try {
                val java = engine.decompileToJava("test.Hello")
                println("=== Vineflower output ===\n$java\n=== end of Vineflower output ===")
                assertTrue(java.contains("HelloVF"), "Vineflower output was:\n$java")
            } finally {
                engine.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
