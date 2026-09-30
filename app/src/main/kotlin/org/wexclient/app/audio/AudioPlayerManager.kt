package org.wexclient.app.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.wexclient.app.vfs.VfsEntry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.max
import org.wexclient.app.util.startHelper

enum class RepeatMode {
    OFF, ALL, ONE
}

object AudioPlayerManager {
    // Supervisor: упавшее воспроизведение (нет ffmpeg) не убивает плеер до перезапуска
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Номер текущего воспроизведения: старое, заменённое перемоткой или другим треком, само выходит. */
    private val playGen = java.util.concurrent.atomic.AtomicInteger()
    private var playbackJob: Job? = null
    private var line: SourceDataLine? = null
    private var ffmpegProcess: Process? = null
    /** Сколько треков подряд не дали ни звука: битый файл при «повторять всё» не должен запускать ffmpeg бесконечно. */
    @Volatile
    private var failedInRow = 0

    var currentTrack by mutableStateOf<VfsEntry?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isPaused by mutableStateOf(false)
        private set

    private val positionMs = mutableStateOf(0L)

    /** Позиция воспроизведения; пишется 5 раз в секунду — для ползунка в окне плеера. */
    var currentPositionMs: Long
        get() = positionMs.value
        private set(value) {
            positionMs.value = value
            val sec = value / 1000
            if (sec != positionSeconds) positionSeconds = sec
        }

    /**
     * Та же позиция с точностью до секунды — для мини-плеера главного окна: он показывает только секунды,
     * а каждое изменение состояния — кадр всего окна (5 в секунду всю песню, даже в свёрнутом окне).
     */
    var positionSeconds by mutableStateOf(0L)
        private set

    var totalDurationMs by mutableStateOf(0L)
        private set

    var volume by mutableStateOf(0.85f)
        private set

    var isMuted by mutableStateOf(false)
        private set

    var isShuffle by mutableStateOf(false)
        private set

    var repeatMode by mutableStateOf(RepeatMode.ALL)
        private set

    var playlist by mutableStateOf<List<VfsEntry>>(emptyList())
        private set

    var currentIndex by mutableStateOf(-1)
        private set

    // Real-time audio spectrum bars (24 frequency bands, values 0.0f .. 1.0f)
    var visualizerBands by mutableStateOf(FloatArray(24) { 0.05f })
        private set

    /** Почему воспроизведение не началось (номер попытки — чтобы одинаковое сообщение показалось снова). */
    var playbackError by mutableStateOf<Pair<Int, String>?>(null)
        private set


    fun play(entry: VfsEntry, queue: List<VfsEntry> = emptyList()) {
        failedInRow = 0
        val newPlaylist = if (queue.isNotEmpty()) queue else listOf(entry)
        playlist = newPlaylist
        val idx = newPlaylist.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        currentIndex = idx
        startTrack(entry, 0L)
    }

    fun playPause() {
        if (isPlaying) {
            if (isPaused) {
                resume()
            } else {
                pause()
            }
        } else {
            currentTrack?.let { startTrack(it, currentPositionMs) }
        }
    }

    fun pause() {
        if (isPlaying && !isPaused) {
            isPaused = true
            line?.stop()
        }
    }

    fun resume() {
        if (isPlaying && isPaused) {
            isPaused = false
            line?.start()
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            ffmpegProcess?.destroyForcibly()
        } catch (_: Throwable) {}
        ffmpegProcess = null

        try {
            line?.stop()
            line?.close()
        } catch (_: Throwable) {}
        line = null

        isPlaying = false
        isPaused = false
        currentPositionMs = 0L
        visualizerBands = FloatArray(24) { 0.05f }
    }

    fun closePlayer() {
        stop()
        currentTrack = null
        playlist = emptyList()
        currentIndex = -1
    }

    fun next() {
        if (playlist.isEmpty()) return
        val nextIdx = if (isShuffle) {
            (0 until playlist.size).random()
        } else {
            (currentIndex + 1) % playlist.size
        }
        currentIndex = nextIdx
        startTrack(playlist[nextIdx], 0L)
    }

