package org.wexclient.app.dex

import com.strobel.assembler.metadata.Buffer
import com.strobel.assembler.metadata.ClasspathTypeLoader
import com.strobel.assembler.metadata.CompositeTypeLoader
import com.strobel.assembler.metadata.ITypeLoader
import com.strobel.decompiler.Decompiler
import com.strobel.decompiler.DecompilerSettings
import com.strobel.decompiler.PlainTextOutput
import org.benf.cfr.reader.api.CfrDriver
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Вкладки просмотра .class. */
enum class ClassView(val title: String) {
    VINEFLOWER("Vineflower"),
    CFR("CFR"),
    PROCYON("Procyon"),
    BYTECODE("Байт-код")
}

/** Заголовок .class: имя, версия, вид. */
class ClassFileInfo(
    /** com/example/Foo$Bar */
    val internalName: String,
    val major: Int,
    val minor: Int,
    val access: Int,
    val superName: String?,
    val kotlin: Boolean
) {
    val className: String get() = internalName.replace('/', '.')
    val simpleName: String get() = internalName.substringAfterLast('/')

    /** 65 → «Java 21», 48 → «Java 1.4» */
    val javaVersion: String
        get() {
            val v = if (major >= 49) "${major - 44}" else "1.${(major - 44).coerceAtLeast(0)}"
            return "Java $v" + if (minor == 0xFFFF) " (preview)" else ""
        }

    val kind: String
        get() = when {
            access and Opcodes.ACC_MODULE != 0 -> "модуль"
            access and Opcodes.ACC_ANNOTATION != 0 -> "аннотация"
            access and Opcodes.ACC_INTERFACE != 0 -> "интерфейс"
            access and Opcodes.ACC_ENUM != 0 -> "перечисление"
            superName == "java/lang/Record" -> "запись"
            else -> "класс"
        }
}

/**
 * Открытый класс и его «семья» из той же папки: собственные вложенные классы (Foo$1, Foo$Bar)
 * декомпилируются вместе с ним, внешний класс и соседние вложенные — только как контекст.
 */
class ClassFileSet(val info: ClassFileInfo, val classes: Map<String, ByteArray>) {
    val mainName: String get() = info.internalName

    fun isOwn(name: String) = name == mainName || name.startsWith("$mainName$")
}

object ClassFiles {
    private const val MAX_FAMILY = 500

    /** CAFEBABE и правдоподобная версия (у fat-бинарников Mach-O там число архитектур). */
    fun isClassFile(head: ByteArray): Boolean {
        if (head.size < 8) return false
        val magic = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
        return magic == 0xCAFEBABE.toInt() && major in 45..255
    }

