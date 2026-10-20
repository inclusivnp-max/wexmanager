package org.wexclient.app.dex

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Каждый класс декомпилируется отдельно: раньше классы копились в общей папке, и N-й просмотр
 * заново прогонял через Vineflower все N классов.
 */
class VineflowerPerClassTest {

    private fun smali(dir: File, name: String, text: String) = File(dir, "$name.smali").apply {
        writeText(
            """
            .class public Ltest/$name;
            .super Ljava/lang/Object;

            .method public static greet()Ljava/lang/String;
                .registers 1
                const-string v0, "$text"
                return-object v0
            .end method
            """.trimIndent()
        )
    }

    private fun leftoverFiles(dir: File) = dir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `classes are decompiled one by one without leftovers`() {
        val tmp = Files.createTempDirectory("vf_single_").toFile()
        try {
            val first = smali(tmp, "First", "FirstVF")
            val second = smali(tmp, "Second", "SecondVF")
            val dexFile = File(tmp, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dexFile.absolutePath }
            assertTrue(Smali.assemble(options, first.absolutePath, second.absolutePath), "smali assembly failed")

            val engine = VineflowerEngine(listOf(DexSource("classes.dex", dexFile.readBytes())))
            try {
                val firstJava = engine.decompileToJava("test.First")
                assertTrue(firstJava.contains("FirstVF"), firstJava)
                assertEquals(emptyList(), leftoverFiles(engine.workDir))

                val secondJava = engine.decompileToJava("test.Second")
                assertTrue(secondJava.contains("SecondVF"), secondJava)
                assertFalse(secondJava.contains("FirstVF"), secondJava)
                assertEquals(emptyList(), leftoverFiles(engine.workDir))

                // Повторный запрос — из кэша, в обеих нотациях имени
                assertEquals(firstJava, engine.decompileToJava("test/First"))
            } finally {
                engine.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
