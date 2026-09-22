package org.wexclient.app.deb

import org.wexclient.app.vfs.archive.ArchiveFormats
import org.wexclient.app.vfs.archive.Compression
import org.wexclient.app.vfs.archive.TarCodec
import org.wexclient.app.vfs.archive.TarEntryKind
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Пакет Debian (.deb) — это архив `ar` из трёх частей: «debian-binary» с версией формата,
 * «control.tar.*» с описанием пакета и скриптами установки и «data.tar.*» с самими файлами.
 *
 * Разбираем сами: формат `ar` простой, а tar внутри читает [TarCodec]. Устанавливать пакет
 * WEX не берётся — это дело менеджера пакетов системы; мы показываем, что внутри, и умеем
 * достать содержимое.
 */
object DebPackage {

    /** Часть контейнера `ar`. */
    class Member(val name: String, val offset: Long, val size: Long)

    /** Файл из data.tar. */
    class Item(val path: String, val size: Long, val mode: Int, val link: String?, val directory: Boolean)

    /** Скрипт установки из control.tar (preinst, postinst, prerm, postrm, config). */
    class Script(val name: String, val text: String)

    class Info(
        val members: List<Member>,
        /** Поля файла control: Package, Version, Architecture, Depends… */
        val fields: Map<String, String>,
        val description: String,
        val scripts: List<Script>,
        val items: List<Item>,
        /** Файлов в data.tar (может быть больше, чем в [items]). */
        val fileCount: Int,
        val payloadBytes: Long,
        /** Список файлов обрезан — их слишком много. */
        val truncated: Boolean
    ) {
        val name: String get() = fields["Package"] ?: "—"
        val version: String get() = fields["Version"] ?: "—"
        val architecture: String get() = fields["Architecture"] ?: "—"

        /** Заявленный размер после установки, байт (в control он в килобайтах). */
        val installedBytes: Long get() = (fields["Installed-Size"]?.trim()?.toLongOrNull() ?: 0L) * 1024L

        fun dependencies(): List<String> = fields["Depends"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    /** Первые байты файла — это точно .deb? */
    fun looksLikeDeb(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /**
     * Оглавление контейнера `ar`: заголовок каждой части — 60 байт открытым текстом,
     * данные выровнены по чётной границе.
     */
    fun members(path: Path): List<Member> {
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            val magic = ByteArray(MAGIC.size)
            if (readFully(ch, 0, magic) < MAGIC.size || !looksLikeDeb(magic)) {
                throw IOException("это не пакет .deb")
            }
            val out = ArrayList<Member>(4)
            var pos = MAGIC.size.toLong()
            val header = ByteArray(HEADER)
            while (pos + HEADER <= size && out.size < MAX_MEMBERS) {
                if (readFully(ch, pos, header) < HEADER) break
                if (header[58].toInt() != 0x60 || header[59].toInt() != 0x0a) break
                val name = String(header, 0, 16, Charsets.UTF_8).trim().trimEnd('/')
                val length = String(header, 48, 10, Charsets.UTF_8).trim().toLongOrNull() ?: break
                if (length < 0 || pos + HEADER + length > size) break
                out.add(Member(name, pos + HEADER, length))
                pos += HEADER + length
                if (length % 2 == 1L) pos++
            }
            if (out.isEmpty()) throw IOException("пакет пуст или повреждён")
            return out
        }
    }

    /** Достать часть контейнера в отдельный файл (control.tar.zst, data.tar.xz…). */
    fun extractMember(path: Path, member: Member, target: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(1 shl 16)
                var left = member.size
                var at = member.offset
                while (left > 0) {
                    val want = minOf(left, buffer.size.toLong()).toInt()
                    val read = readFully(ch, at, buffer, want)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    at += read
                    left -= read
                }
            }
        }
    }

