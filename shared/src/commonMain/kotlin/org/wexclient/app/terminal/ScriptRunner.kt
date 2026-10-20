package org.wexclient.app.terminal

import org.wexclient.app.util.readUpTo

import org.wexclient.app.vfs.archive.ArchiveFormats
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Запуск скриптов (.sh, .bat…) во встроенной консоли. */
object ScriptRunner {
    private val shells = setOf("sh", "bash", "zsh", "fish", "command")
    private val windows = setOf("bat", "cmd")

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Скрипт на диске, который можно запустить кнопкой. */
    fun isRunnable(path: Path, isDirectory: Boolean): Boolean {
        if (isDirectory || path.fileSystem !== FileSystems.getDefault()) return false
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in shells || ext in windows || ext == "ps1"
    }

    private val plainWord = Regex("[A-Za-z0-9_./+-]+")

    /**
     * Строка как одно слово и для sh/bash/zsh, и для fish. В fish внутри одинарных кавычек
     * действуют обратная косая черта и экранированная кавычка, поэтому эти два символа ставим
     * вне кавычек, экранированными (прежний вариант в fish позволял выйти из кавычек).
     */
    fun quote(s: String): String {
        if (s.isEmpty()) return "''"
        val sb = StringBuilder(s.length + 8)
        var open = false
        for (c in s) {
            if (c == '\'' || c == '\\') {
                if (open) {
                    sb.append('\'')
                    open = false
                }
                sb.append('\\').append(c)
            } else {
                if (!open) {
                    sb.append('\'')
                    open = true
                }
                sb.append(c)
            }
        }
        if (open) sb.append('\'')
        return sb.toString()
    }

    /** Простое слово — как есть, остальное — [quote]. */
    fun word(s: String): String = if (plainWord.matches(s)) s else quote(s)

    /**
     * Управляющие символы в команде, которую WEX печатает в консоль, сработали бы как клавиши
     * (Ctrl+C, Enter) — такие имена не запускаем.
     */
    fun hasControlChars(s: String): Boolean = s.any { it.code < 0x20 || it.code in 0x7F..0x9F }

    /**
     * Команда запуска из папки скрипта. Интерпретатор — из первой строки (#!),
     * если его нет в системе (например, /system/bin/sh из Android) — по расширению.
     */
    fun command(script: Path): String {
        val name = script.fileName.toString()
        val ext = name.substringAfterLast('.', "").lowercase()
        val local = word("./$name")
        when (ext) {
            in windows -> return when {
                isWindows -> "cmd /c ${quote(name)}"
                ArchiveFormats.toolAvailable("wine") -> "wine cmd /c ${quote(name)}"
                else -> throw IOException("Для запуска .$ext нужен Wine (пакет wine)")
            }
            "ps1" -> return when {
                ArchiveFormats.toolAvailable("pwsh") -> "pwsh -File $local"
                isWindows -> "powershell -File $local"
                else -> throw IOException("Для запуска .ps1 нужен PowerShell (pwsh)")
            }
        }
        shebang(script)?.let { return "$it $local" }
        return when (ext) {
            "fish", "zsh", "bash" -> if (ArchiveFormats.toolAvailable(ext)) "$ext $local" else "sh $local"
            else -> if (ArchiveFormats.toolAvailable("bash")) "bash $local" else "sh $local"
        }
    }

    // «#!/usr/bin/env bash -e» → «/usr/bin/env bash -e»; интерпретатора нет — null
    private fun shebang(script: Path): String? {
        val first = runCatching {
            Files.newInputStream(script).use { it.readUpTo(256) }
        }.getOrNull() ?: return null
        val line = String(first, StandardCharsets.UTF_8).lineSequence().firstOrNull()?.trim() ?: return null
        if (!line.startsWith("#!")) return null
        val parts = line.removePrefix("#!").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val interpreter = parts.firstOrNull() ?: return null
        val exists = if (interpreter.startsWith("/")) {
            Files.isExecutable(Paths.get(interpreter))
        } else {
            ArchiveFormats.toolAvailable(interpreter)
        }
        // env без аргументов бессмысленен; у «env python3» проверяем сам интерпретатор
        if (interpreter.endsWith("/env")) {
            val target = parts.drop(1).firstOrNull { !it.startsWith("-") } ?: return null
            if (!ArchiveFormats.toolAvailable(target)) return null
        }
        return if (exists) parts.joinToString(" ") { word(it) } else null
    }
}
