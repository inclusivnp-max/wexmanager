package com.tim.dex.data

object DexMagic {
    val DEX_035 = "dex\n035\u0000".toByteArray()
    val DEX_037 = "dex\n037\u0000".toByteArray()
    val DEX_038 = "dex\n038\u0000".toByteArray()
    val DEX_039 = "dex\n039\u0000".toByteArray()
    val DEX_040 = "dex\n040\u0000".toByteArray()
    val DEX_041 = "dex\n041\u0000".toByteArray()
    val DEX_042 = "dex\n042\u0000".toByteArray()

    val ALL_VERSIONS = listOf(DEX_035, DEX_037, DEX_038, DEX_039, DEX_040, DEX_041, DEX_042)

    /** Первая версия с заголовком 0x78 байт (container_size, header_offset). */
    const val CONTAINER_VERSION = 41

    fun getVersion(magic: ByteArray): String = when {
        magic.contentEquals(DEX_035) -> "035"
        magic.contentEquals(DEX_037) -> "037"
        magic.contentEquals(DEX_038) -> "038"
        magic.contentEquals(DEX_039) -> "039"
        magic.contentEquals(DEX_040) -> "040"
        magic.contentEquals(DEX_041) -> "041"
        magic.contentEquals(DEX_042) -> "042"
        else -> "unknown"
    }

    fun isSupported(magic: ByteArray): Boolean = versionOf(magic) > 0

    /** Числовая версия (35, 37, ...) или `-1`, если magic не поддерживается. */
    fun versionOf(magic: ByteArray): Int {
        for (m in ALL_VERSIONS) {
            if (magic.contentEquals(m)) return (m[4] - '0'.code) * 100 + (m[5] - '0'.code) * 10 + (m[6] - '0'.code)
        }
        return -1
    }

    /** magic для числовой версии (копия, её можно менять). */
    fun magicOf(version: Int): ByteArray {
        for (m in ALL_VERSIONS) {
            if (versionOf(m) == version) return m.copyOf()
        }
        throw IllegalArgumentException("Неподдерживаемая версия DEX: $version")
    }

    /** Размер заголовка для версии. */
    fun headerSize(version: Int): Int = if (version >= CONTAINER_VERSION) 0x78 else 0x70

    /** Читаемое представление magic для сообщений об ошибках. */
    fun describe(magic: ByteArray): String =
        magic.joinToString(" ") { String.format("%02x", it.toInt() and 0xFF) }
}
