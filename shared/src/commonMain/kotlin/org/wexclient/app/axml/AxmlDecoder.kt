package org.wexclient.app.axml

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, self-contained Android Binary XML (AXML) decompiler.
 * Decompiles AndroidManifest.xml and layout binary XML files into human-readable formatted XML.
 */
class AxmlDecoder {

    companion object {
        private const val CHUNK_AXML_FILE = 0x00080003
        private const val CHUNK_RESOURCEIDS = 0x00080180
        private const val CHUNK_STRINGS = 0x001C0001
        private const val CHUNK_XML_START_NAMESPACE = 0x00100100
        private const val CHUNK_XML_END_NAMESPACE = 0x00100101
        private const val CHUNK_XML_START_TAG = 0x00100102
        private const val CHUNK_XML_END_TAG = 0x00100103
        private const val CHUNK_XML_TEXT = 0x00100104

        // TypedValue types
        private const val TYPE_NULL = 0x00
        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_ATTRIBUTE = 0x02
        private const val TYPE_STRING = 0x03
        private const val TYPE_FLOAT = 0x04
        private const val TYPE_DIMENSION = 0x05
        private const val TYPE_FRACTION = 0x06
        private const val TYPE_DYNAMIC_REFERENCE = 0x07
        private const val TYPE_DYNAMIC_ATTRIBUTE = 0x08
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_HEX = 0x11
        private const val TYPE_INT_BOOLEAN = 0x12
        private const val TYPE_INT_COLOR_ARGB8 = 0x1c
        private const val TYPE_INT_COLOR_RGB8 = 0x1d
        private const val TYPE_INT_COLOR_ARGB4 = 0x1e
        private const val TYPE_INT_COLOR_RGB4 = 0x1f

        /** Глубже отступ не растёт: иначе размер текста квадратичен по числу вложенных тегов. */
        private const val MAX_INDENT = 64
        private const val MAX_OUTPUT_CHARS = 32 shl 20

        fun isBinaryXml(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val buf = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            if (magic == CHUNK_AXML_FILE) return true
            // Протекторы иногда затирают магию файла (00 00 08 00), оставляя валидные чанки:
            // детектим бинарь по string pool чанку на месте первого чанка.
            buf.position(8)
            return buf.int == CHUNK_STRINGS
        }
    }

