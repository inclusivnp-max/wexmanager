package org.wexclient.app.dex

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.wexclient.app.editor.FoldingEngine
import org.wexclient.app.editor.HighlightModel
import org.wexclient.app.editor.OutlineEngine
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.JvmBytecodeLexer
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.editor.lang.Token
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassFileTest {
    private lateinit var dir: Path
    private lateinit var classes: Path

    private val source = """
        package com.example;

        import java.util.List;
        import java.util.function.Function;

        public class Sample<T extends Comparable<T>> implements Runnable {
            private static final String GREETING = "Привет, мир";
            private final List<T> items;
            protected int counter = 42;

            public Sample(List<T> items) { this.items = items; }

            @Override
            public void run() {
                Runnable r = new Runnable() {
                    @Override public void run() { System.out.println(GREETING + counter); }
                };
                r.run();
                Function<T, String> f = t -> t.toString().toUpperCase();
                for (T item : items) System.out.println(f.apply(item));
            }

            public static long sum(int[] values) {
                long total = 0L;
                for (int v : values) total += v;
                return total;
            }

            enum Mode { FAST, SLOW }

            static class Holder {
                double value = 3.14;
                String describe(Mode mode) {
                    return switch (mode) { case FAST -> "быстро " + value; case SLOW -> "медленно"; };
                }
            }
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_classfile")
        val src = dir.resolve("src/com/example/Sample.java")
        Files.createDirectories(src.parent)
        Files.writeString(src, source)
        classes = dir.resolve("classes")
        Files.createDirectories(classes)
        val javac = assertNotNull(ToolProvider.getSystemJavaCompiler(), "для теста нужен JDK с javac")
        val code = javac.run(null, null, null, "-g", "-encoding", "UTF-8", "-d", classes.toString(), src.toString())
        assertEquals(0, code)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun pkg() = classes.resolve("com/example")

    private fun load(path: Path) = ClassFiles.load(
        path,
        readBytes = { Files.readAllBytes(it) },
        listDir = { d -> Files.list(d).use { s -> s.toList() } }
    )

    @Test
    fun `class files are detected`() {
        assertEquals(VfsItemType.JAVA_CLASS, VfsEntry.detectType("Sample.class", false))
        assertEquals(VfsItemType.JAVA_CLASS, VfsEntry.detectType("Sample\$1.CLASS", false))
        val bytes = Files.readAllBytes(pkg().resolve("Sample.class"))
        assertTrue(ClassFiles.isClassFile(bytes))
        assertFalse(ClassFiles.isClassFile(bytes.copyOf(6)))
        // Mach-O fat: тот же CAFEBABE, дальше число архитектур
        assertFalse(ClassFiles.isClassFile(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 2)))
    }

    @Test
    fun `class and its inner classes are decompiled together`() {
        val set = load(pkg().resolve("Sample.class"))
        val info = set.info
        assertEquals("com/example/Sample", set.mainName)
        assertEquals("com.example.Sample", info.className)
        assertEquals("класс", info.kind)
        assertEquals("Java 21", info.javaVersion)
        assertFalse(info.kotlin)
        assertEquals(
            setOf("com/example/Sample", "com/example/Sample\$1", "com/example/Sample\$Holder", "com/example/Sample\$Mode"),
            set.classes.keys
        )

        val vf = ClassFiles.render(set, ClassView.VINEFLOWER)
        assertTrue("public class Sample<T extends Comparable<T>> implements Runnable" in vf, vf)
        assertTrue("\"Привет, мир\"" in vf && "static class Holder" in vf && "new Runnable()" in vf, vf)
        assertTrue("    public static long sum" in vf, "отступ — 4 пробела")

        val cfr = ClassFiles.render(set, ClassView.CFR)
        assertTrue("class Sample" in cfr && "\"Привет, мир\"" in cfr && "class Holder" in cfr, cfr)

        val procyon = ClassFiles.render(set, ClassView.PROCYON)
        assertTrue("class Sample" in procyon && "\"Привет, мир\"" in procyon, procyon)

        val bytecode = ClassFiles.render(set, ClassView.BYTECODE)
        assertTrue("INVOKESPECIAL java/lang/Object.<init> ()V" in bytecode, bytecode)
        assertTrue("GREETING = \"Привет, мир\"" in bytecode, bytecode)
        assertFalse("Sample\$Holder.describe" in bytecode, "в байт-коде только сам класс")

        // Вложенный класс отдельно: внешний и соседи — контекст (switch по enum восстанавливается)
        val inner = load(pkg().resolve("Sample\$Holder.class"))
        assertEquals("com/example/Sample\$Holder", inner.mainName)
        assertTrue(inner.isOwn("com/example/Sample\$Holder") && !inner.isOwn("com/example/Sample"))
        val innerJava = ClassFiles.render(inner, ClassView.VINEFLOWER)
        assertTrue("case FAST ->" in innerJava, innerJava)
        assertFalse("class Sample<" in innerJava, "внешний класс не декомпилируется")
        assertEquals("перечисление", load(pkg().resolve("Sample\$Mode.class")).info.kind)
    }

    @Test
    fun `class inside a jar`() {
        val jar = dir.resolve("app.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            Files.list(pkg()).use { s -> s.toList() }.forEach { p ->
                zip.putNextEntry(ZipEntry("com/example/${p.name}"))
                zip.write(Files.readAllBytes(p))
                zip.closeEntry()
            }
        }
        FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), emptyMap<String, Any>()).use { fs ->
            val set = load(fs.getPath("/com/example/Sample.class"))
            assertEquals(4, set.classes.size)
            assertTrue("static class Holder" in ClassFiles.render(set, ClassView.VINEFLOWER))
            assertTrue("class Sample" in ClassFiles.render(set, ClassView.CFR))
        }
    }

    @Test
    fun `class name cannot escape the temp folder`() {
        val tmp = Files.createTempDirectory("wex_target")
        try {
            assertNull(ClassFiles.safeTarget(tmp, "../../evil"))
            assertNull(ClassFiles.safeTarget(tmp, "a/../../evil"))
            assertNull(ClassFiles.safeTarget(tmp, "/etc/passwd"))
            assertNull(ClassFiles.safeTarget(tmp, "a//b"))
            assertEquals(tmp.resolve("a/b/C.class"), ClassFiles.safeTarget(tmp, "a/b/C"))
        } finally {
            tmp.toFile().deleteRecursively()
        }

        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "../../wex_evil", null, "java/lang/Object", null)
        writer.visitEnd()
        val evil = dir.resolve("work/evil.class")
        Files.createDirectories(evil.parent)
        Files.write(evil, writer.toByteArray())
        val set = load(evil)
        val vf = ClassFiles.render(set, ClassView.VINEFLOWER)
        assertTrue(vf.startsWith("// Vineflower: не удалось") && "Недопустимое имя класса" in vf, vf)
        assertTrue("../../wex_evil" in ClassFiles.render(set, ClassView.BYTECODE))
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        assertFalse(Files.exists(tempRoot.resolve("wex_evil.class")))
        assertFalse(Files.exists(tempRoot.parent?.resolve("wex_evil.class") ?: tempRoot))
    }

    @Test
    fun `unicode escapes are shown as text`() {
        val bs = "\\"
        fun u(hex: String) = bs + "u" + hex
        assertEquals("\"При\"", ClassFiles.unescapeUnicode("\"" + u("041f") + u("0440") + u("0438") + "\""))
        assertEquals("😀", ClassFiles.unescapeUnicode(u("D83D") + u("DE00")))
        // Экранированный обратный слеш, управляющие, невидимые и одиночные суррогаты — как были
        for (kept in listOf(bs + u("0041"), u("0000"), u("00a0"), u("2028"), u("D83D") + "x", u("12G4"), u("200B"), "tail" + bs + "u04")) {
            assertEquals(kept, ClassFiles.unescapeUnicode(kept))
        }
        assertEquals("a" + bs + "n" + "Я", ClassFiles.unescapeUnicode("a" + bs + "n" + u("042F")))
    }

    private fun lex(line: String): List<Pair<String, Tok>> {
        val out = ArrayList<Token>()
        JvmBytecodeLexer.tokenize(line, 0, out)
        out.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "перекрытие в «$line»: $out") }
        return out.map { line.substring(it.start, it.end) to it.type }
    }

    @Test
    fun `bytecode highlighting`() {
        assertTrue("INVOKEVIRTUAL" in JvmBytecodeLexer.opcodes && "ALOAD" in JvmBytecodeLexer.opcodes)
        assertEquals(
            listOf(
                "INVOKESPECIAL" to Tok.OPCODE, "java/lang/Object" to Tok.TYPE, "." to Tok.PUNCTUATION,
                "<init>" to Tok.FUNCTION, "(" to Tok.BRACKET, ")" to Tok.BRACKET, "V" to Tok.TYPE
            ),
            lex("    INVOKESPECIAL java/lang/Object.<init> ()V")
        )
        assertEquals(
            listOf(
                "PUTFIELD" to Tok.OPCODE, "com/example/Sample" to Tok.TYPE, "." to Tok.PUNCTUATION,
                "counter" to Tok.PROPERTY, ":" to Tok.OPERATOR, "I" to Tok.TYPE
            ),
            lex("    PUTFIELD com/example/Sample.counter : I")
        )
        assertEquals(listOf("L0" to Tok.LABEL), lex("   L0"))
        assertEquals(listOf("LINENUMBER" to Tok.DIRECTIVE, "11" to Tok.NUMBER, "L0" to Tok.LABEL), lex("    LINENUMBER 11 L0"))
        assertEquals(
            listOf(
                "LOCALVARIABLE" to Tok.DIRECTIVE, "this" to Tok.VARIABLE, "Lcom/example/Sample;" to Tok.TYPE,
                "L0" to Tok.LABEL, "L3" to Tok.LABEL, "0" to Tok.NUMBER
            ),
            lex("    LOCALVARIABLE this Lcom/example/Sample; L0 L3 0")
        )
        assertEquals(
            listOf(
                "private" to Tok.MODIFIER, "final" to Tok.MODIFIER, "static" to Tok.MODIFIER,
                "Ljava/lang/String;" to Tok.TYPE, "GREETING" to Tok.PROPERTY, "=" to Tok.OPERATOR, "\"Привет\"" to Tok.STRING
            ),
            lex("  private final static Ljava/lang/String; GREETING = \"Привет\"")
        )
        assertEquals(listOf("protected" to Tok.MODIFIER, "I" to Tok.TYPE, "counter" to Tok.PROPERTY), lex("  protected I counter"))
        assertEquals(
            listOf("final" to Tok.MODIFIER, "static" to Tok.MODIFIER, "C" to Tok.TYPE, "C" to Tok.PROPERTY, "=" to Tok.OPERATOR, "1103" to Tok.NUMBER),
            lex("  final static C C = 1103")
        )
        assertEquals(
            listOf(
                "public" to Tok.MODIFIER, "class" to Tok.KEYWORD, "com/example/Sample" to Tok.TYPE,
                "implements" to Tok.KEYWORD, "java/lang/Runnable" to Tok.TYPE, "java/io/Serializable" to Tok.TYPE, "{" to Tok.BRACKET
            ),
            lex("public class com/example/Sample implements java/lang/Runnable java/io/Serializable {")
        )
        assertEquals(listOf("class" to Tok.KEYWORD, "Main" to Tok.TYPE, "extends" to Tok.KEYWORD, "Base" to Tok.TYPE), lex("class Main extends Base"))
        assertEquals(
            listOf(
                "public" to Tok.MODIFIER, "static" to Tok.MODIFIER, "sum" to Tok.FUNCTION, "(" to Tok.BRACKET,
                "[I" to Tok.TYPE, ")" to Tok.BRACKET, "J" to Tok.TYPE
            ),
            lex("  public static sum([I)J")
        )
        assertEquals(
            listOf(
                "static" to Tok.MODIFIER, "sw" to Tok.FUNCTION, "(" to Tok.BRACKET, "I" to Tok.TYPE, "I" to Tok.TYPE,
                "Ljava/lang/String;" to Tok.TYPE, ")" to Tok.BRACKET, "I" to Tok.TYPE, "throws" to Tok.KEYWORD,
                "java/io/IOException" to Tok.TYPE
            ),
            lex("  static sw(IILjava/lang/String;)I throws java/io/IOException")
        )
        assertEquals("(itf)" to Tok.MODIFIER, lex("    INVOKEINTERFACE java/lang/Runnable.run ()V (itf)").last())
        assertEquals(
            listOf("LDC" to Tok.OPCODE, "Ljava/lang/String;" to Tok.TYPE, "." to Tok.PUNCTUATION, "class" to Tok.KEYWORD),
            lex("    LDC Ljava/lang/String;.class")
        )
        assertEquals(listOf("NEWARRAY" to Tok.OPCODE, "T_BYTE" to Tok.CONSTANT), lex("    NEWARRAY T_BYTE"))
        assertEquals(listOf("MULTIANEWARRAY" to Tok.OPCODE, "[[I" to Tok.TYPE, "2" to Tok.NUMBER), lex("    MULTIANEWARRAY [[I 2"))
        assertEquals(
            listOf(
                "INVOKEDYNAMIC" to Tok.OPCODE, "apply" to Tok.FUNCTION, "(" to Tok.BRACKET, ")" to Tok.BRACKET,
                "Ljava/util/function/Function;" to Tok.TYPE, "[" to Tok.BRACKET
            ),
            lex("    INVOKEDYNAMIC apply()Ljava/util/function/Function; [")
        )
        assertEquals(
            listOf(
                "@Ljava/lang/annotation/Retention;" to Tok.ANNOTATION, "(" to Tok.BRACKET, "value" to Tok.PROPERTY,
                "=" to Tok.OPERATOR, "Ljava/lang/annotation/RetentionPolicy;" to Tok.TYPE, "." to Tok.PUNCTUATION,
                "RUNTIME" to Tok.CONSTANT, ")" to Tok.BRACKET
            ),
            lex("  @Ljava/lang/annotation/Retention;(value=Ljava/lang/annotation/RetentionPolicy;.RUNTIME)")
        )
        assertEquals(listOf("default" to Tok.KEYWORD, ":" to Tok.OPERATOR, "L15" to Tok.LABEL), lex("      default: L15"))
        assertEquals(listOf("NEW" to Tok.OPCODE, "com/example/Sample\$1" to Tok.TYPE), lex("    NEW com/example/Sample\$1"))
        assertEquals(listOf("CHECKCAST" to Tok.OPCODE, "Foo" to Tok.TYPE), lex("    CHECKCAST Foo"))
        assertEquals(listOf("// access flags 0x1" to Tok.COMMENT), lex("  // access flags 0x1"))
        for (n in listOf("1234567890123L", "NaND", "-1.5F", "-InfinityF")) {
            assertEquals(listOf("LDC" to Tok.OPCODE, n to Tok.NUMBER), lex("    LDC $n"))
        }
        assertEquals(
            listOf(
                "static" to Tok.MODIFIER, "INNERCLASS" to Tok.DIRECTIVE, "com/example/Sample\$Holder" to Tok.TYPE,
                "com/example/Sample" to Tok.TYPE, "Holder" to Tok.TYPE
            ),
            lex("  static INNERCLASS com/example/Sample\$Holder com/example/Sample Holder")
        )
        assertEquals(listOf("RECORDCOMPONENT" to Tok.DIRECTIVE, "I" to Tok.TYPE, "x" to Tok.PROPERTY), lex("  RECORDCOMPONENT   I x"))
        assertEquals(
            listOf(
                "INVOKEVIRTUAL" to Tok.OPCODE, "[Lcom/example/Sample\$Mode;" to Tok.TYPE, "." to Tok.PUNCTUATION,
                "clone" to Tok.FUNCTION, "(" to Tok.BRACKET, ")" to Tok.BRACKET, "Ljava/lang/Object;" to Tok.TYPE
            ),
            lex("    INVOKEVIRTUAL [Lcom/example/Sample\$Mode;.clone ()Ljava/lang/Object;")
        )
        assertEquals(
            listOf(
                "java/lang/invoke/LambdaMetafactory" to Tok.TYPE, "." to Tok.PUNCTUATION, "metafactory" to Tok.FUNCTION,
                "(" to Tok.BRACKET, "Ljava/lang/invoke/MethodHandles\$Lookup;" to Tok.TYPE, ")" to Tok.BRACKET,
                "Ljava/lang/invoke/CallSite;" to Tok.TYPE, "," to Tok.PUNCTUATION
            ),
            lex("      java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles\$Lookup;)Ljava/lang/invoke/CallSite;, ")
        )
        assertEquals(listOf("}" to Tok.BRACKET), lex("}"))
    }

    @Test
    fun `bytecode outline and folding`() {
        val text = ClassFiles.render(load(pkg().resolve("Sample.class")), ClassView.BYTECODE)
        val model = HighlightModel(Languages.JVM_BYTECODE)
        model.reset(text)
        val snap = model.snapshot
        val folds = FoldingEngine.compute(snap)
        val outline = OutlineEngine.compute(snap, folds)
        val names = outline.map { it.name to it.kind }
        for (expected in listOf(
            "Sample" to ItemKind.TYPE, "<init>" to ItemKind.FUNCTION, "run" to ItemKind.FUNCTION,
            "sum" to ItemKind.FUNCTION, "GREETING" to ItemKind.PROPERTY, "items" to ItemKind.PROPERTY,
            "counter" to ItemKind.PROPERTY
        )) {
            assertTrue(expected in names, "$expected нет в $names")
        }
        assertEquals("(int[]): long  · public static", outline.first { it.name == "sum" }.detail)
        assertFalse(names.any { it.first.startsWith("@") || it.first == "INNERCLASS" }, names.toString())
        // Тело каждого метода сворачивается целиком
        val run = outline.first { it.name == "run" }
        val fold = assertNotNull(folds.firstOrNull { it.startLine == run.line }, "метод run не сворачивается")
        assertTrue(snap.text.substring(fold.start, fold.end).contains("MAXLOCALS"))
        assertTrue(folds.count { it.startLine > 0 } >= 4, folds.toString())
    }
}
