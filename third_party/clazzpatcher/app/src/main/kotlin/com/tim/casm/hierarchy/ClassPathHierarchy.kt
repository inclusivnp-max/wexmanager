package com.tim.casm.hierarchy

import java.io.File
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Иерархия по classpath: каталоги и jar/zip с `.class`, плюс (по желанию) классы JDK из `jrt:/`.
 * Читается только заголовок класса (пул констант, флаги, this/super), результат кэшируется.
 * Классы не загружаются в JVM — только байты. Потокобезопасна, чтение без блокировок.
 */
class ClassPathHierarchy(
    entries: List<File>,
    includeJdk: Boolean = true
) : ClassHierarchy, AutoCloseable {

    private class Header(val superName: String?, val isInterface: Boolean, val interfaces: List<String>)

    private val dirs = entries.filter { it.isDirectory }
    private val zips = entries.filter { it.isFile }.map { ZipFile(it) }
    private val jrt: FileSystem? = if (includeJdk) openJrt() else null
    /** Пакет (`java.lang`) → каталоги модулей, в которых он есть (`/modules/java.base`). */
    private val jrtPackages = ConcurrentHashMap<String, List<Path>>()

    /** Кэш без блокировок на чтение: потоки конвертеров обращаются к иерархии постоянно. */
    private val cache = ConcurrentHashMap<String, Any>()

    override fun superClass(internalName: String): String? = header(internalName)?.superName

    override fun isInterface(internalName: String): Boolean? = header(internalName)?.isInterface

    override fun interfaces(internalName: String): List<String>? = header(internalName)?.interfaces

    private fun header(name: String): Header? {
        val cached = cache[name] ?: run {
            // Гонка двух потоков за одним именем безвредна: оба прочитают один и тот же заголовок.
            val h: Any = find(name)?.let { parse(it, name) } ?: MISSING
            cache.putIfAbsent(name, h) ?: h
        }
        return cached as? Header
    }

    private fun find(name: String): ByteArray? {
        val rel = "$name.class"
        for (d in dirs) {
            val f = File(d, rel)
            if (f.isFile) return f.readBytes()
        }
        for (z in zips) {
            val e = z.getEntry(rel) ?: continue
            return z.getInputStream(e).use { it.readBytes() }
        }
        if (jrt != null) {
            for (m in jrtModules(jrt, name)) {
                val p = m.resolve(rel)
                if (Files.isRegularFile(p)) return Files.readAllBytes(p)
            }
        }
        return null
    }

    /** В jrt класс лежит в /modules/<модуль>/<пакет>/<Имя>.class, а /packages/<пакет>/ перечисляет модули. */
    private fun jrtModules(fs: FileSystem, name: String): List<Path> {
        val slash = name.lastIndexOf('/')
        if (slash < 0) return emptyList()
        val pkg = name.substring(0, slash).replace('/', '.')
        return jrtPackages.getOrPut(pkg) {
            val dir = fs.getPath("/packages", pkg)
            if (!Files.isDirectory(dir)) emptyList()
            else Files.newDirectoryStream(dir).use { s -> s.map { fs.getPath("/modules", it.fileName.toString()) } }
        }
    }

    override fun close() {
        for (z in zips) z.close()
    }

    companion object {
        /** Отметка «класса нет» в кэше (ConcurrentHashMap не хранит null). */
        private val MISSING = Any()

        /** Только классы JDK (если среда выполнения предоставляет `jrt:/`). */
        fun jdk(): ClassPathHierarchy = ClassPathHierarchy(emptyList(), includeJdk = true)

        private fun openJrt(): FileSystem? = try {
            FileSystems.getFileSystem(URI.create("jrt:/"))
        } catch (e: Exception) {
            null // Java 8 и Android: jrt недоступен — иерархия JDK неизвестна
        }

        /** Разбор заголовка класса (JVMS §4.1) без чтения полей, методов и атрибутов. */
        private fun parse(b: ByteArray, name: String): Header {
            fun u1(p: Int) = b[p].toInt() and 0xFF
            fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)
            require(b.size >= 10 && u2(0) == 0xCAFE && u2(2) == 0xBABE) { "Класс $name: неверная сигнатура файла" }
            val count = u2(8)
            val utf8Start = IntArray(count)
            val classNameIdx = IntArray(count)
            var p = 10
            var i = 1
            while (i < count) {
                val tag = u1(p)
                when (tag) {
                    1 -> { utf8Start[i] = p; p += 3 + u2(p + 1) }
                    7 -> { classNameIdx[i] = u2(p + 1); p += 3 }
                    8, 16, 19, 20 -> p += 3
                    15 -> p += 4
                    3, 4, 9, 10, 11, 12, 17, 18 -> p += 5
                    5, 6 -> { p += 9; i++ }
                    else -> throw IllegalArgumentException("Класс $name: неизвестный тег пула констант $tag")
                }
                i++
            }
            val access = u2(p)
            val superIdx = u2(p + 4)
            fun utf8(idx: Int): String {
                val s = utf8Start[idx]
                return String(b, s + 3, u2(s + 1), Charsets.UTF_8)
            }
            val superName = if (superIdx == 0) null else utf8(classNameIdx[superIdx])
            val interfaces = List(u2(p + 6)) { k -> utf8(classNameIdx[u2(p + 8 + 2 * k)]) }
            return Header(superName, access and 0x0200 != 0, interfaces)
        }
    }
}
