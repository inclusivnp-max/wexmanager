package org.wexclient.app.arsc

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArscParserTest {

    private fun buildPool(strings: List<String>): ByteArray {
        val encoded = strings.map { s ->
            val b = s.toByteArray(Charsets.UTF_8)
            byteArrayOf(s.length.toByte(), b.size.toByte()) + b + byteArrayOf(0)
        }
        val offsets = ArrayList<Int>()
        var acc = 0
        encoded.forEach { offsets.add(acc); acc += it.size }
        val buf = ByteBuffer.allocate(28 + 4 * strings.size + acc).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001); buf.putShort(28); buf.putInt(28 + 4 * strings.size + acc)
        buf.putInt(strings.size); buf.putInt(0); buf.putInt(0x100)
        buf.putInt(28 + 4 * strings.size); buf.putInt(0)
        offsets.forEach { buf.putInt(it) }
        encoded.forEach { buf.put(it) }
        return buf.array()
    }

    /** Config размером 64 байта; поля задаются по смещению внутри ResTable_config. */
    private fun config(vararg fields: Pair<Int, ByteArray>): ByteArray {
        val cfg = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(64).array()
        for ((off, bytes) in fields) bytes.copyInto(cfg, off)
        return cfg
    }

    private fun u16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /**
     * Type-чанк по раскладке AOSP: заголовок (20 + config) -> таблица оффсетов -> entry.
     * Оффсеты — от entriesStart. slots: байты таблицы оффсетов, entries: байты записей.
     */
    private fun typeChunk(typeId: Int, flags: Int, count: Int, cfg: ByteArray, slots: ByteArray, entries: ByteArray): ByteArray {
        val headerSize = 20 + cfg.size
        val entriesStart = headerSize + slots.size
        val size = entriesStart + entries.size
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0201); putShort(headerSize.toShort()); putInt(size)
            put(typeId.toByte()); put(flags.toByte()); putShort(0); putInt(count); putInt(entriesStart)
            put(cfg)
            put(slots)
            put(entries)
        }.array()
    }

    /** ResTable_entry (8 байт) + Res_value (8 байт). */
    private fun simpleEntry(key: Int, type: Int, data: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(8); putShort(0); putInt(key)
            putShort(8); put(0); put(type.toByte()); putInt(data)
        }.array()

    private fun ints(vararg v: Int): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putInt(it) } }.array()

    private fun arsc(
        globalStrings: List<String>,
        typeNames: List<String>,
        keyNames: List<String>,
        typeChunks: List<ByteArray>,
        corruptPoolOffsets: Boolean = false
    ): ByteArray {
        val global = buildPool(globalStrings)
        val typePool = buildPool(typeNames)
        val keyPool = buildPool(keyNames)

        val typeSpec = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0202); putShort(16); putInt(20)
            put(1); put(0); putShort(0); putInt(1)
            putInt(0)
        }.array()

        val pkgBody = typeChunks.fold(typePool + keyPool + typeSpec) { acc, c -> acc + c }
        val pkgSize = 288 + pkgBody.size
        val pkg = ByteBuffer.allocate(pkgSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0200); putShort(288); putInt(pkgSize)
            putInt(0x7f)
            "test".forEach { putChar(it) }
            repeat(128 - 4) { putChar(Char(0)) }
            // Протектор затирает оффсеты пулов — парсер уходит в скан региона
            putInt(if (corruptPoolOffsets) 0x7FFFFFF0 else 288); putInt(0)
            putInt(if (corruptPoolOffsets) 0x7FFFFFF0 else 288 + typePool.size); putInt(0)
            putInt(0)
            put(pkgBody)
        }.array()

        return ByteBuffer.allocate(12 + global.size + pkg.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0002); putShort(12); putInt(12 + global.size + pkg.size)
            putInt(1)
            put(global)
            put(pkg)
        }.array()
    }

    /** Минимальный валидный resources.arsc: 1 пакет, 1 тип string, 1 entry -> "hello". */
    private fun syntheticArsc(): ByteArray = arsc(
        globalStrings = listOf("hello", "world"),
        typeNames = listOf("string"),
        keyNames = listOf("app_name"),
        typeChunks = listOf(typeChunk(1, 0, 1, config(), ints(0), simpleEntry(0, 0x03, 0)))
    )

    @Test
    fun `synthetic arsc parses`() {
        val raw = syntheticArsc()
        println("synthetic size=${raw.size}")
        val table = ArscParser.parse(raw)
        assertEquals(listOf("hello", "world"), table.globalStrings)
        assertEquals(1, table.packages.size)
        val pkg = table.packages[0]
        assertEquals(0x7f, pkg.id)
        assertEquals("test", pkg.name)
        assertEquals(1, pkg.types.size)
        val t = pkg.types[0]
        assertEquals(1, t.id)
        assertEquals("string", t.name)
        assertEquals(1, t.entries.size)
        val e = t.entries[0]
        assertEquals("app_name", e.name)
        assertEquals(0x7F010000, e.resId)
        assertEquals("hello", e.displayValue(table.globalStrings))
        assertEquals(1, table.totalEntries)
    }

    @Test
    fun `string patch round trip`() {
        val doc = ArscDocument(syntheticArsc())
        assertEquals("hello", doc.getGlobalString(0))
        doc.setGlobalString(0, "hallo")
        assertEquals("hallo", doc.getGlobalString(0))
        assertTrue(doc.isDirty())
        // Перепарс видит правку в entry
        val re = ArscDocument(doc.toByteArray())
        assertEquals("hallo", re.table.packages[0].types[0].entries[0].displayValue(re.globalStrings))
        // Другая длина — отказ
        assertFailsWith<IllegalArgumentException> { doc.setGlobalString(0, "way too long") }
        assertFailsWith<IllegalArgumentException> { doc.setGlobalString(0, "x") }
    }

    @Test
    fun `offsets are relative to entriesStart and table follows header`() {
        // 3 записи: #0 отсутствует, #1 -> "world", #2 -> "hello"; entry лежат в обратном порядке
        val entries = simpleEntry(1, 0x03, 0) + simpleEntry(0, 0x03, 1)
        val raw = arsc(
            listOf("hello", "world"), listOf("string"), listOf("first", "second"),
            listOf(typeChunk(1, 0, 3, config(), ints(-1, 16, 0), entries))
        )
        val t = ArscParser.parse(raw).packages[0].types[0]
        val global = listOf("hello", "world")
        assertEquals(listOf(1, 2), t.entries.map { it.id })
        assertEquals("first" to "world", t.entries[0].name to t.entries[0].displayValue(global))
        assertEquals("second" to "hello", t.entries[1].name to t.entries[1].displayValue(global))
        assertEquals(0x7F010002, t.entries[1].resId)
    }

    @Test
    fun `fallback scan uses the same type layout`() {
        val entries = simpleEntry(1, 0x03, 0) + simpleEntry(0, 0x03, 1)
        val raw = arsc(
            listOf("hello", "world"), listOf("attr", "string"), listOf("first", "second"),
            listOf(typeChunk(2, 0, 3, config(), ints(-1, 16, 0), entries)),
            corruptPoolOffsets = true
        )
        val t = ArscParser.parse(raw).packages[0].types.single()
        assertEquals("string", t.name)
        assertEquals(listOf("first", "second"), t.entries.map { it.name })
        assertEquals(listOf(1, 0), t.entries.map { it.valueData })
    }

    @Test
    fun `sparse offset16 and compact entries`() {
        // FLAG_SPARSE: (idx u16, offset/4 u16)
        val sparse = typeChunk(
            1, 0x01, 2, config(),
            u16(5) + u16(0) + u16(9) + u16(4),
            simpleEntry(0, 0x03, 0) + simpleEntry(1, 0x03, 1)
        )
        // FLAG_OFFSET16: offset/4 u16, 0xFFFF — нет записи; вторая запись компактная (key u16, flags, data)
        val compact = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1); putShort((0x10 shl 8 or 0x08).toShort()); putInt(42)
        }.array()
        val offset16 = typeChunk(
            1, 0x02, 2, config(0 to ints(64), 29 to byteArrayOf(0x20)),
            u16(0xFFFF) + u16(0),
            compact
        )
        val raw = arsc(listOf("a", "b"), listOf("string"), listOf("k0", "k1"), listOf(sparse, offset16))
        val t = ArscParser.parse(raw).packages[0].types[0]
        val default = t.configs.first { it.qualifier.isEmpty() }
        assertEquals(listOf(5 to "k0", 9 to "k1"), default.entries.map { it.id to it.name })
        val night = t.configs.first { it.qualifier == "night" }
        val e = night.entries.single()
        assertEquals(Triple(1, "k1", 42), Triple(e.id, e.name, e.valueData))
        assertEquals(0x10, e.valueType)
    }

    @Test
    fun `config qualifiers use ResTable_config offsets`() {
        fun q(vararg fields: Pair<Int, ByteArray>): String {
            val cfg = config(*fields)
            return ArscParser.parseConfigQualifiers(cfg, 0, cfg.size)
        }
        assertEquals("", q())
        assertEquals("night", q(29 to byteArrayOf(0x20)))
        assertEquals("v23", q(24 to u16(23)))
        assertEquals("sw600dp", q(30 to u16(600)))
        assertEquals("h720dp", q(34 to u16(720)))
        assertEquals("ru-rRU-land-xhdpi-v21", q(8 to "ruRU".toByteArray(), 12 to byteArrayOf(2), 14 to u16(320), 24 to u16(21)))
        // "fil" упакован как 0xAD 0x05 (AOSP packLanguageOrRegion); без скрипта — старая форма, как у aapt
        assertEquals("fil", q(8 to byteArrayOf(0xAD.toByte(), 0x05)))
        assertEquals("b+sr+Latn", q(8 to "sr".toByteArray(), 36 to "Latn".toByteArray()))
        assertEquals("ldrtl-large-watch-xxhdpi", q(28 to byteArrayOf(0x83.toByte()), 29 to byteArrayOf(0x06), 14 to u16(480)))
    }

    @Test
    fun `dimension radix decoding`() {
        fun dim(mantissa: Int, radix: Int, unit: Int) = (mantissa shl 8) or (radix shl 4) or unit
        assertEquals("16dp", ArscParser.decodeDimension(dim(16, 0, 1)))
        assertEquals("1.5dp", ArscParser.decodeDimension(dim(192, 1, 1)))   // 1.5 * 2^7
        assertEquals("0.5sp", ArscParser.decodeDimension(dim(1 shl 14, 2, 2))) // 0.5 * 2^15
        assertEquals("-2px", ArscParser.decodeDimension(dim(-2, 0, 0)))
    }

    @Test
    fun `malformed rejected`() {
        assertFailsWith<IllegalArgumentException> { ArscParser.parse(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { ArscParser.parse(ByteArray(64)) }
    }

    @Test
    fun `real Ai Tools arsc parses`() {
        val apk = File("/home/zapahtravki/Загрузки/Telegram Desktop/Ai Tools_1.5.apk")
        if (!apk.exists()) {
            println("SKIP: нет APK на этой машине")
            return
        }
        val bytes = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("resources.arsc")).readBytes()
        }
        println("real arsc size=${bytes.size}")
        val doc = ArscDocument(bytes)
        println("packages=${doc.packages.map { "%02X:%s".format(it.id, it.name) }}")
        println("globalValid=${doc.globalValidCount} entries=${doc.totalEntries}")
        assertTrue(doc.packages.isNotEmpty())
        assertTrue(doc.packages.any { it.id == 0x7f })
        assertEquals("Ai Tools", doc.globalStrings[3])
        assertTrue(doc.globalValidCount in 1000..26747, "живых строк: ${doc.globalValidCount}")
        assertTrue(doc.totalEntries > 100, "записей мало: ${doc.totalEntries}")
        val pkg7f = doc.packages.first { it.id == 0x7f }
        println("types=${pkg7f.types.take(12).map { "${it.id}:${it.name}(${it.entries.size})" }}")
        val strType = pkg7f.types.firstOrNull { it.name == "string" }
        assertTrue(strType != null && strType.entries.isNotEmpty(), "тип string не resolved")
        val sample = strType.entries.first()
        println("sample entry=${sample.name} = ${sample.displayValue(doc.globalStrings).take(60)}")
        assertTrue(sample.name.isNotEmpty() && sample.name != "entry${sample.id}")

        // In-place патч на реальной строке ASCII той же длины
        val idx = doc.globalStrings.indexOfFirst { s -> s.length in 4..24 && s.all { it.code in 0x20..0x7E } }
        assertTrue(idx >= 0, "нет ASCII-строки для патча")
        val orig = doc.getGlobalString(idx)
        val patched = "X".repeat(orig.length)
        doc.setGlobalString(idx, patched)
        val re = ArscDocument(doc.toByteArray())
        assertEquals(patched, re.getGlobalString(idx))
        println("patched #$idx '$orig' -> '$patched' OK")

        // Проверка конфигураций и декомпиляции в XML (как в MT Manager)
        assertTrue(strType.configs.isNotEmpty(), "список конфигураций пуст")
        val defaultCfg = strType.configs.first()
        println("defaultCfg: title=${defaultCfg.configTitle}, path=${defaultCfg.fullXmlPath}, entries=${defaultCfg.entries.size}")
        val xml = defaultCfg.toXml(doc.currentGlobalStrings())
        assertTrue(xml.startsWith("<?xml version='1.0' encoding='utf-8' ?>"))
        assertTrue(xml.contains("<resources>"))
        assertTrue(xml.contains("<string name=\""))
        println("Sample XML:\n" + xml.lines().take(6).joinToString("\n"))

        val typeInfoXml = strType.toTypeInfoXml()
        assertTrue(typeInfoXml.contains("<type-info"))
        val pkgInfoXml = pkg7f.toPackageInfoXml()
        assertTrue(pkgInfoXml.contains("<package-info"))
    }
}
