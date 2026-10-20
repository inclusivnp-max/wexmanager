package org.wexclient.app.dex

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DexHeader(
    val magic: String,
    val checksum: Long,
    val signature: String,
    val fileSize: Long,
    val headerSize: Long,
    val endianTag: Long,
    val stringIdsSize: Int,
    val typeIdsSize: Int,
    val protoIdsSize: Int,
    val fieldIdsSize: Int,
    val methodIdsSize: Int,
    val classDefsSize: Int,
    val dataSize: Long,
    val dataOff: Long
)

class DexParser {
    fun parse(bytes: ByteArray): Pair<DexHeader?, List<String>> {
        if (bytes.size < 0x70) return null to emptyList()

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Check magic "dex\n035\0" or similar
        val magicBytes = ByteArray(8)
        buf.get(magicBytes)
        val magic = String(magicBytes)
        if (!magic.startsWith("dex\n")) {
            return null to emptyList()
        }

        val checksum = buf.int.toLong() and 0xFFFFFFFFL

        val sigBytes = ByteArray(20)
        buf.get(sigBytes)
        val signature = sigBytes.joinToString("") { "%02x".format(it) }

        val fileSize = buf.int.toLong() and 0xFFFFFFFFL
        val headerSize = buf.int.toLong() and 0xFFFFFFFFL
        val endianTag = buf.int.toLong() and 0xFFFFFFFFL

        val linkSize = buf.int
        val linkOff = buf.int

        val mapOff = buf.int

        val stringIdsSize = buf.int
        val stringIdsOff = buf.int

        val typeIdsSize = buf.int
        val typeIdsOff = buf.int

        val protoIdsSize = buf.int
        val protoIdsOff = buf.int

        val fieldIdsSize = buf.int
        val fieldIdsOff = buf.int

        val methodIdsSize = buf.int
        val methodIdsOff = buf.int

        val classDefsSize = buf.int
        val classDefsOff = buf.int

        val dataSize = buf.int.toLong() and 0xFFFFFFFFL
        val dataOff = buf.int.toLong() and 0xFFFFFFFFL

        val header = DexHeader(
            magic = magic.replace("\n", "\\n").replace("\u0000", ""),
            checksum = checksum,
            signature = signature,
            fileSize = fileSize,
            headerSize = headerSize,
            endianTag = endianTag,
            stringIdsSize = stringIdsSize,
            typeIdsSize = typeIdsSize,
            protoIdsSize = protoIdsSize,
            fieldIdsSize = fieldIdsSize,
            methodIdsSize = methodIdsSize,
            classDefsSize = classDefsSize,
            dataSize = dataSize,
            dataOff = dataOff
        )

        val stringOffsets = IntArray(stringIdsSize)
        buf.position(stringIdsOff)
        for (i in 0 until stringIdsSize) {
            stringOffsets[i] = buf.int
        }

        // Helper to read MUTF-8 string
        fun readString(index: Int): String {
            if (index < 0 || index >= stringIdsSize) return ""
            val offset = stringOffsets[index]
            buf.position(offset)
            // Skip uleb128 utf16_size
            readUleb128(buf)
            val bytesList = mutableListOf<Byte>()
            while (buf.hasRemaining()) {
                val b = buf.get()
                if (b == 0.toByte()) break
                bytesList.add(b)
            }
            return String(bytesList.toByteArray(), Charsets.UTF_8)
        }

        val typeIdToStringIndex = IntArray(typeIdsSize)
        buf.position(typeIdsOff)
        for (i in 0 until typeIdsSize) {
            typeIdToStringIndex[i] = buf.int
        }

        // Read ClassDefs (каждый class_def_item ровно 32 байта).
        // readString() двигает позицию буфера, поэтому перед каждой итерацией
        // возвращаемся на начало очередного item'а — иначе со 2-го класса читается мусор.
        val classNames = ArrayList<String>(classDefsSize)
        for (i in 0 until classDefsSize) {
            buf.position(classDefsOff + i * 32)
            val classIdx = buf.int
            buf.position(buf.position() + 28) // skip accessFlags, superclassIdx, interfacesOff, sourceFileIdx, annotationsOff, classDataOff, staticValuesOff
            val typeStrIdx = if (classIdx in 0 until typeIdsSize) typeIdToStringIndex[classIdx] else -1
            val descriptor = readString(typeStrIdx)
            // Lcom/example/MyClass; -> com.example.MyClass
            val cleanName = if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            } else {
                descriptor
            }
            if (cleanName.isNotEmpty()) {
                classNames.add(cleanName)
            }
        }

        return header to classNames.sorted()
    }

    private fun readUleb128(buf: ByteBuffer): Int {
        var result = 0
        var cur: Int
        var count = 0
        do {
            cur = buf.get().toInt() and 0xFF
            result = result or ((cur and 0x7F) shl (count * 7))
            count++
        } while (((cur and 0x80) == 0x80) && count < 5)
        return result
    }
}
