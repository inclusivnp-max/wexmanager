package org.wexclient.app.audio

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.wexclient.app.util.ProcessRunner
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * closePlayer() — то, что зовут, когда закрыто последнее окно: после него не остаётся ни ffmpeg, ни открытой
 * звуковой линии — и когда играло, и на паузе, и когда закрыли посреди запуска. Играет тишина.
 */
class AudioPlayerReleaseTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        assumeTrue(ProcessRunner.which("ffmpeg") != null && ProcessRunner.which("ffprobe") != null, "нет ffmpeg")
        dir = Files.createTempDirectory("wex-audio")
        // Имя уникальное: по нему ищем свой ffmpeg среди процессов
        file = dir.resolve("silence-${System.nanoTime()}.wav")
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono", "-t", "60", file.toString())
            .redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        assertEquals(0, p.waitFor())
    }

    @AfterTest
    fun tearDown() {
        if (!::file.isInitialized) return
        AudioPlayerManager.closePlayer()
        ffmpegFor().forEach { it.destroyForcibly() }
        dir.toFile().deleteRecursively()
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun ffmpegFor(): List<ProcessHandle> =
        ProcessHandle.allProcesses().filter { h ->
            h.info().arguments().map { args -> file.toAbsolutePath().toString() in args }.orElse(false)
        }.toList()

    /** Открытые в этом процессе линии вывода — по всем устройствам. */
    private fun openLines(): Int = AudioSystem.getMixerInfo().sumOf { info ->
        runCatching { AudioSystem.getMixer(info).sourceLines.count { it is SourceDataLine && it.isOpen } }.getOrDefault(0)
    }

    private fun startPlaying() {
        AudioPlayerManager.play(VfsEntry.of(file))
        val started = waitFor(10_000) { ffmpegFor().isNotEmpty() && AudioPlayerManager.isPlaying && openLines() > 0 }
        assumeTrue(started, "воспроизведение не началось (нет звукового устройства?): ${AudioPlayerManager.playbackError}")
    }

    private fun assertReleased() {
        assertTrue(waitFor(3000) { ffmpegFor().isEmpty() }, "ffmpeg жив после closePlayer")
        assertTrue(waitFor(3000) { openLines() == 0 }, "звуковая линия открыта после closePlayer")
        assertFalse(AudioPlayerManager.isPlaying)
        assertNull(AudioPlayerManager.currentTrack)
        assertTrue(AudioPlayerManager.playlist.isEmpty())
    }

    @Test
    fun `closePlayer releases playing and paused playback`() {
        startPlaying()
        AudioPlayerManager.closePlayer()
        assertReleased()
        // Повторно и когда ничего не играет — тоже можно
        AudioPlayerManager.closePlayer()

        startPlaying()
        AudioPlayerManager.pause()
        Thread.sleep(300)
        AudioPlayerManager.closePlayer()
        assertReleased()
    }

    /** Свой дочерний процесс [name] (ffprobe плеера) — по имени файла в аргументах. */
    private fun child(name: String): ProcessHandle? {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) {
            val found = ProcessHandle.current().children().filter { h ->
                h.info().command().map { it.substringAfterLast('/') == name }.orElse(false) &&
                    h.info().arguments().map { args -> file.toAbsolutePath().toString() in args }.orElse(false)
            }.findFirst().orElse(null)
            if (found != null) return found
            Thread.onSpinWait()
        }
        return null
    }

    /**
     * Закрыли посреди запуска. Опаснее всего сразу после ffprobe: запуск уже прошёл проверку отмены и открывает
     * звуковое устройство, а stop() линии ещё не видит — раньше она так и оставалась открытой. Окно — миллисекунды:
     * закрываем плеер по выходу ffprobe с разной задержкой, часть попыток в него попадает.
     */
    @Test
    fun `closePlayer during start leaves nothing behind`() {
        for (attempt in 0 until 16) {
            AudioPlayerManager.play(VfsEntry.of(file))
            child("ffprobe")?.let { runCatching { it.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) } }
            val until = System.nanoTime() + (attempt % 8) * 1_000_000L
            while (System.nanoTime() < until) Thread.onSpinWait()
            AudioPlayerManager.closePlayer()
            // Запуск, не заметивший отмены, досчитывает до публикации линии и ffmpeg — ждём и его
            Thread.sleep(300)
            assertReleased()
        }
    }
}
