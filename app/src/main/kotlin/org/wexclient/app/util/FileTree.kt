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
                    // Junction Windows — тоже ссылка, хоть Java и считает его папкой
                    if (attrs.isOther) {
                        runCatching { removeOne(dir) }
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    runCatching {
                        val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
                        if (PosixFilePermission.OWNER_WRITE !in perms || PosixFilePermission.OWNER_EXECUTE !in perms) {
                            Files.setPosixFilePermissions(dir, perms + PosixFilePermission.OWNER_WRITE + PosixFilePermission.OWNER_EXECUTE + PosixFilePermission.OWNER_READ)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { removeOne(file) }
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

    /**
     * Удаление по команде пользователя на Windows: первая ошибка — исключением. Junction (точка
     * повторной обработки) удаляется сам, без захода внутрь: Files.walk считает его папкой и стёр бы
     * то, на что он указывает. «Только чтение» (.git/objects) перед удалением снимается.
     */
    @Throws(IOException::class)
    fun deleteOrThrow(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isOther) return FileVisitResult.CONTINUE
                removeOne(dir)
                return FileVisitResult.SKIP_SUBTREE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                removeOne(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                removeOne(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Удалить один файл, папку или ссылку; на Windows файл «только чтение» — сняв этот флаг. */
    private fun removeOne(p: Path) {
        try {
            Files.deleteIfExists(p)
        } catch (e: java.nio.file.AccessDeniedException) {
            if (runCatching { Files.setAttribute(p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS) }.isFailure) throw e
            Files.deleteIfExists(p)
        }
    }
}
