package com.tim.cli

import com.tim.Main
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Smoke-тесты командной строки: Main.run вызывается напрямую, без System.exit. */
class MainCliTest {
    @TempDir
    lateinit var tmp: File

    private class Result(val code: Int, val out: String, val err: String) {
        override fun toString() = "код $code\n--- stdout:\n$out\n--- stderr:\n$err"
    }

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Main.run(arrayOf(*args), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun assertOk(r: Result) = assertEquals(0, r.code, r.toString())

    private fun assertFails(r: Result, message: String) {
        assertEquals(1, r.code, r.toString())
        assertTrue(r.err.contains(message), "ожидалось сообщение '$message'\n$r")
        assertFalse(r.err.contains("\tat "), "без --stacktrace стек не печатается\n$r")
    }

    private fun fixture(name: String, fileName: String = "classes.dex"): File =
        Fixtures.dexFile(name).copyTo(File(tmp, "in/$fileName"), overwrite = true)

    private fun apk(vararg entries: Pair<String, File>): File {
        val file = File(tmp, "app.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0, 1, 2))
            for ((name, f) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(f.readBytes())
            }
        }
        return file
    }

    @Test
    fun `--help и --version`() {
        val help = run("--help")
        assertOk(help)
        assertTrue(help.out.contains("Использование: clazzpatcher"), help.toString())
        listOf("info", "disasm", "asm", "rt", "class-rt", "bench").forEach { assertTrue(help.out.contains(it), "в справке нет $it") }
        val version = run("--version")
        assertOk(version)
        assertEquals("clazzpatcher ${Main.VERSION}", version.out.trim())
    }

    @Test
    fun `без аргументов печатается справка и код 1`() {
        val r = run()
        assertEquals(1, r.code, r.toString())
        assertTrue(r.out.contains("Использование"), r.toString())
    }

    @Test
    fun `ошибки аргументов`() {
        assertFails(run("frobnicate"), "неизвестная команда 'frobnicate'")
        assertFails(run("d"), "не указан входной файл")
        assertFails(run("d", "a.dex", "-x"), "неизвестный параметр '-x'")
        assertFails(run("rt", "a.dex", "-o"), "требует значения")
        assertFails(run("info", "a.dex", "b.dex"), "лишние аргументы")
        assertFails(run("bench", fixture("gen/hello").path, "-n", "0"), "не меньше 1")
        assertFails(run("bench", fixture("gen/hello").path, "-n", "abc"), "целым числом")
    }

    @Test
    fun `info печатает версию, счётчики и секции`() {
        val r = run("info", fixture("user").path)
        assertOk(r)
        listOf("Версия DEX:\\s+035", "Классов:\\s+326", "Методов:\\s+2055", "Контрольная сумма:\\s+верна", "code_item\\s+1615", "map_list")
            .forEach { assertTrue(Regex(it).containsMatchIn(r.out), "нет '$it'\n$r") }
    }

    @Test
    fun `info для APK перечисляет все classes·dex`() {
        val r = run("info", apk("classes2.dex" to fixture("sw"), "classes.dex" to fixture("gen/hello", "hello.dex")).path)
        assertOk(r)
        val first = r.out.indexOf("== classes.dex")
        val second = r.out.indexOf("== classes2.dex")
        assertTrue(first >= 0 && second > first, r.toString())
    }

    @Test
    fun `ошибка печатается цепочкой сообщений, стек только с --stacktrace`() {
        val missing = File(tmp, "нет/такого.dex").path
        assertFails(run("info", missing), "файл не найден")
        val withStack = run("info", missing, "--stacktrace")
        assertEquals(1, withStack.code)
        assertTrue(withStack.err.contains("\tat com.tim.Main"), withStack.toString())

        val notDex = File(tmp, "text.dex").apply { writeText("просто текст") }
        assertFails(run("info", notDex.path), "не DEX")
        assertFails(run("rt", notDex.path, "-o", File(tmp, "o.dex").path), "не DEX-файл")
    }

    @Test
    fun `rt записывает DEX`() {
        val out = File(tmp, "out/rt.dex")
        val r = run("rt", fixture("gen/hello").path, "-o", out.path)
        assertOk(r)
        assertTrue(out.isFile && out.length() > 0, r.toString())
        assertTrue(r.out.contains("байт"), r.toString())
    }

    @Test
    fun `class-rt записывает class`() {
        val out = File(tmp, "Main_rt.class")
        val r = run("class-rt", Fixtures.classFiles.first { it.name == "Main.class" }.path, "-o", out.path)
        assertOk(r)
        assertTrue(out.isFile && out.length() > 0, r.toString())
    }

    @Test
    fun `d и a — DEX в smali и обратно`() {
        val smali = File(tmp, "smali")
        val d = run("d", fixture("gen/hello").path, "-o", smali.path)
        assertOk(d)
        assertTrue(File(smali, "fx/hello/Hello.smali").isFile, d.toString() + "\nфайлы: " + smali.walkTopDown().toList())
        val out = File(tmp, "hello.dex")
        val a = run("a", smali.path, "-o", out.path)
        assertOk(a)
        assertTrue(DexSemantics.of(out.readBytes()).classIndex.containsKey("Lfx/hello/Hello;"), a.toString())
    }

    @Test
    fun `d не пишет в непустой каталог без -f`() {
        val dir = File(tmp, "busy").apply { mkdirs() }
        val stale = File(dir, "stale.smali").apply { writeText("old") }
        assertFails(run("d", fixture("gen/hello").path, "-o", dir.path), "не пуст")
        assertTrue(stale.exists())
        assertOk(run("d", fixture("gen/hello").path, "-o", dir.path, "-f"))
        assertFalse(stale.exists(), "-f должен очистить каталог")
        assertTrue(File(dir, "fx/hello/Hello.smali").isFile)
    }

    @Test
    fun `d -f отказывается очищать каталог со входным файлом`() {
        val input = fixture("gen/hello")
        assertFails(run("d", input.path, "-o", input.parentFile.path, "-f"), "отказ очищать")
        assertTrue(input.isFile, "входной файл удалён")
    }

    @Test
    fun `d для APK раскладывает classes·dex по smali и smali_classesN`() {
        val out = File(tmp, "app")
        val r = run("d", apk("classes.dex" to fixture("gen/hello", "hello.dex"), "classes2.dex" to fixture("sw")).path, "-o", out.path)
        assertOk(r)
        assertTrue(File(out, "smali/fx/hello/Hello.smali").isFile, r.toString())
        assertTrue(File(out, "smali_classes2/Sw.smali").isFile, r.toString())
    }

    @Test
    fun `a не собирает разборку multidex одним DEX`() {
        val dir = File(tmp, "app")
        File(dir, "smali").mkdirs()
        File(dir, "smali_classes2").mkdirs()
        assertFails(run("a", dir.path, "-o", File(tmp, "x.dex").path), "smali_classes2")
        assertFails(run("a", File(tmp, "нет").path), "каталог не найден")
    }

    @Test
    fun `bench печатает таблицу фаз и убирает за собой`() {
        val work = File(tmp, "bench").apply { mkdirs() }
        val r = run("bench", fixture("gen/hello").path, "-n", "2", "-w", "1", "-o", work.path)
        assertOk(r)
        listOf("чтение DEX", "запись DEX", "дизассемблирование", "сохранение smali", "загрузка smali", "ассемблирование", "итого", "замер 2")
            .forEach { assertTrue(r.out.contains(it), "нет '$it'\n$r") }
        assertEquals(emptyList<String>(), work.list()!!.toList(), "временные файлы не удалены")
    }

    @Test
    fun `cd и ca - class в csmali и обратно, каталог и jar`() {
        val classes = File(tmp, "classes")
        for (f in Fixtures.classFiles) f.copyTo(File(classes, f.name))
        val smali = File(tmp, "smali")
        assertOk(run("cd", classes.path, "-o", smali.path))
        val files = smali.walkTopDown().filter { it.name.endsWith(".smali") }.toList()
        assertEquals(Fixtures.classFiles.size, files.size)
        assertTrue(files.all { it.readText().startsWith(".class ") })

        val jar = File(tmp, "out.jar")
        assertOk(run("ca", smali.path, "-o", jar.path))
        java.util.zip.ZipFile(jar).use { z -> assertEquals(Fixtures.classFiles.size, z.size()) }
        val dir = File(tmp, "out")
        assertOk(run("ca", smali.path, "-o", dir.path))
        assertEquals(Fixtures.classFiles.size, dir.walkTopDown().count { it.name.endsWith(".class") })

        // Повторный cd в непустой каталог без -f — ошибка
        assertFails(run("cd", classes.path, "-o", smali.path), "не пуст")
    }

    @Test
    fun `d2c берёт из APK те classes·dex, что загрузил бы ART`() {
        // classes1.dex (дубль classes.dex) и classes4.dex (после пропуска) ART не загружает.
        val full = apk(
            "classes.dex" to fixture("gen/hello", "hello.dex"), "classes2.dex" to fixture("sw"),
            "classes1.dex" to fixture("gen/hello", "hello1.dex"), "classes4.dex" to fixture("gen/switches", "switches.dex")
        ).copyTo(File(tmp, "full.apk"))
        val plain = apk("classes.dex" to fixture("gen/hello", "hello.dex"), "classes2.dex" to fixture("sw"))
        val r = run("d2c", full.path, "-o", File(tmp, "full.jar").path)
        assertOk(r)
        assertTrue(r.err.contains("classes1.dex") && r.err.contains("classes4.dex"), r.toString())
        assertOk(run("d2c", plain.path, "-o", File(tmp, "plain.jar").path))
        fun names(jar: String) = java.util.zip.ZipFile(File(tmp, jar)).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        assertEquals(names("plain.jar"), names("full.jar"))
    }

    @Test
    fun `имя класса не выводит запись за пределы каталога`() {
        val out = File(tmp, "out")
        for (name in listOf("../evil", "a//b", "/abs", "a/./b")) {
            assertThrows<IllegalArgumentException>(name) { com.tim.ClassIo.write(mapOf(name to byteArrayOf(1)), out) }
        }
        assertFalse(File(tmp, "evil.class").exists())
    }

    /**
     * c2d в один `.dex`: строк больше 65 535 — один DEX с const-string/jumbo, как у d8, а не отказ
     * «не помещаются в один DEX» (деление на несколько DEX идёт и по строкам — только для каталога).
     */
    @Test
    fun c2dSingleDexWithJumboStrings() {
        val src = File(tmp, "src").apply { mkdirs() }
        val files = (0 until 3).map { k ->
            File(src, "S$k.java").apply {
                writeText(buildString {
                    append("public class S$k {\n")
                    for (j in 0 until 12) {
                        append("  static void m$j(java.util.List<String> l) {\n")
                        for (i in 0 until 2000) append("    l.add(\"s${k}_${j}_$i\");\n")
                        append("  }\n")
                    }
                    append("}\n")
                })
            }
        }
        val classes = File(tmp, "classes")
        com.tim.testutil.JvmVerifier.compileJava(files, classes, "--release", "8")
        val out = File(tmp, "one.dex")
        assertOk(run("c2d", classes.path, "-o", out.path))
        val bytes = out.readBytes()
        val dex = com.tim.dex.parser.DexReader().read(bytes)
        assertTrue(dex.strings.size > 0xFFFF, "строк ${dex.strings.size}")
        com.tim.c2d.C2dTestKit.assertValidDex(bytes, "jumbo", expectedClasses = 3)
    }
}
