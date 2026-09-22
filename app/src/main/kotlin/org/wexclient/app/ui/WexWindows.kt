package org.wexclient.app.ui

import androidx.compose.runtime.mutableStateListOf
import java.nio.file.Files
import java.nio.file.Path
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType

/**
 * Окна программы: менеджер и отдельные окна редактора. Один процесс держит их все — открытие
 * файла из другой программы не поднимает вторую копию WEX и не морозит на запуск.
 *
 * Текстовый файл открывается своим окном редактора, без панелей за ним. Папка и всё остальное
 * (APK, архив, картинка, PDF) — в окне менеджера.
 */
object WexWindows {

    class ManagerWindow(
        val id: Int,
        val startPath: Path?,
        val primary: Boolean,
        /** Имя файла, который нужно подсветить в папке [startPath] («Расположение»). */
        val revealName: String? = null
    )

    /** Путь, который должно показать уже открытое окно менеджера. */
    class Pending(val managerId: Int, val path: Path, val revealName: String?)

    class EditorWindow(val id: Int, val file: Path)

    val managers = mutableStateListOf<ManagerWindow>()
    val editors = mutableStateListOf<EditorWindow>()

    /** Окно, которое просят показать поверх остальных (его id); сбрасывается самим окном. */
    var raise: Int? = null
        private set

    private var nextId = 1

    val isEmpty: Boolean get() = managers.isEmpty() && editors.isEmpty()

    /** Путь из командной строки или от другого запуска WEX — в нужное окно. */
    @Synchronized
    fun open(path: Path?) {
        when {
            path == null -> openManager(null)
            Files.isDirectory(path) -> openManager(path)
            opensInEditor(path) -> openEditor(path)
            else -> openManager(path)
        }
    }

    @Synchronized
    fun openManager(path: Path?, revealName: String? = null) {
        // Папку показывает уже открытый менеджер (новой вкладкой) — это делает он сам
        val existing = managers.firstOrNull()
        if (path != null && existing != null) {
            raise = existing.id
            pendingForManager.add(Pending(existing.id, path, revealName))
            return
        }
        managers.add(ManagerWindow(nextId++, path, primary = managers.isEmpty(), revealName = revealName))
    }

    /** «Расположение» из окна редактора: показать файл в папке, а не открыть его заново. */
    fun reveal(file: Path) {
        val dir = file.parent
        if (dir == null) openManager(file) else openManager(dir, file.fileName?.toString())
    }

    @Synchronized
    fun openEditor(file: Path) {
        val same = editors.firstOrNull { it.file == file }
        if (same != null) {
            raise = same.id
            return
        }
        editors.add(EditorWindow(nextId++, file))
    }

    @Synchronized
    fun close(window: ManagerWindow) {
        managers.remove(window)
    }

    @Synchronized
    fun close(window: EditorWindow) {
        editors.remove(window)
    }

    @Synchronized
    fun raised(id: Int) {
        if (raise == id) raise = null
    }

    private val pendingForManager = mutableStateListOf<Pending>()

    /** Что ждёт это окно (не забирая) — окно следит за этим состоянием. */
    fun pendingFor(managerId: Int): Pending? = pendingForManager.firstOrNull { it.managerId == managerId }

    @Synchronized
    fun takePending(managerId: Int): Pending? {
        val index = pendingForManager.indexOfFirst { it.managerId == managerId }
        if (index < 0) return null
        return pendingForManager.removeAt(index)
    }

    /**
     * Файл, который открывается сразу редактором: текст, код, разметка. Двоичное, картинки,
     * архивы, APK и документы идут в менеджер — там для каждого свой просмотрщик.
     */
    fun opensInEditor(path: Path): Boolean = runCatching {
        if (!Files.isRegularFile(path)) return reject(path, "не обычный файл")
        val name = path.fileName?.toString() ?: return false
        val type = VfsEntry.detectType(name, false)
        if (type in NOT_TEXT) return reject(path, "тип $type")
        val size = Files.size(path)
        if (size > MAX_EDITOR_BYTES) return reject(path, "размер $size")
        val head = ByteArray(8192)
        val read = Files.newInputStream(path).use { it.read(head) }.coerceAtLeast(0)
        if (org.wexclient.app.util.TextFiles.looksBinary(head, read)) return reject(path, "похоже на двоичный")
        true
    }.getOrElse { e ->
        reject(path, "ошибка ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Почему файл пошёл не в редактор — видно при WEX_STARTUP_LOG=1. */
    private fun reject(path: Path, why: String): Boolean {
        if (org.wexclient.app.util.Startup.enabled) {
            org.wexclient.app.util.Startup.mark("«${path.fileName}» не в редактор: $why")
        }
        return false
    }

    /**
     * Предел текста для отдельного окна редактора. Раньше было два мегабайта: больше просто не
     * открывалось за разумное время. Теперь большой файл показывает быстрый редактор, который
     * рисует только видимые строки, поэтому предел тот же, что и у панели.
     */
    private const val MAX_EDITOR_BYTES = 10L * 1024 * 1024

    private val NOT_TEXT = setOf(
        VfsItemType.FOLDER, VfsItemType.PARENT_DIR,
        VfsItemType.ARCHIVE_APK, VfsItemType.ARCHIVE_APKS, VfsItemType.ARCHIVE_ZIP,
        VfsItemType.ARCHIVE_JAR, VfsItemType.ARCHIVE_AAR, VfsItemType.APPIMAGE,
        VfsItemType.DEX, VfsItemType.JAVA_CLASS, VfsItemType.ARSC, VfsItemType.AXML_MANIFEST,
        VfsItemType.NATIVE_SO, VfsItemType.KEYSTORE, VfsItemType.CERTIFICATE,
        VfsItemType.IMAGE, VfsItemType.VIDEO, VfsItemType.AUDIO, VfsItemType.AUDIO_VIDEO,
        VfsItemType.DATABASE, VfsItemType.DOCUMENT_PDF, VfsItemType.DOCUMENT,
        VfsItemType.SPREADSHEET, VfsItemType.PRESENTATION, VfsItemType.FONT,
        VfsItemType.PACKAGE, VfsItemType.BINARY, VfsItemType.SPECIAL
    )
}
