package org.wexclient.app.elf

import java.util.concurrent.TimeUnit
import org.wexclient.app.util.startHelper

// --- Модель ----------------------------------------------------------------------

enum class NativeTool { LLVM, GNU }

data class NativeFunc(
    val name: String,
    val vaddr: Long,
    val size: Long
)

enum class NativeJump { NONE, JUMP, CALL, RET, INDIRECT }

data class NativeInsn(
    val vaddr: Long,
    val bytesHex: String,
    val mnemonic: String,
    val operands: String,
    val jump: NativeJump,
    /** Статическая цель ветвления/вызова (виртуальный адрес) или null. */
    val targetVaddr: Long?
)

data class NativeCode(
    val toolName: String,
    val functions: List<NativeFunc>,
    val insns: List<NativeInsn>,
    val truncated: Boolean
)

// --- Движок ----------------------------------------------------------------------

object NativeDisasm {

    private const val TIMEOUT_SEC = 60L
    private const val MAX_LINES = 400_000

    private val labelRe = Regex("^([0-9a-fA-F]+)\\s+<([^>]*)>:$")
    // Порядок важен: сначала GNU (строгий :\t), потом LLVM.
    // В GNU между байтами и мнемоникой идут пробелы+TAB — сепаратор [ \t]+,
    // иначе бэктрекинг рвет байты и мнемонику.
    private val gnuInsnRe =
        Regex("^\\s*([0-9a-fA-F]+):\\t((?:[0-9a-fA-F]{2} )+)?[ \\t]+([A-Za-z][\\w.]*|\\(bad\\))\\s*(.*)$")
    private val llvmInsnRe =
        Regex("^\\s*([0-9a-fA-F]+):\\s+([0-9a-fA-F]+)(?:\\s{2,}|\\t)(?:([A-Za-z][\\w.]*)(?:\\s+(.*))?|<unknown>)\\s*$")
    private val hexAddrRe = Regex("0x([0-9a-fA-F]+)")
    private val bareHexRe = Regex("^([0-9a-fA-F]{3,})$")

    private val AARCH64_CALL = setOf("bl")
    private val AARCH64_JUMP = setOf("b", "cbz", "cbnz", "tbz", "tbnz")
    private val AARCH64_INDIRECT = setOf("br", "blr", "ret")
    private val AARCH64_COND = Regex("^b\\.[a-z]+$")
    private val ARM_CALL = setOf("bl", "blx")
    private val ARM_INDIRECT = setOf("bx")
    private val ARM_COND = Regex("^b(l)?(eq|ne|hs|lo|mi|pl|vs|vc|hi|ls|ge|lt|gt|le|al)$")
    private val RISCV_JUMP = setOf("jal", "beq", "bne", "blt", "bge", "bltu", "bgeu")

    @Volatile
    private var availability: Pair<Boolean, Boolean>? = null

    /** Есть ли хоть один рабочий дизассемблер. */
    fun isAvailable(): Boolean {
        availability?.let { return it.first || it.second }
        val llvm = probeTool(listOf("llvm-objdump", "--version"))
        val gnu = probeTool(listOf("objdump", "--version"))
        availability = llvm to gnu
        return llvm || gnu
    }

    fun toolForMachine(machine: Int): NativeTool = when (machine) {
        40, 183, 243 -> NativeTool.LLVM // ARM / AArch64 / RISC-V — только llvm
        3, 62 -> NativeTool.GNU // x86 — GNU objdump
        else -> NativeTool.LLVM // неизвестно — пробуем llvm, потом GNU
    }

    private fun probeTool(cmd: List<String>): Boolean {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).startHelper()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun disassemble(doc: ElfDocument): NativeCode =
        disassembleBytes(doc.toByteArray(), doc.parsed.header.machine)

