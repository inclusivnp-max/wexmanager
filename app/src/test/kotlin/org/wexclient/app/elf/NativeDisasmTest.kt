package org.wexclient.app.elf

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeDisasmTest {

    private fun systemX86So(): File? {
        return listOf(
            "/usr/lib/liba52.so",
            "/lib/x86_64-linux-gnu/libc.so.6",
            "/usr/lib/x86_64-linux-gnu/libc.so.6"
        ).map { File(it) }.firstOrNull { it.exists() }
    }

    @Test
    fun `x86 disasm`() {
        if (!NativeDisasm.isAvailable()) {
            println("SKIP: нет дизассемблера")
            return
        }
        val so = systemX86So()
        if (so == null) {
            println("SKIP: нет системного .so")
            return
        }
        val doc = ElfDocument(so.readBytes())
        println("machine=${doc.parsed.header.machine}")
        val code = NativeDisasm.disassemble(doc)
        println("tool=${code.toolName} insns=${code.insns.size} truncated=${code.truncated}")
        assertTrue(code.insns.isNotEmpty(), "пустой листинг")
        assertTrue(
            code.insns.any { it.mnemonic.startsWith("ret") },
            "нет ret: ${code.insns.take(5).map { it.mnemonic }}"
        )
        val funcs = NativeDisasm.functionsOf(doc)
        println("funcs=${funcs.size} sample=${funcs.take(3).map { it.name }}")
        assertTrue(funcs.isNotEmpty(), "нет функций")
        val branch = code.insns.firstOrNull {
            (it.jump == NativeJump.JUMP || it.jump == NativeJump.CALL) && it.targetVaddr != null
        }
        assertTrue(branch != null, "нет ветвлений с целью")
        // Цели — адреса кода, а не мелкие immediate (биты tbz, rip-отступы)
        val tiny = code.insns.filter {
            (it.jump == NativeJump.JUMP || it.jump == NativeJump.CALL) &&
                    it.targetVaddr != null && it.targetVaddr!! < 0x1000
        }
        assertTrue(tiny.isEmpty(), "ложные цели: ${tiny.take(3)}")
        println("branch: ${branch!!.mnemonic} ${branch.operands} -> 0x${
            java.lang.Long.toHexString(branch.targetVaddr!!).uppercase()
        }")

        // Срез функции + XREF не падают
        val f = funcs.first { it.size > 0 }
        val idx = funcs.indexOf(f)
        val next = funcs.subList(idx + 1, funcs.size).firstOrNull()?.vaddr
        val slice = NativeDisasm.sliceForFunc(code.insns, f, next)
        println("slice ${f.name}: ${slice.size} инстр.")
        assertTrue(slice.isNotEmpty())
        val xrefs = NativeDisasm.xrefsTo(code.insns, f, next)
        println("xrefs to ${f.name}: ${xrefs.size}")
    }

    @Test
    fun `arm64 disasm from apk`() {
        if (!NativeDisasm.isAvailable()) {
            println("SKIP: нет дизассемблера")
            return
        }
        val apk = File("/home/zapahtravki/Загрузки/Telegram Desktop/Ai Tools_1.5.apk")
        if (!apk.exists()) {
            println("SKIP: нет APK на этой машине")
            return
        }
        val bytes = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("lib/arm64-v8a/libprotector.so")).readBytes()
        }
        val doc = ElfDocument(bytes)
        assertTrue(doc.parsed.header.machine == 183, "ожидался AArch64")
        val code = NativeDisasm.disassemble(doc)
        println("tool=${code.toolName} insns=${code.insns.size}")
        assertTrue(code.insns.size > 100, "мало инструкций: ${code.insns.size}")
        val funcs = NativeDisasm.functionsOf(doc)
        println("funcs=${funcs.size}")
        val branches = code.insns.count {
            (it.jump == NativeJump.JUMP || it.jump == NativeJump.CALL) && it.targetVaddr != null
        }
        println("branches with target=$branches")
        println("sample: " + code.insns.filter {
            it.jump == NativeJump.JUMP || it.jump == NativeJump.CALL
        }.take(3).joinToString(" | ") { "${it.mnemonic} ${it.operands}" })
    }

    @Test
    fun `garbage fails cleanly`() {
        if (!NativeDisasm.isAvailable()) {
            println("SKIP: нет дизассемблера")
            return
        }
        assertFailsWith<IllegalStateException> {
            NativeDisasm.disassembleBytes(ByteArray(128) { 0x41 }, 62)
        }
    }
}
