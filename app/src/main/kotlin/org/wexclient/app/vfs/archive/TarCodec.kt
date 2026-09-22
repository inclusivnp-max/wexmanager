package org.wexclient.app.vfs.archive

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class TarEntryKind { FILE, DIR, SYMLINK, HARDLINK, SPECIAL }

/** Запись оглавления tar: где лежат данные и атрибуты. */
internal class TarRecord(
    /** Путь как в архиве (без нормализации). */
    val rawPath: String,
    val kind: TarEntryKind,
    val typeFlag: Char,
    /** Размер данных в архиве. */
    val size: Long,
    val dataOffset: Long,
    val mtime: Long,
    val mode: Int,
    val uid: Long,
    val gid: Long,
    val uname: String,
    val gname: String,
    val link: String?,
    val devMajor: Int,
    val devMinor: Int,
    /** Почему содержимое нельзя прочитать как обычный файл (sparse и т.п.). */
    val unsupported: String?,
    /** PAX-атрибуты, которые сами не разбираем (xattr, ACL, atime…): при записи возвращаются как были. */
    val extraPax: Map<String, String> = emptyMap(),
    /** Имя или цель ссылки — не UTF-8: при перезаписи байты имени изменились бы. */
    val nameNotUtf8: Boolean = false
)

/**
 * Чтение и запись tar: ustar/POSIX (prefix), GNU (длинные имена L/K, base-256), PAX (x/g).
 */
internal object TarCodec {
    const val BLOCK = 512
    const val RECORD = BLOCK * 20

    private val ZERO = ByteArray(BLOCK)

