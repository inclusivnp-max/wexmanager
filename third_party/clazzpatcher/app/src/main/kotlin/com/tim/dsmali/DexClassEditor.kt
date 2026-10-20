package com.tim.dsmali

import com.tim.dasm.DexAsm
import com.tim.dasm.model.ClassInfo
import com.tim.dasm.util.ClassTransfer
import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.asm.SmaliRefs
import com.tim.dsmali.asm.TreeToDex
import com.tim.dsmali.disasm.DexToTree
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.io.SmaliWriter
import com.tim.dsmali.model.SmaliClass

/**
 * Правка классов одного DEX по одному — для редактора: smali класса по запросу, проверка текста одного
 * класса и запись DEX, в котором заменены только правленые классы. Остальные классы не разбираются
 * в smali и не собираются заново: DEX читается в модель и пишется обратно (8 МБ — доли секунды).
 *
 * Потокобезопасен: [dex] только читается, каждая запись собирает свой [DexAsm].
 */
class DexClassEditor(val dex: DexFile) {

    constructor(bytes: ByteArray) : this(DexReader().read(bytes))

    private val single by lazy { DexToTree.Single(dex) }

    /** Дескриптор класса → индекс class_def. */
    private val index: Map<String, Int> by lazy {
        val out = HashMap<String, Int>(dex.classDefs.size * 2)
        dex.classDefs.forEachIndexed { i, cd ->
            val desc = dex.strings.getOrNull(dex.typeIds.getOrNull(cd.classIdx)?.descriptorIdx ?: -1)
                ?: throw IllegalStateException("class_def #$i: тип #${cd.classIdx} вне пула")
            out.putIfAbsent(desc, i)
        }
        out
    }

    /** Дескрипторы классов в порядке class_defs. */
    val classes: List<String>
        get() = index.entries.sortedBy { it.value }.map { it.key }

    operator fun contains(descriptor: String): Boolean = descriptor in index

    /** Модель класса из DEX; null — такого класса нет. */
    fun smaliClass(descriptor: String): SmaliClass? = index[descriptor]?.let { single.build(it) }

    /** Текст класса (раскладка baksmali); null — такого класса нет. */
    fun smali(descriptor: String): String? = smaliClass(descriptor)?.let { SmaliWriter.write(it) }

    /**
     * DEX, где классы [replacements] стоят на месте одноимённых (класса не было — добавляется в конец).
     * Прежние тела заменённых классов в файл не попадают. Ошибка сборки — исключение, строку даёт [lineOf].
     */
    fun rebuild(replacements: Collection<SmaliClass>): ByteArray {
        if (replacements.isEmpty()) return DexWriter(dex).buildDex()
        val seen = HashSet<String>()
        for (cls in replacements) require(seen.add(cls.descriptor)) { "Класс ${cls.descriptor} заменяется дважды" }
        val asm = DexAsm.load(dex)
        asm.callSites.nameLoadedAsNumbered()
        val refs = SmaliRefs(asm)
        val built = replacements.map { cls ->
            try {
                cls to buildInto(asm, refs, cls)
            } catch (e: Exception) {
                throw wrap(cls, e)
            }
        }
        asm.useDsl {
            for ((cls, info) in built) {
                if (cls.descriptor in index) replaceClass(info) else addClass(info)
            }
        }
        // Сжатая запись: строки, поля и методы прежних тел заменённых классов в файл не попадают
        return DexWriter(asm.build(), compact = true).buildDex()
    }

    /**
     * Класс в пулах [asm]: как в [TreeToDex] — отдельно и переносом, а с `invoke-custom` или со строкой
     * `const-string` за пределом 16 бит в общих пулах — прямо в них.
     */
    private fun buildInto(asm: DexAsm, refs: SmaliRefs, cls: SmaliClass): ClassInfo {
        val tree = TreeToDex(1)
        if (!tree.usesCallSites(cls)) {
            val local = DexAsm.load(tree.emptyDexFile())
            val localRefs = SmaliRefs(local)
            tree.constStrings(cls, localRefs)
            val info = tree.buildClass(local, localRefs, cls)
            try {
                return ClassTransfer(local, asm).transfer(info)
            } catch (_: ClassTransfer.StringIndexOverflow) {
                // ниже — в общих пулах
            }
        }
        tree.constStrings(cls, refs)
        return tree.buildClass(asm, refs, cls)
    }

    companion object {
        /**
         * Разобрать текст одного класса и проверить его сборкой в отдельных пулах (DEX для этого не нужен).
         * Ошибка — исключение: строка — [lineOf] (0, если место неизвестно), причина — [reasonOf].
         */
        fun compile(text: String): SmaliClass {
            val cls = SmaliParser.parseClass(text) ?: throw SmaliParseException(1, "В тексте нет .class")
            val tree = TreeToDex(1)
            val local = DexAsm.load(tree.emptyDexFile())
            val refs = SmaliRefs(local)
            try {
                tree.constStrings(cls, refs)
                tree.buildClass(local, refs, cls)
            } catch (e: Exception) {
                throw wrap(cls, e)
            }
            return cls
        }

        private fun wrap(cls: SmaliClass, e: Exception): Exception {
            val msg = e.message ?: e.toString()
            return SmaliBuildException(lineOf(e), if (msg.startsWith("Класс ${cls.descriptor}")) msg else "Класс ${cls.descriptor}: $msg", e)
        }
    }
}

/**
 * Суть ошибки без обёрток «класс …: метод …: элемент …» — место уже дала строка ([lineOf]):
 * сообщение самой глубокой причины.
 */
fun reasonOf(e: Throwable): String {
    var t: Throwable = e
    var depth = 0
    while (depth++ < 64) {
        val c = t.cause ?: break
        if (c.message.isNullOrBlank()) break
        t = c
    }
    val msg = t.message?.takeIf { it.isNotBlank() } ?: return t.javaClass.simpleName
    return PARSER_PREFIX.find(msg)?.let { msg.substring(it.range.last + 1) } ?: msg
}

private val PARSER_PREFIX = Regex("^SmaliParser: строка \\d+: ")
