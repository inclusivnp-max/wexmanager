package org.wexclient.app.dex

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class ClassFileFamilyTest {
    private val d = '$'
    private fun fixture(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("wex-jvm-family-")
        try {
            val source = dir.resolve("MainActivity.java")
            Files.writeString(source, """
                public class MainActivity {
                    public String marker() { return "OWNER_MARK"; }
                    public static class Inner {
                        public String marker() { return "INNER_MARK"; }
                        public static class Nested { public String nested() { return "NESTED_MARK"; } }
                    }
                    public static class Companion { public String marker() { return "COMPANION_MARK"; } }
                    public Runnable worker() { return new Runnable() { public void run() { System.out.println("ANON_MARK"); } }; }
                }
            """.trimIndent())
            assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, "-d", dir.toString(), source.toString()))
            block(dir)
        } finally { dir.toFile().deleteRecursively() }
    }
    private fun load(file: Path) = ClassFiles.load(file, Files::readAllBytes) { dir -> Files.list(dir).use { it.toList() } }
    @Test fun `opening an inner class discovers its full family`() = fixture { dir ->
        val set = load(dir.resolve("MainActivity${d}Inner.class"))
        assertEquals("MainActivity", set.family.root)
        assertEquals(5, set.family.members.size)
        assertTrue(set.info.enclosingName == "MainActivity")
    }
    @Test fun `whole class output includes all nested method bodies`() = fixture { dir ->
        val set = load(dir.resolve("MainActivity${d}Inner.class"));val whole = set.forClass(set.family.root)
        for (view in listOf(ClassView.VINEFLOWER, ClassView.CFR, ClassView.PROCYON)) {
            val code = ClassFiles.render(whole, view)
            for (marker in listOf("OWNER_MARK", "INNER_MARK", "NESTED_MARK", "COMPANION_MARK", "ANON_MARK")) assertContains(code, marker, message = view.name)
        }
    }
    @Test fun `part output does not turn into the outer class`() = fixture { dir ->
        val set = load(dir.resolve("MainActivity.class")).forClass("MainActivity${d}Inner")
        assertTrue(set.isOwn("MainActivity${d}Inner${d}Nested"))
        assertFalse(set.isOwn("MainActivity"));assertFalse(set.isOwn("MainActivity${d}Companion"))
        assertContains(ClassFiles.render(set, ClassView.VINEFLOWER), "INNER_MARK")
        assertFalse("OWNER_MARK" in ClassFiles.render(set, ClassView.VINEFLOWER))
    }
    @Test fun `renamed file still discovers siblings from its actual class name`() = fixture { dir ->
        val alias = dir.resolve("renamed.class");Files.copy(dir.resolve("MainActivity${d}Inner.class"), alias)
        assertEquals("MainActivity", load(alias).family.root)
    }
    @Test fun `missing outer file keeps the available inner subtree usable`() = fixture { dir ->
        Files.delete(dir.resolve("MainActivity.class"))
        val set = load(dir.resolve("MainActivity${d}Inner.class"))
        assertEquals("MainActivity${d}Inner", set.family.root)
        assertEquals(2, set.family.members.size)
    }
    @Test fun `all DEX jars contribute family members without unrelated classes`() = fixture { dir ->
        val names = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(".class") }.toList() }
        val jars = names.mapIndexed { index, file ->
            val jar = dir.resolve("part$index.jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { out -> out.putNextEntry(ZipEntry(file.fileName.toString()));out.write(Files.readAllBytes(file));out.closeEntry() }
            jar.toString()
        }
        val set = assertNotNull(ClassFiles.fromJars(jars, "MainActivity"))
        assertEquals(5, set.family.members.size)
        assertContains(ClassFiles.render(set, ClassView.PROCYON), "COMPANION_MARK")
    }
    @Test fun `independent generated helpers remain in the whole class output`() = fixture { dir ->
        val name = "MainActivity${d}${d}ExternalSyntheticLambda0"
        val source = dir.resolve("$name.java")
        Files.writeString(source, "class $name { public static String marker() { return \"HELPER_MARK\"; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()))
        val set = load(dir.resolve("MainActivity.class"))
        assertEquals(6, set.family.members.size)
        for (view in listOf(ClassView.VINEFLOWER, ClassView.CFR, ClassView.PROCYON)) {
            val code = ClassFiles.render(set, view)
            assertContains(code, "HELPER_MARK", message = view.name)
            assertEquals(1, Regex("OWNER_MARK").findAll(code).count())
        }
    }

    @Test fun `family count limit is checked before reading sibling files`() = fixture { dir ->
        val path = dir.resolve("MainActivity.class")
        var reads = 0
        assertFailsWith<java.io.IOException> {
            ClassFiles.load(path, { reads++; Files.readAllBytes(it) }) {
                (1..ClassFamilyLimits.MAX_MEMBERS).map { dir.resolve("MainActivity${d}Part$it.class") }
            }
        }
        assertEquals(1, reads)
    }
    @Test fun `oversized jar family is rejected before decompression`() = fixture { dir ->
        val jar = dir.resolve("oversized.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("MainActivity.class"));out.write(Files.readAllBytes(dir.resolve("MainActivity.class")));out.closeEntry()
            out.putNextEntry(ZipEntry("MainActivity${d}Bomb.class"))
            val block = ByteArray(1024 * 1024)
            repeat(65) { out.write(block) }
            out.closeEntry()
        }
        assertFailsWith<java.io.IOException> { ClassFiles.fromJars(listOf(jar.toString()), "MainActivity") }
    }

}
