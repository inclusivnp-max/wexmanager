package com.tim.dsmali.io

import com.tim.dsmali.disasm.Parallel
import com.tim.dsmali.model.SmaliTree
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32

/**
 * Сохранение/загрузка smali-файлов (один класс — один файл, как у baksmali/apktool).
 *
 * Имена файлов не обязаны совпадать с именем класса: при чтении класс берётся из
 * директивы `.class`. Поэтому при записи имена подбираются так, чтобы файлы не затирали
 * друг друга на регистронезависимых ФС (Android `/storage/emulated/0`, Windows, macOS):
 * `LA;` и `La;` → `A.smali` и `a.1.smali` (суффиксы — по порядку дескрипторов); слишком длинные компоненты пути (> 255 байт)
 * укорачиваются с хэшем.
 */
object SmaliFiles {
    private const val EXTENSION = ".smali"

    /** Максимум байт UTF-8 в компоненте пути (с запасом под суффикс `.NNNNN`). */
    private const val MAX_NAME_BYTES = 255 - 6

    fun write(tree: SmaliTree, dir: File) {
        val seen = HashSet<String>(tree.classes.size * 2)
        for (cls in tree.classes) {
            if (!seen.add(cls.descriptor)) throw IllegalArgumentException("Класс ${cls.descriptor} встречается в дереве дважды")
        }
        val planner = NamePlanner(isWindows())
        // Имена подбираются в порядке дескрипторов, а не классов DEX: после сборки порядок классов
        // может стать другим, а суффиксы у классов, различающихся только регистром, — остаться прежними.
        val names = arrayOfNulls<String>(tree.classes.size)
        for (i in tree.classes.indices.sortedBy { tree.classes[it].descriptor }) names[i] = planner.fileFor(tree.classes[i].descriptor)
        val files = names.map { it!! }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Не удалось создать каталог $dir")
        for (rel in planner.directories) {
            val d = File(dir, rel)
            if (!d.isDirectory && !d.mkdirs()) throw IOException("Не удалось создать каталог $d")
        }
        Parallel.forEach(files.size) { i ->
            val cls = tree.classes[i]
            val file = File(dir, files[i])
            try {
                val bytes = SmaliWriter.write(cls).toByteArray(Charsets.UTF_8)
                FileOutputStream(file).use { it.write(bytes) }
            } catch (e: Exception) {
                throw IOException("Не удалось записать класс ${cls.descriptor} в $file: ${e.message}", e)
            }
        }
    }

    fun read(dir: File): SmaliTree {
        if (!dir.isDirectory) throw IOException("Каталог со smali-файлами не найден: $dir")
        val root = dir.path.length + 1
        val files = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .map { it.path.substring(root).replace(File.separatorChar, '/') }
            .sorted()
            .toList()
        val classes = Parallel.map(files.size) { i ->
            val file = File(dir, files[i])
            try {
                SmaliParser.parseClass(readUtf8(file))
                    ?: throw IllegalStateException("не найдена директива .class")
            } catch (e: Exception) {
                throw IllegalStateException("Ошибка в файле $file: ${e.message}", e)
            }
        }
        val owners = HashMap<String, String>(classes.size * 2)
        classes.forEachIndexed { i, cls ->
            val other = owners.put(cls.descriptor, files[i])
            if (other != null) {
                throw IllegalStateException("Класс ${cls.descriptor} определён дважды: в $other и в ${files[i]}")
            }
        }
        val tree = SmaliTree()
        tree.classes.addAll(classes)
        return tree
    }

