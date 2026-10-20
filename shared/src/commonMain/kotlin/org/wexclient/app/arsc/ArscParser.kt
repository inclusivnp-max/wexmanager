package org.wexclient.app.arsc

import java.nio.ByteBuffer
import java.nio.ByteOrder

// --- Структуры -----------------------------------------------------------------

data class ArscEntry(
    val id: Int,
    val resId: Int,
    val name: String,
    val valueType: Int,
    val valueData: Int,
    val isComplex: Boolean
) {
    fun displayValue(globalPool: List<String>): String {
        if (isComplex) return "<bag> 0x" + Integer.toHexString(valueData)
        return when (valueType) {
            0x00 -> if (valueData == 0) "@null" else "0x" + Integer.toHexString(valueData)
            0x03 -> globalPool.getOrNull(valueData) ?: "@string/0x" + Integer.toHexString(valueData)
            0x01, 0x02, 0x07 -> "@0x" + Integer.toHexString(valueData).padStart(8, '0')
            0x10 -> valueData.toString()
            0x11 -> "0x" + Integer.toHexString(valueData)
            0x12 -> if (valueData != 0) "true" else "false"
            0x04 -> java.lang.Float.intBitsToFloat(valueData).toString()
            0x1c, 0x1d, 0x1e, 0x1f -> "#" + Integer.toHexString(valueData).padStart(8, '0').uppercase()
            else -> "0x" + Integer.toHexString(valueData) + " (тип 0x" + Integer.toHexString(valueType) + ")"
        }
    }
}

data class ArscConfig(
    val typeId: Int,
    val typeName: String,
    val qualifier: String,
    val configTitle: String,
    val fullXmlPath: String,
    val entries: List<ArscEntry>
) {
    fun toXml(globalPool: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version='1.0' encoding='utf-8' ?>")
        sb.appendLine("<resources>")
        for (e in entries) {
            val escapedName = ArscParser.escapeXml(e.name)
            val valStr = e.displayValue(globalPool)
            when (typeName) {
                "string" -> {
                    val rawText = globalPool.getOrNull(e.valueData) ?: valStr
                    sb.append("    <string name=\"").append(escapedName).append("\">")
                        .append(ArscParser.escapeXml(rawText))
                        .appendLine("</string>")
                }
                "color" -> {
                    val hexColor = if (e.valueType in 0x1c..0x1f) {
                        "#" + Integer.toHexString(e.valueData).padStart(8, '0').uppercase()
                    } else valStr
                    sb.append("    <color name=\"").append(escapedName).append("\">")
                        .append(hexColor)
                        .appendLine("</color>")
                }
                "dimen" -> {
                    val dimStr = if (e.valueType == 0x05) ArscParser.decodeDimension(e.valueData) else valStr
                    sb.append("    <dimen name=\"").append(escapedName).append("\">")
                        .append(dimStr)
                        .appendLine("</dimen>")
                }
                "bool" -> {
                    val bStr = if (e.valueType == 0x12) (if (e.valueData != 0) "true" else "false") else valStr
                    sb.append("    <bool name=\"").append(escapedName).append("\">")
                        .append(bStr)
                        .appendLine("</bool>")
                }
                "integer" -> {
                    val iStr = if (e.valueType == 0x10) e.valueData.toString() else valStr
                    sb.append("    <integer name=\"").append(escapedName).append("\">")
                        .append(iStr)
                        .appendLine("</integer>")
                }
                "id" -> {
                    sb.append("    <item type=\"id\" name=\"").append(escapedName).append("\" />\n")
                }
                else -> {
                    sb.append("    <item type=\"").append(typeName).append("\" name=\"").append(escapedName).append("\">")
                        .append(ArscParser.escapeXml(valStr))
                        .appendLine("</item>")
                }
            }
        }
        sb.appendLine("</resources>")
        return sb.toString()
    }
}

data class ArscType(
    val id: Int,
    val name: String,
    val entries: List<ArscEntry>,
    val configs: List<ArscConfig> = emptyList()
) {
    val resIdHigh: Int get() = id

    fun toTypeInfoXml(): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version='1.0' encoding='utf-8' ?>")
        sb.appendLine("<!-- Информация о типе ресурсов -->")
        sb.appendLine("<type-info id=\"0x%02x\" name=\"$name\" totalEntries=\"${entries.size}\">".format(id))
        sb.appendLine("    <configurations count=\"${configs.size}\">")
        for (c in configs) {
            val q = if (c.qualifier.isEmpty()) "default" else c.qualifier
            sb.appendLine("        <config name=\"$q\" entries=\"${c.entries.size}\" file=\"${c.fullXmlPath}\" />")
        }
        sb.appendLine("    </configurations>")
        sb.appendLine("</type-info>")
        return sb.toString()
    }
}

