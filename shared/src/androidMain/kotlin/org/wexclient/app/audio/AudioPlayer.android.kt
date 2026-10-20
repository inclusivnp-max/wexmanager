package org.wexclient.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.wexclient.app.util.AppDirs
import org.wexclient.app.vfs.VfsEntry
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.sin

actual val AudioPlayerManager: AudioPlayer get() = AndroidAudioPlayer

/**
 * Android: декодирует и играет MediaPlayer (все форматы, что знает система). Файл из архива сначала
 * копируется в кэш — MediaPlayer читает только настоящие файлы. Всё — в главном потоке.
 */
object AndroidAudioPlayer : AudioPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var attempt = 0
    private var failedInRow = 0

    override var currentTrack by mutableStateOf<VfsEntry?>(null)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    override var isPaused by mutableStateOf(false)
        private set
    override var currentPositionMs by mutableStateOf(0L)
        private set
    override var positionSeconds by mutableStateOf(0L)
        private set
    override var totalDurationMs by mutableStateOf(0L)
        private set
    override var volume by mutableStateOf(0.85f)
        private set
    override var isMuted by mutableStateOf(false)
        private set
    override var isShuffle by mutableStateOf(false)
        private set
    override var repeatMode by mutableStateOf(RepeatMode.ALL)
        private set
    override var playlist by mutableStateOf<List<VfsEntry>>(emptyList())
        private set
    override var currentIndex by mutableStateOf(-1)
        private set
    override var visualizerBands by mutableStateOf(FloatArray(24) { 0.05f })
        private set
    override var playbackError by mutableStateOf<Pair<Int, String>?>(null)
        private set

    /** Позиция и полосы — 5 раз в секунду, пока играет. */
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (isPlaying && !isPaused) {
                runCatching { setPosition(p.currentPosition.toLong()) }
                animateBands()
            }
            main.postDelayed(this, 200)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun setPosition(ms: Long) {
        currentPositionMs = ms
        val sec = ms / 1000
        if (sec != positionSeconds) positionSeconds = sec
    }

    override fun play(entry: VfsEntry, queue: List<VfsEntry>) = onMain {
        failedInRow = 0
        val list = queue.ifEmpty { listOf(entry) }
        playlist = list
        currentIndex = list.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        startTrack(entry, 0L)
    }

    override fun playPause() = onMain {
        when {
            isPlaying && isPaused -> resume()
            isPlaying -> pause()
            else -> currentTrack?.let { startTrack(it, currentPositionMs) }
        }
    }

    override fun pause() = onMain {
        if (isPlaying && !isPaused) {
            runCatching { player?.pause() }
            isPaused = true
        }
    }

    override fun resume() = onMain {
        if (isPlaying && isPaused) {
            runCatching { player?.start() }
            isPaused = false
        }
    }

    override fun stop() = onMain {
        release()
        isPlaying = false
        isPaused = false
        setPosition(0L)
        visualizerBands = FloatArray(24) { 0.05f }
    }

    override fun closePlayer() = onMain {
        stop()
        currentTrack = null
        playlist = emptyList()
        currentIndex = -1
    }

    override fun next() = onMain {
        if (playlist.isEmpty()) return@onMain
        currentIndex = if (isShuffle) playlist.indices.random() else (currentIndex + 1) % playlist.size
        startTrack(playlist[currentIndex], 0L)
    }

    override fun previous() = onMain {
        if (playlist.isEmpty()) return@onMain
        if (currentPositionMs > 3000) {
            seekTo(0L)
            return@onMain
        }
        currentIndex = if (isShuffle) playlist.indices.random() else (currentIndex - 1 + playlist.size) % playlist.size
        startTrack(playlist[currentIndex], 0L)
    }

    override fun seekTo(positionMs: Long) = onMain {
        val p = player
        val clamped = positionMs.coerceIn(0L, maxOf(1000L, totalDurationMs))
        if (p != null && isPlaying) {
            runCatching { p.seekTo(clamped.toInt()) }
            setPosition(clamped)
        } else {
            currentTrack?.let { startTrack(it, clamped) }
        }
    }

    override fun setVolumeLevel(newVol: Float) = onMain {
        volume = newVol.coerceIn(0f, 1f)
        if (isMuted && newVol > 0f) isMuted = false
        applyVolume()
    }

    override fun toggleMute() = onMain {
        isMuted = !isMuted
        applyVolume()
    }

    override fun toggleShuffle() = onMain { isShuffle = !isShuffle }

    override fun cycleRepeatMode() = onMain {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun applyVolume() {
        val v = if (isMuted) 0f else volume
        runCatching { player?.setVolume(v, v) }
    }

    private fun release() {
        main.removeCallbacks(ticker)
        player?.let { runCatching { it.reset(); it.release() } }
        player = null
    }

    private fun startTrack(entry: VfsEntry, startMs: Long) {
        release()
        val gen = ++attempt
        currentTrack = entry
        setPosition(startMs)
        isPlaying = true
        isPaused = false
        // Файл из архива — в кэш (вне потока окна), потом в плеер
        Thread({
            val file = runCatching { localFile(entry) }.getOrElse { e ->
                main.post { if (gen == attempt) fail(gen, e.message ?: e.javaClass.simpleName) }
                return@Thread
            }
            main.post { if (gen == attempt) open(file, startMs, gen) }
        }, "wex-audio-prepare").apply { isDaemon = true }.start()
    }

    private fun open(file: File, startMs: Long, gen: Int) {
        val p = MediaPlayer()
        player = p
        runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                if (gen != attempt) return@setOnPreparedListener
                totalDurationMs = it.duration.toLong().coerceAtLeast(0L)
                if (startMs > 0) it.seekTo(startMs.toInt())
                applyVolume()
                it.start()
                failedInRow = 0
                main.post(ticker)
            }
            p.setOnCompletionListener { if (gen == attempt) onFinished() }
            p.setOnErrorListener { _, what, extra ->
                if (gen == attempt) fail(gen, "MediaPlayer: $what/$extra")
                true
            }
            p.prepareAsync()
        }.onFailure { fail(gen, it.message ?: it.javaClass.simpleName) }
    }

    private fun onFinished() {
        when {
            repeatMode == RepeatMode.ONE -> currentTrack?.let { startTrack(it, 0L) }
            repeatMode == RepeatMode.ALL || currentIndex < playlist.lastIndex -> next()
            else -> stop()
        }
    }

    private fun fail(gen: Int, why: String) {
        release()
        isPlaying = false
        isPaused = false
        playbackError = gen to why
        // Битый файл при «повторять всё» не должен перебирать очередь бесконечно
        if (++failedInRow < playlist.size && repeatMode != RepeatMode.ONE) next()
    }

    private fun localFile(entry: VfsEntry): File {
        val path = entry.path
        if (path.fileSystem === FileSystems.getDefault()) return path.toFile()
        val dir = AppDirs.cache.resolve("audio").also { Files.createDirectories(it) }
        val target = dir.resolve("track-" + entry.name)
        Files.newInputStream(path).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return target.toFile()
    }

    /** Без доступа к звуковому потоку (Visualizer требует разрешения на запись) — спокойная волна в такт времени. */
    private fun animateBands() {
        val t = currentPositionMs / 1000.0
        visualizerBands = FloatArray(24) { i ->
            (0.25f + 0.6f * abs(sin(t * (1.3 + i * 0.17) + i * 0.7)).toFloat()) * (1f - i / 40f)
        }
    }
}
