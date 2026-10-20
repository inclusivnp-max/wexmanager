package com.tim.dex.util

import java.nio.ByteBuffer

/**
 * LEB128 поверх [ByteBuffer] и расчёт размеров кодирования.
 *
 * Декодирование повторяет ART: значение занимает не более 5 байт, у 5-го байта
 * берутся младшие биты (старшие игнорируются), бит продолжения в 5-м байте — ошибка.
 */
object DexLeb128 {
    fun readULEB128(buffer: ByteBuffer): Int {
        var result = 0
        for (i in 0 until 5) {
            val byte = buffer.get().toInt() and 0xFF
            if (i == 4) {
                if (byte > 0x7F) throw IllegalArgumentException("ULEB128 длиннее 5 байт")
                return result or (byte shl 28)
            }
            result = result or ((byte and 0x7F) shl (i * 7))
            if (byte <= 0x7F) return result
        }
        return result
    }

    fun readSLEB128(buffer: ByteBuffer): Int {
        var result = 0
        for (i in 0 until 5) {
            val byte = buffer.get().toInt() and 0xFF
            if (i == 4) {
                if (byte > 0x7F) throw IllegalArgumentException("SLEB128 длиннее 5 байт")
                return result or (byte shl 28)
            }
            result = result or ((byte and 0x7F) shl (i * 7))
            if (byte <= 0x7F) {
                val shift = 32 - 7 * (i + 1)
                return (result shl shift) shr shift
            }
        }
        return result
    }

    fun readULEB128p1(buffer: ByteBuffer): Int = readULEB128(buffer) - 1

    fun writeULEB128(buffer: ByteBuffer, value: Int) {
        var remaining = value
        do {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) byte = byte or 0x80
            buffer.put(byte.toByte())
        } while (remaining != 0)
    }

    fun writeSLEB128(buffer: ByteBuffer, value: Int) {
        var v = value
        while (true) {
            val byte = v and 0x7F
            v = v shr 7
            if ((v == 0 && (byte and 0x40) == 0) || (v == -1 && (byte and 0x40) != 0)) {
                buffer.put(byte.toByte())
                return
            }
            buffer.put((byte or 0x80).toByte())
        }
    }

    fun writeULEB128p1(buffer: ByteBuffer, value: Int) = writeULEB128(buffer, value + 1)

    fun getULEB128Size(value: Int): Int {
        var remaining = value ushr 7
        var size = 1
        while (remaining != 0) {
            remaining = remaining ushr 7
            size++
        }
        return size
    }

    fun getSLEB128Size(value: Int): Int {
        var v = value
        var count = 0
        while (true) {
            val byte = v and 0x7F
            v = v shr 7
            count++
            if ((v == 0 && (byte and 0x40) == 0) || (v == -1 && (byte and 0x40) != 0)) return count
        }
    }
}
