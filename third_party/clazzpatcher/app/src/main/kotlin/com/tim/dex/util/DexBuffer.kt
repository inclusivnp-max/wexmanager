package com.tim.dex.util

/**
 * Little-endian чтение DEX прямо из массива байт (без [java.nio.ByteBuffer]).
 *
 * Позиция — абсолютное смещение в массиве. Выход за границы массива даёт
 * [ArrayIndexOutOfBoundsException] (файл обрезан).
 */
class DexBuffer(val bytes: ByteArray) {
    var pos = 0

    fun position(): Int = pos
    fun position(newPosition: Int) { pos = newPosition }
    fun remaining(): Int = bytes.size - pos
    fun hasRemaining(): Boolean = pos < bytes.size
    fun limit(): Int = bytes.size

    fun readByte(): Byte = bytes[pos++]
    fun readUByte(): Int = bytes[pos++].toInt() and 0xFF

    fun readUShort(): Int {
        val p = pos
        pos = p + 2
        return u2At(p)
    }

    fun readShort(): Short = readUShort().toShort()

    fun readInt(): Int {
        val p = pos
        pos = p + 4
        return u4At(p)
    }

    fun readUInt(): Int = readInt()

    fun readBytes(size: Int): ByteArray {
        val p = pos
        if (size < 0 || size > bytes.size - p) {
            throw IllegalArgumentException("Блок из $size байт по смещению 0x${p.toString(16)} выходит за конец файла")
        }
        pos = p + size
        return bytes.copyOfRange(p, p + size)
    }

    fun u1At(offset: Int): Int = bytes[offset].toInt() and 0xFF

    fun u2At(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun u4At(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** ULEB128 (до 5 байт, как в ART). */
    fun readULEB128(): Int {
        val b = bytes
        var p = pos
        var result = b[p++].toInt() and 0xFF
        if (result > 0x7F) {
            var cur = b[p++].toInt() and 0xFF
            result = (result and 0x7F) or ((cur and 0x7F) shl 7)
            if (cur > 0x7F) {
                cur = b[p++].toInt() and 0xFF
                result = result or ((cur and 0x7F) shl 14)
                if (cur > 0x7F) {
                    cur = b[p++].toInt() and 0xFF
                    result = result or ((cur and 0x7F) shl 21)
                    if (cur > 0x7F) {
                        cur = b[p++].toInt() and 0xFF
                        if (cur > 0x7F) throw IllegalArgumentException("ULEB128 длиннее 5 байт по смещению 0x${pos.toString(16)}")
                        result = result or (cur shl 28)
                    }
                }
            }
        }
        pos = p
        return result
    }

    /** SLEB128 (до 5 байт, как в ART). */
    fun readSLEB128(): Int {
        val b = bytes
        var p = pos
        var result = b[p++].toInt() and 0xFF
        if (result <= 0x7F) {
            result = (result shl 25) shr 25
        } else {
            var cur = b[p++].toInt() and 0xFF
            result = (result and 0x7F) or ((cur and 0x7F) shl 7)
            if (cur <= 0x7F) {
                result = (result shl 18) shr 18
            } else {
                cur = b[p++].toInt() and 0xFF
                result = result or ((cur and 0x7F) shl 14)
                if (cur <= 0x7F) {
                    result = (result shl 11) shr 11
                } else {
                    cur = b[p++].toInt() and 0xFF
                    result = result or ((cur and 0x7F) shl 21)
                    if (cur <= 0x7F) {
                        result = (result shl 4) shr 4
                    } else {
                        cur = b[p++].toInt() and 0xFF
                        if (cur > 0x7F) throw IllegalArgumentException("SLEB128 длиннее 5 байт по смещению 0x${pos.toString(16)}")
                        result = result or (cur shl 28)
                    }
                }
            }
        }
        pos = p
        return result
    }

    /** uleb128p1: 0 означает `-1` (NO_INDEX). */
    fun readULEB128p1(): Int = readULEB128() - 1

    fun align4() {
        pos = (pos + 3) and -4
    }

    companion object {
        fun wrap(bytes: ByteArray): DexBuffer = DexBuffer(bytes)
    }
}
