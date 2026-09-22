package org.wexclient.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FileChecksumTest {

    @Test
    fun testEmptyBytesHashes() {
        val empty = ByteArray(0)
        val hashes = FileChecksumEngine.computeHashes(empty)

        // Well-known standard hashes for empty input:
        // MD5: d41d8cd98f00b204e9800998ecf8427e
        // SHA-1: da39a3ee5e6b4b0d3255bfef95601890afd80709
        // SHA-256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        // CRC32: 00000000
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hashes.md5.lowercase())
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hashes.sha1.lowercase())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hashes.sha256.lowercase())
        assertEquals("00000000", hashes.crc32)
    }

    @Test
    fun testKnownStringHashes() {
        val bytes = "Hello, World!".toByteArray(Charsets.UTF_8)
        val hashes = FileChecksumEngine.computeHashes(bytes)

        // MD5("Hello, World!") = 65a8e27d8879283831b664bd8b7f0ad4
        // SHA-1("Hello, World!") = 0a0a9f2a6772942557ab5355d76af442f8f65e01
        // SHA-256("Hello, World!") = dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f
        // CRC32("Hello, World!") = EC4AC3D0
        assertEquals("65a8e27d8879283831b664bd8b7f0ad4", hashes.md5.lowercase())
        assertEquals("0a0a9f2a6772942557ab5355d76af442f8f65e01", hashes.sha1.lowercase())
        assertEquals("dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", hashes.sha256.lowercase())
        assertEquals("EC4AC3D0", hashes.crc32.uppercase())
    }
}
