package org.wexclient.app.dex

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Сквозной тест Procyon-движка без APK: smali -> dex (smali-lib) -> jar (dex2jar) -> Java (Procyon).
 */
class ProcyonEngineTest {

    @Test
    fun `dex to java via Procyon end to end`() {
        val tmp = Files.createTempDirectory("procyon_test_").toFile()
        try {
            val smaliFile = File(tmp, "Hello.smali").apply {
                writeText(
                    """
                    .class public Ltest/Hello;
                    .super Ljava/lang/Object;

                    .method public static greet()Ljava/lang/String;
                        .registers 1
                        const-string v0, "HelloProcyon"
                        return-object v0
                    .end method
                    """.trimIndent()
                )
            }
            val dexFile = File(tmp, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dexFile.absolutePath }
            assertTrue(Smali.assemble(options, smaliFile.absolutePath), "smali assembly failed")
            assertTrue(dexFile.exists() && dexFile.length() > 0, "dex not produced")

            val engine = ProcyonEngine(listOf(DexSource("classes.dex", dexFile.readBytes())))
            try {
                val java = engine.decompileToJava("test.Hello")
                println("=== Procyon output ===\n$java\n=== end of Procyon output ===")
                assertTrue(java.contains("HelloProcyon"), message = "Procyon output was:\n$java")
            } finally {
                engine.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
