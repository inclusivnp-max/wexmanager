package com.tim.dex

import com.tim.dex.DexTestUtil.typeName
import com.tim.dex.model.DexFile
import com.tim.dex.model.HiddenApiClassData
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Круговые проверки на реальных DEX: образцы smali 3.0.9 ([DexSamples]), `classes.dex` репозитория
 * и, при заданной переменной `DEXPARSER_CORPUS` (пути через `:`), произвольный корпус.
 */
class DexRoundTripTest {

    private fun inputs(): Map<String, ByteArray> {
        val result = LinkedHashMap(DexSamples.ALL)
        val repoDex = File(System.getProperty("user.dir")).resolve("../classes.dex").canonicalFile
        if (repoDex.isFile) result["classes.dex"] = repoDex.readBytes()
        System.getenv("DEXPARSER_CORPUS")?.split(':')?.filter { it.isNotBlank() }?.forEach { result[it] = File(it).readBytes() }
        return result
    }

    @Test
    fun rewriteIsValidAndIdempotent() {
        for ((name, bytes) in inputs()) {
            val first = DexWriter(DexReader().read(bytes)).buildDex()
            DexTestUtil.verifyWithDexdump(first, "rt-" + File(name).name)
            val second = DexWriter(DexReader().read(first)).buildDex()
            assertContentEquals(first, second, "повторная запись $name")
        }
    }

    /** Перемешанная модель с дубликатами (как у asm-слоя) пишется побайтно так же, как исходная. */
    @Test
    fun shuffledModelWritesIdentically() {
        for ((name, bytes) in inputs()) {
            val dex = DexReader().read(bytes)
            val expected = DexWriter(dex).buildDex()
            for (seed in 1L..3L) {
                val shuffled = DexShuffler(dex, seed).shuffle()
                assertContentEquals(expected, DexWriter(shuffled).buildDex(), "$name, seed=$seed")
            }
        }
    }

    /** class_defs в обратном порядке: writer восстанавливает порядок «родители раньше детей». */
    @Test
    fun reversedClassDefsAreReordered() {
        for ((name, bytes) in inputs()) {
            val dex = DexReader().read(bytes)
            val reversed = dex.copy(
                classDefs = dex.classDefs.reversed(),
                hiddenApiClassDataItems = dex.hiddenApiClassDataItems.map { HiddenApiClassData(it.flags.reversed()) }
            )
            val out = DexWriter(reversed).buildDex()
            DexTestUtil.verifyWithDexdump(out, "rev-" + File(name).name)
            val back = DexReader().read(out)
            assertEquals(dex.classDefs.map { typeName(dex, it.classIdx) }.toSet(), back.classDefs.map { typeName(back, it.classIdx) }.toSet())
            assertParentsFirst(back, name)
            assertEquals(hiddenByClass(dex), hiddenByClass(back), name)
        }
    }

    private fun assertParentsFirst(dex: DexFile, name: String) {
        val position = HashMap<String, Int>()
        dex.classDefs.forEachIndexed { i, def -> position[typeName(dex, def.classIdx)] = i }
        dex.classDefs.forEachIndexed { i, def ->
            val parents = ArrayList<String>()
            if (def.superclassIdx != -1) parents.add(typeName(dex, def.superclassIdx))
            if (def.interfacesTypeListIdx != -1) dex.typeLists[def.interfacesTypeListIdx].typeIdx.forEach { parents.add(typeName(dex, it)) }
            for (p in parents) {
                val pos = position[p] ?: continue
                assertTrue(pos < i, "$name: $p должен идти раньше ${typeName(dex, def.classIdx)}")
            }
        }
    }

    private fun hiddenByClass(dex: DexFile): Map<String, List<Int>?> {
        val flags = dex.hiddenApiClassDataItems.singleOrNull()?.flags ?: return emptyMap()
        return dex.classDefs.indices.associate { typeName(dex, dex.classDefs[it].classIdx) to flags[it]?.toList() }
    }
}