data class ArscPackage(
    val id: Int,
    val name: String,
    val types: List<ArscType>
) {
    fun toPackageInfoXml(): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version='1.0' encoding='utf-8' ?>")
        sb.appendLine("<!-- Информация о пакете ресурсов -->")
        sb.appendLine("<package-info id=\"0x%02x\" name=\"$name\" typesCount=\"${types.size}\">".format(id))
        sb.appendLine("    <types>")
        for (t in types) {
            sb.appendLine("        <type id=\"0x%02x\" name=\"${t.name}\" configs=\"${t.configs.size}\" entries=\"${t.entries.size}\" />".format(t.id))
        }
        sb.appendLine("    </types>")
        sb.appendLine("</package-info>")
        return sb.toString()
    }
}

data class ArscTable(
    val packages: List<ArscPackage>,
    val globalStrings: List<String>,
    val globalValidCount: Int = 0
) {
    val totalEntries: Int get() = packages.sumOf { p -> p.types.sumOf { it.entries.size } }
}

// --- Парсер --------------------------------------------------------------------

object ArscParser {

    private const val CHUNK_TABLE = 0x0002
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_PACKAGE = 0x0200
    private const val CHUNK_TYPE = 0x0201
    private const val CHUNK_TYPE_SPEC = 0x0202

    const val TYPE_STRING = 0x03

    // ResTable_type.flags
    private const val TYPE_FLAG_SPARSE = 0x01
    private const val TYPE_FLAG_OFFSET16 = 0x02

    // ResTable_entry.flags
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008

    private const val TYPE_HEADER_MIN = 20

    /**
     * Раскладка ResTable_type (AOSP ResourceTypes.h): таблица оффсетов идёт сразу
     * за заголовком (headerSize), сами entry — с entriesStart, и оффсеты считаются от него.
     */
    private class TypeLayout(val offsetsStart: Long, val entriesStart: Long, val flags: Int, val count: Int) {
        val slotSize: Int = when {
            flags and TYPE_FLAG_SPARSE != 0 -> 4
            flags and TYPE_FLAG_OFFSET16 != 0 -> 2
            else -> 4
        }

        /** (id записи, абсолютная позиция entry) или null, если записи нет в этой конфигурации. */
        fun slot(r: Reader, i: Int): Pair<Int, Long>? {
            val p = offsetsStart + i.toLong() * slotSize
            return when {
                flags and TYPE_FLAG_SPARSE != 0 -> {
                    val id = r.u16(p)
                    id to entriesStart + r.u16(p + 2) * 4L
                }
                flags and TYPE_FLAG_OFFSET16 != 0 -> {
                    val off = r.u16(p)
                    if (off == 0xFFFF) null else i to entriesStart + off * 4L
                }
                else -> {
                    val off = r.u32(p)
                    if (off == 0xFFFFFFFFL) null else i to entriesStart + off
                }
            }
        }
    }

    private fun typeLayout(r: Reader, chunkStart: Long, chunkSize: Long): TypeLayout? {
        val headerSize = r.u16(chunkStart + 2)
        val flags = r.u8(chunkStart + 9)
        val count = r.u32(chunkStart + 12).toInt()
        val entriesStart = r.u32(chunkStart + 16)
        if (count < 0 || count > 100000) return null
        if (entriesStart < TYPE_HEADER_MIN || entriesStart > chunkSize) return null
        val probe = TypeLayout(0, 0, flags, count)
        // Протектор может побить headerSize — тогда таблица стоит вплотную перед entry
        val offsetsRel = if (headerSize >= TYPE_HEADER_MIN && headerSize + count.toLong() * probe.slotSize <= entriesStart) {
            headerSize.toLong()
        } else {
            entriesStart - count.toLong() * probe.slotSize
        }
        if (offsetsRel < TYPE_HEADER_MIN) return null
        return TypeLayout(chunkStart + offsetsRel, chunkStart + entriesStart, flags, count)
    }

    /** Ключ (индекс в пуле имён) записи; компактная запись хранит его в первых 2 байтах. */
    private fun entryKey(r: Reader, ePos: Long): Int {
        val flags = r.u16(ePos + 2)
        return if (flags and ENTRY_FLAG_COMPACT != 0) r.u16(ePos) else r.u32(ePos + 4).toInt()
    }

    private class Reader(val bytes: ByteArray) {
        val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun ok(pos: Long, size: Int): Boolean = pos >= 0 && pos + size <= bytes.size.toLong()

        fun u16(pos: Int): Int = u16(pos.toLong())
        fun u32(pos: Int): Long = u32(pos.toLong())
        fun u8(pos: Int): Int = u8(pos.toLong())

        fun u16(pos: Long): Int {
            if (!ok(pos, 2)) throw IndexOutOfBoundsException("u16@$pos")
            return buf.getShort(pos.toInt()).toInt() and 0xFFFF
        }

        fun u32(pos: Long): Long {
            if (!ok(pos, 4)) throw IndexOutOfBoundsException("u32@$pos")
            return buf.getInt(pos.toInt()).toLong() and 0xFFFFFFFFL
        }

