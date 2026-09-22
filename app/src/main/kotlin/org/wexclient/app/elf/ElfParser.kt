package org.wexclient.app.elf

import java.nio.ByteBuffer
import java.nio.ByteOrder

// --- Структуры -----------------------------------------------------------------

data class ElfHeader(
    val is64Bit: Boolean,
    val littleEndian: Boolean,
    val osAbi: Int,
    val type: Int,
    val machine: Int,
    val version: Int,
    val entry: Long,
    val phOff: Long,
    val shOff: Long,
    val flags: Long,
    val ehSize: Int,
    val phEntSize: Int,
    val phNum: Int,
    val shEntSize: Int,
    val shNum: Int,
    val shStrNdx: Int
)

data class ElfProgram(
    val index: Int,
    val type: Int,
    val flags: Long,
    val offset: Long,
    val vaddr: Long,
    val paddr: Long,
    val fileSize: Long,
    val memSize: Long,
    val align: Long
)

data class ElfSection(
    val index: Int,
    val name: String,
    val type: Int,
    val flags: Long,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val link: Int,
    val info: Int,
    val align: Long,
    val entSize: Long
)

data class ElfSymbol(
    val name: String,
    val value: Long,
    val size: Long,
    val bind: Int,
    val type: Int,
    val shndx: Int,
    val table: String
)

data class ElfDynamic(
    val tag: Long,
    val value: Long
)

// --- Парсер --------------------------------------------------------------------

object ElfParser {

    const val ET_NONE = 0
    const val ET_REL = 1
    const val ET_EXEC = 2
    const val ET_DYN = 3
    const val ET_CORE = 4

    fun isElf(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
    }

    fun typeName(type: Int): String = when (type) {
        ET_NONE -> "NONE (неизвестный)"
        ET_REL -> "REL (перемещаемый)"
        ET_EXEC -> "EXEC (исполняемый)"
        ET_DYN -> "DYN (разделяемая библиотека)"
        ET_CORE -> "CORE (core-дамп)"
        else -> "Тип $type"
    }

    fun machineName(machine: Int): String = when (machine) {
        0 -> "Не указана"
        3 -> "x86 (386)"
        20 -> "PowerPC"
        21 -> "PowerPC64"
        40 -> "ARM (32-bit)"
        62 -> "x86-64"
        183 -> "AArch64 (ARM 64-bit)"
        243 -> "RISC-V"
        8 -> "MIPS"
        else -> "Machine $machine"
    }

    fun osAbiName(osAbi: Int): String = when (osAbi) {
        0 -> "System V / Android"
        1 -> "HP-UX"
        2 -> "NetBSD"
        3 -> "Linux"
        6 -> "Solaris"
        9 -> "FreeBSD"
        12 -> "OpenBSD"
        97 -> "ARM"
        255 -> "Embedded"
        else -> "ABI $osAbi"
    }

    fun progTypeName(type: Int): String = when (type) {
        0 -> "NULL"
        1 -> "LOAD"
        2 -> "DYNAMIC"
        3 -> "INTERP"
        4 -> "NOTE"
        5 -> "SHLIB"
        6 -> "PHDR"
        7 -> "TLS"
        0x6474e550 -> "GNU_EH_FRAME"
        0x6474e551 -> "GNU_STACK"
        0x6474e552 -> "GNU_RELRO"
        0x6474e553 -> "GNU_PROPERTY"
        0x6ffffffa -> "LOOS+5ffffffa"
        0x6ffffffb -> "LOOS+5ffffffb"
        else -> "0x" + Integer.toHexString(type)
    }

    fun progFlagsString(flags: Long): String {
        val sb = StringBuilder()
        if (flags and 0x4 != 0L) sb.append('R')
        if (flags and 0x2 != 0L) sb.append('W')
        if (flags and 0x1 != 0L) sb.append('E')
        if (sb.isEmpty()) sb.append('-')
        return sb.toString()
    }

    fun sectionTypeName(type: Int): String = when (type) {
        0 -> "NULL"
        1 -> "PROGBITS"
        2 -> "SYMTAB"
        3 -> "STRTAB"
        4 -> "RELA"
        5 -> "HASH"
        6 -> "DYNAMIC"
        7 -> "NOTE"
        8 -> "NOBITS"
        9 -> "REL"
        10 -> "SHLIB"
        11 -> "DYNSYM"
        14 -> "INIT_ARRAY"
        15 -> "FINI_ARRAY"
        16 -> "PREINIT_ARRAY"
        17 -> "GROUP"
        18 -> "SYMTAB_SHNDX"
        0x6ffffff6 -> "GNU_HASH"
        0x6ffffffd -> "GNU_VERDEF"
        0x6ffffffe -> "GNU_VERNEED"
        0x6fffffff -> "GNU_VERSYM"
        else -> "0x" + Integer.toHexString(type)
    }

