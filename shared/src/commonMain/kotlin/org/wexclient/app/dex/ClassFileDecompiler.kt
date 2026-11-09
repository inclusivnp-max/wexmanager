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
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import java.util.zip.ZipFile
import org.wexclient.app.util.readUpTo
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
    val kotlin: Boolean,
    val enclosingName: String? = null
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
class ClassFileSet(
    val info: ClassFileInfo,
    val classes: Map<String, ByteArray>,
    private val knownInfo: Map<String, ClassFileInfo>? = null
) {
    val mainName: String get() = info.internalName
    private val metadata by lazy { knownInfo ?: classes.mapValues { (name, bytes) -> if (name == mainName) info else ClassFiles.info(bytes) } }
    internal val familyIndex by lazy { ClassFamilyIndex(classes.keys, { it }) { metadata.getValue(it).enclosingName } }
    internal val family: ClassFamily<String> get() = familyIndex.familyOf(mainName)
    fun infoOf(name: String): ClassFileInfo = metadata.getValue(name)
    fun forClass(name: String) = ClassFileSet(infoOf(name), classes, metadata)
    fun isOwn(name: String) = familyIndex.isWithin(name, mainName)
    internal val sourceRoots: List<String> get() {
        val owned = classes.keys.filter(::isOwn).toSet()
        return listOf(mainName) + owned.filter { it != mainName && infoOf(it).enclosingName !in owned }.sorted()
    }
}

