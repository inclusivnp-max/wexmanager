package org.wexclient.app.dex

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import com.tim.dsmali.DexClassEditor
import com.tim.dsmali.io.SmaliWriter
import com.tim.dsmali.model.SmaliClass
import kotlinx.coroutines.sync.Mutex
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import java.io.File

data class DexSource(
    val name: String,
    val bytes: ByteArray,
    val tempFile: File? = null,
    /** Откуда DEX прочитан — туда же пишется после правки smali (null — только просмотр). */
    val path: java.nio.file.Path? = null
)

data class DexClassNode(
    val fullName: String,
    val simpleName: String,
    val packageName: String,
    val classDef: ClassDef,
    val dexSourceName: String = ""
) {
    val isInterface: Boolean get() = (classDef.accessFlags and 0x0200) != 0
    val isEnum: Boolean get() = (classDef.accessFlags and 0x4000) != 0
    val isAbstract: Boolean get() = (classDef.accessFlags and 0x0400) != 0
    val isAnnotation: Boolean get() = (classDef.accessFlags and 0x2000) != 0
}

data class DexPackageNode(
    val name: String,
    val fullPath: String,
    val subPackages: List<DexPackageNode>,
    val classes: List<DexClassNode>
) {
    val totalClassesCount: Int by lazy {
        classes.size + subPackages.sumOf { it.totalClassesCount }
    }
}

enum class DexSearchMode(val label: String) {
    CLASSES("Классы"),
    METHODS("Методы"),
    FIELDS("Поля"),
    STRINGS("Строки")
}

data class DexSearchResult(
    val title: String,
    val subtitle: String,
    val classNode: DexClassNode?,
    val type: DexSearchMode
)