    fun info(bytes: ByteArray): ClassFileInfo {
        if (!isClassFile(bytes)) throw IOException("Это не class-файл Java")
        val reader = try {
            ClassReader(bytes)
        } catch (e: Exception) {
            throw IOException("Повреждённый class-файл: ${e.message ?: e.javaClass.simpleName}")
        }
        return ClassFileInfo(
            internalName = reader.className,
            major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF),
            minor = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF),
            access = reader.access,
            superName = reader.superName,
            kotlin = String(bytes, Charsets.ISO_8859_1).contains("Lkotlin/Metadata;")
        )
    }

    /** Чтение и список папки — через VfsManager, чтобы работало и внутри архивов. */
    fun load(path: Path, readBytes: (Path) -> ByteArray, listDir: (Path) -> List<Path>): ClassFileSet {
        val bytes = readBytes(path)
        val info = info(bytes)
        val classes = LinkedHashMap<String, ByteArray>()
        classes[info.internalName] = bytes
        val fileName = path.fileName?.toString().orEmpty()
        val top = fileName.substringBeforeLast('.').substringBefore('$')
        val parent = path.parent
        if (parent != null && top.isNotEmpty()) {
            val family = runCatching { listDir(parent) }.getOrDefault(emptyList())
                .filter { p ->
                    val n = p.fileName?.toString() ?: return@filter false
                    n != fileName && n.endsWith(".class", ignoreCase = true) &&
                        (n.substringBeforeLast('.') == top || n.startsWith("$top$"))
                }
                .sortedBy { it.fileName.toString() }
                .take(MAX_FAMILY)
            for (p in family) {
                val b = runCatching { readBytes(p) }.getOrNull() ?: continue
                if (!isClassFile(b)) continue
                val name = runCatching { ClassReader(b).className }.getOrNull() ?: continue
                classes.putIfAbsent(name, b)
            }
        }
        return ClassFileSet(info, classes)
    }

    fun render(set: ClassFileSet, view: ClassView): String {
        return try {
            when (view) {
                ClassView.VINEFLOWER -> vineflower(set)
                ClassView.CFR -> cfr(set)
                ClassView.PROCYON -> unescapeUnicode(procyon(set))
                ClassView.BYTECODE -> unescapeUnicode(bytecode(set.classes.getValue(set.mainName)))
            }
        } catch (e: Exception) {
            failure(view, e)
        } catch (e: StackOverflowError) {
            failure(view, e)
        } catch (e: LinkageError) {
            failure(view, e)
        }
    }

    private fun failure(view: ClassView, e: Throwable) =
        "// ${view.title}: не удалось разобрать класс — ${e.message ?: e.javaClass.simpleName}\n"

    /** Листинг как у javap -c (ASM Textifier), без кадров стека. */
    fun bytecode(bytes: ByteArray): String {
        val out = StringWriter()
        ClassReader(bytes).accept(TraceClassVisitor(null, Textifier(), PrintWriter(out)), ClassReader.SKIP_FRAMES)
        return out.toString()
    }

    private fun vineflower(set: ClassFileSet): String = withClassFiles(set) { files ->
        val saver = VineflowerEngine.CollectingSaver()
        try {
            val options = VineflowerEngine.options() + mapOf(
                IFernflowerPreferences.INDENT_STRING to "    ",
                IFernflowerPreferences.HIDE_EMPTY_SUPER to "1"
            )
            val decompiler = BaseDecompiler(saver, options, IFernflowerLogger.NO_OP)
            for ((name, file) in files) {
                if (set.isOwn(name)) decompiler.addSource(file) else decompiler.addLibrary(file)
            }
            decompiler.decompileContext()
            saver.classes[set.mainName] ?: saver.classes[set.info.className]
                ?: "// Vineflower: класс ${set.info.className} не декомпилирован\n"
        } finally {
            runCatching { saver.close() }
        }
    }

    private fun cfr(set: ClassFileSet): String = withClassFiles(set) { files ->
        val collected = ConcurrentHashMap<String, String>()
        CfrDriver.Builder()
            .withOptions(mapOf("showversion" to "false", "hideutf" to "false"))
            .withOutputSink(CfrEngine.collectingSinkInto(collected))
            .build()
            .analyse(listOf(files.getValue(set.mainName).absolutePath))
        collected[set.info.className] ?: collected.values.firstOrNull()
            ?: "// CFR: класс ${set.info.className} не декомпилирован\n"
    }

    private fun procyon(set: ClassFileSet): String {
        val settings = DecompilerSettings.javaDefaults()
        settings.typeLoader = CompositeTypeLoader(MapTypeLoader(set.classes), ClasspathTypeLoader())
        val out = StringWriter()
        Decompiler.decompile(set.mainName, PlainTextOutput(out), settings)
        return out.toString()
    }

    private class MapTypeLoader(private val classes: Map<String, ByteArray>) : ITypeLoader {
        override fun tryLoadType(internalName: String, buffer: Buffer): Boolean {
            val bytes = classes[internalName] ?: return false
            buffer.reset(bytes.size)
            buffer.putByteArray(bytes, 0, bytes.size)
            buffer.position(0)
            return true
        }
    }

    /**
     * CFR и Vineflower читают файлы: семья раскладывается по пакетам во временной папке.
     * Имя берётся из самого класса, поэтому «../» в нём не должно выводить за её пределы.
     */
    private fun <T> withClassFiles(set: ClassFileSet, block: (Map<String, File>) -> T): T {
        val dir = org.wexclient.app.util.ProcessScratch.work.newDir("class-")
        try {
            val files = LinkedHashMap<String, File>()
            for ((name, bytes) in set.classes) {
                val target = safeTarget(dir, name)
                val written = target != null && runCatching {
                    Files.createDirectories(target.parent)
                    Files.write(target, bytes)
                }.isSuccess
                if (written) files[name] = target!!.toFile()
                else if (name == set.mainName) throw IOException("Недопустимое имя класса: $name")
            }
            return block(files)
        } finally {
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    internal fun safeTarget(dir: Path, internalName: String): Path? {
        val parts = internalName.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || it.any { c -> c.code < 32 } }) return null
        val target = dir.resolve("$internalName.class").normalize()
        return target.takeIf { it.startsWith(dir) && it != dir }
    }

    /**
     * \u041f → П: Procyon и ASM экранируют всё, что не ASCII. Управляющие, невидимые
     * и одиночные суррогаты остаются как есть, `\\u` — тоже.
     */
    fun unescapeUnicode(text: String): String {
        if (!text.contains("\\u")) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                sb.append(c)
                i++
                continue
            }
            if (text[i + 1] == 'u') {
                val code = hex4(text, i + 2)
                if (code >= 0) {
                    val ch = code.toChar()
                    if (Character.isHighSurrogate(ch)) {
                        val low = if (text.startsWith("\\u", i + 6)) hex4(text, i + 8) else -1
                        if (low >= 0 && Character.isLowSurrogate(low.toChar()) &&
                            visible(Character.toCodePoint(ch, low.toChar()))
                        ) {
                            sb.append(ch).append(low.toChar())
                            i += 12
                            continue
                        }
                    } else if (!Character.isSurrogate(ch) && visible(code)) {
                        sb.append(ch)
                        i += 6
                        continue
                    }
                }
            }
            // Любое другое экранирование переносим целиком, вместе со следующим символом
            sb.append(c).append(text[i + 1])
            i += 2
        }
        return sb.toString()
    }

    private fun hex4(s: String, from: Int): Int {
        if (from + 4 > s.length) return -1
        var v = 0
        for (k in from until from + 4) {
            val d = Character.digit(s[k], 16)
            if (d < 0) return -1
            v = v * 16 + d
        }
        return v
    }

    private val hiddenTypes = setOf(
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.SPACE_SEPARATOR, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE
    ).map { it.toInt() }.toSet()

    private fun visible(codePoint: Int): Boolean = codePoint >= 0xA0 && Character.getType(codePoint) !in hiddenTypes
}
