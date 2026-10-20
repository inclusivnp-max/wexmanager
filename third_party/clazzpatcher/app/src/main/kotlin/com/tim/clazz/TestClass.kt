package com.tim.clazz

import com.tim.clazz.model.ClassFile
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import java.io.File
import java.util.zip.ZipFile

/**
 * Программная точка входа для проверки class-файлов: чтение → запись → побайтовое сравнение.
 */
object TestClass {

    /** Итог round-trip одного класса; [firstDifference] = -1, если байты совпали. */
    data class Result(
        val name: String,
        val inputSize: Int,
        val outputSize: Int,
        val firstDifference: Int
    ) {
        val identical: Boolean get() = firstDifference < 0
    }

    /** Итог round-trip архива: [failures] — имя класса → сообщение об ошибке чтения/записи. */
    data class ArchiveResult(
        val archive: String,
        val classes: Int,
        val identical: Int,
        val different: List<Result>,
        val failures: Map<String, String>
    ) {
        val ok: Boolean get() = different.isEmpty() && failures.isEmpty()

        override fun toString(): String =
            "$archive: классов $classes, совпало $identical, отличается ${different.size}, ошибок ${failures.size}"
    }

    /** Байты класса, заново записанные из модели; [name] попадает в сообщения об ошибках. */
    @JvmStatic
    @JvmOverloads
    fun roundTrip(bytes: ByteArray, name: String? = null): ByteArray =
        ClassWriter(ClassReader().read(bytes, name)).toByteArray()

    /** Читает [input], записывает модель в [output] и сравнивает байты. */
    @JvmStatic
    fun roundTrip(input: File, output: File): Result {
        val original = input.readBytes()
        val written = roundTrip(original, input.path)
        output.writeBytes(written)
        return Result(input.path, original.size, written.size, firstDifference(original, written))
    }

    /** Round-trip всех `.class` в jar/zip (включая META-INF/versions); архив не изменяется. */
    @JvmStatic
    fun roundTripArchive(archive: File): ArchiveResult {
        var classes = 0
        var identical = 0
        val different = ArrayList<Result>()
        val failures = LinkedHashMap<String, String>()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                classes++
                val original = zip.getInputStream(entry).use { it.readBytes() }
                val written = try {
                    roundTrip(original, entry.name)
                } catch (e: RuntimeException) {
                    failures[entry.name] = e.message ?: e.toString()
                    continue
                }
                val diff = firstDifference(original, written)
                if (diff < 0) identical++ else different.add(Result(entry.name, original.size, written.size, diff))
            }
        }
        return ArchiveResult(archive.path, classes, identical, different, failures)
    }

    /** Краткое описание класса. */
    @JvmStatic
    fun describe(classFile: ClassFile): String = buildString {
        append("класс ${classFile.thisClassName}")
        classFile.superClassName?.let { append(" extends $it") }
        append(", версия ${classFile.majorVersion}.${classFile.minorVersion}")
        append(", флаги 0x${Integer.toHexString(classFile.accessFlags)}")
        append(", пул ${classFile.constantPoolCount}, интерфейсов ${classFile.interfacesCount}")
        append(", полей ${classFile.fieldsCount}, методов ${classFile.methodsCount}")
        append(", атрибутов ${classFile.attributesCount}")
    }

    /** Позиция первого отличающегося байта или -1, если массивы равны. */
    @JvmStatic
    fun firstDifference(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return i
        return if (a.size == b.size) -1 else n
    }

    /**
     * `<in.class> <out.class>` — round-trip одного класса;
     * `<archive.jar>...` — round-trip всех классов архивов.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 2 && args[0].endsWith(".class")) {
            val input = File(args[0])
            val result = roundTrip(input, File(args[1]))
            println(describe(ClassReader().read(input)))
            println(
                if (result.identical) "Совпадает байт-в-байт (${result.outputSize} байт)"
                else "ОТЛИЧАЕТСЯ: первый различающийся байт ${result.firstDifference} (${result.inputSize} → ${result.outputSize} байт)"
            )
            if (!result.identical) System.exit(1)
            return
        }
        require(args.isNotEmpty()) { "Использование: TestClass <in.class> <out.class> | <archive.jar>..." }
        var ok = true
        for (path in args) {
            val result = roundTripArchive(File(path))
            println(result)
            result.different.take(10).forEach { println("  отличается: ${it.name} (байт ${it.firstDifference})") }
            result.failures.entries.take(10).forEach { println("  ошибка: ${it.key}: ${it.value}") }
            ok = ok && result.ok
        }
        if (!ok) System.exit(1)
    }
}