        fun u8(pos: Long): Int {
            if (!ok(pos, 1)) throw IndexOutOfBoundsException("u8@$pos")
            return bytes[pos.toInt()].toInt() and 0xFF
        }

        fun utf16String(pos: Long, maxChars: Int): String {
            if (!ok(pos, 2)) return ""
            val sb = StringBuilder()
            var p = pos
            var n = 0
            while (n < maxChars && ok(p, 2)) {
                val c = u16(p)
                if (c == 0) break
                sb.append(c.toChar())
                p += 2
                n++
            }
            return sb.toString()
        }

        fun chunkType(pos: Long): Int = u16(pos)
        fun chunkSize(pos: Long): Long = u32(pos + 4)
    }

    /** Метаданные string pool'а — нужны для in-place правок (см. ArscDocument). */
    data class PoolMeta(
        val strings: List<String>,
        val isUtf8: Boolean,
        /** Абсолютные оффсеты начал закодированных строк (индексы стабильны!). */
        val entryOffsets: IntArray,
        val chunkStart: Int,
        val chunkSize: Long,
        /** Правда протектора: min(declared, по раскладу). Остальное — мусор. */
        val realCount: Int,
        /** Сколько оффсетов реально указывают внутрь пула. */
        val validCount: Int,
        /** Поживая маска валидности оффсетов (индексы стабильны). */
        val valid: BooleanArray
    )

    private fun isValidOffset(stringsStart: Int, chunkSize: Long, off: Int): Boolean {
        if (off < 0) return false
        val abs = stringsStart.toLong() + off
        return abs >= 0 && abs < chunkSize
    }

    fun parsePool(bytes: ByteArray, poolStart: Int): PoolMeta {
        val r = Reader(bytes)
        val chunkSize = r.chunkSize(poolStart.toLong())
        require(r.chunkType(poolStart.toLong()) == CHUNK_STRING_POOL) { "Не string pool по оффсету $poolStart" }
        require(chunkSize in 28..bytes.size) { "Битый string pool" }

        val stringCount = r.u32(poolStart + 8).toInt()
        val styleCount = r.u32(poolStart + 12).toInt()
        val flags = r.u32(poolStart + 16).toInt()
        var stringsStart = r.u32(poolStart + 20).toInt()
        require(stringCount in 0..500000) { "Подозрительный stringCount=$stringCount" }
        require(styleCount in 0..100000) { "Подозрительный styleCount=$styleCount" }

        val isUtf8 = (flags and (1 shl 8)) != 0
        // stringsStart тоже могут побить: пробуем стандартный расклад как запасной
        var absStringsStart = poolStart + stringsStart
        if (absStringsStart < 0 || absStringsStart > bytes.size) {
            val std = 28 + 4 * (stringCount + styleCount)
            if (std >= 0 && poolStart + std <= bytes.size) {
                stringsStart = std
                absStringsStart = poolStart + std
            } else {
                throw IllegalArgumentException("stringsStart за файлом")
            }
        }

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = try {
                r.u32(poolStart + 28L + i * 4).toInt()
            } catch (_: Exception) {
                -1
            }
        }

        // Живые оффсеты (указывают внутрь пула). Индексы НЕ сжимаем —
        // на них ссылаются значения entry, иначе всё поедет.
        val valid = BooleanArray(stringCount) { i ->
            isValidOffset(stringsStart, chunkSize, offsets[i])
        }
        val validTotal = valid.count { it }

        val strings = ArrayList<String>(minOf(stringCount, 1024))
        // Полностью читаем только первые 20k (защита UI), оффсеты знаем для всех
        val readUpTo = minOf(stringCount, 20000)
        for (i in 0 until readUpTo) {
            val o = offsets[i]
            strings.add(
                if (isValidOffset(stringsStart, chunkSize, o)) {
                    readPoolString(bytes, absStringsStart + o, isUtf8)
                } else {
                    ""
                }
            )
        }
        // Остальные — лениво пустые заглушки, доберем по запросу через entryOffsets
        repeat(stringCount - readUpTo) { strings.add("") }

