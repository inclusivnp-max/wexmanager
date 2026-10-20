package org.wexclient.app.util

import java.nio.file.FileSystem
import java.nio.file.Path

/**
 * То, что в JDK 9–16 есть, а в Android (API Java 8) — нет: дерево процессов, «выбросить вывод»,
 * zip как файловая система. У каждой платформы своё.
 */

/** Вывод процесса — в никуда (org.wexclient.app.util.DiscardOutput на десктопе, /dev/null на Android). */
expect val DiscardOutput: ProcessBuilder.Redirect

/** [action] — когда процесс завершится (в постороннем потоке). */
expect fun Process.whenExited(action: () -> Unit)

/** Остановить процесс вместе с его детьми (tar запускает xz/zstd); [force] — сразу SIGKILL. */
expect fun Process.destroyTree(force: Boolean)

/** Жив ли сам процесс или кто-то из его детей. */
expect fun Process.treeAlive(): Boolean

/** zip/APK/jar как файловая система (чтение и правка на месте). */
expect fun openZipFileSystem(archive: Path, env: Map<String, Any?>): FileSystem

/** Файловая система — zip (JDK zipfs на десктопе, zipfilesystem-standalone на Android). */
fun isZipFileSystem(fs: FileSystem): Boolean = fs.provider().scheme.let { it == "jar" || it == "zipfs" }

/** Метка этого процесса: «pid-время старта» — у другого процесса с тем же pid она другая. */
expect fun currentProcessKey(): String

/** Метка живого процесса [pid] (как у [currentProcessKey]); null — такого процесса нет. */
expect fun processKeyOf(pid: Long): String?

/** Когда запущен этот процесс (мс эпохи). */
expect fun processStartMillis(): Long

/** Сколько миллисекунд работает этот процесс. */
expect fun processUptimeMillis(): Long
