package com.tim.testutil

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

/** Наборы реальных .class для корпусных тестов: фикстуры, kotlin-stdlib, собственные классы, JDK. */
object ClassCorpus {

    private val classpath: List<File> by lazy {
        System.getProperty("java.class.path").split(File.pathSeparator).map { File(it) }
    }

    /** Внутреннее имя → байты. */
    fun fixtures(): Map<String, ByteArray> =
        Fixtures.classFiles.associate { it.readBytes().let { b -> internalName(b) to b } }

    fun kotlinStdlib(): Map<String, ByteArray> {
        val jar = classpath.firstOrNull { it.name.startsWith("kotlin-stdlib") && it.name.endsWith(".jar") }
            ?: throw IllegalStateException("kotlin-stdlib не найден в classpath тестов")
        return jarClasses(jar)
    }

    /** Классы основного кода проекта (скомпилированы kotlinc). */
    fun projectMain(): Map<String, ByteArray> {
        val dir = classpath.firstOrNull { it.isDirectory && it.path.replace('\\', '/').endsWith("classes/kotlin/main") }
            ?: throw IllegalStateException("каталог classes/kotlin/main не найден в classpath тестов")
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
            .associate { f -> f.readBytes().let { internalName(it) to it } }
    }

    fun kotlinStdlibJar(): File = classpath.first { it.name.startsWith("kotlin-stdlib") && it.name.endsWith(".jar") }

    fun jarClasses(jar: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                out[e.name.removeSuffix(".class")] = z.getInputStream(e).use { it.readBytes() }
            }
        }
        return out
    }

    /** Классы модуля JDK (например, java.base) из jrt:/ — только для проверок без загрузки в JVM. */
    fun jdkModule(module: String): Map<String, ByteArray> {
        val fs = FileSystems.getFileSystem(URI.create("jrt:/"))
        val root = fs.getPath("/modules", module)
        val out = LinkedHashMap<String, ByteArray>()
        Files.walk(root).use { s ->
            s.filter { it.toString().endsWith(".class") && !it.fileName.toString().startsWith("module-info") }
                .forEach { p -> out[root.relativize(p).toString().removeSuffix(".class")] = Files.readAllBytes(p) }
        }
        return out
    }

    /** Внутреннее имя класса по его байтам (this_class). */
    fun internalName(bytes: ByteArray): String = com.tim.clazz.parser.ClassReader().read(bytes, "?").thisClassName
}
