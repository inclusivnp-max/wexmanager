package org.wexclient.app.util

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessRunnerTest {
    @BeforeTest
    fun requireShell() {
        assumeTrue(ProcessRunner.isAvailable("sh"))
    }

    @Test
    fun `output limit applies to a single long line`() {
        val result = ProcessRunner.run(listOf("sh", "-c", "printf 0123456789"), maxOutputChars = 5)
        assertTrue(result.ok)
        assertEquals("01234", result.output)
    }

    @Test
    fun `output beyond the limit is drained while input is written`() {
        val result = ProcessRunner.run(
            listOf("sh", "-c", "cat"),
            stdin = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() },
            maxOutputChars = 32,
            timeoutMs = 5_000
        )
        assertTrue(result.ok)
        assertEquals("x".repeat(32), result.output)
    }

    @Test
    fun `timeout also applies when child does not read stdin`() {
        val result = ProcessRunner.run(
            listOf("sh", "-c", "exec sleep 3"),
            stdin = ByteArray(2 * 1024 * 1024),
            timeoutMs = 100
        )
        assertTrue(result.timedOut)
        assertFalse(result.ok)
    }

    @Test
    fun `cancellation also applies when child does not read stdin`() {
        val result = ProcessRunner.run(
            listOf("sh", "-c", "exec sleep 1"),
            stdin = ByteArray(2 * 1024 * 1024),
            isCancelled = { true }
        )
        assertTrue(result.cancelled)
        assertFalse(result.ok)
    }

    @Test
    fun `line callback keeps receiving output after capture limit`() {
        val lines = mutableListOf<String>()
        val result = ProcessRunner.run(
            listOf("sh", "-c", "printf 'one\\ntwo\\nthree\\n'"),
            maxOutputChars = 0,
            onLine = { lines += it }
        )
        assertTrue(result.ok)
        assertEquals("", result.output)
        assertEquals(listOf("one", "two", "three"), lines)
    }

    @Test
    fun `nonzero exit status is preserved`() {
        val result = ProcessRunner.run(listOf("sh", "-c", "printf error; exit 7"))
        assertEquals(7, result.exitCode)
        assertFalse(result.ok)
    }
}
