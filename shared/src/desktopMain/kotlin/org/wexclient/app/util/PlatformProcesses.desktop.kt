package org.wexclient.app.util

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path

actual val DiscardOutput: ProcessBuilder.Redirect get() = ProcessBuilder.Redirect.DISCARD

actual fun Process.whenExited(action: () -> Unit) {
    onExit().thenRun(action)
}

actual fun Process.destroyTree(force: Boolean) {
    // Дети — до родителя: без родителя их уже не найти
    descendants().forEach { if (force) it.destroyForcibly() else it.destroy() }
    if (force) destroyForcibly() else destroy()
}

actual fun Process.treeAlive(): Boolean = isAlive || descendants().anyMatch { it.isAlive }

// newFileSystem(Path) не регистрирует ФС в глобальном реестре jar:-URI
actual fun openZipFileSystem(archive: Path, env: Map<String, Any?>): FileSystem = FileSystems.newFileSystem(archive, env)

private fun keyOf(handle: ProcessHandle): String =
    handle.pid().toString() + "-" + handle.info().startInstant().map { it.toEpochMilli() }.orElse(0L)

actual fun currentProcessKey(): String = keyOf(ProcessHandle.current())

actual fun processKeyOf(pid: Long): String? = ProcessHandle.of(pid).map { keyOf(it) }.orElse(null)

actual fun processStartMillis(): Long =
    ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(System.currentTimeMillis())

actual fun processUptimeMillis(): Long = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
