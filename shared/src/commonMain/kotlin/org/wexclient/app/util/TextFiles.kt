package org.wexclient.app.util

/** Текст это или двоичный файл — по началу содержимого, как делает панель при открытии. */
object TextFiles {

    /** true — похоже на двоичное: есть NUL или слишком много управляющих байтов. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val probe = minOf(length, bytes.size, 8192)
        if (probe == 0) return false
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return true
        }
        var nonText = 0
        for (i in 0 until probe) {
            val v = bytes[i].toInt() and 0xFF
            if (v < 0x09 || (v in 0x0E..0x1F) || v == 0x7F) nonText++
        }
        return nonText * 100 / probe > 10
    }
}