    fun previous() {
        if (playlist.isEmpty()) return
        if (currentPositionMs > 3000) {
            // Seek to start if already playing for more than 3 seconds
            seekTo(0L)
            return
        }
        val prevIdx = if (isShuffle) {
            (0 until playlist.size).random()
        } else {
            (currentIndex - 1 + playlist.size) % playlist.size
        }
        currentIndex = prevIdx
        startTrack(playlist[prevIdx], 0L)
    }

    fun seekTo(positionMs: Long) {
        val track = currentTrack ?: return
        val clamped = positionMs.coerceIn(0L, max(1000L, totalDurationMs))
        currentPositionMs = clamped
        startTrack(track, clamped)
    }

    fun setVolumeLevel(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        if (isMuted && newVol > 0f) {
            isMuted = false
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
    }

    fun toggleShuffle() {
        isShuffle = !isShuffle
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun startTrack(entry: VfsEntry, startOffsetMs: Long) {
        playbackJob?.cancel()
        val gen = playGen.incrementAndGet()
        val sameTrack = currentTrack?.path == entry.path
        currentTrack = entry
        currentPositionMs = startOffsetMs
        isPlaying = true
        isPaused = false

        playbackJob = scope.launch {
            // Длительность — при смене трека (перемотка её не меняет)
            if (!sameTrack || totalDurationMs <= 0L) totalDurationMs = probeDuration(entry)
            try {
                playPcmStream(entry, startOffsetMs, gen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // ffmpeg не запустился или занято звуковое устройство — не делаем вид, что играем
                if (gen == playGen.get()) {
                    isPlaying = false
                    isPaused = false
                    playbackError = gen to (e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun playPcmStream(entry: VfsEntry, startOffsetMs: Long, gen: Int) = withContext(Dispatchers.IO) {
        try {
            ffmpegProcess?.destroyForcibly()
        } catch (_: Throwable) {}

        val audioFormat = AudioFormat(44100.0f, 16, 2, true, false)
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)

        var audioLine = line
        if (audioLine == null || !audioLine.isOpen) {
            try {
                audioLine = AudioSystem.getLine(dataLineInfo) as SourceDataLine
                audioLine.open(audioFormat, 44100 * 4) // 1 second buffer
                audioLine.start()
                line = audioLine
            } catch (e: Exception) {
                // IllegalArgumentException — «No line matching…»: нет ни одного устройства вывода
                val why = if (e is IllegalArgumentException) "нет устройства вывода" else e.message ?: e.javaClass.simpleName
                throw java.io.IOException("Звуковое устройство недоступно: $why", e)
            }
        } else {
            audioLine.flush()
            audioLine.start()
        }

        val startSec = String.format(java.util.Locale.US, "%.3f", startOffsetMs / 1000.0)
        val filePath = entry.path.toAbsolutePath().toString()

        val process = ProcessBuilder(
            "ffmpeg",
            "-nostdin",
            "-ss", startSec,
            "-protocol_whitelist", "file",
            "-i", filePath,
            "-f", "s16le",
            "-ac", "2",
            "-ar", "44100",
            "-"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).let {
            try {
                it.startHelper()
            } catch (e: java.io.IOException) {
                throw java.io.IOException("Не удалось запустить ffmpeg: ${e.message}", e)
            }
        }
        ffmpegProcess = process

        val stream = process.inputStream
        val buffer = ByteArray(4096)
        val shortBuffer = ShortArray(2048)
        val byteBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        var bytesRead: Int
        // Позиция — от числа байт целиком: округление каждого блока отставало на ~1 %
        var produced = 0L
        var deviceError: Exception? = null

        try {
            var lastBandsAt = 0L
            var lastPositionAt = 0L
            while (isActive && gen == playGen.get()) {
                if (isPaused) {
                    // Ждём снятия паузы, а не проверяем её 20 раз в секунду до закрытия плеера
                    kotlinx.coroutines.withTimeoutOrNull(1_000) {
                        androidx.compose.runtime.snapshotFlow { isPaused }.first { !it }
                    }
                    continue
                }

                bytesRead = stream.read(buffer)
                if (bytesRead == -1) break
                // Звуковая карта принимает только целые кадры (4 байта), а труба может отдать и 4095
                while (bytesRead % 4 != 0) {
                    val more = stream.read(buffer, bytesRead, 4 - bytesRead % 4)
                    if (more < 0) break
                    bytesRead += more
                }
                bytesRead -= bytesRead % 4
                if (bytesRead == 0) continue

                val sampleCount = bytesRead / 2
                byteBuf.rewind()
                for (i in 0 until sampleCount) {
                    shortBuffer[i] = byteBuf.short
                }

                // Calculate visualizer frequency bands
                val bands = FloatArray(24)
                val samplesPerBand = max(1, sampleCount / 24)
                val effectiveVol = if (isMuted) 0f else volume

                for (b in 0 until 24) {
                    var sum = 0L
                    val start = b * samplesPerBand
                    val end = minOf(sampleCount, start + samplesPerBand)
                    for (s in start until end) {
                        sum += abs(shortBuffer[s].toInt())
                    }
                    val count = max(1, end - start)
                    val avgAmp = (sum.toFloat() / count) / 32768f
                    bands[b] = (avgAmp * 2.8f).coerceIn(0.04f, 1.0f)
                }
                // 20 раз в секунду хватает для анимации; чаще — лишние перерисовки окна
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastBandsAt >= 50) {
                    lastBandsAt = nowMs
                    visualizerBands = bands
                }

                // Apply volume scaling to PCM buffer before writing to speaker
                if (effectiveVol < 0.99f) {
                    byteBuf.rewind()
                    for (i in 0 until sampleCount) {
                        val scaled = (shortBuffer[i] * effectiveVol).toInt().coerceIn(-32768, 32767).toShort()
                        byteBuf.putShort(scaled)
                    }
                }

                try {
                    audioLine.write(buffer, 0, bytesRead)
                } catch (e: Exception) {
                    deviceError = e
                    break
                }

                // 44100 stereo 16-bit = 176 400 байт в секунду
                produced += bytesRead
                if (nowMs - lastPositionAt >= 200) {
                    lastPositionAt = nowMs
                    currentPositionMs = startOffsetMs + produced * 1000 / BYTES_PER_SECOND
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                process.destroyForcibly()
            } catch (_: Throwable) {}
        }

        // Заменено перемоткой или другим треком — дальше решает новое воспроизведение
        if (gen != playGen.get()) return@withContext

        if (isActive && !isPaused) {
            val error = deviceError
            // С начала — и ни одного байта: файл не читается (перемотка за конец — это просто конец)
            val broken = produced == 0L && startOffsetMs == 0L
            failedInRow = if (broken) failedInRow + 1 else 0
            if (error != null || (broken && failedInRow >= playlist.size.coerceAtLeast(1))) {
                failedInRow = 0
                withContext(Dispatchers.Main) {
                    if (gen == playGen.get()) {
                        stop()
                        playbackError = gen to if (error != null) {
                            "Воспроизведение прервано: ${error.message ?: error.javaClass.simpleName}"
                        } else if (playlist.size > 1) {
                            "Ни один трек списка не воспроизводится (ffmpeg не читает файлы)"
                        } else {
                            "Не удалось воспроизвести «${entry.name}»: ffmpeg не читает файл"
                        }
                    }
                }
                return@withContext
            }
            // Дослушать то, что уже в буфере звуковой карты
            if (!broken) runCatching { audioLine.drain() }
            // Track completed naturally
            withContext(Dispatchers.Main) {
                if (gen != playGen.get()) return@withContext
                when (repeatMode) {
                    RepeatMode.ONE -> {
                        startTrack(entry, 0L)
                    }
                    RepeatMode.ALL -> {
                        next()
                    }
                    RepeatMode.OFF -> {
                        if (currentIndex < playlist.size - 1) {
                            next()
                        } else {
                            stop()
                        }
                    }
                }
            }
        }
    }

    private fun probeDuration(entry: VfsEntry): Long {
        try {
            val text = org.wexclient.app.util.MediaTools.probe(
                listOf("-v", "quiet", "-show_entries", "format=duration", "-of", "csv=p=0"),
                entry.path.toAbsolutePath().toString()
            ).orEmpty().trim()
            val sec = text.toDoubleOrNull() ?: 0.0
            return (sec * 1000).toLong()
        } catch (_: Throwable) {
            return 0L
        }
    }

    private const val BYTES_PER_SECOND = 44100L * 4

    fun formatTime(millis: Long): String {
        val totalSec = max(0, millis / 1000)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }
}
