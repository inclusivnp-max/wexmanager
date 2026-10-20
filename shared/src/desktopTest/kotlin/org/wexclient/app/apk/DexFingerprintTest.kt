package org.wexclient.app.apk

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.immutable.instruction.ImmutableArrayPayload
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t
import org.jf.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import org.jf.dexlib2.immutable.instruction.ImmutableSwitchElement
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DexFingerprintTest {
    private val exact = DexCompareOptions(
        ignoreDebugInfo = false,
        ignoreCompilerOptimization = false,
        ignoreRegisters = false,
        ignoreInstructionOrder = false
    )

    @Test
    fun `branch destination is part of an exact comparison`() {
        val a = ImmutableInstruction10t(Opcode.GOTO, 1)
        val b = ImmutableInstruction10t(Opcode.GOTO, 2)
        assertNotEquals(ApkDexDiff.instructionKey(a, exact), ApkDexDiff.instructionKey(b, exact))
    }

    @Test
    fun `array payload values are compared`() {
        val a = ImmutableArrayPayload(4, listOf<Number>(1, 2))
        val b = ImmutableArrayPayload(4, listOf<Number>(1, 3))
        assertNotEquals(ApkDexDiff.instructionKey(a, exact), ApkDexDiff.instructionKey(b, exact))
    }

    @Test
    fun `switch payload destinations are compared`() {
        val a = ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(1, 2)))
        val b = ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(1, 4)))
        assertNotEquals(ApkDexDiff.instructionKey(a, exact), ApkDexDiff.instructionKey(b, exact))
    }

    @Test
    fun `field visibility changes the class fingerprint`() {
        assertNotEquals(signature("public"), signature("private"))
    }

    private fun signature(visibility: String): Long {
        val root = Files.createTempDirectory("wex-field-signature").toFile()
        try {
            val source = root.resolve("Fields.smali").apply {
                writeText(".class public Ltest/Fields;\n.super Ljava/lang/Object;\n.field $visibility value:I\n")
            }
            val output = root.resolve("classes.dex")
            val options = SmaliOptions().apply { outputDexFile = output.absolutePath }
            assertTrue(Smali.assemble(options, listOf(source.absolutePath)))
            return ApkDexDiff.read(listOf(output.readBytes()), exact).values.single().fingerprint
        } finally {
            root.deleteRecursively()
        }
    }
}
