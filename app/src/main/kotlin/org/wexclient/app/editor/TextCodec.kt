package org.wexclient.app.editor

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class LineSeparator(val value: String, val label: String, val title: String) {
    LF("\n", "LF", "LF — Linux/macOS (\\n)"),
    CRLF("\r\n", "CRLF", "CRLF — Windows (\\r\\n)"),
    CR("\r", "CR", "CR — старый Mac (\\r)")
}

/** Как файл лежит на диске: кодировка, BOM и перевод строк. */
data class TextFileFormat(
    val charset: Charset = Charsets.UTF_8,
    val bom: Boolean = false,
    val lineSeparator: LineSeparator = LineSeparator.LF
) {
    val label: String
        get() = charsetLabel(charset) + if (bom) " BOM" else ""

    companion object {
        fun charsetLabel(cs: Charset): String = when (cs.name()) {
            "windows-1251" -> "Windows-1251"
            "windows-1252" -> "Windows-1252"
            "IBM866" -> "CP866"
            else -> cs.name()
        }
    }
}

class DecodedText(val text: String, val format: TextFileFormat, val hadMalformedInput: Boolean)

object TextCodec {
    private fun cs(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    /** Кодировки для меню «Открыть заново в…» / «Сохранять в…». */
    val menuCharsets: List<Charset> = listOfNotNull(
        Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE, cs("windows-1251"), cs("KOI8-R"), cs("IBM866"),
        cs("ISO-8859-5"), cs("x-MacCyrillic"), cs("KOI8-U"), Charsets.ISO_8859_1, cs("windows-1252"), Charsets.US_ASCII,
        cs("GBK"), cs("Big5"), cs("Shift_JIS"), cs("EUC-JP"), cs("EUC-KR"), cs("windows-1250"), cs("ISO-8859-2")
    )

    /** Декодирование с определением BOM, UTF-8/UTF-16 и кириллических однобайтовых кодировок. */
    fun decode(bytes: ByteArray, forced: Charset? = null): DecodedText {
        var offset = 0
        var bom = false
        var charset: Charset? = null
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> {
                offset = 3; bom = true; charset = Charsets.UTF_8
            }
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                offset = 2; bom = true; charset = Charsets.UTF_16LE
            }
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                offset = 2; bom = true; charset = Charsets.UTF_16BE
            }
        }
        if (forced != null) {
            // BOM другой кодировки не выбрасываем — пусть будет виден как текст
            if (charset != null && charset != forced) {
                offset = 0
                bom = false
            }
            charset = forced
        }
        if (charset == null) charset = guessUtf16(bytes)
        val body = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        if (charset == null) {
            val strict = tryDecodeStrict(body.duplicate(), Charsets.UTF_8)
            if (strict != null) return finish(strict, Charsets.UTF_8, false, false)
            charset = guessSingleByte(bytes)
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val text = decoder.decode(body).toString()
        val malformed = text.indexOf('�') >= 0
        return finish(text, charset, bom, malformed)
    }

    private fun finish(raw: String, charset: Charset, bom: Boolean, malformed: Boolean): DecodedText {
        val sep = detectLineSeparator(raw)
        return DecodedText(normalize(raw), TextFileFormat(charset, bom, sep), malformed)
    }

    private fun tryDecodeStrict(buf: ByteBuffer, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(buf).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    // UTF-16 без BOM: нули через байт в ASCII-тексте
    private fun guessUtf16(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 4096) and 1.inv()
        if (n < 8) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) {
            if (bytes[i] == 0.toByte()) if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        val half = n / 2
        return when {
            oddZeros > half * 0.4 && evenZeros < half * 0.05 -> Charsets.UTF_16LE
            evenZeros > half * 0.4 && oddZeros < half * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /**
     * Не UTF-8: выбираем между Windows-1251, KOI8-R и CP866 по диапазонам строчных букв
     * (в русском тексте строчных большинство), иначе — Windows-1252.
     */
    private fun guessSingleByte(bytes: ByteArray): Charset {
        var high = 0
        var cp1251 = 0
        var koi8 = 0
        var cp866 = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x80) continue
            high++
            if (v >= 0xE0) cp1251++
            if (v in 0xC0..0xDF) koi8++
            if (v in 0xA0..0xAF || v in 0xE0..0xEF) cp866++
        }
        if (high == 0) return Charsets.UTF_8
        val best = maxOf(cp1251, koi8, cp866)
        if (best * 10 < high * 4) return cs("windows-1252") ?: Charsets.ISO_8859_1
        return when (best) {
            cp1251 -> cs("windows-1251")
            koi8 -> cs("KOI8-R")
            else -> cs("IBM866")
        } ?: Charsets.ISO_8859_1
    }

    fun detectLineSeparator(text: String): LineSeparator {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\r' -> if (i + 1 < n && text[i + 1] == '\n') {
                    crlf++; i++
                } else cr++
                '\n' -> lf++
            }
            i++
        }
        return when {
            crlf > 0 && crlf >= lf && crlf >= cr -> LineSeparator.CRLF
            cr > lf && cr > crlf -> LineSeparator.CR
            else -> LineSeparator.LF
        }
    }

    /** Все переводы строк -> '\n' (в редакторе текст всегда с LF). */
    fun normalize(text: String): String {
        if (text.indexOf('\r') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\r') {
                sb.append('\n')
                if (i + 1 < n && text[i + 1] == '\n') i++
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun denormalize(text: String, sep: LineSeparator): String =
        if (sep == LineSeparator.LF) text else text.replace("\n", sep.value)

    /** Текст (с '\n') -> байты файла. Непредставимые символы — ошибка, а не тихая замена на '?'. */
    fun encode(text: String, format: TextFileFormat): ByteArray {
        val body = denormalize(text, format.lineSeparator)
        val encoder = format.charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = try {
            encoder.encode(CharBuffer.wrap(body))
        } catch (e: CharacterCodingException) {
            val bad = body.firstOrNull { !format.charset.newEncoder().canEncode(it) }
            val what = bad?.let { "«$it» (U+%04X)".format(it.code) } ?: "часть символов"
            throw IllegalStateException(
                "$what нельзя записать в кодировке ${TextFileFormat.charsetLabel(format.charset)} — выберите UTF-8 в строке состояния"
            )
        }
        val bomBytes = if (!format.bom) ByteArray(0) else when (format.charset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        val out = ByteArray(bomBytes.size + encoded.remaining())
        System.arraycopy(bomBytes, 0, out, 0, bomBytes.size)
        encoded.get(out, bomBytes.size, encoded.remaining())
        return out
    }

    /** Поддерживает ли кодировка BOM (для пункта меню). */
    fun supportsBom(cs: Charset) = cs == Charsets.UTF_8 || cs == Charsets.UTF_16LE || cs == Charsets.UTF_16BE
}
