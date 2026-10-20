package org.wexclient.app.util

import com.github.marschall.com.sun.nio.zipfs.ZipFileSystemProvider
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path

actual val DiscardOutput: ProcessBuilder.Redirect get() = ProcessBuilder.Redirect.to(File("/dev/null"))

actual fun Process.whenExited(action: () -> Unit) {
    Thread({
        runCatching { waitFor() }
        action()
    }, "wex-process-exit").apply { isDaemon = true }.start()
}

// Детей процесса Java 8 не видит; служебные процессы на Android — свои, без внуков (sh -c … у нас не бывает)
actual fun Process.destroyTree(force: Boolean) {
    if (force) destroyForcibly() else destroy()
}

actual fun Process.treeAlive(): Boolean = isAlive

private val zipProvider by lazy { ZipFileSystemProvider() }

actual fun openZipFileSystem(archive: Path, env: Map<String, Any?>): FileSystem = zipProvider.newFileSystem(archive, env)

/** Время старта процесса в тиках с загрузки (поле 22 /proc/pid/stat): у нового процесса с тем же pid оно другое. */
private fun startTicks(pid: Long): String? = runCatching {
    val stat = File("/proc/$pid/stat").readText()
    // Имя процесса в скобках может содержать пробелы — поля считаем после последней «)»
    stat.substring(stat.lastIndexOf(')') + 2).split(' ')[19]
}.getOrNull()

actual fun currentProcessKey(): String {
    val pid = android.os.Process.myPid().toLong()
    return "$pid-${startTicks(pid) ?: 0}"
}

actual fun processKeyOf(pid: Long): String? = startTicks(pid)?.let { "$pid-$it" }

actual fun processStartMillis(): Long =
    System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())

actual fun processUptimeMillis(): Long = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
