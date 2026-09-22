package org.wexclient.app.vfs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Что можно вставить в папку. */
sealed interface ClipContent {
    data class FileList(val paths: List<Path>, val cut: Boolean) : ClipContent
    class Picture(val image: BufferedImage) : ClipContent
    data class PlainText(val text: String) : ClipContent
}

/**
 * Файлы в системном буфере обмена: Ctrl+C / Ctrl+X в WEX → вставка в Dolphin/Nautilus и обратно.
 * Список файлов — text/uri-list (javaFileListFlavor), «вырезать» — форматы GNOME и KDE.
 */
object FileClipboard {
    private val gnomeFlavor = DataFlavor("x-special/gnome-copied-files;class=java.io.InputStream", "GNOME files")
    private val kdeCutFlavor = DataFlavor("application/x-kde-cutselection;class=java.io.InputStream", "KDE cut")

    init {
        (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)?.let { map ->
            map.addUnencodedNativeForFlavor(gnomeFlavor, "x-special/gnome-copied-files")
            map.addFlavorForUnencodedNative("x-special/gnome-copied-files", gnomeFlavor)
            map.addUnencodedNativeForFlavor(kdeCutFlavor, "application/x-kde-cutselection")
            map.addFlavorForUnencodedNative("application/x-kde-cutselection", kdeCutFlavor)
        }
    }

    private class Owned(val paths: List<Path>, val cut: Boolean, val transferable: Transferable)

    // Свои данные: пути внутри архивов в системный буфер не передать, но вставлять их можно
    @Volatile
    private var owned: Owned? = null

    /** Вырезанные пути — в списке они полупрозрачные. */
    var cutPaths by mutableStateOf<Set<Path>>(emptySet())
        private set

    private val owner = ClipboardOwner { _, contents ->
        if (owned?.transferable === contents) {
            owned = null
            cutPaths = emptySet()
        }
    }

