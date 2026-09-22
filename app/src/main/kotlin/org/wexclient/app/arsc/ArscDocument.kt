package org.wexclient.app.arsc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Редактируемый resources.arsc: рабочая копия + разбор.
 *
 * Правки строк — строго in-place той же закодированной длины
 * (оффсеты пула не сдвигаются, файл остаётся валидным).
 * Бросает IllegalArgumentException с понятным текстом, если не влезает.
 */
class ArscDocument(original: ByteArray) {

    @Volatile
    private var orig: ByteArray = original.copyOf()
    val data: ByteArray = original.copyOf()

    val table: ArscTable = ArscParser.parse(data)
    val globalMeta: ArscParser.PoolMeta = ArscParser.parsePool(data, 12)

    val packages: List<ArscPackage> get() = table.packages
    val globalStrings: List<String> get() = table.globalStrings
    val totalEntries: Int get() = table.totalEntries
    /** Живых строк в глобальном пуле (остальные индексы — мусор протектора). */
    val globalValidCount: Int get() = table.globalValidCount

    /** Свежие строки глобального пула из рабочей копии (после правок). */
    fun currentGlobalStrings(): List<String> = ArscParser.resolveAll(data, globalMeta)

    fun isDirty(): Boolean = !data.contentEquals(orig)

    /** Записано на диск: [saved] — снимок, который записали (правки после него остаются «изменено»). */
    fun markSaved(saved: ByteArray) {
        orig = saved
    }

    fun toByteArray(): ByteArray = data.copyOf()

    /** Текущее значение глобальной строки (честно из рабочей копии). */
    fun getGlobalString(index: Int): String {
        require(index in globalMeta.entryOffsets.indices) { "Нет строки #$index" }
        return ArscParser.readPoolString(data, globalMeta.entryOffsets[index], globalMeta.isUtf8)
    }

    /** Закодированная длина строки в пуле (для подсказки «влезет/нет»). */
    fun globalStringEncodedLength(index: Int): Int {
        require(index in globalMeta.entryOffsets.indices) { "Нет строки #$index" }
        return encodedLength(data, globalMeta.entryOffsets[index], globalMeta.isUtf8)
    }

    fun setGlobalString(index: Int, newText: String) {
        require(index in globalMeta.entryOffsets.indices) { "Нет строки #$index" }
        val pos = globalMeta.entryOffsets[index]
        val oldTotal = encodedLength(data, pos, globalMeta.isUtf8)
        require(oldTotal > 0) { "Строка #$index битая, правка невозможна" }
        val encoded = encodeSameLength(newText, oldTotal, globalMeta.isUtf8, index)
        require(pos + encoded.size <= data.size) { "Строка #$index вылезает за файл" }
        encoded.copyInto(data, pos)
    }

    // --- Кодирование ---

    private fun encodedLength(bytes: ByteArray, pos: Int, isUtf8: Boolean): Int {
        if (pos < 0 || pos >= bytes.size) return -1
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(pos)
            if (isUtf8) {
                var p = pos
                var len = buf.get().toInt() and 0xFF; p++
                if (len and 0x80 != 0) {
                    if (!buf.hasRemaining()) return -1
                    buf.get(); p++
                }
                var byteLen = buf.get().toInt() and 0xFF; p++
                if (byteLen and 0x80 != 0) {
                    if (!buf.hasRemaining()) return -1
                    buf.get(); p++
                }
                val total = (p - pos) + byteLen + 1 // +NUL
                if (pos + total > bytes.size) -1 else total
            } else {
                if (buf.remaining() < 2) return -1
                var charLen = buf.short.toInt() and 0xFFFF
                var head = 2
                if (charLen and 0x8000 != 0) {
                    if (buf.remaining() < 2) return -1
                    charLen = ((charLen and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
                    head = 4
                }
                val total = head + charLen * 2 + 2 // +NUL
                if (charLen < 0 || pos + total > bytes.size) -1 else total
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun encodeSameLength(text: String, totalLength: Int, isUtf8: Boolean, index: Int): ByteArray {
        if (isUtf8) {
            val payload = text.toByteArray(Charsets.UTF_8)
            val prefix = encodeUtf8Len(text.length) + encodeUtf8Len(payload.size)
            val available = totalLength - prefix.size - 1 // -NUL
            require(payload.size == available) {
                "Строка #$index: надо ${payload.size} байт, доступно $available " +
                        "(in-place — только та же длина)"
            }
            return prefix + payload + byteArrayOf(0)
        } else {
            val payload = text.toByteArray(Charsets.UTF_16LE)
            val charLen = text.length
            val head: ByteArray = if (charLen < 0x8000) {
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(charLen.toShort()).array()
            } else {
                val v = Int.MIN_VALUE or charLen
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            }
            val out = head + payload + byteArrayOf(0, 0)
            require(out.size == totalLength) {
                "Строка #$index: «$text» не влезает in-place (доступно ${totalLength - head.size - 2} байт)"
            }
            return out
        }
    }

    private fun encodeUtf8Len(v: Int): ByteArray {
        return if (v < 0x80) {
            byteArrayOf(v.toByte())
        } else {
            byteArrayOf((0x80 or (v shr 8)).toByte(), (v and 0xFF).toByte())
        }
    }
}
