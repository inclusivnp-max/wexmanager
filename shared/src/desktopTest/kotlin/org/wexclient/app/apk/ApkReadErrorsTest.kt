package org.wexclient.app.apk

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkReadErrorsTest {
    private val root = Files.createTempDirectory("wex-apk-errors")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `unreadable archive is not treated as an empty APK`() {
        val path = Files.write(root.resolve("broken.apk"), byteArrayOf(1, 2, 3))
        assertFailsWith<IOException> { ApkDiff.entries(path) }
    }

    @Test
    fun `invalid dex is not treated as an APK without classes`() {
        assertFailsWith<IOException> { ApkDexDiff.read(listOf(byteArrayOf(1, 2, 3))) }
    }

    @Test
    fun `invalid resources are not treated as an empty table`() {
        assertFailsWith<IOException> { ApkResourceDiff.read(byteArrayOf(0, 1, 2, 3)) }
    }

    @Test
    fun `cancelled comparison does not parse another dex`() {
        assertFailsWith<CancellationException> {
            ApkDexDiff.read(listOf(byteArrayOf(1, 2, 3)), cancelled = { true })
        }
    }

    @Test
    fun `APK without dex still has no classes`() {
        assertTrue(ApkDexDiff.read(emptyList()).isEmpty())
    }
}