    fun symbolBindName(bind: Int): String = when (bind) {
        0 -> "LOCAL"
        1 -> "GLOBAL"
        2 -> "WEAK"
        10 -> "UNIQUE"
        else -> "$bind"
    }

    fun symbolTypeName(type: Int): String = when (type) {
        0 -> "NOTYPE"
        1 -> "OBJECT"
        2 -> "FUNC"
        3 -> "SECTION"
        4 -> "FILE"
        5 -> "COMMON"
        6 -> "TLS"
        10 -> "IFUNC"
        else -> "$type"
    }

    fun dynamicTagName(tag: Long): String = when (tag) {
        0L -> "NULL"
        1L -> "NEEDED"
        2L -> "PLTRELSZ"
        3L -> "PLTGOT"
        4L -> "HASH"
        5L -> "STRTAB"
        6L -> "SYMTAB"
        7L -> "RELA"
        8L -> "RELASZ"
        9L -> "RELAENT"
        10L -> "STRSZ"
        11L -> "SYMENT"
        12L -> "INIT"
        13L -> "FINI"
        14L -> "SONAME"
        15L -> "RPATH"
        16L -> "SYMBOLIC"
        17L -> "REL"
        18L -> "RELSZ"
        19L -> "RELENT"
        20L -> "PLTREL"
        21L -> "DEBUG"
        22L -> "TEXTREL"
        23L -> "JMPREL"
        24L -> "BIND_NOW"
        25L -> "INIT_ARRAY"
        26L -> "FINI_ARRAY"
        27L -> "INIT_ARRAYSZ"
        28L -> "FINI_ARRAYSZ"
        29L -> "RUNPATH"
        30L -> "FLAGS"
        32L -> "PREINIT_ARRAY"
        33L -> "PREINIT_ARRAYSZ"
        0x6ffffff0L -> "VERSYM"
        0x6ffffffbL -> "FLAGS_1"
        0x6ffffffcL -> "VERDEF"
        0x6ffffffdL -> "VERDEFNUM"
        0x6ffffffeL -> "VERNEED"
        0x6fffffffL -> "VERNEEDNUM"
        else -> "0x" + java.lang.Long.toHexString(tag)
    }

    // --- Низкоуровневое чтение с проверкой границ ---

    private class Reader(val bytes: ByteArray, littleEndian: Boolean) {
        val buf: ByteBuffer = ByteBuffer.wrap(bytes)
            .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        fun ok(pos: Long, size: Int): Boolean = pos >= 0 && pos + size <= bytes.size.toLong()

        fun u16(pos: Long): Int {
            if (!ok(pos, 2)) throw IndexOutOfBoundsException("u16@$pos")
            return buf.getShort(pos.toInt()).toInt() and 0xFFFF
        }

        fun u32(pos: Long): Long {
            if (!ok(pos, 4)) throw IndexOutOfBoundsException("u32@$pos")
            return buf.getInt(pos.toInt()).toLong() and 0xFFFFFFFFL
        }

        fun u64(pos: Long): Long {
            if (!ok(pos, 8)) throw IndexOutOfBoundsException("u64@$pos")
            return buf.getLong(pos.toInt())
        }

        fun u8(pos: Long): Int {
            if (!ok(pos, 1)) throw IndexOutOfBoundsException("u8@$pos")
            return bytes[pos.toInt()].toInt() and 0xFF
        }

        fun cString(pos: Long, maxLen: Int = 4096): String {
            if (pos < 0 || pos >= bytes.size) return ""
            var end = pos
            val stopAt = minOf(bytes.size.toLong(), pos + maxLen)
            while (end < stopAt && bytes[end.toInt()] != 0.toByte()) end++
            return String(bytes, pos.toInt(), (end - pos).toInt(), Charsets.UTF_8)
        }
    }

    data class Parsed(
        val header: ElfHeader,
        val programs: List<ElfProgram>,
        val sections: List<ElfSection>
    )

