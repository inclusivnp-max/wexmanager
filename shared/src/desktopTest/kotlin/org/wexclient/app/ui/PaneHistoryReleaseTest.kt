package org.wexclient.app.ui

import androidx.compose.runtime.snapshots.Snapshot
import org.wexclient.app.vfs.VfsManager
import java.lang.ref.WeakReference
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** История «назад» не держит в памяти архив, которым давно не пользовались. */
class PaneHistoryReleaseTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_pane_history")
        Files.createDirectory(dir.resolve("sub"))
        ZipOutputStream(Files.newOutputStream(dir.resolve("a.zip"))).use { z ->
            z.putNextEntry(ZipEntry("inner/x.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
        }
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    // Отдельной функцией: ФС архива не остаётся в локальных переменных теста
    private fun visitArchive(state: FilePaneState): WeakReference<FileSystem> {
        val root = vfs.openArchive(dir.resolve("a.zip"))
        state.navigateTo(root)
        state.navigateTo(root.resolve("inner"))
        state.navigateTo(dir)
        return WeakReference(root.fileSystem)
    }

    @Test
    fun `pane history lets go of an idle archive`() {
        val state = FilePaneState(vfs, dir)
        try {
            val weak = visitArchive(state)
            state.navigateTo(dir.resolve("sub"))
            repeat(5) {
                System.gc()
                Thread.sleep(10)
            }
            assertNotNull(weak.get(), "недавний архив держится — возврат «назад» мгновенный")

            vfs.releaseIdle(System.currentTimeMillis() + VfsManager.IDLE_MS + 1)
            // Слушатель меняет историю в потоке окна
            javax.swing.SwingUtilities.invokeAndWait { }
            // «Назад»: sub → папка с архивом (три шага внутри архива стали ею же) → и дальше некуда
            state.goBack()
            assertEquals(dir, state.currentPath)
            assertFalse(state.canGoBack)

            // Прежние значения состояний Compose тоже не должны держать архив
            state.navigateTo(dir.resolve("sub"))
            state.navigateTo(dir)
            Snapshot.sendApplyNotifications()
            var tries = 0
            while (weak.get() != null && tries++ < 100) {
                System.gc()
                Thread.sleep(20)
            }
            assertTrue(weak.get() == null, "история держит отпущенный архив")
        } finally {
            state.dispose()
        }
    }
}