    private fun readUtf8(file: File): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
        // BOM оставляют некоторые редакторы (Windows)
        return if (text.startsWith('﻿')) text.substring(1) else text
    }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").startsWith("Windows")

    /**
     * Подбор уникальных относительных путей. Детерминирован (зависит только от порядка классов),
     * ФС не опрашивает: имена сравниваются без учёта регистра и Unicode-нормализации всегда.
     */
    internal class NamePlanner(private val windowsNames: Boolean) {
        private class Dir(val path: String) {
            val subdirs = HashMap<String, Dir>()
            val used = HashSet<String>()
        }

        private val root = Dir("")

        /** Созданные каталоги (относительные пути) в порядке появления. */
        val directories = ArrayList<String>()

        fun fileFor(descriptor: String): String {
            if (descriptor.length < 3 || descriptor[0] != 'L' || descriptor[descriptor.length - 1] != ';') {
                throw IllegalArgumentException("Некорректный дескриптор класса: $descriptor")
            }
            val parts = descriptor.substring(1, descriptor.length - 1).split('/')
            var dir = root
            for (k in 0 until parts.size - 1) {
                val part = checkPart(parts[k], descriptor)
                dir = dir.subdirs[part] ?: run {
                    val name = unique(dir, shorten(fixReserved(sanitize(part)), MAX_NAME_BYTES), "")
                    Dir(if (dir.path.isEmpty()) name else dir.path + "/" + name).also {
                        dir.subdirs[part] = it
                        directories.add(it.path)
                    }
                }
            }
            val simple = checkPart(parts[parts.size - 1], descriptor)
            val name = unique(dir, shorten(fixReserved(sanitize(simple)), MAX_NAME_BYTES - EXTENSION.length), EXTENSION)
            return if (dir.path.isEmpty()) name else dir.path + "/" + name
        }

        private fun checkPart(part: String, descriptor: String): String {
            if (part.isEmpty()) throw IllegalArgumentException("Некорректный дескриптор класса: $descriptor")
            return part
        }

        /**
         * Символы, которые ФС не сохранит как есть (непарные суррогаты, управляющие, запрещённые
         * в FAT/Windows), и имена `.`/`..` заменяются на `#XXXX`: иначе разные классы молча
         * получили бы одинаковый файл, а `..` вывел бы за пределы каталога.
         */
        private fun sanitize(part: String): String {
            if (part == "." || part == "..") return part.replace(".", "#002e")
            var sb: StringBuilder? = null
            var i = 0
            while (i < part.length) {
                val c = part[i]
                val pair = Character.isHighSurrogate(c) && i + 1 < part.length && Character.isLowSurrogate(part[i + 1])
                val bad = !pair && (Character.isSurrogate(c) || c < ' ' || c == '\u007f' || c in FORBIDDEN)
                if (bad && sb == null) sb = StringBuilder(part.length + 8).append(part, 0, i)
                if (bad) {
                    sb!!.append('#').append(String.format(Locale.ROOT, "%04x", c.code))
                } else {
                    sb?.append(c)
                    if (pair) sb?.append(part[i + 1])
                }
                i += if (pair) 2 else 1
            }
            return sb?.toString() ?: part
        }

        private fun unique(dir: Dir, base: String, ext: String): String {
            var name = base + ext
            var n = 0
            while (!dir.used.add(fold(name))) {
                n++
                name = "$base.$n$ext"
            }
            return name
        }

        private fun fixReserved(name: String): String =
            if (windowsNames && RESERVED.matcher(name).matches()) "$name#" else name

        private fun fold(name: String): String {
            val sb = StringBuilder(name.length)
            for (c in name) sb.append(Character.toLowerCase(Character.toUpperCase(c)))
            return Normalizer.normalize(sb, Normalizer.Form.NFC)
        }
    }

    /** Укорачивает имя до [maxBytes] байт UTF-8: начало имени + `#` + CRC32 полного имени. */
    internal fun shorten(name: String, maxBytes: Int): String {
        if (utf8Length(name, 0, name.length) <= maxBytes) return name
        val crc = CRC32()
        crc.update(name.toByteArray(Charsets.UTF_8))
        val hash = String.format(Locale.ROOT, "%08x", crc.value)
        val budget = maxBytes - 1 - hash.length
        var end = 0
        var bytes = 0
        while (end < name.length) {
            val cp = name.codePointAt(end)
            val len = utf8Length(cp)
            if (bytes + len > budget) break
            bytes += len
            end += Character.charCount(cp)
        }
        return name.substring(0, end) + "#" + hash
    }

    private fun utf8Length(s: String, from: Int, to: Int): Int {
        var n = 0
        var i = from
        while (i < to) {
            val cp = s.codePointAt(i)
            n += utf8Length(cp)
            i += Character.charCount(cp)
        }
        return n
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private const val FORBIDDEN = "\"*:<>?\\|"

    private val RESERVED = java.util.regex.Pattern.compile(
        "^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", java.util.regex.Pattern.CASE_INSENSITIVE
    )
}