    private fun readBlock(ch: FileChannel, pos: Long, into: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(into)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n < 0) return false
            p += n
        }
        return true
    }

    private fun readBytes(ch: FileChannel, pos: Long, size: Long): ByteArray {
        if (size > 64L * 1024 * 1024) throw IOException("Слишком большой служебный заголовок tar ($size байт)")
        val data = ByteArray(size.toInt())
        if (!readBlock(ch, pos, data)) throw IOException("Архив обрезан")
        return data
    }

    /** Похоже ли начало на tar: верная контрольная сумма первого заголовка или пустой архив. */
    fun looksLikeTar(ch: FileChannel): Boolean {
        if (ch.size() < BLOCK) return false
        val b = ByteArray(BLOCK)
        if (!readBlock(ch, 0, b)) return false
        if (b.contentEquals(ZERO)) return true
        return checksumOk(b)
    }

    fun checksumOk(b: ByteArray): Boolean {
        val stored = parseOctal(b, 148, 8) ?: return false
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            if (i in 148..155) {
                unsigned += 32
                signed += 32
            } else {
                unsigned += b[i].toInt() and 0xff
                signed += b[i].toInt()
            }
        }
        return stored == unsigned || stored == signed
    }

    fun read(ch: FileChannel, onRecord: (TarRecord) -> Unit) {
        val size = ch.size()
        val h = ByteArray(BLOCK)
        var pos = 0L
        var longName: String? = null
        var longLink: String? = null
        var longNameUtf8 = true
        var longLinkUtf8 = true
        var pax: Map<String, String>? = null
        val globalPax = HashMap<String, String>()
        var first = true
        while (pos + BLOCK <= size) {
            if (!readBlock(ch, pos, h)) break
            if (h.contentEquals(ZERO)) {
                // Конец архива — два нулевых блока (часто архив обрезан после первого)
                pos += BLOCK
                continue
            }
            if (!checksumOk(h)) {
                if (first) throw IOException("Файл не является tar-архивом")
                throw IOException("Повреждённый заголовок tar (смещение $pos)")
            }
            first = false
            val type = (h[156].toInt() and 0xff).toChar()
            var stored = parseNumber(h, 124, 12)
            val dataOffset = pos + BLOCK
            // Блоки-продолжения карты старого GNU sparse — перед данными
            var extraBlocks = 0L
            when (type) {
                'L' -> readBytes(ch, dataOffset, stored).let {
                    longName = cString(it)
                    longNameUtf8 = isUtf8(it, 0, cLength(it))
                }
                'K' -> readBytes(ch, dataOffset, stored).let {
                    longLink = cString(it)
                    longLinkUtf8 = isUtf8(it, 0, cLength(it))
                }
                'x', 'X' -> pax = parsePax(readBytes(ch, dataOffset, stored))
                'g' -> parsePax(readBytes(ch, dataOffset, stored)).forEach { (k, v) ->
                    if (v.isEmpty()) globalPax.remove(k) else globalPax[k] = v
                }
                'V' -> { /* метка тома */ }
                else -> {
                    val attrs = HashMap(globalPax)
                    pax?.forEach { (k, v) -> if (v.isEmpty()) attrs.remove(k) else attrs[k] = v }
                    val magic = String(h, 257, 6, StandardCharsets.ISO_8859_1)
                    var name = longName ?: attrs["path"] ?: run {
                        val base = decodeName(h, 0, 100)
                        // Поле prefix есть только у POSIX ustar; у GNU там atime/ctime
                        val prefix = if (magic == "ustar\u0000") decodeName(h, 345, 155) else ""
                        if (prefix.isNotEmpty()) "$prefix/$base" else base
                    }
                    val link = longLink ?: attrs["linkpath"] ?: decodeName(h, 157, 100).takeIf { it.isNotEmpty() }
                    // Байты имён: откуда взяли имя — то и проверяем
                    val binaryPax = attrs["hdrcharset"] == "BINARY" || NON_UTF8 in attrs
                    val nameUtf8 = when {
                        longName != null -> longNameUtf8
                        attrs["path"] != null -> !binaryPax
                        else -> isUtf8Field(h, 0, 100) && (magic != "ustar\u0000" || isUtf8Field(h, 345, 155))
                    }
                    val linkUtf8 = when {
                        longLink != null -> longLinkUtf8
                        attrs["linkpath"] != null -> !binaryPax
                        else -> isUtf8Field(h, 157, 100)
                    }
                    attrs["size"]?.toLongOrNull()?.let { stored = it }
                    var unsupported: String? = null
                    if (attrs.keys.any { it.startsWith("GNU.sparse.") }) {
                        unsupported = "разреженный файл (sparse)"
                        attrs["GNU.sparse.name"]?.let { name = it }
                    }
                    if (type == 'S') {
                        unsupported = "разреженный файл (sparse)"
                        // Продолжение карты разрежённости — отдельные блоки
                        if (h[482].toInt() != 0) {
                            val ext = ByteArray(BLOCK)
                            var p = dataOffset
                            do {
                                if (!readBlock(ch, p, ext)) break
                                p += BLOCK
                                extraBlocks += BLOCK
                            } while (ext[504].toInt() != 0)
                        }
                    }
                    if (type == 'M') unsupported = "часть многотомного архива"
                    val kind = when (type) {
                        '5', 'D' -> TarEntryKind.DIR
                        '1' -> TarEntryKind.HARDLINK
                        '2' -> TarEntryKind.SYMLINK
                        '3', '4', '6' -> TarEntryKind.SPECIAL
                        else -> if (name.endsWith("/")) TarEntryKind.DIR else TarEntryKind.FILE
                    }
                    val mtime = attrs["mtime"]?.let { parsePaxTime(it) } ?: (parseNumber(h, 136, 12) * 1000)
                    onRecord(
                        TarRecord(
                            rawPath = name,
                            kind = kind,
                            typeFlag = type,
                            size = if (kind == TarEntryKind.FILE) stored else 0L,
                            dataOffset = dataOffset + extraBlocks,
                            mtime = mtime,
                            mode = (parseNumber(h, 100, 8) and 0xFFF).toInt(),
                            uid = attrs["uid"]?.toLongOrNull() ?: parseNumber(h, 108, 8),
                            gid = attrs["gid"]?.toLongOrNull() ?: parseNumber(h, 116, 8),
                            uname = attrs["uname"] ?: decodeName(h, 265, 32),
                            gname = attrs["gname"] ?: decodeName(h, 297, 32),
                            link = link,
                            devMajor = parseNumber(h, 329, 8).toInt(),
                            devMinor = parseNumber(h, 337, 8).toInt(),
                            unsupported = unsupported,
                            extraPax = attrs.filterKeys { k ->
                                k !in HANDLED_PAX && k != NON_UTF8 && !k.startsWith("GNU.sparse.") &&
                                    // Время храним отдельно, только если оно точнее секунды
                                    (k != "mtime" || attrs["mtime"]?.contains('.') == true)
                            },
                            nameNotUtf8 = !nameUtf8 || (link != null && !linkUtf8)
                        )
                    )
                    longName = null
                    longLink = null
                    longNameUtf8 = true
                    longLinkUtf8 = true
                    pax = null
                }
            }
            if (stored < 0) throw IOException("Повреждённый заголовок tar (смещение $pos)")
            // Данные есть у файлов и служебных записей; у ссылок/каталогов поле размера — 0
            val dataBlocks = if (type in "125346") 0L else stored
            pos = dataOffset + extraBlocks + (dataBlocks + BLOCK - 1) / BLOCK * BLOCK
        }
    }

    /** Ключи PAX, которые разбираются в поля записи; остальные переносятся как есть. */
    private val HANDLED_PAX = setOf("path", "linkpath", "size", "uid", "gid", "uname", "gname", "hdrcharset")

    /** Метка в разобранном PAX: значение было не в UTF-8. */
    private const val NON_UTF8 = "\u0000wex.non-utf8"

    private fun cLength(b: ByteArray): Int = b.indexOf(0).let { if (it < 0) b.size else it }

    private fun cString(b: ByteArray): String = decode(b, 0, cLength(b))

    private fun isUtf8(b: ByteArray, off: Int, len: Int): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, len))
        true
    } catch (_: Exception) {
        false
    }

    private fun isUtf8Field(b: ByteArray, off: Int, len: Int): Boolean {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return isUtf8(b, off, end - off)
    }

    /** Время из PAX (секунды с дробью) в миллисекундах. */
    fun paxTimeMillis(s: String): Long? = parsePaxTime(s)

    private fun decodeName(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return decode(b, off, end - off)
    }

    // Имена в tar — байты; почти всегда UTF-8, иначе — побайтно (Latin-1), чтобы не терять символы
    private fun decode(b: ByteArray, off: Int, len: Int): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, len))
            .toString()
    } catch (_: Exception) {
        String(b, off, len, StandardCharsets.ISO_8859_1)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long? {
        var i = off
        val end = off + len
        while (i < end && (b[i] == ' '.code.toByte() || b[i].toInt() == 0)) i++
        if (i >= end) return 0L
        var v = 0L
        var any = false
        while (i < end) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) break
            if (c < '0'.code || c > '7'.code) return null
            v = v * 8 + (c - '0'.code)
            any = true
            i++
        }
        return if (any) v else 0L
    }

    /** Восьмеричное число или base-256 (GNU: старший бит первого байта). */
    fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
        val first = b[off].toInt() and 0xff
        if ((first and 0x80) != 0) {
            val negative = first == 0xff
            var v = if (negative) -1L else 0L
            for (i in 0 until len) {
                val byte = if (i == 0) (if (negative) 0xff else first and 0x7f) else (b[off + i].toInt() and 0xff)
                v = (v shl 8) or byte.toLong()
            }
            return v
        }
        return parseOctal(b, off, len) ?: 0L
    }

    private fun parsePaxTime(s: String): Long? {
        val t = s.trim()
        val negative = t.startsWith("-")
        val body = t.removePrefix("-")
        val secs = body.substringBefore('.').toLongOrNull() ?: return null
        val frac = body.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val millis = secs * 1000 + frac
        return if (negative) -millis else millis
    }

    fun parsePax(data: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val len = String(data, i, sp - i, StandardCharsets.US_ASCII).trim().toIntOrNull() ?: break
            if (len <= sp - i || i.toLong() + len > data.size) break
            val recEnd = i + len - 1 // без завершающего \n
            val eq = (sp + 1 until recEnd).firstOrNull { data[it] == '='.code.toByte() }
            if (eq != null) {
                val key = String(data, sp + 1, eq - sp - 1, StandardCharsets.UTF_8)
                // xattr могут быть двоичными — их значения берём побайтно, чтобы записать обратно те же байты
                val binaryValue = key.startsWith("SCHILY.xattr.") || key.startsWith("LIBARCHIVE.xattr.")
                if (!binaryValue && (key == "path" || key == "linkpath") && !isUtf8(data, eq + 1, recEnd - eq - 1)) {
                    map[NON_UTF8] = "1"
                }
                map[key] = if (binaryValue) {
                    String(data, eq + 1, recEnd - eq - 1, StandardCharsets.ISO_8859_1)
                } else {
                    String(data, eq + 1, recEnd - eq - 1, StandardCharsets.UTF_8)
                }
            }
            i += len
        }
        return map
    }

    /** Запись tar в POSIX-формате (PAX для длинных имён), как GNU tar --format=posix. */
    class Writer(out: OutputStream) : Closeable {
        private val out = BufferedOutputStream(out, 1 shl 16)
        private var written = 0L

        fun entry(
            name: String,
            type: Char,
            size: Long,
            mtimeMillis: Long,
            mode: Int,
            uid: Long,
            gid: Long,
            uname: String,
            gname: String,
            link: String? = null,
            devMajor: Int = 0,
            devMinor: Int = 0,
            extraPax: Map<String, String> = emptyMap(),
            data: ((OutputStream) -> Unit)? = null
        ) {
            val pax = LinkedHashMap<String, String>(extraPax)
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
            var nameField = nameBytes
            var prefixField = ByteArray(0)
            if (nameBytes.size > 100) {
                val split = splitUstar(nameBytes)
                if (split != null) {
                    prefixField = split.first
                    nameField = split.second
                } else {
                    pax["path"] = name
                    nameField = truncate(nameBytes, 100)
                }
            }
            var linkField = ByteArray(0)
            if (link != null) {
                val linkBytes = link.toByteArray(StandardCharsets.UTF_8)
                linkField = linkBytes
                if (linkBytes.size > 100) {
                    pax["linkpath"] = link
                    linkField = truncate(linkBytes, 100)
                }
            }
            val unameBytes = uname.toByteArray(StandardCharsets.UTF_8)
            val gnameBytes = gname.toByteArray(StandardCharsets.UTF_8)
            if (unameBytes.size > 32) pax["uname"] = uname
            if (gnameBytes.size > 32) pax["gname"] = gname
            if (pax.isNotEmpty()) writePax(pax, mtimeMillis)

            val h = ByteArray(BLOCK)
            put(h, 0, nameField, 100)
            putNumber(h, 100, 8, (mode and 0xFFF).toLong())
            putNumber(h, 108, 8, uid)
            putNumber(h, 116, 8, gid)
            putNumber(h, 124, 12, size)
            putNumber(h, 136, 12, Math.floorDiv(mtimeMillis, 1000L))
            h[156] = type.code.toByte()
            put(h, 157, linkField, 100)
            put(h, 257, "ustar".toByteArray(StandardCharsets.US_ASCII), 6)
            put(h, 263, "00".toByteArray(StandardCharsets.US_ASCII), 2)
            put(h, 265, truncate(unameBytes, 32), 32)
            put(h, 297, truncate(gnameBytes, 32), 32)
            if (type == '3' || type == '4') {
                putNumber(h, 329, 8, devMajor.toLong())
                putNumber(h, 337, 8, devMinor.toLong())
            }
            put(h, 345, prefixField, 155)
            for (i in 148..155) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            val digits = Integer.toOctalString(sum).padStart(6, '0').toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(digits, 0, h, 148, 6)
            h[154] = 0
            h[155] = ' '.code.toByte()
            out.write(h)
            written += BLOCK

            if (size > 0) {
                val counter = CountingOutput(out)
                data?.invoke(counter)
                if (counter.count != size) {
                    throw IOException("«$name»: записано ${counter.count} байт вместо $size")
                }
                pad(size)
            }
        }

        private fun writePax(pax: Map<String, String>, mtimeMillis: Long) {
            val body = ByteArrayOutputStream()
            for ((k, v) in pax) {
                val binaryValue = k.startsWith("SCHILY.xattr.") || k.startsWith("LIBARCHIVE.xattr.")
                val kv = " $k=".toByteArray(StandardCharsets.UTF_8) +
                    v.toByteArray(if (binaryValue) StandardCharsets.ISO_8859_1 else StandardCharsets.UTF_8) +
                    byteArrayOf('\n'.code.toByte())
                var digits = (kv.size + 1).toString().length
                while ((kv.size + digits).toString().length != digits) digits++
                body.write((kv.size + digits).toString().toByteArray(StandardCharsets.US_ASCII))
                body.write(kv)
            }
            val data = body.toByteArray()
            entry("././@PaxHeader", 'x', data.size.toLong(), mtimeMillis, 420, 0, 0, "", "") { it.write(data) }
        }

        private fun pad(size: Long) {
            val rem = (size % BLOCK).toInt()
            if (rem != 0) out.write(ZERO, 0, BLOCK - rem)
            written += (size + BLOCK - 1) / BLOCK * BLOCK
        }

        /** Конец архива: два нулевых блока и выравнивание по записи 10240 байт, как у GNU tar. */
        fun finish() {
            out.write(ZERO)
            out.write(ZERO)
            written += 2 * BLOCK
            val rem = (written % RECORD).toInt()
            if (rem != 0) {
                var left = RECORD - rem
                while (left > 0) {
                    val n = minOf(left, BLOCK)
                    out.write(ZERO, 0, n)
                    left -= n
                }
                written += RECORD - rem
            }
            out.flush()
        }

        override fun close() = out.close()

        private fun put(h: ByteArray, off: Int, src: ByteArray, max: Int) {
            System.arraycopy(src, 0, h, off, minOf(src.size, max))
        }

        private fun truncate(b: ByteArray, max: Int): ByteArray = if (b.size <= max) b else b.copyOf(max)

        // Не влезает в восьмеричное поле — base-256, как у GNU tar
        private fun putNumber(h: ByteArray, off: Int, len: Int, value: Long) {
            val maxOctal = (1L shl (3 * (len - 1))) - 1
            if (value in 0..maxOctal) {
                val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
                System.arraycopy(s.toByteArray(StandardCharsets.US_ASCII), 0, h, off, len - 1)
                h[off + len - 1] = 0
            } else {
                var v = value
                for (i in len - 1 downTo 0) {
                    h[off + i] = (v and 0xff).toByte()
                    v = v shr 8
                }
                h[off] = if (value < 0) 0xff.toByte() else 0x80.toByte()
            }
        }

        // Путь длиннее 100 байт делится на prefix (до 155) и name (до 100) по «/»
        private fun splitUstar(b: ByteArray): Pair<ByteArray, ByteArray>? {
            if (b.size > 256) return null
            val hi = minOf(155, b.size - 2)
            val lo = maxOf(b.size - 101, 1)
            for (i in hi downTo lo) {
                if (b[i] == '/'.code.toByte()) {
                    return b.copyOfRange(0, i) to b.copyOfRange(i + 1, b.size)
                }
            }
            return null
        }
    }

    private class CountingOutput(private val inner: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            inner.write(b)
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }
        override fun flush() = inner.flush()
        override fun close() { /* поток архива закрывает Writer */ }
    }
}
