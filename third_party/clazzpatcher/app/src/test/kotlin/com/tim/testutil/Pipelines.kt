package com.tim.testutil

import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.io.SmaliWriter
import com.tim.dsmali.model.SmaliTree
import org.junit.jupiter.api.Assertions.fail

/** Имя параметризованного теста в отчётах: метод и параметр. */
const val TEST_NAME = "{displayName} [{0}]"

/** Продуктовые конвейеры, которые проверяют тесты (в одном месте). */
object Pipelines {
    /** DexReader -> DexWriter. */
    fun rawRoundTrip(dex: ByteArray): ByteArray = DexWriter(DexReader().read(dex)).buildDex()

    /** DEX -> SmaliTree -> текст каждого класса (SmaliWriter): дескриптор -> текст. */
    fun disassembleToText(dex: ByteArray): Map<String, String> {
        val tree = Smali.disassemble(DexReader().read(dex))
        val result = LinkedHashMap<String, String>()
        for (cls in tree.classes) {
            if (result.put(cls.descriptor, SmaliWriter.write(cls)) != null) throw IllegalStateException("дизассемблер выдал класс ${cls.descriptor} дважды")
        }
        return result
    }

    /** Тексты классов -> SmaliParser -> SmaliTree. */
    fun parse(texts: Collection<String>): SmaliTree {
        val tree = SmaliTree()
        for (text in texts) {
            val cls = SmaliParser.parseClass(text) ?: throw IllegalStateException("SmaliParser вернул null для текста:\n${text.take(300)}")
            if (tree.findClass(cls.descriptor) != null) throw IllegalStateException("класс ${cls.descriptor} встречается дважды")
            tree.classes.add(cls)
        }
        return tree
    }

    /** Тексты классов -> SmaliParser -> TreeToDex -> DexWriter. */
    fun assembleFromText(texts: Collection<String>): ByteArray = DexWriter(Smali.assemble(parse(texts))).buildDex()

    /** DEX -> smali-текст -> DEX (в памяти). */
    fun smaliRoundTrip(dex: ByteArray): ByteArray = assembleFromText(disassembleToText(dex).values)
}

/** Кэш результатов (вместе с исключениями) на время прогона тестов: одна фикстура — одно вычисление. */
object Memo {
    private val cache = HashMap<String, Result<Any?>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, compute: () -> T): T {
        val r = synchronized(cache) { cache.getOrPut(key) { runCatching { compute() } } }
        return r.getOrThrow() as T
    }
}

/** Побайтное сравнение с понятным сообщением (первое расхождение и необязательные подробности). */
fun assertSameBytes(expected: ByteArray, actual: ByteArray, what: String, details: () -> String = { "" }) {
    if (expected.contentEquals(actual)) return
    val n = minOf(expected.size, actual.size)
    var i = 0
    while (i < n && expected[i] == actual[i]) i++
    fail<Unit>(
        "$what: байты различаются, первое расхождение по смещению 0x${Integer.toHexString(i)} " +
            "(размеры ${expected.size} и ${actual.size})" + details()
    )
}

/** Падает с отчётом, если отчёт о расхождениях не пуст. */
fun assertNoDiff(report: String?, what: String) {
    if (report != null) fail<Unit>("$what:\n$report")
}