    fun parse(bytes: ByteArray): Parsed {
        require(isElf(bytes)) { "Не ELF-файл (нет магии 7F 45 4C 46)" }
        require(bytes.size >= 52) { "Файл короче ELF-заголовка" }

        val clazz = bytes[4].toInt() and 0xFF
        val data = bytes[5].toInt() and 0xFF
        require(clazz == 1 || clazz == 2) { "Неизвестный класс ELF: $clazz" }
        require(data == 1 || data == 2) { "Неизвестный порядок байт ELF: $data" }

        val is64 = clazz == 2
        val le = data == 1
        val r = Reader(bytes, le)
        val osAbi = r.u8(7)

        val header: ElfHeader
        if (!is64) {
            header = ElfHeader(
                is64Bit = false, littleEndian = le, osAbi = osAbi,
                type = r.u16(16), machine = r.u16(18), version = r.u32(20).toInt(),
                entry = r.u32(24), phOff = r.u32(28), shOff = r.u32(32),
                flags = r.u32(36), ehSize = r.u16(40), phEntSize = r.u16(42),
                phNum = r.u16(44), shEntSize = r.u16(46), shNum = r.u16(48),
                shStrNdx = r.u16(50)
            )
        } else {
            require(bytes.size >= 64) { "Файл короче ELF64-заголовка" }
            header = ElfHeader(
                is64Bit = true, littleEndian = le, osAbi = osAbi,
                type = r.u16(16), machine = r.u16(18), version = r.u32(20).toInt(),
                entry = r.u64(24), phOff = r.u64(32), shOff = r.u64(40),
                flags = r.u32(48), ehSize = r.u16(52), phEntSize = r.u16(54),
                phNum = r.u16(56), shEntSize = r.u16(58), shNum = r.u16(60),
                shStrNdx = r.u16(62)
            )
        }

        // Program headers
        val programs = ArrayList<ElfProgram>()
        if (header.phOff != 0L && header.phNum > 0 && header.phNum < 100) {
            val entSize = if (header.phEntSize != 0) header.phEntSize else if (is64) 56 else 32
            for (i in 0 until header.phNum) {
                val p = header.phOff + i.toLong() * entSize
                try {
                    if (!is64) {
                        programs.add(
                            ElfProgram(
                                index = i, type = r.u32(p).toInt(),
                                offset = r.u32(p + 4), vaddr = r.u32(p + 8),
                                paddr = r.u32(p + 12), fileSize = r.u32(p + 16),
                                memSize = r.u32(p + 20), flags = r.u32(p + 24),
                                align = r.u32(p + 28)
                            )
                        )
                    } else {
                        programs.add(
                            ElfProgram(
                                index = i, type = r.u32(p).toInt(), flags = r.u32(p + 4),
                                offset = r.u64(p + 8), vaddr = r.u64(p + 16),
                                paddr = r.u64(p + 24), fileSize = r.u64(p + 32),
                                memSize = r.u64(p + 40), align = r.u64(p + 48)
                            )
                        )
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        // Section headers (имена резолвим после чтения shstrtab)
        data class RawSh(
            val nameIdx: Int, val type: Int, val flags: Long, val addr: Long,
            val offset: Long, val size: Long, val link: Int, val info: Int,
            val align: Long, val entSize: Long
        )
        val rawSections = ArrayList<RawSh>()
        val minShEnt = if (is64) 64 else 40
        if (header.shOff != 0L && header.shNum > 0 && header.shNum < 1000 &&
            header.shEntSize >= minShEnt
        ) {
            for (i in 0 until header.shNum) {
                val s = header.shOff + i.toLong() * header.shEntSize
                try {
                    if (!is64) {
                        rawSections.add(
                            RawSh(
                                nameIdx = r.u32(s).toInt(), type = r.u32(s + 4).toInt(),
                                flags = r.u32(s + 8), addr = r.u32(s + 12),
                                offset = r.u32(s + 16), size = r.u32(s + 20),
                                link = r.u32(s + 24).toInt(), info = r.u32(s + 28).toInt(),
                                align = r.u32(s + 32), entSize = r.u32(s + 36)
                            )
                        )
                    } else {
                        rawSections.add(
                            RawSh(
                                nameIdx = r.u32(s).toInt(), type = r.u32(s + 4).toInt(),
                                flags = r.u64(s + 8), addr = r.u64(s + 16),
                                offset = r.u64(s + 24), size = r.u64(s + 32),
                                link = r.u32(s + 40).toInt(), info = r.u32(s + 44).toInt(),
                                align = r.u64(s + 48), entSize = r.u64(s + 56)
                            )
                        )
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        // shstrtab: обычный индекс, либо расширенный (SHN_XINDEX через sh_link секции 0)
        var shStrNdx = header.shStrNdx
        if (shStrNdx == 0xffff && rawSections.isNotEmpty()) {
            shStrNdx = rawSections[0].link
        }
        val shStrOff: Long = if (shStrNdx in rawSections.indices) rawSections[shStrNdx].offset else -1L

        val sections = rawSections.mapIndexed { i, rs ->
            val name = if (shStrOff >= 0 && rs.nameIdx >= 0) {
                r.cString(shStrOff + rs.nameIdx)
            } else {
                ""
            }
            ElfSection(
                index = i, name = name, type = rs.type, flags = rs.flags,
                addr = rs.addr, offset = rs.offset, size = rs.size,
                link = rs.link, info = rs.info, align = rs.align, entSize = rs.entSize
            )
        }

        return Parsed(header, programs, sections)
    }

    /** Все символы из .dynsym и .symtab (если есть). Лимит от переполнения UI. */
    fun symbols(bytes: ByteArray, parsed: Parsed, maxSymbols: Int = 20000): List<ElfSymbol> {
        val le = parsed.header.littleEndian
        val is64 = parsed.header.is64Bit
        val r = Reader(bytes, le)
        val out = ArrayList<ElfSymbol>()
        for (sec in parsed.sections) {
            if (sec.type != 11 && sec.type != 2) continue
            val tableName = if (sec.type == 11) ".dynsym" else ".symtab"
            val strSec = parsed.sections.getOrNull(sec.link) ?: continue
            val entSize = if (sec.entSize != 0L) sec.entSize else if (is64) 24L else 16L
            if (entSize <= 0) continue
            val count = minOf(sec.size / entSize, maxSymbols - out.size.toLong())
            if (count <= 0) break
            // Пропускаем нулевой символ (индекс 0 всегда пустой)
            for (i in 1 until count) {
                val p = sec.offset + i * entSize
                try {
                    val name: String
                    val value: Long
                    val size: Long
                    val bind: Int
                    val type: Int
                    val shndx: Int
                    if (!is64) {
                        val nameIdx = r.u32(p).toInt()
                        value = r.u32(p + 4); size = r.u32(p + 8)
                        val info = r.u8(p + 12)
                        r.u8(p + 13)
                        shndx = r.u16(p + 14)
                        bind = info shr 4; type = info and 0xF
                        name = r.cString(strSec.offset + nameIdx)
                    } else {
                        val nameIdx = r.u32(p).toInt()
                        val info = r.u8(p + 4)
                        r.u8(p + 5)
                        shndx = r.u16(p + 6)
                        value = r.u64(p + 8); size = r.u64(p + 16)
                        bind = info shr 4; type = info and 0xF
                        name = r.cString(strSec.offset + nameIdx)
                    }
                    out.add(ElfSymbol(name, value, size, bind, type, shndx, tableName))
                    if (out.size >= maxSymbols) return out
                } catch (_: Exception) {
                    break
                }
            }
        }
        return out
    }

    /** Записи .dynamic; имена NEEDED/SONAME резолвятся через связанный strtab. */
    fun dynamics(bytes: ByteArray, parsed: Parsed): List<ElfDynamic> {
        val le = parsed.header.littleEndian
        val is64 = parsed.header.is64Bit
        val r = Reader(bytes, le)
        val out = ArrayList<ElfDynamic>()
        val sec = parsed.sections.firstOrNull { it.type == 6 } ?: return out
        val entSize = if (is64) 16L else 8L
        if (sec.size <= 0 || sec.size > 1_000_000) return out
        val count = sec.size / entSize
        for (i in 0 until count) {
            val p = sec.offset + i * entSize
            try {
                val tag: Long
                val value: Long
                if (!is64) {
                    tag = r.u32(p); value = r.u32(p + 4)
                } else {
                    tag = r.u64(p); value = r.u64(p + 8)
                }
                out.add(ElfDynamic(tag, value))
                if (tag == 0L) break
            } catch (_: Exception) {
                break
            }
        }
        return out
    }

    fun dynamicString(bytes: ByteArray, parsed: Parsed, offsetInStrtab: Long): String {
        val dynSec = parsed.sections.firstOrNull { it.type == 6 } ?: return ""
        val strSec = parsed.sections.getOrNull(dynSec.link) ?: return ""
        val le = parsed.header.littleEndian
        return Reader(bytes, le).cString(strSec.offset + offsetInStrtab)
    }

    fun neededLibraries(bytes: ByteArray, parsed: Parsed): List<String> {
        return dynamics(bytes, parsed)
            .filter { it.tag == 1L }
            .map { dynamicString(bytes, parsed, it.value) }
            .filter { it.isNotEmpty() }
    }

    fun soname(bytes: ByteArray, parsed: Parsed): String? {
        val d = dynamics(bytes, parsed).firstOrNull { it.tag == 14L } ?: return null
        return dynamicString(bytes, parsed, d.value).ifEmpty { null }
    }
}
