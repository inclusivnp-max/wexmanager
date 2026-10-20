package com.tim.csmali.io

import com.tim.csmali.model.SmaliClass
import com.tim.csmali.model.SmaliTree
import com.tim.csmali.util.Parallel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Файлы csmali: один класс — один `.smali`. Каталог: `<пакет>/<Имя>.smali` по внутреннему имени.
 * Имена, совпадающие без учёта регистра (a/A на Android и Windows), получают суффикс `.2`, `.3`…;
 * недопустимые в именах файлов символы и слишком длинные имена заменяются — имя класса всё равно
 * берётся из директивы `.class`. Запись и чтение каталога идут параллельно.
 */
object SmaliFiles {

    fun write(tree: SmaliTree, outputFile: File) {
        require(tree.classes.size == 1) { "csmali: один .smali = один класс (в дереве ${tree.classes.size})" }
        writeClass(tree.classes[0], outputFile)
    }

    fun writeClass(cls: SmaliClass, outputFile: File) {
        val text = SmaliWriter.write(cls)
        outputFile.absoluteFile.parentFile?.mkdirs()
        try {
            outputFile.writeBytes(text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Не удалось записать класс ${cls.descriptor} в $outputFile: ${e.message}", e)
        }
    }

    fun read(inputFile: File): SmaliTree = SmaliTree().apply { addClass(readClass(inputFile)) }

    fun readClass(inputFile: File): SmaliClass {
        val text = readUtf8(inputFile)
        return try {
            SmaliParser.parseClass(text) ?: throw IllegalStateException("не найдена директива .class")
        } catch (e: Exception) {
            throw IllegalStateException("Ошибка в файле $inputFile: ${e.message}", e)
        }
    }

    /** Все классы дерева — в каталог [dir]; возвращает число записанных файлов. */
    fun writeDir(tree: SmaliTree, dir: File): Int {
        val names = FileNames()
        val files = tree.classes.map { File(dir, names.fileFor(it.descriptor)) }
        Parallel.forEach(tree.classes.size) { i -> writeClass(tree.classes[i], files[i]) }
        return files.size
    }

    /** Все `.smali` из каталога (рекурсивно), в порядке путей. */
    fun readDir(dir: File): SmaliTree {
        require(dir.isDirectory) { "Каталог не найден: $dir" }
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".smali") }.sortedBy { it.path }.toList()
        val classes = arrayOfNulls<SmaliClass>(files.size)
        Parallel.forEach(files.size) { i -> classes[i] = readClass(files[i]) }
        val tree = SmaliTree()
        val seen = HashMap<String, File>()
        for ((i, c) in classes.withIndex()) {
            val prev = seen.put(c!!.descriptor, files[i])
            if (prev != null) throw IllegalStateException("Класс ${c.descriptor} объявлен дважды: $prev и ${files[i]}")
            tree.classes.add(c)
        }
        return tree
    }

    /**
     * Текст файла в UTF-8. Быстрый путь — интринсик `String(bytes, UTF_8)`; если в результате есть
     * символ замены U+FFFD, файл перепроверяется строгим декодером (некорректный UTF-8 — ошибка).
     */
    private fun readUtf8(file: File): String {
        val bytes = file.readBytes()
        var text = String(bytes, Charsets.UTF_8)
        if (text.indexOf('\uFFFD') >= 0) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            text = try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: Exception) {
                throw IOException("Файл $file: некорректный UTF-8", e)
            }
        }
        return if (text.startsWith('\uFEFF')) text.substring(1) else text
    }

    /** Отображение дескрипторов в пути файлов с защитой от коллизий без учёта регистра. */
    private class FileNames {
        private val used = HashSet<String>()

        fun fileFor(descriptor: String): String {
            require(descriptor.startsWith("L") && descriptor.endsWith(";")) { "Некорректный дескриптор класса $descriptor" }
            val parts = descriptor.substring(1, descriptor.length - 1).split('/').map { safe(it) }
            val base = parts.joinToString("/")
            var candidate = "$base.smali"
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = "$base.${n++}.smali"
            return candidate
        }

        /** Сегмент пути: без символов, запрещённых в распространённых ФС, и не длиннее 200 байт UTF-8. */
        private fun safe(part: String): String {
            val sb = StringBuilder(part.length)
            for (c in part) {
                if (c < ' ' || c in "\\:*?\"<>|") sb.append('_').append(Integer.toHexString(c.code)) else sb.append(c)
            }
            var s = sb.toString()
            if (s.isEmpty() || s == "." || s == "..") s = "_$s"
            if (s.toByteArray(Charsets.UTF_8).size > 200) {
                val hash = MessageDigest.getInstance("SHA-1").digest(part.toByteArray(Charsets.UTF_8))
                    .take(8).joinToString("") { String.format("%02x", it) }
                s = s.take(60) + "_" + hash
            }
            return s
        }
    }
}
