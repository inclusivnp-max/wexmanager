package org.wexclient.app.vfs.ssh

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.wexclient.app.vfs.remote.RemoteFileSystem
import org.wexclient.app.vfs.remote.RemoteFileSystemProvider

/**
 * Папки на сервере как обычная файловая система: панель ходит по ним, копирует F5
 * в обе стороны, удаляет и переименовывает тем же кодом, что и на диске.
 * Каждая операция — команда через одно фоновое ssh-соединение; общая часть — в [RemoteFileSystem].
 */
class SshFileSystem internal constructor(
    provider: SshFileSystemProvider,
    override val connection: SshConnection,
    /** Домашняя папка на сервере — откуда начинаем. */
    override val home: String
) : RemoteFileSystem(provider) {

    val target: SshTarget get() = connection.target

    override val label: String get() = target.label

    override val title: String get() = "SSH " + target.label

    override val cacheKey: String get() = target.label

    override val whereText: String get() = "На сервере"

    override val kind: String get() = "ssh"

    override fun uriOf(path: String): URI =
        URI(SshFileSystemProvider.SCHEME, target.user, target.host, target.port, path, null, null)
}

/** Поставщик файловой системы «wexssh»: одна ФС на сервер. */
class SshFileSystemProvider : RemoteFileSystemProvider() {

    private val systems = ConcurrentHashMap<String, SshFileSystem>()

    override fun getScheme(): String = SCHEME

    /** Подключиться (или взять уже открытое подключение к этому серверу). */
    fun open(target: SshTarget): SshFileSystem {
        systems[target.label]?.takeIf { it.isOpen }?.let { return it }
        val connection = SshConnection(target)
        val home = connection.connect()
        val fs = SshFileSystem(this, connection, home)
        systems[target.label] = fs
        return fs
    }

    override fun forget(fs: RemoteFileSystem) {
        systems.values.remove(fs)
    }

    /** Открытые сейчас подключения — для списка «недавних» и закрытия при выходе. */
    fun opened(): List<SshFileSystem> = systems.values.filter { it.isOpen }

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem {
        val target = SshTarget(uri.userInfo, uri.host ?: throw IllegalArgumentException("Нет адреса"), if (uri.port > 0) uri.port else 22)
        if (systems[target.label]?.isOpen == true) throw FileSystemAlreadyExistsException(target.label)
        return open(target)
    }

    override fun getFileSystem(uri: URI): FileSystem {
        val target = SshTarget(uri.userInfo, uri.host ?: throw IllegalArgumentException("Нет адреса"), if (uri.port > 0) uri.port else 22)
        return systems[target.label] ?: throw java.nio.file.FileSystemNotFoundException(target.label)
    }

    override fun getPath(uri: URI): Path = getFileSystem(uri).getPath(uri.path ?: "/")

    companion object {
        const val SCHEME = "wexssh"

        /** Один поставщик на программу: у него список открытых подключений. */
        val instance: SshFileSystemProvider by lazy { SshFileSystemProvider() }
    }
}
