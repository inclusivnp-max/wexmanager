package org.wexclient.app.vfs.ssh

import androidx.compose.runtime.mutableStateListOf
import java.io.File

/**
 * Серверы, к которым подключались, и имена из ~/.ssh/config — для быстрого выбора.
 * Хранится только адрес: пароли и ключи остаются там, где их держит сам ssh.
 */
object SshServers {
    private val file: File get() = File(System.getProperty("user.home"), ".config/wexdesktop/servers.tsv")

    /** Недавние подключения, свежие сверху. */
    val recent = mutableStateListOf<String>()

    init {
        runCatching {
            if (file.isFile) {
                file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .distinct().take(MAX).forEach { recent += it }
            }
        }
    }

    /** Запомнить удачное подключение. */
    fun remember(label: String) {
        recent.remove(label)
        recent.add(0, label)
        while (recent.size > MAX) recent.removeAt(recent.lastIndex)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("# Серверы WEXdesktop, к которым подключались\n" + recent.joinToString("\n") + "\n")
        }
    }

    fun forget(label: String) {
        recent.remove(label)
        runCatching { file.writeText("# Серверы WEXdesktop, к которым подключались\n" + recent.joinToString("\n") + "\n") }
    }

    /**
     * Имена хостов из ~/.ssh/config (строки `Host имя`), без шаблонов со звёздочкой.
     * Ssh сам знает для них адрес, пользователя, порт и ключ — достаточно имени.
     */
    fun configHosts(): List<String> = runCatching {
        val config = File(System.getProperty("user.home"), ".ssh/config")
        if (!config.isFile) return emptyList()
        config.readLines()
            .map { it.trim() }
            .filter { it.startsWith("Host ", ignoreCase = true) || it.startsWith("Host\t", ignoreCase = true) }
            .flatMap { it.substring(4).trim().split(Regex("\\s+")) }
            .filter { it.isNotEmpty() && '*' !in it && '?' !in it && '!' !in it }
            .distinct()
    }.getOrDefault(emptyList())

    private const val MAX = 12
}