    fun decode(bytes: ByteArray): String {
        if (!isBinaryXml(bytes)) {
            return String(bytes, Charsets.UTF_8)
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")

        val magic = buffer.int
        val fileSize = buffer.int

        var stringTable: List<String> = emptyList()
        var resourceIds: IntArray = intArrayOf()
        val namespaces = mutableMapOf<String, String>() // prefix to uri
        var indentLevel = 0

        while (buffer.hasRemaining()) {
            val chunkStart = buffer.position()
            if (buffer.remaining() < 8) break
            // Враждебный файл может раздуть текст до гигабайт (атрибуты со ссылкой на длинную строку)
            if (sb.length > MAX_OUTPUT_CHARS) {
                sb.append("<!-- … дальше не показано: слишком большой результат -->\n")
                break
            }

            val chunkType = buffer.int
            val chunkSize = buffer.int

            // В Long: chunkStart + 0x7FFFFFFF переполнял Int, и 300-байтный файл занимал гигабайты
            if (chunkSize < 8 || chunkStart.toLong() + chunkSize > bytes.size) {
                break
            }

            // Битые чанки не роняют разбор: чтения ниже защищены границами чанка,
            // после любой ветки позиция принудительно ставится на следующий чанк.
            when (chunkType) {
                CHUNK_STRINGS -> {
                    stringTable = parseStringPool(buffer, chunkStart, chunkSize)
                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_RESOURCEIDS -> {
                    val count = minOf((chunkSize - 8) / 4, buffer.remaining() / 4)
                    resourceIds = IntArray(count)
                    for (i in 0 until count) {
                        resourceIds[i] = buffer.int
                    }
                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_XML_START_NAMESPACE -> {
                    val lineNumber = buffer.int
                    val commentIndex = buffer.int
                    val prefixIndex = buffer.int
                    val uriIndex = buffer.int

                    val prefix = getString(stringTable, prefixIndex)
                    val uri = getString(stringTable, uriIndex)
                    if (prefix.isNotEmpty() && uri.isNotEmpty()) {
                        namespaces[prefix] = uri
                    }
                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_XML_END_NAMESPACE -> {
                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_XML_START_TAG -> {
                    val lineNumber = buffer.int
                    val commentIndex = buffer.int
                    val nsIndex = buffer.int
                    val nameIndex = buffer.int
                    val attributeStart = buffer.short.toInt() and 0xFFFF
                    val attributeSize = buffer.short.toInt() and 0xFFFF
                    val attributeCount = buffer.short.toInt() and 0xFFFF
                    val idIndex = buffer.short.toInt() and 0xFFFF
                    val classIndex = buffer.short.toInt() and 0xFFFF
                    val styleIndex = buffer.short.toInt() and 0xFFFF

                    val tagName = getString(stringTable, nameIndex)

                    val indent = "    ".repeat(minOf(indentLevel, MAX_INDENT))
                    sb.append(indent).append("<").append(tagName)

                    if (indentLevel == 0 && namespaces.isNotEmpty()) {
                        for ((pfx, u) in namespaces) {
                            sb.append("\n").append(indent).append("    xmlns:").append(pfx).append("=\"").append(u).append("\"")
                        }
                    }

                    // Parse attributes (мусорный attributeCount от протектора
                    // упирается в границу чанка вместо вылета за файл)
                    val chunkEnd = chunkStart + chunkSize
                    for (i in 0 until attributeCount) {
                        if (attributeCount < 0 || attributeCount > 512) break
                        if (buffer.remaining() < 20 || buffer.position() + 20 > chunkEnd) break
                        val attrNsIndex = buffer.int
                        val attrNameIndex = buffer.int
                        val attrRawValIndex = buffer.int
                        val attrValSize = buffer.short.toInt() and 0xFFFF
                        val attrValRes0 = buffer.get().toInt()
                        val attrValType = buffer.get().toInt() and 0xFF
                        val attrValData = buffer.int

                        val attrNs = getString(stringTable, attrNsIndex)
                        val attrName = getString(stringTable, attrNameIndex)
                        val attrVal = formatAttributeValue(stringTable, attrRawValIndex, attrValType, attrValData)

                        val prefix = namespaces.entries.firstOrNull { it.value == attrNs }?.key
                        val qualifiedName = if (prefix != null) "$prefix:$attrName" else attrName

                        if (attributeCount > 2) {
                            sb.append("\n").append(indent).append("    ")
                        } else {
                            sb.append(" ")
                        }
                        sb.append(qualifiedName).append("=\"").append(escapeXml(attrVal)).append("\"")
                    }

                    sb.append(">\n")
                    indentLevel++
                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_XML_END_TAG -> {
                    val lineNumber = buffer.int
                    val commentIndex = buffer.int
                    val nsIndex = buffer.int
                    val nameIndex = buffer.int

                    val tagName = getString(stringTable, nameIndex)
                    if (indentLevel > 0) indentLevel--
                    val indent = "    ".repeat(minOf(indentLevel, MAX_INDENT))
                    sb.append(indent).append("</").append(tagName).append(">\n")

                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_XML_TEXT -> {
                    val lineNumber = buffer.int
                    val commentIndex = buffer.int
                    val nameIndex = buffer.int
                    val rawValIndex = buffer.int
                    val text = getString(stringTable, nameIndex)

                    val indent = "    ".repeat(minOf(indentLevel, MAX_INDENT))
                    sb.append(indent).append(escapeXml(text)).append("\n")

                    buffer.position(chunkStart + chunkSize)
                }

                else -> {
                    buffer.position(chunkStart + chunkSize)
                }
            }
        }

        return sb.toString()
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, chunkSize: Int): List<String> {
        val limit = buffer.capacity()
        if (buffer.remaining() < 20) return emptyList()
        val stringCount = buffer.int
        val styleCount = buffer.int
        val flags = buffer.int
        val stringsStart = buffer.int
        val stylesStart = buffer.int

        // Санити-чеки против битых протектором пулов (иначе OOM/вылет за границу)
        if (stringCount < 0 || stringCount > 100000) return emptyList()
        if (styleCount < 0 || styleCount > 100000) return emptyList()
        if (stringsStart < 0 || chunkStart + stringsStart >= limit) return emptyList()
        if (buffer.remaining() < stringCount * 4) return emptyList()

        val isUtf8 = (flags and (1 shl 8)) != 0

        val stringOffsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            stringOffsets[i] = buffer.int
        }

        val stringsAbsoluteStart = chunkStart + stringsStart
        val strings = ArrayList<String>(minOf(stringCount, 1024))
        // Одно смещение — одна строка: 100 000 ссылок на длинную строку не должны стать 100 000 копий
        val byOffset = HashMap<Int, String>()

        for (i in 0 until stringCount) {
            val offset = stringsAbsoluteStart + stringOffsets[i]
            if (offset < 0 || offset >= limit) {
                strings.add("")
                continue
            }
            strings.add(byOffset.getOrPut(offset) {
                try {
                    buffer.position(offset)
                    readPoolString(buffer, isUtf8)
                } catch (_: Exception) {
                    ""
                }
            })
        }

        return strings
    }

    private fun readPoolString(buffer: ByteBuffer, isUtf8: Boolean): String {
        if (isUtf8) {
            // UTF-8 string: skip length bytes
            if (buffer.remaining() < 2) return ""
            val len1 = buffer.get().toInt() and 0xFF
            if ((len1 and 0x80) != 0) {
                if (!buffer.hasRemaining()) return ""
                buffer.get()
            }
            if (!buffer.hasRemaining()) return ""
            val len2 = buffer.get().toInt() and 0xFF
            val byteLen = if ((len2 and 0x80) != 0) {
                if (!buffer.hasRemaining()) return ""
                ((len2 and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
            } else {
                len2
            }
            if (byteLen < 0 || byteLen > buffer.remaining()) return ""
            val bytes = ByteArray(byteLen)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        } else {
            // UTF-16 string
            if (buffer.remaining() < 2) return ""
            var charLen = buffer.short.toInt() and 0xFFFF
            if ((charLen and 0x8000) != 0) {
                if (buffer.remaining() < 2) return ""
                charLen = ((charLen and 0x7FFF) shl 16) or (buffer.short.toInt() and 0xFFFF)
            }
            if (charLen < 0 || charLen.toLong() * 2 > buffer.remaining()) return ""
            val chars = CharArray(charLen)
            for (c in 0 until charLen) {
                chars[c] = buffer.short.toInt().toChar()
            }
            return String(chars)
        }
    }

    private fun formatAttributeValue(stringTable: List<String>, rawIndex: Int, type: Int, data: Int): String {
        if (rawIndex >= 0 && rawIndex < stringTable.size) {
            val raw = stringTable[rawIndex]
            if (raw.isNotEmpty()) return raw
        }

        return when (type) {
            TYPE_STRING -> getString(stringTable, data)
            TYPE_REFERENCE -> "@0x" + Integer.toHexString(data).padStart(8, '0')
            TYPE_ATTRIBUTE -> "?0x" + Integer.toHexString(data).padStart(8, '0')
            TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_DIMENSION -> org.wexclient.app.arsc.ArscParser.decodeDimension(data)
            TYPE_INT_COLOR_ARGB8, TYPE_INT_COLOR_RGB8, TYPE_INT_COLOR_ARGB4, TYPE_INT_COLOR_RGB4 -> {
                "#" + Integer.toHexString(data).padStart(8, '0')
            }
            else -> "0x" + Integer.toHexString(data)
        }
    }

    private fun getString(stringTable: List<String>, index: Int): String {
        return if (index >= 0 && index < stringTable.size) stringTable[index] else ""
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
