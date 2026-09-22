package org.wexclient.app.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

object FileTree {
    /**
     * Удалить папку со всем содержимым, не заходя в символические ссылки: File.deleteRecursively()
     * из Kotlin заходит в ссылку на папку и удаляет чужие файлы (ссылка «x -> /home/user» во
     * временной папке распаковки стёрла бы домашнюю папку). Ссылки удаляются как ссылки.
     * Папки «только чтение» (так бывает в архивах) перед удалением открываются на запись.
     * true — ничего не осталось.
     */
    fun delete(root: Path): Boolean {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching {
                        val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
                        if (PosixFilePermission.OWNER_WRITE !in perms || PosixFilePermission.OWNER_EXECUTE !in perms) {
                            Files.setPosixFilePermissions(dir, perms + PosixFilePermission.OWNER_WRITE + PosixFilePermission.OWNER_EXECUTE + PosixFilePermission.OWNER_READ)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    fun delete(root: File): Boolean = delete(root.toPath())
}
