package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveConcurrencyTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_arch_conc")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `parallel writes into one tar archive keep everything`() {
        val seed = Files.createDirectory(dir.resolve("seed"))
        Files.writeString(seed.resolve("keep.txt"), "keep")
        val archive = dir.resolve("box.tar.gz")
        vfs.compressToNewFile(listOf(seed.resolve("keep.txt")), archive, "tar_gz")
        val root = vfs.openArchive(archive)

        val big = dir.resolve("big.bin").also { Files.write(it, Random(1).nextBytes(8 shl 20)) }
        val small = (1..5).map { i -> dir.resolve("s$i.txt").also { Files.writeString(it, "small $i") } }

        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        pool.submit {
            start.await()
            runCatching { FileTransfers.transfer(vfs, listOf(big), vfs.live(root), move = false).error?.let { throw it } }.onFailure { errors.add(it) }
        }
        pool.submit {
            start.await()
            runCatching {
                for (f in small) FileTransfers.transfer(vfs, listOf(f), vfs.live(root), move = false).error?.let { throw it }
            }.onFailure { errors.add(it) }
        }
        pool.submit {
            start.await()
            runCatching { vfs.createDirectory(vfs.live(root), "new-folder") }.onFailure { errors.add(it) }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(emptyList(), errors.map { it.toString() })

        vfs.closeAll()
        val listing = ProcessBuilder("tar", "-tzf", archive.toString()).start().inputStream.readAllBytes().decodeToString()
            .lines().filter { it.isNotBlank() }.map { it.trimEnd('/') }.toSet()
        assertEquals(setOf("keep.txt", "big.bin", "s1.txt", "s2.txt", "s3.txt", "s4.txt", "s5.txt", "new-folder"), listing)
    }
}