class DexEditorEngine(
    initialSources: List<DexSource>
) {
    constructor(dexBytes: ByteArray, tempDexFile: File? = null, path: java.nio.file.Path? = null) : this(
        listOf(DexSource("classes.dex", dexBytes, tempDexFile, path))
    )

    /** Текущие DEX (после пересборки с правками smali — новые байты). */
    @Volatile
    var sources: List<DexSource> = initialSources
        private set

    private val opcodes = Opcodes.getDefault()

    val dexFiles: List<Pair<DexSource, DexBackedDexFile>> = sources.map { src ->
        src to DexBackedDexFile.fromInputStream(opcodes, java.io.ByteArrayInputStream(src.bytes))
    }

    val dexFile: DexBackedDexFile get() = dexFiles.first().second

    val classes: List<DexClassNode> = dexFiles.flatMap { (src, df) ->
        df.classes.map { c ->
            val full = cleanDescriptor(c.type)
            val pkg = full.substringBeforeLast('.', "")
            val simple = full.substringAfterLast('.')
            DexClassNode(
                fullName = full,
                simpleName = simple,
                packageName = pkg,
                classDef = c,
                dexSourceName = src.name
            )
        }
    }.distinctBy { it.fullName }.sortedBy { it.fullName }

    val packageTreeCompact: DexPackageNode by lazy {
        buildPackageTree(classes, compact = true)
    }

    val packageTreeFull: DexPackageNode by lazy {
        buildPackageTree(classes, compact = false)
    }

    fun getPackageTree(compact: Boolean): DexPackageNode =
        if (compact) packageTreeCompact else packageTreeFull

    fun getAllPackagePaths(compact: Boolean): Set<String> {
        val set = mutableSetOf<String>()
        fun collect(pkg: DexPackageNode) {
            if (pkg.fullPath.isNotEmpty()) {
                set.add(pkg.fullPath)
            }
            pkg.subPackages.forEach { collect(it) }
        }
        getPackageTree(compact).subPackages.forEach { collect(it) }
        return set
    }

    fun getAncestorPathsForClass(classNode: DexClassNode, compact: Boolean): Set<String> {
        val root = getPackageTree(compact)
        val result = mutableSetOf<String>()
        fun find(current: DexPackageNode): Boolean {
            if (current.classes.any { it.fullName == classNode.fullName }) {
                if (current.fullPath.isNotEmpty()) result.add(current.fullPath)
                return true
            }
            for (sub in current.subPackages) {
                if (find(sub)) {
                    if (current.fullPath.isNotEmpty()) result.add(current.fullPath)
                    return true
                }
            }
            return false
        }
        find(root)
        return result
    }

    private fun buildPackageTree(classList: List<DexClassNode>, compact: Boolean): DexPackageNode {
        class MutableNode(val segment: String, val fullPath: String) {
            val subPackages = mutableMapOf<String, MutableNode>()
            val classes = mutableListOf<DexClassNode>()

            fun toImmutable(compact: Boolean): DexPackageNode {
                var currentName = segment
                var currentPath = fullPath
                var targetNode = this

                if (compact && segment.isNotEmpty()) {
                    while (targetNode.classes.isEmpty() && targetNode.subPackages.size == 1) {
                        val singleChild = targetNode.subPackages.values.first()
                        currentName = "$currentName.${singleChild.segment}"
                        currentPath = singleChild.fullPath
                        targetNode = singleChild
                    }
                }

                val immutableSubPackages = targetNode.subPackages.values
                    .map { it.toImmutable(compact) }
                    .sortedBy { it.name.lowercase() }

                val immutableClasses = targetNode.classes
                    .sortedBy { it.simpleName.lowercase() }

                return DexPackageNode(
                    name = currentName,
                    fullPath = currentPath,
                    subPackages = immutableSubPackages,
                    classes = immutableClasses
                )
            }
        }

        val root = MutableNode("", "")

        for (c in classList) {
            if (c.packageName.isBlank()) {
                root.classes.add(c)
            } else {
                val segments = c.packageName.split('.').filter { it.isNotEmpty() }
                var current = root
                var pathAcc = ""
                for (seg in segments) {
                    pathAcc = if (pathAcc.isEmpty()) seg else "$pathAcc.$seg"
                    current = current.subPackages.getOrPut(seg) { MutableNode(seg, pathAcc) }
                }
                current.classes.add(c)
            }
        }

        return root.toImmutable(compact)
    }

    // Lazy JADX decompiler instance for Java decompilation
    private var jadxInstance: JadxDecompiler? = null
    private val jadxInputs = ArrayList<File>()

    private fun getJadx(): JadxDecompiler {
        if (jadxInstance != null) return jadxInstance!!
        val args = JadxArgs()
        args.threadsCount = 2
        val files = sources.map { src ->
            src.tempFile ?: run {
                val f = org.wexclient.app.util.ProcessScratch.work.newFile("jadx-", "_${src.name.replace('/', '_')}").toFile()
                jadxInputs.add(f)
                f.writeBytes(src.bytes)
                f
            }
        }
        args.setInputFiles(files)
        val d = JadxDecompiler(args)
        d.load()
        jadxInstance = d
        return d
    }

    // --- Правка smali (Dex Editor+) ---

    // дескриптор класса -> (имя DEX, собранный класс)
    private val editedClasses = LinkedHashMap<String, Pair<String, SmaliClass>>()

    /**
     * smali-модели DEX (clazzpatcher) по имени DEX — из исходных байтов: правки хранятся отдельно и
     * накладываются при каждой записи. Модель втрое-вчетверо больше самого DEX (8 МБ — около 26 МБ кучи),
     * поэтому держатся две последние: у APK на десяток DEX (и в сравнении двух APK) остальные читаются
     * заново по запросу — десятки миллисекунд.
     */
    private val smaliEditors = object : LinkedHashMap<String, DexClassEditor>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DexClassEditor>) = size > MAX_SMALI_MODELS
    }

    private val originalSources = initialSources

    private fun smaliEditor(sourceName: String): DexClassEditor {
        val source = originalSources.firstOrNull { it.name == sourceName } ?: originalSources.first()
        synchronized(smaliEditors) { smaliEditors[source.name] }?.let { return it }
        // Чтение — вне блокировки: соседний DEX тем временем открывается без очереди
        val editor = DexClassEditor(source.bytes)
        return synchronized(smaliEditors) { smaliEditors.getOrPut(source.name) { editor } }
    }

    private val pendingRebuild = LinkedHashSet<String>()
    private val unsavedSources = LinkedHashSet<String>()

    val hasUnsavedChanges: Boolean
        @Synchronized get() = unsavedSources.isNotEmpty()

    /** Можно ли записать правки обратно (у всех изменённых DEX известен путь). */
    val canSave: Boolean
        get() = sources.all { it.path != null }

    @Synchronized
    fun isEdited(node: DexClassNode): Boolean = editedClasses.containsKey(descriptorOf(node))

    private fun descriptorOf(node: DexClassNode): String = node.classDef.type

    /**
     * Собирает smali класса и запоминает результат (DEX пересобирается лениво).
     * Ошибки синтаксиса — [SmaliSyntaxException] с номерами строк.
     */
    fun compileSmali(node: DexClassNode, smali: String) {
        // Сборка — без блокировки движка: запись DEX в фоне её не задерживает
        val cls = SmaliAssembler.assemble(smali)
        if (cls.descriptor != node.classDef.type) {
            throw SmaliSyntaxException(
                listOf("Имя класса изменено (${cleanDescriptor(cls.descriptor)}) — переименование через .class не поддерживается")
            )
        }
        synchronized(this) { remember(node, cls) }
    }

    private fun remember(node: DexClassNode, cls: SmaliClass) {
        val source = sources.firstOrNull { it.name == node.dexSourceName } ?: sources.first()
        editedClasses[cls.descriptor] = source.name to cls
        pendingRebuild.add(source.name)
        unsavedSources.add(source.name)
        editVersion++
        invalidateDecompilers()
    }

    /**
     * Номер последней сборки класса. Запись DEX идёт без блокировки движка, и класс могут собрать прямо
     * во время неё: [markSaved] с номером, снятым до записи, такую сборку сохранённой не посчитает.
     */
    private var editVersion = 0L

    val currentEditVersion: Long
        @Synchronized get() = editVersion

    /** Пересобирает DEX с правками: заменяются только правленые классы (доли секунды; из фонового потока). */
    @Synchronized
    fun ensureRebuilt() {
        if (pendingRebuild.isEmpty()) return
        val updated = sources.toMutableList()
        for (name in pendingRebuild) {
            val idx = updated.indexOfFirst { it.name == name }
            if (idx < 0) continue
            // Все правки этого DEX — поверх исходного: модель та же, что для показа smali
            val replacements = editedClasses.values.filter { it.first == name }.map { it.second }
            val bytes = shallow { smaliEditor(name).rebuild(replacements) }
            updated[idx] = updated[idx].copy(bytes = bytes, tempFile = null)
        }
        pendingRebuild.clear()
        sources = updated
        invalidateDecompilers()
    }

    /** Изменённые DEX для записи на диск/в архив. */
    @Synchronized
    fun modifiedDex(): List<DexSource> {
        ensureRebuilt()
        return sources.filter { it.name in unsavedSources }
    }

    /** Записанное — сохранено; если после [upTo] собирали ещё классы, пометки остаются (запишутся в следующий раз). */
    @Synchronized
    fun markSaved(upTo: Long = editVersion) {
        if (editVersion != upTo) return
        unsavedSources.clear()
    }

    private fun invalidateDecompilers() {
        closeDecompilers()
        jarCache = DexJarCache(sources)
    }

    fun disassembleToSmali(classNode: DexClassNode): String {
        val desc = descriptorOf(classNode)
        synchronized(this) { editedClasses[desc] }?.let { return SmaliWriter.write(it.second) }
        return shallow { smaliEditor(classNode.dexSourceName).smali(desc) } ?: "# Класс $desc не найден в DEX"
    }

    /**
     * Разбор вложенных значений (аннотации, массивы) рекурсивен: DEX с нарочно глубокой вложенностью переполнил
     * бы стек, а StackOverflowError — не Exception, и окно его не поймало бы. Здесь он становится обычной ошибкой.
     */
    private inline fun <T> shallow(block: () -> T): T =
        try {
            block()
        } catch (_: StackOverflowError) {
            throw IllegalStateException("Слишком глубокая вложенность значений в DEX")
        }

    fun decompileToJava(classNode: DexClassNode): String = decompiling {
        try {
            ensureRebuilt()
            val jadx = getJadx()
            val jClass = jadx.classes.firstOrNull { it.fullName == classNode.fullName }
            if (jClass != null) {
                jClass.code
            } else {
                "// Класс ${classNode.fullName} не найден в декомпиляторе JADX"
            }
        } catch (e: Exception) {
            "// Ошибка декомпиляции Java: ${e.message}\n" + e.stackTraceToString()
        }
    }

    // Общий кэш трансляции DEX -> jar для CFR, Vineflower и Procyon (один перевод на всех)
    @Volatile
    private var jarCache = DexJarCache(initialSources)

    // Кэш XREF для Ghidra-листинга (дорогой полный скан — один раз на движок)
    internal val ghidraXrefCache = java.util.concurrent.ConcurrentHashMap<String, List<GhidraXref>>()
    internal val ghidraXrefMutex = Mutex()

    /**
     * Фоновый прогрев: транслирует DEX -> jar заранее, чтобы первое открытие
     * вкладок CFR/Vineflower не упиралось в трансляцию. Вызывать из IO-потока.
     */
    fun prewarmAltEngines() {
        try {
            jarCache.getJars()
        } catch (_: Exception) {
            // Ошибка всплывет позже текстом в редакторе
        }
    }

    // Lazy CFR engine instance (DEX -> class via d2c/dex2jar -> Java via CFR)
    private var cfrEngine: CfrEngine? = null

    fun decompileToJavaCfr(classNode: DexClassNode): String = decompiling {
        try {
            ensureRebuilt()
            val engine = cfrEngine ?: CfrEngine(sources, jarCache).also { cfrEngine = it }
            engine.decompileToJava(classNode.fullName)
        } catch (e: Exception) {
            "// Ошибка декомпиляции CFR: ${e.message}\n" + e.stackTraceToString()
        }
    }

    // Lazy Vineflower engine instance (DEX -> class via d2c/dex2jar -> Java via Vineflower)
    private var vineflowerEngine: VineflowerEngine? = null

    fun decompileToJavaVineflower(classNode: DexClassNode): String = decompiling {
        try {
            ensureRebuilt()
            val engine = vineflowerEngine ?: VineflowerEngine(sources, jarCache).also { vineflowerEngine = it }
            engine.decompileToJava(classNode.fullName)
        } catch (e: Exception) {
            "// Ошибка декомпиляции Vineflower: ${e.message}\n" + e.stackTraceToString()
        }
    }

    // Lazy Procyon engine instance (DEX -> class via d2c/dex2jar -> Java via Procyon)
    private var procyonEngine: ProcyonEngine? = null

    fun decompileToJavaProcyon(classNode: DexClassNode): String = decompiling {
        try {
            ensureRebuilt()
            val engine = procyonEngine ?: ProcyonEngine(sources, jarCache).also { procyonEngine = it }
            engine.decompileToJava(classNode.fullName)
        } catch (e: Exception) {
            "// Ошибка декомпиляции Procyon: ${e.message}\n" + e.stackTraceToString()
        }
    }

    fun searchClasses(query: String): List<DexClassNode> {
        val q = query.lowercase()
        return classes.filter { it.fullName.lowercase().contains(q) }
    }

    fun search(query: String, mode: DexSearchMode): List<DexSearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val results = mutableListOf<DexSearchResult>()

        when (mode) {
            DexSearchMode.CLASSES -> {
                for (c in classes) {
                    if (c.fullName.lowercase().contains(q)) {
                        results.add(DexSearchResult(c.simpleName, c.fullName, c, DexSearchMode.CLASSES))
                        if (results.size >= 500) break
                    }
                }
            }
            DexSearchMode.METHODS -> {
                for (c in classes) {
                    for (m in c.classDef.methods) {
                        if (m.name.lowercase().contains(q)) {
                            results.add(DexSearchResult("${m.name}()", c.fullName, c, DexSearchMode.METHODS))
                            if (results.size >= 500) break
                        }
                    }
                    if (results.size >= 500) break
                }
            }
            DexSearchMode.FIELDS -> {
                for (c in classes) {
                    for (f in c.classDef.fields) {
                        if (f.name.lowercase().contains(q)) {
                            results.add(DexSearchResult(f.name, c.fullName, c, DexSearchMode.FIELDS))
                            if (results.size >= 500) break
                        }
                    }
                    if (results.size >= 500) break
                }
            }
            DexSearchMode.STRINGS -> {
                try {
                    val matchingStrings = mutableSetOf<String>()
                    for ((_, df) in dexFiles) {
                        val strings = df.stringSection
                        for (i in 0 until strings.size) {
                            val s = strings[i]
                            if (s.contains(query, ignoreCase = true)) {
                                matchingStrings.add(s)
                                if (matchingStrings.size >= 300) break
                            }
                        }
                    }
                    for (s in matchingStrings) {
                        val containingClass = classes.firstOrNull { c ->
                            c.fullName.contains(s) || c.simpleName.contains(s)
                        }
                        results.add(DexSearchResult("\"$s\"", "Строка в пуле DEX", containingClass, DexSearchMode.STRINGS))
                    }
                } catch (_: Exception) {}
            }
        }
        return results
    }

    /**
     * Окно закрыто. Декомпиляция, начатая ещё до этого, создаёт движок уже после close() — со своей временной
     * папкой, удалить которую было бы некому. Такой вызов в конце закрывает всё сам ([decompiling]).
     */
    @Volatile
    private var closed = false

    private inline fun decompiling(block: () -> String): String =
        try {
            block()
        } finally {
            if (closed) closeDecompilers()
        }

    fun close() {
        closed = true
        closeDecompilers()
    }

    @Synchronized
    private fun closeDecompilers() {
        try {
            jadxInstance?.close()
        } catch (e: Exception) {
            // ignore
        }
        jadxInstance = null
        jadxInputs.forEach { it.delete() }
        jadxInputs.clear()
        try {
            cfrEngine?.close()
        } catch (e: Exception) {
            // ignore
        }
        cfrEngine = null
        try {
            vineflowerEngine?.close()
        } catch (e: Exception) {
            // ignore
        }
        vineflowerEngine = null
        try {
            procyonEngine?.close()
        } catch (e: Exception) {
            // ignore
        }
        procyonEngine = null
        ghidraXrefCache.clear()
        jarCache.close()
        if (closed) synchronized(smaliEditors) { smaliEditors.clear() }
    }

    companion object {
        private const val MAX_SMALI_MODELS = 2

        fun cleanDescriptor(desc: String): String {
            return if (desc.startsWith("L") && desc.endsWith(";")) {
                desc.substring(1, desc.length - 1).replace('/', '.')
            } else {
                desc
            }
        }
    }
}
