package org.wexclient.app.audio

import org.wexclient.app.vfs.VfsEntry

enum class RepeatMode {
    OFF, ALL, ONE
}

/**
 * Аудиоплеер WEX: очередь из папки, повтор, перемешивание, громкость, полосы визуализатора.
 * Состояние — снимки Compose: окно плеера и мини-плеер перерисовываются сами. Играет платформа:
 * десктоп — ffmpeg в звуковую линию Java, Android — MediaPlayer.
 */
interface AudioPlayer {
    val currentTrack: VfsEntry?
    val isPlaying: Boolean
    val isPaused: Boolean

    /** Позиция воспроизведения; обновляется несколько раз в секунду — для ползунка в окне плеера. */
    val currentPositionMs: Long

    /** Та же позиция с точностью до секунды — для мини-плеера главного окна (реже перерисовка). */
    val positionSeconds: Long
    val totalDurationMs: Long
    val volume: Float
    val isMuted: Boolean
    val isShuffle: Boolean
    val repeatMode: RepeatMode
    val playlist: List<VfsEntry>
    val currentIndex: Int

    /** Полосы спектра (24 штуки, 0..1). */
    val visualizerBands: FloatArray

    /** Почему воспроизведение не началось (номер попытки — чтобы одинаковое сообщение показалось снова). */
    val playbackError: Pair<Int, String>?

    fun play(entry: VfsEntry, queue: List<VfsEntry> = emptyList())
    fun playPause()
    fun pause()
    fun resume()
    fun stop()

    /** Закрыть плеер и отпустить всё; можно звать из любого потока, повторно и когда ничего не играет. */
    fun closePlayer()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setVolumeLevel(newVol: Float)
    fun toggleMute()
    fun toggleShuffle()
    fun cycleRepeatMode()

    /** «мм:сс» для подписей позиции и длительности. */
    fun formatTime(millis: Long): String {
        val totalSec = maxOf(0, millis / 1000)
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }
}

/** Плеер программы (один на процесс). */
expect val AudioPlayerManager: AudioPlayer
