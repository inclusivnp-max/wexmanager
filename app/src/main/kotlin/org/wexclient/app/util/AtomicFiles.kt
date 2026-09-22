package org.wexclient.app.util

import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Запись настроек целиком или никак: оборванная запись не должна оставлять пустой файл. */
object AtomicFiles {
    fun write(target: File, body: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: throw java.io.IOException("Нет папки для ${target.path}")
        dir.mkdirs()
        val tmp = Files.createTempFile(dir.toPath(), ".${target.name}.", ".tmp")
        try {
            Files.newOutputStream(tmp).use(body)
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun writeProperties(target: File, props: Properties, comment: String) = write(target) { props.store(it, comment) }
}