    fun disassembleBytes(data: ByteArray, machine: Int): NativeCode {
        val order = listOf(toolForMachine(machine)) +
                if (toolForMachine(machine) == NativeTool.LLVM) {
                    listOf(NativeTool.GNU)
                } else {
                    listOf(NativeTool.LLVM)
                }
        var lastError = "нет дизассемблера (нужен llvm-objdump или binutils objdump)"
        for (tool in order.distinct()) {
            try {
                return runTool(tool, data, machine)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException(lastError)
    }

    private fun runTool(tool: NativeTool, data: ByteArray, machine: Int): NativeCode {
        val tmp = org.wexclient.app.util.ProcessScratch.work.newFile("disasm-", ".so").toFile()
        try {
            tmp.writeBytes(data)
            val cmd = when (tool) {
                NativeTool.LLVM -> listOf("llvm-objdump", "-d", "--print-imm-hex", tmp.absolutePath)
                NativeTool.GNU -> listOf("objdump", "-d", "-M", "intel", tmp.absolutePath)
            }
            val pb = ProcessBuilder(cmd)
            pb.environment()["LC_ALL"] = "C"
            // llvm-objdump 22 ищет отладочную информацию в сети (debuginfod): при медленной
            // сети дизассемблирование висело до таймаута, а для листинга она не нужна
            pb.environment().remove("DEBUGINFOD_URLS")
            pb.redirectErrorStream(false)
            val proc = pb.startHelper()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8)
            val insns = ArrayList<NativeInsn>(65536)
            val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
            var lines = 0
            // Читаем потоки сразу в отдельных нитях, чтобы не забить pipe и не зависнуть
            val readerThread = Thread({
                try {
                    var line = out.readLine()
                    while (line != null) {
                        if (lines++ >= MAX_LINES) {
                            // Дальше листинг не нужен: не ждём, пока дизассемблер допишет гигабайты
                            truncated.set(true)
                            proc.destroy()
                            break
                        }
                        parseLine(line, machine)?.let {
                            synchronized(insns) { insns.add(it) }
                        }
                        line = out.readLine()
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        out.close()
                    } catch (_: Exception) {
                    }
                }
            }, "wex-objdump-reader")
            readerThread.isDaemon = true
            var errText = ""
            val errThread = Thread({
                errText = try {
                    // Предупреждения о битом файле бывают бесконечными: храним начало, остальное сливаем
                    proc.errorStream.use { err ->
                        val head = err.readNBytes(64 * 1024)
                        err.transferTo(java.io.OutputStream.nullOutputStream())
                        String(head, Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    ""
                }
            }, "wex-objdump-err")
            errThread.isDaemon = true
            readerThread.start()
            errThread.start()
            val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw IllegalStateException("Дизассемблер завис (таймаут ${TIMEOUT_SEC}с)")
            }
            readerThread.join(5000)
            errThread.join(2000)
            // Важно: тулзы часто выходят с кодом != 0 С выводом (варнинги про кривые
            // секции пакеров). Решаем по содержимому, а не по коду выхода.
            if (insns.isEmpty()) {
                throw IllegalStateException(
                    "Пустой вывод (exit=${if (finished) proc.exitValue() else "timeout"}): " +
                            "${errText.take(200).ifEmpty { "не тот формат или нет кода" }}"
                )
            }
            return NativeCode(
                toolName = if (tool == NativeTool.LLVM) "llvm-objdump" else "objdump",
                functions = emptyList(), // функции подставит вызыватель из символов ELF
                insns = insns.toList(),
                truncated = truncated.get()
            )
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Функции из символов ELF (только определённые FUNC), по возрастанию адресов. */
    fun functionsOf(doc: ElfDocument): List<NativeFunc> {
        val isArm32 = doc.parsed.header.machine == 40
        return doc.symbols()
            .filter { it.type == 2 && it.shndx != 0 && it.name.isNotEmpty() }
            .map {
                // Thumb-бит ARM32: младший бит адреса — режим, не адрес
                val addr = if (isArm32) it.value and 0xFFFFFFFEL else it.value
                NativeFunc(it.name, addr, it.size)
            }
            .sortedBy { it.vaddr }
            .distinctBy { it.vaddr to it.name }
    }

    /** Срез инструкций функции [vaddr, end). */
    fun sliceForFunc(all: List<NativeInsn>, func: NativeFunc, nextVaddr: Long?): List<NativeInsn> {
        val end = when {
            func.size > 0 -> func.vaddr + func.size
            nextVaddr != null && nextVaddr > func.vaddr -> minOf(nextVaddr, func.vaddr + 4096)
            else -> func.vaddr + 4096
        }
        return all.filter { it.vaddr >= func.vaddr && it.vaddr < end }
    }

    /** XREF: инструкции, чья статическая цель лежит внутри функции. */
    fun xrefsTo(all: List<NativeInsn>, func: NativeFunc, nextVaddr: Long?): List<NativeInsn> {
        val end = if (func.size > 0) func.vaddr + func.size else nextVaddr ?: (func.vaddr + 4096)
        return all.filter { it.targetVaddr != null && it.targetVaddr!! >= func.vaddr && it.targetVaddr!! < end }
    }

    internal fun parseLine(line: String, machine: Int): NativeInsn? {
        val t = line.trimEnd()
        if (t.isEmpty()) return null
        if (labelRe.matches(t)) return null // метки пропускаем, границы — по символам ELF
        val m = gnuInsnRe.matchEntire(t) ?: llvmInsnRe.matchEntire(t) ?: return null
        return try {
            val addr = m.groupValues[1].toLong(16)
            val rawBytes = m.groupValues[2].trim()
            val mnemonic = m.groupValues[3].trim()
            if (mnemonic.isEmpty() || mnemonic == "<unknown>" || mnemonic == "(bad)") {
                return NativeInsn(addr, spacedBytes(rawBytes), "<данные>", "", NativeJump.NONE, null)
            }
            val operands = m.groupValues[4].trim()
            val (jump, target) = classifyBranch(machine, mnemonic, operands)
            NativeInsn(addr, spacedBytes(rawBytes), mnemonic, operands, jump, target)
        } catch (_: Exception) {
            null
        }
    }

    private fun spacedBytes(raw: String): String {
        val clean = raw.replace(" ", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return raw.trim()
        if (!clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return raw.trim()
        return clean.chunked(2).joinToString(" ")
    }

    private fun classifyBranch(machine: Int, mnemonic: String, operands: String): Pair<NativeJump, Long?> {
        val jump = when (machine) {
            183 -> when {
                mnemonic in AARCH64_CALL -> NativeJump.CALL
                mnemonic in AARCH64_JUMP || AARCH64_COND.matches(mnemonic) -> NativeJump.JUMP
                mnemonic in AARCH64_INDIRECT || mnemonic == "ret" -> NativeJump.INDIRECT
                else -> NativeJump.NONE
            }
            40 -> when {
                mnemonic in ARM_CALL -> NativeJump.CALL
                mnemonic == "b" || ARM_COND.matches(mnemonic) -> NativeJump.JUMP
                mnemonic in ARM_INDIRECT || mnemonic == "ret" -> NativeJump.INDIRECT
                else -> NativeJump.NONE
            }
            243 -> when {
                mnemonic in RISCV_JUMP -> NativeJump.JUMP
                mnemonic == "ret" || mnemonic == "jalr" -> NativeJump.INDIRECT
                else -> NativeJump.NONE
            }
            else -> when {
                mnemonic == "call" || mnemonic == "callq" -> NativeJump.CALL
                mnemonic == "jmp" || mnemonic == "jmpq" -> NativeJump.JUMP
                mnemonic.startsWith("ret") -> NativeJump.RET
                mnemonic.startsWith("j") && mnemonic.length in 2..6 -> NativeJump.JUMP
                mnemonic.startsWith("loop") -> NativeJump.JUMP
                else -> NativeJump.NONE
            }
        }
        if (jump != NativeJump.JUMP && jump != NativeJump.CALL) {
            return jump to null
        }
        // GNU objdump дописывает `<func+0x..>` — режем, иначе 0x оттуда даст ложную цель.
        // Скобки [...] тоже режем: там rip-отступы/indirect (call [rip+0xff20]), не цели.
        // Из оставшихся 0x берём ПОСЛЕДНИЙ: у tbz/tbnz первый — номер бита (#0x12).
        val cleanOps = operands.substringBefore('<')
            .replace(Regex("\\[[^\\]]*\\]"), " ").trim()
        hexAddrRe.findAll(cleanOps).lastOrNull()?.let {
            return jump to it.groupValues[1].toLong(16)
        }
        val first = cleanOps.split(Regex("[\\s,\\[\\](){}+\\-*]+")).firstOrNull { it.isNotEmpty() }
        if (first != null && bareHexRe.matches(first)) {
            try {
                return jump to first.toLong(16)
            } catch (_: Exception) {
            }
        }
        return jump to null
    }
}
