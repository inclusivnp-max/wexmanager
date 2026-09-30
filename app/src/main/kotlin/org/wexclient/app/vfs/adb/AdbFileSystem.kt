package org.wexclient.app.vfs.adb

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.wexclient.app.adb.AdbManager
import org.wexclient.app.vfs.remote.RemoteFileSystem
import org.wexclient.app.vfs.remote.RemoteFileSystemProvider

/**
 * Телефон по adb как файловая система панели: общая память, папки приложений в Android/data,
 * /data/local/tmp — всё, куда Android пускает без root. Общая часть — в [RemoteFileSystem].
 */
class AdbFileSystem internal constructor(
    provider: AdbFileSystemProvider,
    override val connection: AdbConnection,
    override val home: String,
    /** Как показывать телефон: «Google Pixel 9 Pro XL». */
    val deviceName: String
) : RemoteFileSystem(provider) {

    val serial: String get() = connection.serial

    override val label: String get() = deviceName

    override val title: String get() = "ADB $deviceName"

    // У адреса Wi‑Fi в номере двоеточие, у mDNS-имени точки — для имени папки годится и то и другое
    override val cacheKey: String get() = "adb-" + serial.replace('/', '_')

    override val whereText: String get() = "На телефоне"

    override val kind: String get() = "adb"

    /** Общая память телефона (FUSE) ссылок не хранит: копируется то, на что ссылка указывает. */
    override val keepsLinks: Boolean get() = false

    override fun uriOf(path: String): URI = URI(AdbFileSystemProvider.SCHEME, serial, path, null, null)
}

/** Поставщик файловой системы «wexadb»: одна ФС на телефон, по серийному номеру. */
class AdbFileSystemProvider : RemoteFileSystemProvider() {

    private val systems = ConcurrentHashMap<String, AdbFileSystem>()

    override fun getScheme(): String = SCHEME

    /** Открыть телефон (или взять уже открытый). [name] — как его показывать в панели. */
    fun open(serial: String, name: String): AdbFileSystem {
        systems[serial]?.takeIf { it.isOpen }?.let { return it }
        val adb = AdbManager.adbPath()
            ?: throw AdbException("adb не найден. Установите android-tools (sudo pacman -S android-tools) или Android SDK Platform-Tools")
        val connection = AdbConnection(serial, adb)
        val home = connection.connect()
        val fs = AdbFileSystem(this, connection, home, name)
        systems[serial] = fs
        return fs
    }

    override fun forget(fs: RemoteFileSystem) {
        systems.values.remove(fs)
    }

    /** Открытые сейчас телефоны. */
    fun opened(): List<AdbFileSystem> = systems.values.filter { it.isOpen }

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem {
        val serial = uri.authority ?: throw IllegalArgumentException("Нет серийного номера")
        if (systems[serial]?.isOpen == true) throw FileSystemAlreadyExistsException(serial)
        return open(serial, serial)
    }

    override fun getFileSystem(uri: URI): FileSystem {
        val serial = uri.authority ?: throw IllegalArgumentException("Нет серийного номера")
        return systems[serial] ?: throw java.nio.file.FileSystemNotFoundException(serial)
    }

    override fun getPath(uri: URI): Path = getFileSystem(uri).getPath(uri.path ?: "/")

    companion object {
        const val SCHEME = "wexadb"

        /** Один поставщик на программу: у него список открытых телефонов. */
        val instance: AdbFileSystemProvider by lazy { AdbFileSystemProvider() }
    }
}
