package org.wexclient.app.dex

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.wexclient.app.ui.DexTreeRow
import org.wexclient.app.ui.flattenDexTree
import java.nio.file.Files
import kotlin.test.*

class DexClassFamilyTest {
    private val d = '$'
    private fun enclosing(owner: String, name: String?) = """
        .annotation system Ldalvik/annotation/EnclosingClass;
            value = Ltest/$owner;
        .end annotation
        .annotation system Ldalvik/annotation/InnerClass;
            accessFlags = 0x9
            name = ${name?.let { "\"$it\"" } ?: "null"}
        .end annotation
    """.trimIndent()
    private fun fixture(block: (DexEditorEngine) -> Unit) {
        val dir = Files.createTempDirectory("wex-family-dex-")
        val definitions = linkedMapOf(
            "MainActivity" to "",
            "MainActivity${d}Inner" to enclosing("MainActivity", "Inner"),
            "MainActivity${d}Inner${d}Nested" to enclosing("MainActivity${d}Inner", "Nested"),
            "MainActivity${d}Companion" to enclosing("MainActivity", "Companion"),
            "MainActivity${d}1" to """
                .annotation system Ldalvik/annotation/EnclosingMethod;
                    value = Ltest/MainActivity;->marker()Ljava/lang/String;
                .end annotation
                .annotation system Ldalvik/annotation/InnerClass;
                    accessFlags = 0x0
                    name = null
                .end annotation
            """.trimIndent(),
            "Subclass" to "",
            "MainActivityExtra" to "",
            "MainActivity${d}${d}ExternalSyntheticLambda0" to ""
        )
        var engine: DexEditorEngine? = null
        try {
            val sources = definitions.entries.chunked(4).mapIndexed { index, entries ->
                val input = Files.createDirectory(dir.resolve("source$index"))
                entries.forEach { (name, annotation) ->
                    val marker = when (name) {
                        "MainActivity" -> "OWNER_MARK"
                        "MainActivity${d}Inner" -> "INNER_MARK"
                        "MainActivity${d}Companion" -> "COMPANION_MARK"
                        "MainActivity${d}${d}ExternalSyntheticLambda0" -> "HELPER_MARK"
                        else -> "OTHER_MARK"
                    }
                    val parent = if (name == "Subclass") "Ltest/MainActivity;" else "Ljava/lang/Object;"
                    Files.writeString(input.resolve("$name.smali"), """
                        .class public Ltest/$name;
                        .super $parent
                        $annotation
                        .method public static marker()Ljava/lang/String;
                            .registers 1
                            const-string v0, "$marker"
                            return-object v0
                        .end method
                    """.trimIndent())
                }
                val output = dir.resolve(if (index == 0) "classes.dex" else "classes${index + 1}.dex")
                assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = output.toString() }, input.toString()))
                DexSource(output.fileName.toString(), Files.readAllBytes(output))
            }
            engine = DexEditorEngine(sources);block(engine)
        } finally { engine?.close();dir.toFile().deleteRecursively() }
    }
    @Test fun `inner anonymous and companion classes group across DEX sources`() = fixture { engine ->
        val owner = engine.classes.single { it.simpleName == "MainActivity" }
        val family = engine.classFamilies.familyOf(owner)
        assertEquals(6, family.members.size)
        assertEquals(setOf("classes.dex", "classes2.dex"), family.members.map { it.dexSourceName }.toSet())
        assertEquals(owner, engine.classFamilies.familyOf(engine.classes.single { it.simpleName == "MainActivity${d}1" }).root)
    }
    @Test fun `inheritance is not confused with lexical nesting`() = fixture { engine ->
        val child = engine.classes.single { it.simpleName == "Subclass" }
        assertFalse(engine.classFamilies.familyOf(child).isSplit)
        assertEquals(3, engine.classFamilies.families.size)
    }
    @Test fun `browser has one row per family while search still finds exact parts`() = fixture { engine ->
        val rows = flattenDexTree(engine.getPackageTree(false), engine.getAllPackagePaths(false), engine.classFamilies)
        assertEquals(3, rows.filterIsInstance<DexTreeRow.ClassRow>().size)
        assertEquals(1, engine.searchClasses("MainActivity${d}Inner${d}Nested").size)
    }
    @Test fun `a part still opens as its own editable Smali unit`() = fixture { engine ->
        val part = engine.classes.single { it.simpleName == "MainActivity${d}Companion" }
        val smali = engine.disassembleToSmali(part)
        assertContains(smali, "COMPANION_MARK")
        assertFalse("OWNER_MARK" in smali)
    }
    @Test fun `JADX opens the outer class with named inner parts`() = fixture { engine ->
        val code = engine.decompileToJava(engine.classes.single { it.simpleName == "MainActivity" })
        assertContains(code, "OWNER_MARK");assertContains(code, "INNER_MARK");assertContains(code, "COMPANION_MARK");assertContains(code, "HELPER_MARK")
    }
    @Test fun `CFR receives the full family context`() = fixture { engine ->
        val code = engine.decompileToJavaCfr(engine.classes.single { it.simpleName == "MainActivity" })
        assertContains(code, "OWNER_MARK");assertContains(code, "INNER_MARK");assertContains(code, "COMPANION_MARK");assertContains(code, "HELPER_MARK")
    }
    @Test fun `Vineflower receives the full family context`() = fixture { engine ->
        val code = engine.decompileToJavaVineflower(engine.classes.single { it.simpleName == "MainActivity" })
        assertContains(code, "OWNER_MARK");assertContains(code, "INNER_MARK");assertContains(code, "COMPANION_MARK");assertContains(code, "HELPER_MARK")
    }
    @Test fun `Procyon receives the full family context`() = fixture { engine ->
        val code = engine.decompileToJavaProcyon(engine.classes.single { it.simpleName == "MainActivity" })
        assertContains(code, "OWNER_MARK");assertContains(code, "INNER_MARK");assertContains(code, "COMPANION_MARK");assertContains(code, "HELPER_MARK")
    }
}