object ClassFiles {

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
        var enclosing: String? = null
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitOuterClass(owner: String, name: String?, descriptor: String?) { enclosing = owner }
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if (name == reader.className && outerName != null) enclosing = outerName
            }
            override fun visitNestHost(nestHost: String) { if (enclosing == null) enclosing = nestHost }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ClassFileInfo(
            internalName = reader.className,
            major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF),
            minor = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF),
            access = reader.access,
            superName = reader.superName,
            kotlin = String(bytes, Charsets.ISO_8859_1).contains("Lkotlin/Metadata;"),
            enclosingName = enclosing
        )
    }

    /** Чтение и список папки — через VfsManager, чтобы работало и внутри архивов. */
    fun load(path: Path, readBytes: (Path) -> ByteArray, listDir: (Path) -> List<Path>): ClassFileSet {
        val bytes = readBytes(path)
        val info = info(bytes)
        val classes = LinkedHashMap<String, ByteArray>()
        classes[info.internalName] = bytes
        val fileName = path.fileName?.toString().orEmpty()
        val top = info.simpleName.substringBefore('$')
        val parent = path.parent
        if (parent != null && top.isNotEmpty()) {
            val family = runCatching { listDir(parent) }.getOrDefault(emptyList())
                .filter { p ->
                    val n = p.fileName?.toString() ?: return@filter false
                    n != fileName && n.endsWith(".class", ignoreCase = true) &&
                        (n.substringBeforeLast('.') == top || n.startsWith("$top$"))
                }
                .sortedBy { it.fileName.toString() }
            if (family.size + 1 > ClassFamilyLimits.MAX_MEMBERS) throw IOException("Слишком много частей класса: ${family.size + 1}")
            var totalBytes = bytes.size
            for (p in family) {
                val b = runCatching { readBytes(p) }.getOrNull() ?: continue
                if (!isClassFile(b)) continue
                val name = runCatching { ClassReader(b).className }.getOrNull() ?: continue
                totalBytes += b.size
                if (totalBytes > ClassFamilyLimits.MAX_BYTES) throw IOException("Части класса занимают больше 64 МБ")
                classes.putIfAbsent(name, b)
            }
        }
        return ClassFileSet(info, classes)
    }

    /** Контекст всей семьи из всех DEX-jar, а не один файл без вложенных классов. */
    internal fun fromJars(jars: List<String>, mainName: String, contextNames: Set<String> = emptySet()): ClassFileSet? {
        val internal = mainName.replace('.', '/')
        val top = internal.substringBefore('$')
        val wanted = contextNames.map { it.replace('.', '/') }.toSet()
        val classes = LinkedHashMap<String, ByteArray>()
        var total = 0
        for (jar in jars) ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val name = entry.name.removeSuffix(".class")
                if (name != internal && name != top && !name.startsWith("$top$") && name !in wanted) continue
                if (name in classes) continue
                if (classes.size >= ClassFamilyLimits.MAX_MEMBERS) throw IOException("Слишком много частей класса")
                val budget = ClassFamilyLimits.MAX_BYTES - total
                if (entry.size > budget) throw IOException("Части класса занимают больше 64 МБ")
                val bytes = zip.getInputStream(entry).use { it.readUpTo(budget + 1) }
                if (bytes.size > budget) throw IOException("Части класса занимают больше 64 МБ")
                val found = info(bytes).internalName
                if (found != name) continue
                total += bytes.size
                classes[name] = bytes
            }
        }
        val main = classes[internal] ?: return null
        return ClassFileSet(info(main), completeMemberTables(classes))
    }

    /** DEX может хранить связь только у дочернего класса; CFR нужен список и у владельца. */
    private fun completeMemberTables(classes: Map<String, ByteArray>): Map<String, ByteArray> {
        data class Member(val name: String, val owner: String, val simple: String?, val access: Int)
        val members = ArrayList<Member>()
        for ((own, bytes) in classes) {
            var enclosing: String? = null
            var inMethod = false
            var self: Member? = null
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
                    enclosing = owner
                    inMethod = name != null
                }
                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    if (name == own) self = Member(name, outerName.orEmpty(), innerName, access)
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            self?.let { entry ->
                val owner = entry.owner.ifEmpty { if (!inMethod && entry.simple != null) enclosing.orEmpty() else "" }
                if (owner in classes) members.add(entry.copy(owner = owner))
            }
        }
        val byOwner = members.groupBy { it.owner }
        val byName = members.associateBy { it.name }
        return classes.mapValues { (name, bytes) ->
            val children = byOwner[name].orEmpty()
            if (children.isEmpty() && name !in byName) return@mapValues bytes
            val reader = ClassReader(bytes)
            val writer = ClassWriter(reader, 0)
            val existing = HashSet<String>()
            reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    existing.add(name)
                    val known = byName[name]
                    super.visitInnerClass(name, known?.owner ?: outerName, innerName, access)
                }
                override fun visitOuterClass(owner: String, method: String?, descriptor: String?) {
                    if (method != null || name !in byName) super.visitOuterClass(owner, method, descriptor)
                }
                override fun visitEnd() {
                    children.filter { it.name !in existing }.forEach { super.visitInnerClass(it.name, it.owner, it.simple, it.access) }
                    super.visitEnd()
                }
            }, 0)
            writer.toByteArray()
        }
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
            val code = saver.classes[set.mainName] ?: saver.classes[set.info.className]
                ?: return@withClassFiles "// Vineflower: класс ${set.info.className} не декомпилирован\n"
            val extra = saver.classes.entries.filter { (name, _) -> name != set.mainName && name != set.info.className && set.isOwn(name.replace('.', '/')) }
                .sortedBy { it.key }.map { it.key to it.value }
            joinClassSources(listOf(set.info.className to code) + extra)
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
            .analyse(set.sourceRoots.map { files.getValue(it).absolutePath })
        val code = collected[set.info.className] ?: return@withClassFiles "// CFR: класс ${set.info.className} не декомпилирован\n"
        val extra = collected.entries.filter { (name, _) -> name != set.info.className && set.isOwn(name.replace('.', '/')) }.sortedBy { it.key }.map { it.key to it.value }
        joinClassSources(listOf(set.info.className to code) + extra)
    }

    private fun procyon(set: ClassFileSet): String {
        val settings = DecompilerSettings.javaDefaults()
        settings.typeLoader = CompositeTypeLoader(MapTypeLoader(set.classes), ClasspathTypeLoader())
        return joinClassSources(set.sourceRoots.map { name ->
            val out = StringWriter()
            Decompiler.decompile(name, PlainTextOutput(out), settings)
            name.replace('/', '.') to out.toString()
        })
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
