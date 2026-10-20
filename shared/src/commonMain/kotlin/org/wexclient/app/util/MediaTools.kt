package org.wexclient.app.util

import java.util.concurrent.TimeUnit

/**
 * Вызовы ffprobe на локальных файлах. Вход — только файл (-protocol_whitelist file): медиафайл может
 * оказаться плейлистом со ссылками на сеть или другие файлы, а миниатюры строятся без щелчка.
 */
object MediaTools {
    /** ffprobe с таймаутом: битый или сетевой файл не должен держать поток вечно. null — не вышло. */
    fun probe(args: List<String>, path: String, timeoutSec: Long = 15): String? {
        val process = try {
            ProcessBuilder(listOf("ffprobe", "-protocol_whitelist", "file") + args + path)
                .redirectError(org.wexclient.app.util.DiscardOutput)
                .startHelper()
        } catch (_: java.io.IOException) {
            return null
        }
        process.outputStream.close()
        var out = ""
        // Теги файла могут быть огромными — берём начало, остальное сливаем
        val reader = Thread {
            out = runCatching {
                process.inputStream.use { input ->
                    val head = input.readUpTo(1 shl 20)
                    input.copyTo(org.wexclient.app.util.NullOutputStream)
                    String(head, Charsets.UTF_8)
                }
            }.getOrDefault("")
        }
            .apply {
                isDaemon = true
                start()
            }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(2000)
        return out
    }
}