    /** Системный буфер можно подменить в тестах. */
    internal var clipboardProvider: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard }

    fun copy(paths: List<Path>) = put(paths, cut = false)

    fun cut(paths: List<Path>) = put(paths, cut = true)

    private fun put(paths: List<Path>, cut: Boolean) {
        if (paths.isEmpty()) return
        val transferable = FilesTransferable(paths, cut)
        owned = Owned(paths, cut, transferable)
        cutPaths = if (cut) paths.toSet() else emptySet()
        runCatching { clipboardProvider().setContents(transferable, owner) }
    }

    /**
     * После вставки вырезанного буфер пустеет — повторная вставка не пытается перенести ещё раз.
     * Вырезанное в другой программе (Dolphin, Nautilus) тоже убирается, если перенесено всё ([moved]):
     * так делают и они. Читает системный буфер — вызывать не из потока интерфейса.
     */
    fun clearCut(moved: Collection<Path> = emptyList()) {
        val o = owned
        if (o == null) {
            if (moved.isEmpty()) return
            val current = runCatching { read() }.getOrNull() as? ClipContent.FileList ?: return
            if (!current.cut || current.paths.isEmpty() || !moved.containsAll(current.paths)) return
        } else {
            if (!o.cut) return
            owned = null
            cutPaths = emptySet()
        }
        runCatching { clipboardProvider().setContents(java.awt.datatransfer.StringSelection(""), null) }
    }

    fun read(): ClipContent? {
        val clipboard = runCatching { clipboardProvider() }.getOrNull() ?: return null
        owned?.let { o ->
            val current = runCatching { clipboard.getContents(null) }.getOrNull()
            // Буфер по-прежнему наш (или систему не спросить) — берём и пути внутри архивов
            if (current == null || current === o.transferable) return ClipContent.FileList(o.paths, o.cut)
        }
        val flavors = runCatching { clipboard.availableDataFlavors.toList() }.getOrDefault(emptyList())
        fun has(f: DataFlavor) = flavors.any { it.equals(f) }
        runCatching {
            if (has(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>
                if (files.isNotEmpty()) return ClipContent.FileList(files.map { it.toPath() }, isCut(clipboard, flavors))
            }
        }
        runCatching {
            if (has(gnomeFlavor)) {
                val (cut, paths) = parseGnome(readStream(clipboard.getData(gnomeFlavor)))
                if (paths.isNotEmpty()) return ClipContent.FileList(paths, cut)
            }
        }
        runCatching {
            if (has(DataFlavor.imageFlavor)) {
                val img = clipboard.getData(DataFlavor.imageFlavor) as Image
                return ClipContent.Picture(toBuffered(img))
            }
        }
        runCatching {
            if (has(DataFlavor.stringFlavor)) {
                val text = clipboard.getData(DataFlavor.stringFlavor) as String
                // Скопированные как текст пути (например, из терминала) — тоже файлы
                pathsFromText(text)?.let { return ClipContent.FileList(it, cut = false) }
                if (text.isNotEmpty()) return ClipContent.PlainText(text)
            }
        }
        return null
    }

    /** Тот же набор форматов, что при Ctrl+C, — для перетаскивания в другие программы. */
    internal fun transferable(paths: List<Path>): Transferable = FilesTransferable(paths, cut = false)

    /** Файлы из перетаскивания другой программы: список файлов, формат GNOME или пути текстом. */
    fun filesFrom(t: Transferable): List<Path>? {
        val flavors = runCatching { t.transferDataFlavors.toList() }.getOrDefault(emptyList())
        fun has(f: DataFlavor) = flavors.any { it.equals(f) }
        runCatching {
            if (has(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                if (files.isNotEmpty()) return files.map { it.toPath() }
            }
        }
        runCatching {
            if (has(gnomeFlavor)) {
                val (_, paths) = parseGnome(readStream(t.getTransferData(gnomeFlavor)))
                if (paths.isNotEmpty()) return paths
            }
        }
        runCatching {
            val uriList = flavors.firstOrNull { it.mimeType.startsWith("text/uri-list") && it.representationClass == String::class.java }
            if (uriList != null) {
                val paths = (t.getTransferData(uriList) as String).lines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull(::uriToPath)
                if (paths.isNotEmpty()) return paths
            }
        }
        runCatching {
            if (has(DataFlavor.stringFlavor)) pathsFromText(t.getTransferData(DataFlavor.stringFlavor) as String)?.let { return it }
        }
        return null
    }

    /** Похоже ли перетаскиваемое на файлы (данные до отпускания читать нельзя — только форматы). */
    fun mayContainFiles(flavors: List<DataFlavor>): Boolean = flavors.any {
        it.equals(DataFlavor.javaFileListFlavor) || it.equals(gnomeFlavor) || it.mimeType.startsWith("text/uri-list")
    }

    private fun isCut(clipboard: Clipboard, flavors: List<DataFlavor>): Boolean = runCatching {
        when {
            flavors.any { it.equals(gnomeFlavor) } -> parseGnome(readStream(clipboard.getData(gnomeFlavor))).first
            flavors.any { it.equals(kdeCutFlavor) } -> readStream(clipboard.getData(kdeCutFlavor)).trim() == "1"
            else -> false
        }
    }.getOrDefault(false)

    private fun readStream(data: Any?): String = when (data) {
        is InputStream -> data.use { it.readAllBytes().decodeToString() }
        is ByteArray -> data.decodeToString()
        is String -> data
        else -> ""
    }

    /** «cut\nfile:///a\nfile:///b» → (true, [/a, /b]) */
    fun parseGnome(text: String): Pair<Boolean, List<Path>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false to emptyList()
        val cut = lines[0] == "cut"
        val uris = if (lines[0] == "cut" || lines[0] == "copy") lines.drop(1) else lines
        return cut to uris.mapNotNull(::uriToPath)
    }

    fun uriToPath(uri: String): Path? = runCatching {
        if (!uri.startsWith("file:")) return null
        Paths.get(URI(uri))
    }.getOrNull()

    fun pathToUri(p: Path): String = "file://" + Trash.encodePath(p.toAbsolutePath().normalize().toString())

    private fun pathsFromText(text: String): List<Path>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > 10_000) return null
        val paths = lines.map { line ->
            when {
                line.startsWith("file://") -> uriToPath(line)
                line.startsWith("/") -> runCatching { Paths.get(line) }.getOrNull()
                else -> null
            } ?: return null
        }
        return paths.takeIf { it.all { p -> Files.exists(p) } }
    }

    private fun toBuffered(img: Image): BufferedImage {
        if (img is BufferedImage) return img
        val w = img.getWidth(null).coerceAtLeast(1)
        val h = img.getHeight(null).coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    private class FilesTransferable(val paths: List<Path>, val cut: Boolean) : Transferable {
        private val local = paths.filter { it.fileSystem === FileSystems.getDefault() }

        private val flavors: Array<DataFlavor> = buildList {
            if (local.isNotEmpty()) {
                add(DataFlavor.javaFileListFlavor)
                add(gnomeFlavor)
                add(kdeCutFlavor)
            }
            add(DataFlavor.stringFlavor)
        }.toTypedArray()

        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.clone()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it.equals(flavor) }

        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor.equals(DataFlavor.javaFileListFlavor) -> local.map { it.toFile() }
            flavor.equals(gnomeFlavor) ->
                ByteArrayInputStream(((if (cut) "cut" else "copy") + local.joinToString("") { "\n" + pathToUri(it) }).toByteArray())
            flavor.equals(kdeCutFlavor) -> ByteArrayInputStream((if (cut) "1" else "0").toByteArray())
            flavor.equals(DataFlavor.stringFlavor) -> paths.joinToString("\n") { it.toString() }
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
