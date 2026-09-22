package org.wexclient.app.dex

import kotlinx.coroutines.runBlocking
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhidraListingTest {

    private fun assembleTestDex(): DexEditorEngine {
        val tmp = Files.createTempDirectory("ghidra_test_").toFile()
        try {
            val smaliFile = File(tmp, "Hello.smali").apply {
                writeText(
                    """
                    .class public Ltest/Hello;
                    .super Ljava/lang/Object;

                    .method public static greet()Ljava/lang/String;
                        .registers 1
                        const-string v0, "HelloGhidra"
                        return-object v0
                    .end method

                    .method public static callIt()Ljava/lang/String;
                        .registers 1
                        invoke-static {}, Ltest/Hello;->greet()Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    .end method

                    .method public static pick(Z)I
                        .registers 2
                        if-eqz v0, :t
                        const/4 v0, 0x1
                        return v0
                        :t
                        const/4 v0, 0x0
                        return v0
                    .end method
                    """.trimIndent()
                )
            }
            val dexFile = File(tmp, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dexFile.absolutePath }
            assertTrue(Smali.assemble(options, smaliFile.absolutePath), "smali assembly failed")
            return DexEditorEngine(listOf(DexSource("classes.dex", dexFile.readBytes())))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun helloClass(engine: DexEditorEngine): DexClassNode {
        return engine.classes.first { it.fullName == "test.Hello" }
    }

    @Test
    fun `addresses sequential and refs resolved`() {
        val engine = assembleTestDex()
        try {
            val cls = helloClass(engine)
            val greet = cls.classDef.methods.first { it.name == "greet" }
            val listing = engine.buildGhidraListing(cls, greet)
            println("greet rows:")
            listing.lines.forEach { println("  ${it.address} ${it.mnemonic} ${it.operands} ;${it.comment}") }

            val insns = listing.lines.filter { it.kind == GhidraLineKind.INSTRUCTION }
            assertTrue(insns.size >= 2, "мало инструкций: ${insns.size}")
            assertEquals(0, insns.first().address)
            for (i in 1 until insns.size) {
                assertTrue(insns[i].address > insns[i - 1].address, "адреса не растут")
            }
            // const-string v0, string_idx 0 -> байты 1A 00 00 00
            val constRow = insns.first { it.mnemonic == "const-string" }
            assertTrue(constRow.operands.contains("v0"), "регистр: ${constRow.operands}")
            assertTrue(constRow.operands.contains("HelloGhidra"), "строка: ${constRow.operands}")
            assertEquals("1A 00 00 00", constRow.bytesHex)

            // invoke-static с навигацией на метод
            val callCls = helloClass(engine)
            val callIt = callCls.classDef.methods.first { it.name == "callIt" }
            val callListing = engine.buildGhidraListing(callCls, callIt)
            val invoke = callListing.lines.first {
                it.kind == GhidraLineKind.INSTRUCTION && it.mnemonic == "invoke-static"
            }
            assertTrue(invoke.operands.contains("greet"), "операнды: ${invoke.operands}")
            val target = invoke.refTarget
            assertTrue(target is GhidraRefTarget.Method, "нет цели: $target")
            assertEquals("test.Hello", (target as GhidraRefTarget.Method).className)
            assertTrue(target.methodKey.contains("greet"), target.methodKey)

            // Ветвление с адресом-назначением внутри метода
            val pick = callCls.classDef.methods.first { it.name == "pick" }
            val pickListing = engine.buildGhidraListing(callCls, pick)
            val branch = pickListing.lines.firstOrNull {
                it.kind == GhidraLineKind.INSTRUCTION && it.refTarget is GhidraRefTarget.Address
            }
            assertTrue(branch != null, "нет ветвления с адресом")
            val dest = (branch!!.refTarget as GhidraRefTarget.Address).address
            assertTrue(pickListing.addrToIndex.containsKey(dest), "цель $dest вне карты")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `xrefs found`() = runBlocking {
        val engine = assembleTestDex()
        try {
            val cls = helloClass(engine)
            val greet = cls.classDef.methods.first { it.name == "greet" }
            val key = engine.methodKey(greet)
            println("greet key=$key")
            assertTrue(key.contains("greet"), key)
            val xrefs = engine.findCallers("test.Hello", key)
            println("xrefs=$xrefs")
            assertTrue(xrefs.isNotEmpty(), "XREF не найдены")
            val hit = xrefs.first { it.callerMethodKey.contains("callIt") }
            assertEquals("test.Hello", hit.callerClass)
            assertEquals(0, hit.address)
            // Второй раз — из кэша
            assertEquals(xrefs, engine.findCallers("test.Hello", key))
        } finally {
            engine.close()
        }
    }
}
