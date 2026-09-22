package org.wexclient.app.elf

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ElfHeaderField(val title: String) {
    TYPE("Тип файла (e_type)"),
    MACHINE("Архитектура (e_machine)"),
    ENTRY("Точка входа (e_entry)"),
    FLAGS("Флаги (e_flags)")
}

/**
 * Редактируемый ELF-документ: рабочая копия байт + свежий разбор таблиц.
 * Таблицы перечитываются при каждом запросе, поэтому правки через hex
 * сразу видны во вкладках. Сохранение — снаружи через [toByteArray].
 */
class ElfDocument(original: ByteArray) {

    @Volatile
    private var orig: ByteArray = original.copyOf()
    val data: ByteArray = original.copyOf()

    /** Первичный разбор (валидация). Бросает исключение, если не ELF. */
    val parsed: ElfParser.Parsed = ElfParser.parse(data)

    val is64Bit: Boolean get() = parsed.header.is64Bit
    val littleEndian: Boolean get() = parsed.header.littleEndian

    private fun order(): ByteOrder =
        if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    /** Смещение и размер поля заголовка для текущей разрядности. */
    fun headerFieldMeta(field: ElfHeaderField): Pair<Int, Int> {
        return when (field) {
            ElfHeaderField.TYPE -> 16 to 2
            ElfHeaderField.MACHINE -> 18 to 2
            ElfHeaderField.ENTRY -> if (is64Bit) 24 to 8 else 24 to 4
            ElfHeaderField.FLAGS -> if (is64Bit) 48 to 4 else 36 to 4
        }
    }

    fun getHeaderField(field: ElfHeaderField): Long {
        val (off, size) = headerFieldMeta(field)
        val buf = ByteBuffer.wrap(data, off, size).order(order())
        return when (size) {
            2 -> (buf.short.toInt() and 0xFFFF).toLong()
            4 -> (buf.int.toLong() and 0xFFFFFFFFL)
            else -> buf.long
        }
    }

    fun setHeaderField(field: ElfHeaderField, value: Long) {
        val (off, size) = headerFieldMeta(field)
        require(off + size <= data.size) { "Поле за границей файла" }
        val buf = ByteBuffer.wrap(data, off, size).order(order())
        when (size) {
            2 -> buf.putShort((value and 0xFFFF).toShort())
            4 -> buf.putInt((value and 0xFFFFFFFFL).toInt())
            else -> buf.putLong(value)
        }
    }

    fun setByte(offset: Int, value: Int) {
        require(offset in data.indices) { "Смещение за границей файла" }
        data[offset] = (value and 0xFF).toByte()
    }

    fun isDirty(): Boolean = !data.contentEquals(orig)

    /** Записано на диск: [saved] — снимок, который записали (правки после него остаются «изменено»). */
    fun markSaved(saved: ByteArray) {
        orig = saved
    }

    fun toByteArray(): ByteArray = data.copyOf()

    // --- Таблицы (всегда свежие, по рабочей копии) ---

    private fun fresh() = ElfParser.parse(data)

    fun sections(): List<ElfSection> = fresh().sections

    fun programs(): List<ElfProgram> = fresh().programs

    fun symbols(max: Int = 20000): List<ElfSymbol> {
        val p = fresh()
        return ElfParser.symbols(data, p, max)
    }

    fun dynamics(): List<ElfDynamic> {
        val p = fresh()
        return ElfParser.dynamics(data, p)
    }

    fun neededLibraries(): List<String> {
        val p = fresh()
        return ElfParser.neededLibraries(data, p)
    }

    fun soname(): String? {
        val p = fresh()
        return ElfParser.soname(data, p)
    }

    companion object {
        /** "0x1C", "28", "0b101" -> Long; null если мусор. */
        fun parseNumber(text: String): Long? {
            val t = text.trim().lowercase()
            return try {
                when {
                    t.startsWith("0x") -> t.substring(2).toLong(16)
                    t.startsWith("0b") -> t.substring(2).toLong(2)
                    t.startsWith("0") && t.length > 1 && t.all { it.isDigit() } ->
                        t.toLong(8)
                    else -> t.toLong(10)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun formatHex(value: Long, width: Int = 8): String =
            "0x" + java.lang.Long.toHexString(value).padStart(width, '0').uppercase()
    }
}
