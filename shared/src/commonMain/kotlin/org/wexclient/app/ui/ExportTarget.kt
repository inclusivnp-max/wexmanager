package org.wexclient.app.ui

import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Куда выгрузить производный файл (CSV листа, текст документа) «рядом с исходным». Исходный внутри архива —
 * в домашнюю папку: запись прямо в архив (APK, zip) его портит (у APK — подпись), а правку zipfs держит
 * в памяти до закрытия архива, и архив, отпущенный за давностью (VfsManager.releaseIdle), унёс бы её с собой.
 */
internal fun exportTarget(source: Path, name: String): Path {
    val beside = source.toAbsolutePath().parent
    val dir = if (beside != null && beside.fileSystem === FileSystems.getDefault()) beside
    else Path.of(org.wexclient.app.util.AppDirs.userHome.toString())
    return dir.resolve(name)
}

/** Как показать место выгрузки: рядом с исходным — имя, иначе — полный путь (иначе не найти). */
internal fun exportShown(source: Path, target: Path): String =
    if (source.toAbsolutePath().parent == target.parent) target.fileName.toString() else target.toString()
