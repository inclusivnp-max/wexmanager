package com.tim

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipFile

/** Ввод/вывод наборов .class для CLI: файлы, каталоги, jar/zip. */
internal object ClassIo {

    /** Внутреннее имя (по пути) → байты класса. Из jar пропускаются каталог META-INF (в т.ч. multi-release) и module-info. */
    fun read(inputs: List<File>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        fun put(name: String, bytes: ByteArray, from: String) {
            if (out.put(name, bytes) != null) throw IllegalArgumentException("класс $name встречается дважды (повтор в $from)")
        }
        for (f in inputs) {
            when {
                f.isDirectory -> f.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                    .sortedBy { it.path }
                    .forEach { put(it.relativeTo(f).path.replace(File.separatorChar, '/').removeSuffix(".class"), it.readBytes(), it.path) }
                f.isFile && f.name.endsWith(".class") -> put(f.name.removeSuffix(".class"), f.readBytes(), f.path)
                f.isFile -> ZipFile(f).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/") || e.name.endsWith("module-info.class")) continue
                        put(e.name.removeSuffix(".class"), z.getInputStream(e).use { it.readBytes() }, "${f.path}!${e.name}")
                    }
                }
                else -> throw IllegalArgumentException("не найден: ${f.path}")
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("во входных данных нет .class: ${inputs.joinToString { it.path }}")
        return out
    }

    /**
     * Запись классов: `*.jar`/`*.zip` — архив, иначе — каталог `<пакет>/<Имя>.class`. Имена берутся из
     * входных данных (DEX, csmali), поэтому путь, выходящий за пределы каталога или архива, — ошибка.
     */
    fun write(classes: Map<String, ByteArray>, output: File) {
        for (name in classes.keys) {
            val bad = name.split('/').any { it.isEmpty() || it == "." || it == ".." }
            if (bad) throw IllegalArgumentException("имя класса «$name» нельзя записать как путь внутри ${output.path}")
        }
        if (output.name.endsWith(".jar") || output.name.endsWith(".zip")) {
            output.absoluteFile.parentFile?.mkdirs()
            writeZip(classes.toSortedMap().map { (name, bytes) -> "$name.class" to bytes }, output)
        } else {
            val entries = classes.entries.toList()
            com.tim.dsmali.disasm.Parallel.forEach(entries.size) { i ->
                val f = File(output, "${entries[i].key}.class")
                f.parentFile.mkdirs()
                f.writeBytes(entries[i].value)
            }
        }
    }

    /**
     * ZIP с параллельным сжатием: записи сжимаются независимо во всех потоках, затем
     * заголовки и данные пишутся последовательно. Время записей фиксировано (1980-01-01) —
     * одинаковый вход даёт побайтно одинаковый архив. Больше 65535 записей — концевые записи ZIP64
     * (APPNOTE 4.3.14–4.3.16); размер архива — до 4 ГБ.
     */
    fun writeZip(entries: List<Pair<String, ByteArray>>, output: File) {
        class Packed(val name: ByteArray, val crc: Int, val method: Int, val data: ByteArray, val size: Int)
        val packed = arrayOfNulls<Packed>(entries.size)
        com.tim.dsmali.disasm.Parallel.forEach(entries.size) { i ->
            val (name, bytes) = entries[i]
            val crc = CRC32().apply { update(bytes) }.value.toInt()
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(bytes)
            deflater.finish()
            val buf = java.io.ByteArrayOutputStream(bytes.size / 2 + 64)
            val chunk = ByteArray(8192)
            while (!deflater.finished()) buf.write(chunk, 0, deflater.deflate(chunk))
            deflater.end()
            val compressed = buf.toByteArray()
            packed[i] = if (compressed.size < bytes.size) Packed(name.toByteArray(Charsets.UTF_8), crc, 8, compressed, bytes.size)
            else Packed(name.toByteArray(Charsets.UTF_8), crc, 0, bytes, bytes.size)
        }
        java.io.DataOutputStream(FileOutputStream(output).buffered(1 shl 16)).use { out ->
            fun u2(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
            fun u4(v: Int) { u2(v and 0xFFFF); u2(v ushr 16) }
            fun u8(v: Long) { u4(v.toInt()); u4((v ushr 32).toInt()) }
            val offsets = IntArray(packed.size)
            var pos = 0L
            for ((i, p) in packed.withIndex()) {
                p!!
                if (pos > 0xFFFFFFFFL - 30 - p.name.size - p.data.size) throw IllegalStateException("архив больше 4 ГБ не поддерживается")
                offsets[i] = pos.toInt()
                u4(0x04034b50); u2(20); u2(FLAG_UTF8); u2(p.method); u2(0); u2(DOS_DATE)
                u4(p.crc); u4(p.data.size); u4(p.size); u2(p.name.size); u2(0)
                out.write(p.name); out.write(p.data)
                pos += 30 + p.name.size + p.data.size
            }
            val cdStart = pos
            for ((i, p) in packed.withIndex()) {
                p!!
                u4(0x02014b50); u2(20); u2(20); u2(FLAG_UTF8); u2(p.method); u2(0); u2(DOS_DATE)
                u4(p.crc); u4(p.data.size); u4(p.size); u2(p.name.size); u2(0); u2(0); u2(0); u2(0); u4(0)
                u4(offsets[i]); out.write(p.name)
                pos += 46 + p.name.size
            }
            val cdSize = pos - cdStart
            val zip64 = packed.size > 0xFFFF || cdStart > 0xFFFFFFFFL || cdSize > 0xFFFFFFFFL || pos > 0xFFFFFFFFL
            if (zip64) {
                val record = pos
                u4(0x06064b50); u8(44); u2(45); u2(45); u4(0); u4(0)
                u8(packed.size.toLong()); u8(packed.size.toLong()); u8(cdSize); u8(cdStart)
                u4(0x07064b50); u4(0); u8(record); u4(1)
            }
            val count = if (zip64) 0xFFFF else packed.size
            u4(0x06054b50); u2(0); u2(0); u2(count); u2(count)
            u4(if (cdSize > 0xFFFFFFFFL) -1 else cdSize.toInt()); u4(if (cdStart > 0xFFFFFFFFL) -1 else cdStart.toInt()); u2(0)
        }
    }

    /** Бит 11: имена в UTF-8. */
    private const val FLAG_UTF8 = 0x0800

    /** Дата DOS 1980-01-01, время 00:00. */
    private const val DOS_DATE = (1 shl 5) or 1
}