        val entryOffsets = IntArray(stringCount) { absStringsStart + offsets[it] }
        // Правда протектора: счётчик завышают, данные целы. Живых не больше,
        // чем влезает по раскладу (stringsStart == 28 + 4*(count+style) у честных).
        val layoutMax = ((stringsStart - 28) / 4 - styleCount).coerceAtLeast(0)
        val realCount = minOf(stringCount, layoutMax)
        return PoolMeta(strings, isUtf8, entryOffsets, poolStart, chunkSize, realCount, validTotal, valid)
    }

    /** Читает одну строку пула по абсолютному оффсету (UTF-8 или UTF-16). */
    fun readPoolString(bytes: ByteArray, absPos: Int, isUtf8: Boolean): String {
        return try {
            if (absPos < 0 || absPos >= bytes.size) return ""
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(absPos)
            if (isUtf8) {
                var len = buf.get().toInt() and 0xFF
                if (len and 0x80 != 0) {
                    if (!buf.hasRemaining()) return ""
                    len = ((len and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                }
                var byteLen = buf.get().toInt() and 0xFF
                if (byteLen and 0x80 != 0) {
                    if (!buf.hasRemaining()) return ""
                    byteLen = ((byteLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                }
                if (byteLen < 0 || byteLen > buf.remaining()) return ""
                val arr = ByteArray(byteLen)
                buf.get(arr)
                String(arr, Charsets.UTF_8)
            } else {
                if (buf.remaining() < 2) return ""
                var charLen = buf.short.toInt() and 0xFFFF
                if (charLen and 0x8000 != 0) {
                    if (buf.remaining() < 2) return ""
                    charLen = ((charLen and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
                }
                if (charLen < 0 || charLen.toLong() * 2 > buf.remaining()) return ""
                val chars = CharArray(charLen)
                for (c in 0 until charLen) chars[c] = buf.short.toInt().toChar()
                String(chars)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun parse(bytes: ByteArray): ArscTable {
        require(bytes.size >= 12) { "Файл короче заголовка таблицы" }
        val r = Reader(bytes)
        require(r.chunkType(0) == CHUNK_TABLE) { "Не resources.arsc (магия 0x0002)" }
        val fileSize = r.chunkSize(0)
        val packageCount = r.u32(8).toInt()
        require(packageCount in 1..16) { "Подозрительный packageCount=$packageCount" }

        // Глобальный string pool идёт сразу за заголовком таблицы
        val globalMeta = parsePool(bytes, 12)
        val globalStrings = resolveAll(bytes, globalMeta)

        val packages = ArrayList<ArscPackage>()
        var pos = 12L + r.chunkSize(12)
        val end = minOf(fileSize, bytes.size.toLong())
        var guard = 0
        while (pos + 8 <= end && guard++ < 64) {
            val type: Int
            val size: Long
            try {
                type = r.chunkType(pos)
                size = r.chunkSize(pos)
            } catch (_: Exception) {
                break
            }
            if (size < 8 || pos + size > end + 1) break
            if (type == CHUNK_PACKAGE) {
                try {
                    packages.add(parsePackage(bytes, pos.toInt()))
                } catch (_: Exception) {
                    // Битый пакет пропускаем
                }
            }
            if (size <= 0) break
            pos += size
        }
        // Запасной путь: протектор затёр размер package-чанка (мусор вместо size) —
        // парсим пакет с клампом до конца файла.
        if (packages.isEmpty()) {
            var p = 12L + try { r.chunkSize(12) } catch (_: Exception) { 0L }
            var guard2 = 0
            while (p + 8 <= end && guard2++ < 64) {
                val t: Int
                try {
                    t = r.chunkType(p)
                } catch (_: Exception) {
                    break
                }
                if (t == CHUNK_PACKAGE) {
                    try {
                        packages.add(parsePackage(bytes, p.toInt(), end))
                    } catch (_: Exception) {
                        // не вышло и так
                    }
                    break
                }
                // Размер чанка тоже может быть битым — шагаем осторожно
                val s: Long = try { r.chunkSize(p) } catch (_: Exception) { 0L }
                if (s < 8 || p + s > end + 1 || s > 100_000_000L) break
                p += s
            }
        }
        require(packages.isNotEmpty()) { "Пакеты не найдены" }
        return ArscTable(packages, globalStrings, globalMeta.validCount)
    }

    /** Дочитывает строки пула полностью (parsePool отдаёт первые 20k сразу). */
    fun resolveAll(bytes: ByteArray, meta: PoolMeta): List<String> {
        return List(meta.entryOffsets.size) { i ->
            val cached = if (i < meta.strings.size) meta.strings[i] else ""
            if (cached.isNotEmpty()) {
                cached
            } else if (i < meta.valid.size && meta.valid[i]) {
                // Мёртвые оффсеты не читаем — там мусор, а не строки
                val pos = meta.entryOffsets[i]
                if (pos in 0 until bytes.size) readPoolString(bytes, pos, meta.isUtf8) else ""
            } else {
                ""
            }
        }
    }

    private fun parsePackage(bytes: ByteArray, pkgStart: Int, forcedEnd: Long? = null): ArscPackage {
        val r = Reader(bytes)
        val pkgSize = try { r.chunkSize(pkgStart.toLong()) } catch (_: Exception) { 0L }
        val pkgId = try { r.u32(pkgStart + 8).toInt() } catch (_: Exception) { 0x7f }
        val pkgName = try { r.utf16String(pkgStart + 12L, 128) } catch (_: Exception) { "" }
        // Кламп конца: размер чанка мог побить протектор
        val pkgEnd = minOf(
            forcedEnd ?: (pkgStart + pkgSize),
            bytes.size.toLong()
        )

        // 1. Пулы по шапке (честный путь)
        var typeNames: List<String>? = null
        var keyNames: List<String>? = null
        try {
            // ResTable_package: header(8) + id(4) + name[128 x char16_t](256) + typeStrings + lastPublicType + keyStrings
            val typeStringsOff = r.u32(pkgStart + 12L + 256).toInt()
            val keyStringsOff = r.u32(pkgStart + 12L + 256 + 8).toInt()
            if (typeStringsOff in 0..bytes.size && keyStringsOff in 0..bytes.size) {
                val tp = parsePool(bytes, pkgStart + typeStringsOff)
                val kp = parsePool(bytes, pkgStart + keyStringsOff)
                if (tp.validCount > 0 && kp.validCount > 0) {
                    typeNames = resolveAll(bytes, tp)
                    keyNames = resolveAll(bytes, kp)
                }
            }
        } catch (_: Exception) {
            // мимо — ниже нечёткий поиск
        }

        // 2. Фолбэк: оффсеты пулов побиты — ищем пулы и type-чанки сканом региона
        val configsByType = LinkedHashMap<Int, MutableList<ArscConfig>>()
        if (typeNames == null || keyNames == null) {
            val pools = scanPools(bytes, pkgStart, pkgEnd)
            if (pools.isNotEmpty()) {
                val typePool = pickTypePool(bytes, pools) ?: pools.minByOrNull { it.second.realCount }?.second
                val keyPool = pools.filter { it.second !== typePool }
                    .maxByOrNull { it.second.realCount }?.second
                if (typePool != null) typeNames = resolveAll(bytes, typePool)
                if (keyPool != null) keyNames = resolveAll(bytes, keyPool)
            }
            if (typeNames == null) typeNames = emptyList()
            if (keyNames == null) keyNames = emptyList()
            mergeScannedTypes(bytes, pkgStart, pkgEnd, pkgId, pkgName, typeNames, keyNames, configsByType)
        } else {
            // Честный путь: шагаем по чанкам от заголовка пакета
            val pkgHeaderSize = try { r.u16(pkgStart + 2) } catch (_: Exception) { 544 }
            var pos = pkgStart.toLong() + maxOf(pkgHeaderSize, 16)
            var guard = 0
            while (pos + 8 <= pkgEnd && guard++ < 4096) {
                val type: Int
                val size: Long
                try {
                    type = r.chunkType(pos)
                    size = r.chunkSize(pos)
                } catch (_: Exception) {
                    break
                }
                if (size < 8 || pos + size > pkgEnd + 1) break
                if (type == CHUNK_TYPE) {
                    try {
                        parseTypeChunk(bytes, pos.toInt(), pkgId, pkgName, typeNames, keyNames)?.let { (typeId, cfg) ->
                            configsByType.getOrPut(typeId) { ArrayList() }.add(cfg)
                        }
                    } catch (_: Exception) {
                        // Битый type-чанк пропускаем
                    }
                }
                if (size <= 0) break
                pos += size
            }
        }

        val types = configsByType.map { (typeId, configList) ->
            val typeName = typeNames.getOrNull(typeId - 1)?.ifEmpty { "type$typeId" } ?: "type$typeId"
            // Дефолтная конфигурация первой, затем по алфавиту
            val sortedConfigs = configList.sortedWith(compareBy({ it.qualifier.isNotEmpty() }, { it.qualifier }))
            // Дедупликация по entryId: значение первой (дефолтной) конфигурации
            val dedup = LinkedHashMap<Int, ArscEntry>()
            for (cfg in sortedConfigs) {
                for (e in cfg.entries) dedup.putIfAbsent(e.id, e)
            }
            ArscType(typeId, typeName, dedup.values.toList(), sortedConfigs)
        }.sortedBy { it.id }

        return ArscPackage(pkgId, pkgName.ifEmpty { "package_%02X".format(pkgId) }, types)
    }

    private val KNOWN_TYPE_WORDS = setOf(
        "attr", "drawable", "layout", "anim", "animator", "color", "dimen",
        "string", "style", "id", "bool", "integer", "array", "plurals",
        "mipmap", "raw", "xml", "menu", "font", "interpolator", "transition",
        "navigation", "declare-styleable"
    )

    /**
     * Нечёткий поиск string pool'ов в регионе: ищем сигнатуру чанка и проверяем
     * когерентность первых строк (защита от случайных совпадений байт).
     */
    private fun scanPools(bytes: ByteArray, from: Int, to: Long): List<Pair<Int, PoolMeta>> {
        val out = ArrayList<Pair<Int, PoolMeta>>()
        var p = maxOf(from, 0)
        val end = minOf(to, bytes.size.toLong())
        var guard = 0
        while (p + 28 <= end && guard++ < 3_000_000) {
            if (bytes[p] == 0x01.toByte() && bytes[p + 1] == 0.toByte() &&
                bytes[p + 2] == 0x1C.toByte() && bytes[p + 3] == 0.toByte()
            ) {
                try {
                    val meta = parsePool(bytes, p)
                    if (meta.realCount >= 2 && poolLooksSane(bytes, meta)) {
                        out.add(p to meta)
                    }
                } catch (_: Exception) {
                    // не пул
                }
            }
            p++
        }
        return out
    }

    private fun poolLooksSane(bytes: ByteArray, meta: PoolMeta): Boolean {
        var good = 0
        var checked = 0
        for (i in 0 until minOf(meta.realCount, 10)) {
            val pos = meta.entryOffsets[i]
            if (pos < 0 || pos >= bytes.size) continue
            val s = readPoolString(bytes, pos, meta.isUtf8)
            checked++
            if (s.isNotEmpty() && s.length < 300 && s.none { it == '\u0000' }) good++
        }
        return checked >= 2 && good.toDouble() / checked >= 0.5
    }

    private fun pickTypePool(
        bytes: ByteArray,
        pools: List<Pair<Int, PoolMeta>>
    ): PoolMeta? {
        var best: PoolMeta? = null
        var bestHits = 0
        for ((_, meta) in pools) {
            if (meta.realCount <= 0 || meta.realCount > 2000) continue
            var hits = 0
            for (i in 0 until minOf(meta.realCount, 60)) {
                val pos = meta.entryOffsets[i]
                if (pos < 0 || pos >= bytes.size) continue
                if (readPoolString(bytes, pos, meta.isUtf8) in KNOWN_TYPE_WORDS) hits++
            }
            if (hits > bestHits) {
                bestHits = hits
                best = meta
            }
        }
        return if (bestHits >= 2) best else null
    }

    /**
     * Побайтовый скан Type-чанков со строгой валидацией:
     * первая запись обязана ссылаться в пул ключей.
     */
    private fun mergeScannedTypes(
        bytes: ByteArray,
        from: Int,
        to: Long,
        pkgId: Int,
        pkgName: String,
        typeNames: List<String>,
        keyNames: List<String>,
        out: MutableMap<Int, MutableList<ArscConfig>>
    ) {
        val end = minOf(to, bytes.size.toLong())
        var pos = maxOf(from, 0).toLong()
        var guard = 0
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val reader = Reader(bytes)
        fun u16a(p: Long): Int = buf.getShort(p.toInt()).toInt() and 0xFFFF
        fun u32a(p: Long): Long = buf.getInt(p.toInt()).toLong() and 0xFFFFFFFFL
        while (pos + 24 <= end && guard++ < 3_000_000) {
            var consumed = false
            try {
                if (u16a(pos) == CHUNK_TYPE) {
                    val size = u32a(pos + 4)
                    val typeId = buf.get(pos.toInt() + 8).toInt() and 0xFF
                    val entryCount = u32a(pos + 12).toInt()
                    val layout = if (size in 24..end - pos) typeLayout(reader, pos, size) else null
                    if (layout != null && typeId in 1..255 && entryCount in 1..100000) {
                        // Строгая проверка: первая существующая запись резолвится в пул ключей
                        val first = (0 until minOf(entryCount, 64)).firstNotNullOfOrNull { layout.slot(reader, it) }
                        if (first != null && first.second + 8 <= pos + size) {
                            val keyIdx = entryKey(reader, first.second)
                            val keysOk = keyNames.isEmpty() ||
                                    (keyIdx in keyNames.indices &&
                                            keyNames[keyIdx].isNotEmpty() &&
                                            keyNames[keyIdx].length < 256)
                            if (keysOk) {
                                parseTypeChunk(bytes, pos.toInt(), pkgId, pkgName, typeNames, keyNames)
                                    ?.let { (tid, cfg) ->
                                        out.getOrPut(tid) { ArrayList() }.add(cfg)
                                    }
                                pos += size
                                consumed = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // мимо
            }
            if (!consumed) pos++
        }
    }

    /**
     * Возвращает (typeId, ArscConfig). Оффсеты entry — от entriesStart (AOSP ResTable_type).
     */
    private fun parseTypeChunk(
        bytes: ByteArray,
        chunkStart: Int,
        pkgId: Int,
        pkgName: String,
        typeNames: List<String>,
        keyNames: List<String>
    ): Pair<Int, ArscConfig>? {
        val r = Reader(bytes)
        val typeId = r.u8(chunkStart + 8L)
        if (typeId <= 0 || typeId > 255) return null
        val chunkSize = r.u32(chunkStart + 4L)
        val chunkEnd = minOf(chunkStart + chunkSize, bytes.size.toLong())
        val layout = typeLayout(r, chunkStart.toLong(), chunkSize) ?: return null
        val entryCount = layout.count

        val typeName = typeNames.getOrNull(typeId - 1)?.ifEmpty { "type$typeId" } ?: "type$typeId"
        val cfgSize = try { r.u32(chunkStart + 20L).toInt() } catch (_: Exception) { 0 }
        val qualifier = if (cfgSize in 4..128 && chunkStart + 20 + cfgSize <= bytes.size) {
            parseConfigQualifiers(bytes, chunkStart + 20, cfgSize)
        } else {
            ""
        }
        val configTitle = if (qualifier.isEmpty()) typeName else "$typeName-$qualifier"
        val fullXmlPath = "$pkgName/$configTitle.xml"

        val out = ArrayList<ArscEntry>()
        for (i in 0 until minOf(entryCount, 20000)) {
            val slot = try {
                layout.slot(r, i)
            } catch (_: Exception) {
                break
            } ?: continue // нет в этой конфигурации
            val (entryId, ePos) = slot
            if (ePos + 8 > chunkEnd) continue
            try {
                val flags = r.u16(ePos + 2)
                val keyIndex = entryKey(r, ePos)
                val name = keyNames.getOrNull(keyIndex)?.ifEmpty { "entry$entryId" } ?: "entry$entryId"
                val resId = (pkgId shl 24) or (typeId shl 16) or entryId
                when {
                    flags and ENTRY_FLAG_COMPACT != 0 -> {
                        // Компактная запись: тип значения в старшем байте flags, данные — следом
                        out.add(ArscEntry(entryId, resId, name, flags ushr 8, r.u32(ePos + 4).toInt(), false))
                    }
                    flags and ENTRY_FLAG_COMPLEX != 0 -> {
                        out.add(ArscEntry(entryId, resId, name, -1, 0, true))
                    }
                    else -> {
                        val entrySize = r.u16(ePos)
                        if (entrySize < 8) continue
                        // Res_value идёт сразу за заголовком записи (его размер — entrySize)
                        val vPos = ePos + entrySize
                        if (vPos + 8 > chunkEnd) continue
                        val valSize = r.u16(vPos)
                        val valType = r.u8(vPos + 3)
                        val valData = r.u32(vPos + 4).toInt()
                        if (valSize < 8) continue
                        out.add(ArscEntry(entryId, resId, name, valType, valData, false))
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return typeId to ArscConfig(typeId, typeName, qualifier, configTitle, fullXmlPath, out)
    }

    fun parseConfigQualifiers(bytes: ByteArray, cfgPos: Int, cfgSize: Int): String {
        if (cfgSize < 4 || cfgPos + cfgSize > bytes.size) return ""
        val r = Reader(bytes)
        val mcc = r.u16(cfgPos + 4L)
        val mnc = r.u16(cfgPos + 6L)
        val l0 = r.u8(cfgPos + 8L)
        val l1 = r.u8(cfgPos + 9L)
        val r0 = r.u8(cfgPos + 10L)
        val r1 = r.u8(cfgPos + 11L)

        // Language
        val lang = when {
            l0 == 0 -> ""
            (l0 and 0x80) != 0 -> {
                // Упакованный 3-буквенный код: первая буква — в младших битах второго байта
                val c0 = ((l1 and 0x1F) + 'a'.code).toChar()
                val c1 = ((((l0 and 0x03) shl 3) or ((l1 and 0xE0) shr 5)) + 'a'.code).toChar()
                val c2 = (((l0 and 0x7C) shr 2) + 'a'.code).toChar()
                "$c0$c1$c2"
            }
            else -> {
                val c0 = l0.toChar()
                val c1 = if (l1 != 0) l1.toChar() else ' '
                "$c0$c1".trim()
            }
        }

        // Region / Country
        val region = when {
            r0 == 0 -> ""
            (r0 and 0x80) != 0 -> {
                val d0 = ((r1 and 0x1F) + '0'.code).toChar()
                val d1 = ((((r1 and 0xE0) shr 5) or ((r0 and 0x03) shl 3)) + '0'.code).toChar()
                val d2 = (((r0 and 0x7C) shr 2) + '0'.code).toChar()
                "$d0$d1$d2"
            }
            else -> {
                val c0 = r0.toChar()
                val c1 = if (r1 != 0) r1.toChar() else ' '
                "$c0$c1".trim()
            }
        }

        // Смещения — ResTable_config (AOSP ResourceTypes.h)
        fun has(end: Int) = cfgSize >= end && cfgPos + end <= bytes.size
        fun u8At(off: Int) = if (has(off + 1)) r.u8(cfgPos + off.toLong()) else 0
        fun u16At(off: Int) = if (has(off + 2)) r.u16(cfgPos + off.toLong()) else 0
        fun asciiAt(off: Int, len: Int) =
            if (has(off + len)) (0 until len).map { r.u8(cfgPos + off.toLong() + it).toChar() }
                .joinToString("").trimEnd('\u0000').trim() else ""

        // localeScript@36, localeVariant@40, localeScriptWasComputed@52, localeNumberingSystem@53
        val script = asciiAt(36, 4)
        val variant = asciiAt(40, 8)
        val scriptWasComputed = u8At(52) != 0
        val numbering = asciiAt(53, 8)
        val scriptProvided = script.isNotEmpty() && !scriptWasComputed

        val parts = ArrayList<String>()
        if (mcc != 0) parts.add("mcc$mcc")
        if (mnc != 0) parts.add(if (mnc == 0xFFFF) "mnc00" else "mnc$mnc")

        // Как aapt: BCP 47 (b+…) только при явном скрипте/варианте/системе счисления
        if (lang.isNotEmpty()) {
            if (!scriptProvided && variant.isEmpty() && numbering.isEmpty()) {
                parts.add(if (region.isNotEmpty()) "$lang-r$region" else lang)
            } else {
                val bcp = mutableListOf("b", lang)
                if (scriptProvided) bcp.add(script)
                if (region.isNotEmpty()) bcp.add(region)
                if (variant.isNotEmpty()) bcp.add(variant)
                if (numbering.isNotEmpty()) bcp.addAll(listOf("u", "nu", numbering))
                parts.add(bcp.joinToString("+"))
            }
        }

        // Дальше порядок aapt (ResTable_config::toString)
        val screenLayout = u8At(28)
        when (screenLayout and 0xC0) {
            0x40 -> parts.add("ldltr")
            0x80 -> parts.add("ldrtl")
        }
        u16At(30).let { if (it != 0) parts.add("sw${it}dp") }
        u16At(32).let { if (it != 0) parts.add("w${it}dp") }
        u16At(34).let { if (it != 0) parts.add("h${it}dp") }
        when (screenLayout and 0x0F) {
            1 -> parts.add("small")
            2 -> parts.add("normal")
            3 -> parts.add("large")
            4 -> parts.add("xlarge")
        }
        when (screenLayout and 0x30) {
            0x10 -> parts.add("notlong")
            0x20 -> parts.add("long")
        }
        val screenLayout2 = u8At(48)
        when (screenLayout2 and 0x03) {
            1 -> parts.add("notround")
            2 -> parts.add("round")
        }
        val colorMode = u8At(49)
        when (colorMode and 0x03) {
            1 -> parts.add("nowidecg")
            2 -> parts.add("widecg")
        }
        when (colorMode and 0x0C) {
            0x04 -> parts.add("lowdr")
            0x08 -> parts.add("highdr")
        }
        when (u8At(12)) {
            1 -> parts.add("port")
            2 -> parts.add("land")
            3 -> parts.add("square")
        }
        val uiMode = u8At(29)
        when (uiMode and 0x0F) {
            2 -> parts.add("desk")
            3 -> parts.add("car")
            4 -> parts.add("television")
            5 -> parts.add("appliance")
            6 -> parts.add("watch")
            7 -> parts.add("vrheadset")
        }
        when (uiMode and 0x30) {
            0x10 -> parts.add("notnight")
            0x20 -> parts.add("night")
        }
        val density = u16At(14)
        when (density) {
            0 -> {}
            120 -> parts.add("ldpi")
            160 -> parts.add("mdpi")
            213 -> parts.add("tvdpi")
            240 -> parts.add("hdpi")
            320 -> parts.add("xhdpi")
            480 -> parts.add("xxhdpi")
            640 -> parts.add("xxxhdpi")
            0xFFFE -> parts.add("anydpi")
            0xFFFF -> parts.add("nodpi")
            else -> parts.add("${density}dpi")
        }
        when (u8At(13)) {
            1 -> parts.add("notouch")
            2 -> parts.add("stylus")
            3 -> parts.add("finger")
        }
        val inputFlags = u8At(18)
        when (inputFlags and 0x03) {
            1 -> parts.add("keysexposed")
            2 -> parts.add("keyshidden")
            3 -> parts.add("keyssoft")
        }
        when (u8At(16)) {
            1 -> parts.add("nokeys")
            2 -> parts.add("qwerty")
            3 -> parts.add("12key")
        }
        when (inputFlags and 0x0C) {
            0x04 -> parts.add("navexposed")
            0x08 -> parts.add("navhidden")
        }
        when (u8At(17)) {
            1 -> parts.add("nonav")
            2 -> parts.add("dpad")
            3 -> parts.add("trackball")
            4 -> parts.add("wheel")
        }
        val screenW = u16At(20)
        val screenH = u16At(22)
        if (screenW != 0 || screenH != 0) parts.add("${screenW}x$screenH")
        val sdk = u16At(24)
        if (sdk != 0) parts.add("v$sdk")

        return parts.joinToString("-")
    }

    fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun unescapeXml(text: String): String {
        return text.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    fun decodeDimension(data: Int): String {
        val complexUnitMask = 0xF
        val units = arrayOf("px", "dp", "sp", "pt", "in", "mm")
        val unit = units.getOrNull(data and complexUnitMask) ?: "dp"
        val radix = (data ushr 4) and 0x3
        val radixShift = intArrayOf(0, 7, 15, 23)[radix]
        // Мантисса — знаковые старшие 24 бита
        val mantissa = (data shr 8).toDouble()
        val value = mantissa / (1 shl radixShift)
        if (value % 1.0 == 0.0) return "${value.toLong()}$unit"
        val text = java.math.BigDecimal(value).setScale(6, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
        return "$text$unit"
    }
}