    /** Разбор пакета: описание, скрипты и список файлов. Временные файлы удаляются сами. */
    fun read(path: Path, workDir: Path? = null): Info {
        val members = members(path)
        val work = workDir ?: Files.createTempDirectory("wex-deb")
        val ownWork = workDir == null
        try {
            var fields: Map<String, String> = emptyMap()
            var description = ""
            var scripts: List<Script> = emptyList()
            var items: List<Item> = emptyList()
            var fileCount = 0
            var payload = 0L
            var truncated = false

            members.firstOrNull { it.name.startsWith("control.tar") }?.let { member ->
                val tar = unpackTar(path, member, work.resolve("control.tar"))
                if (tar != null) {
                    FileChannel.open(tar, StandardOpenOption.READ).use { ch ->
                        val found = ArrayList<Script>()
                        var control = ""
                        TarCodec.read(ch) { record ->
                            val clean = record.rawPath.removePrefix("./").trimEnd('/')
                            if (record.kind == TarEntryKind.FILE && record.size in 1..MAX_TEXT) {
                                when {
                                    clean == "control" -> control = readText(ch, record.dataOffset, record.size)
                                    clean in SCRIPTS -> found.add(Script(clean, readText(ch, record.dataOffset, record.size)))
                                }
                            }
                        }
                        val parsed = parseControl(control)
                        fields = parsed.first
                        description = parsed.second
                        scripts = found.sortedBy { it.name }
                    }
                }
            }

            members.firstOrNull { it.name.startsWith("data.tar") }?.let { member ->
                val tar = unpackTar(path, member, work.resolve("data.tar"))
                if (tar != null) {
                    FileChannel.open(tar, StandardOpenOption.READ).use { ch ->
                        val list = ArrayList<Item>()
                        TarCodec.read(ch) { record ->
                            val directory = record.kind == TarEntryKind.DIR
                            if (!directory) {
                                fileCount++
                                payload += record.size
                            }
                            if (list.size < MAX_ITEMS) {
                                list.add(
                                    Item(
                                        path = "/" + record.rawPath.removePrefix("./").trimEnd('/'),
                                        size = record.size,
                                        mode = record.mode,
                                        link = record.link,
                                        directory = directory
                                    )
                                )
                            } else {
                                truncated = true
                            }
                        }
                        items = list
                    }
                }
            }

            return Info(members, fields, description, scripts, items, fileCount, payload, truncated)
        } finally {
            if (ownWork) runCatching { deleteTree(work) }
        }
    }

    /**
     * Часть контейнера в распакованный tar. Сжатие определяем по началу файла: у разных сборок
     * это gzip, xz или zstd, а расширение иногда врёт.
     */
    fun unpackTar(deb: Path, member: Member, target: Path): Path? = runCatching {
        val raw = target.resolveSibling(target.fileName.toString() + ".raw")
        extractMember(deb, member, raw)
        val head = ByteArray(8)
        Files.newInputStream(raw).use { it.read(head) }
        val compression = Compression.detect(head) ?: Compression.NONE
        if (compression == Compression.NONE) return@runCatching raw
        ArchiveFormats.decompress(compression, raw, target, null)
        runCatching { Files.deleteIfExists(raw) }
        target
    }.getOrNull()

    /**
     * Поля control: «Ключ: значение», продолжение строки начинается с пробела.
     * Description особый: первая строка — короткая, дальше — подробное описание.
     */
    fun parseControl(text: String): Pair<Map<String, String>, String> {
        val fields = LinkedHashMap<String, String>()
        val description = StringBuilder()
        var key: String? = null
        for (raw in text.lineSequence()) {
            if (raw.isEmpty()) continue
            if (raw[0] == ' ' || raw[0] == '\t') {
                val line = raw.trim()
                if (key == "Description") {
                    description.append(if (line == ".") "" else line).append('\n')
                } else if (key != null) {
                    fields[key] = (fields[key].orEmpty() + " " + line).trim()
                }
                continue
            }
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            key = raw.substring(0, colon).trim()
            fields[key] = raw.substring(colon + 1).trim()
        }
        return fields to description.toString().trim()
    }

    private fun readText(ch: FileChannel, offset: Long, size: Long): String {
        val bytes = ByteArray(minOf(size, MAX_TEXT).toInt())
        readFully(ch, offset, bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readFully(ch: FileChannel, offset: Long, buffer: ByteArray, length: Int = buffer.size): Int {
        val bb = java.nio.ByteBuffer.wrap(buffer, 0, length)
        var at = offset
        var total = 0
        while (bb.hasRemaining()) {
            val read = ch.read(bb, at)
            if (read <= 0) break
            at += read
            total += read
        }
        return total
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private val MAGIC = "!<arch>\n".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 60
    private const val MAX_MEMBERS = 64
    private const val MAX_ITEMS = 50_000
    private const val MAX_TEXT = 512L * 1024

    private val SCRIPTS = setOf("preinst", "postinst", "prerm", "postrm", "config", "triggers", "conffiles", "md5sums")
}
