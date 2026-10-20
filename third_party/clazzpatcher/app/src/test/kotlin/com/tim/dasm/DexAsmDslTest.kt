package com.tim.dasm

import com.tim.dasm.model.AccessFlags
import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Сценарий [TestAsm] как модульный тест. Проверка верификатором ART (dexdump) выполняется,
 * если задан путь: `-Dclazzpatcher.dexdump=...` или переменная окружения `DEXDUMP`.
 */
class DexAsmDslTest {

    private fun scenario(): DexFile {
        val asm = DexAsm.load(TestAsm.emptyDex())
        TestAsm.buildScenario(asm)
        return asm.build()
    }

    private fun DexFile.string(idx: Int) = strings[idx]
    private fun DexFile.type(idx: Int) = strings[typeIds[idx].descriptorIdx]

    private fun DexFile.methodCode(cls: String, name: String): com.tim.dex.model.CodeItem {
        val cd = classDefs.first { type(it.classIdx) == cls }
        val data = classDataItems[cd.classDataIdx]
        val m = (data.directMethods + data.virtualMethods).first { string(methodIds[it.methodIdx].nameIdx) == name }
        return codeItems[m.codeIdx]
    }

    @Test
    fun scenarioBuildsConsistentModel() {
        val dex = scenario()
        assertEquals(listOf("Lcom/example/asm/AnnotatedClass;", "Lcom/example/asm/Calculator;"), dex.classDefs.map { dex.type(it.classIdx) })

        val nested = dex.methodCode("Lcom/example/asm/Calculator;", "nestedTry")
        assertEquals(2, nested.tries.size) // вложенные диапазоны разрезаны на непересекающиеся
        assertTrue(nested.tries[0].startAddr + nested.tries[0].insnCount <= nested.tries[1].startAddr)

        val multi = dex.methodCode("Lcom/example/asm/Calculator;", "multiCatch")
        assertEquals(1, multi.tries.size)
        val h = multi.catchHandlers[multi.tries[0].handlerIdx]
        assertEquals(3, h.handlers.size)
        assertTrue(h.catchAllAddr >= 0)

        val add = dex.methodCode("Lcom/example/asm/Calculator;", "addNumbers")
        val dbg = dex.debugInfoItems[add.debugInfoIdx]
        assertEquals(2, dbg.parametersSize)
        assertEquals(listOf("a", "b"), dbg.parameterNames.map { dex.string(it) })

        // Signature — системная аннотация
        val dirIdx = dex.classDefs.first { dex.type(it.classIdx) == "Lcom/example/asm/Calculator;" }.annotationsDirectoryIdx
        val dir = dex.annotationsDirectoryItems[dirIdx]
        val sig = dir.methodAnnotations.flatMap { dex.annotationSetItems[it.annotationsSetIdx].annotationIdxs }
            .map { dex.annotationItems[it] }.first { dex.type(it.annotation.typeIdx) == "Ldalvik/annotation/Signature;" }
        assertEquals(2.toByte(), sig.visibility)
    }

    @Test
    fun writtenDexReadsBackAndVerifies() {
        val bytes = DexWriter(scenario()).buildDex()
        val back = DexReader().read(bytes)
        assertEquals(2, back.classDefs.size)

        val dexdump = System.getProperty("clazzpatcher.dexdump") ?: System.getenv("DEXDUMP") ?: return
        val f = File.createTempFile("asm-scenario", ".dex")
        try {
            f.writeBytes(bytes)
            val p = ProcessBuilder(dexdump, "-d", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            assertTrue(p.exitValue() == 0 && !out.contains(" E dexdump"), out.lines().filter { " E " in it }.take(10).joinToString("\n"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun patchClassBySignature() {
        val asm = DexAsm.load(scenario())
        asm.useDsl {
            patchClass("Lcom/example/asm/Calculator;") {
                method("addNumbers", listOf("I", "I"), "I") {
                    registers(3)
                    code {
                        label("start")
                        const4(0, 5)
                        ifEqz(0, "start")
                        returnValue(0)
                    }
                }
            }
        }
        val dex = asm.build()
        val code = dex.methodCode("Lcom/example/asm/Calculator;", "addNumbers")
        assertEquals(3, code.registersSize)
        assertEquals(4, code.insnsSize) // const/4 + if-eqz + return
        val dbg = dex.debugInfoItems[code.debugInfoIdx]
        assertEquals(listOf("a", "b"), dbg.parameterNames.map { dex.string(it) }) // имена параметров сохранены
        assertEquals(1, dbg.opcodes.size) // старые .line/.local отброшены (только END_SEQUENCE)
    }

    @Test
    fun patchErrors() {
        val asm = DexAsm.load(scenario())
        assertFailsWith<IllegalStateException> {
            asm.useDsl { patchClass("Lcom/example/asm/Missing;") { method("x") { code { returnVoid() } } } }
        }
        assertFailsWith<IllegalStateException> {
            asm.useDsl { patchClass("Lcom/example/asm/Calculator;") { method("nope") { code { returnVoid() } } } }
        }
        assertFailsWith<IllegalStateException> {
            asm.useDsl {
                patchClass("Lcom/example/asm/Calculator;") {
                    method("addNumbers", listOf("J"), "I") { code { returnVoid() } }
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            asm.useDsl { createClass("Lcom/example/asm/Calculator;") { } }
        }
    }

    @Test
    fun registersAreValidated() {
        val asm = DexAsm.load(TestAsm.emptyDex())
        val e = assertFailsWith<IllegalStateException> {
            asm.useDsl {
                createClass("LX;") {
                    method("f") {
                        static()
                        registers(1)
                        instructions { const4(3, 0); returnVoid() }
                    }
                }
            }
        }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message!!.contains("v3") })
    }

    @Test
    fun callSiteAndMethodHandleApi() {
        val asm = DexAsm.load(TestAsm.emptyDex())
        val lookup = listOf("Ljava/lang/invoke/MethodHandles\$Lookup;", "Ljava/lang/String;", "Ljava/lang/invoke/MethodType;")
        val bsm = asm.methodHandles.addMethod(
            com.tim.dasm.model.MethodHandleKind.INVOKE_STATIC, "LX;", "bsm", lookup, "Ljava/lang/invoke/CallSite;"
        )
        val cs = asm.callSites.add(bsm, "run", "V", emptyList())
        asm.useDsl {
            createClass("LX;") {
                method("f") {
                    accessFlags(AccessFlags.PUBLIC or AccessFlags.STATIC)
                    registers(1)
                    instructions {
                        invokeCustom(cs)
                        constMethodHandle(0, bsm)
                        constMethodType(0, "V", listOf("I"))
                        returnVoid()
                    }
                }
            }
        }
        val dex = asm.build()
        assertEquals("039", com.tim.dex.data.DexMagic.getVersion(dex.header.magic))
        assertEquals(1, dex.callSiteIds.size)
        assertEquals(1, dex.methodHandles.size)
    }
}
