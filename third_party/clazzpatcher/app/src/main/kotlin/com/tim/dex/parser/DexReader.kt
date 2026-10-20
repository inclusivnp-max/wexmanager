package com.tim.dex.parser

import com.tim.dex.data.DebugConstants
import com.tim.dex.data.DexConstants
import com.tim.dex.data.DexMagic
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.MapItemType
import com.tim.dex.model.*
import com.tim.dex.model.Annotation
import com.tim.dex.util.DexBuffer
import com.tim.dex.util.IntIntMap
import com.tim.dex.util.StringPool
import java.io.File

/**
 * Парсер DEX.
 *
 * Контракт индексов: `-1` = отсутствует, `>= 0` = индекс.
 * Каждый вызов [read] работает со своим состоянием: ранее возвращённые [DexFile] не меняются.
 */
class DexReader {
    fun read(file: File): DexFile = read(file.readBytes())

    fun read(bytes: ByteArray): DexFile = DexParser(bytes).parse()
}

/** Состояние одного чтения. */
/**
 * Предел вложенности encoded_value (массивы и аннотации в значениях): формат его не задаёт, компиляторы
 * не порождают и десятка уровней, а рекурсивный разбор (и все обходы значений ниже по конвейеру)
 * должен укладываться в стек.
 */
const val MAX_VALUE_DEPTH = 256

private val KNOWN_MAP_TYPES = intArrayOf(
    MapItemType.HEADER_ITEM, MapItemType.STRING_ID_ITEM, MapItemType.TYPE_ID_ITEM, MapItemType.PROTO_ID_ITEM,
    MapItemType.FIELD_ID_ITEM, MapItemType.METHOD_ID_ITEM, MapItemType.CLASS_DEF_ITEM, MapItemType.CALL_SITE_ID_ITEM,
    MapItemType.METHOD_HANDLE_ITEM, MapItemType.MAP_LIST, MapItemType.TYPE_LIST, MapItemType.ANNOTATION_SET_REF_LIST,
    MapItemType.ANNOTATION_SET_ITEM, MapItemType.CLASS_DATA_ITEM, MapItemType.CODE_ITEM, MapItemType.STRING_DATA_ITEM,
    MapItemType.DEBUG_INFO_ITEM, MapItemType.ANNOTATION_ITEM, MapItemType.ENCODED_ARRAY_ITEM,
    MapItemType.ANNOTATIONS_DIRECTORY_ITEM, MapItemType.HIDDENAPI_CLASS_DATA_ITEM
)

private class DexParser(private val bytes: ByteArray) {
    private val buf = DexBuffer(bytes)
    private val b = DexFileBuilder()
    private var fileSize = 0
    private var mapList: List<MapItem> = emptyList()

    // смещение -> индекс элемента в модели (размеры берутся из map_list)
    private lateinit var typeListByOff: IntIntMap
    private lateinit var encodedArrayByOff: IntIntMap
    private lateinit var debugInfoByOff: IntIntMap
    private lateinit var codeItemByOff: IntIntMap
    private lateinit var classDataByOff: IntIntMap
    private lateinit var annotationItemByOff: IntIntMap
    private lateinit var annotationSetByOff: IntIntMap
    private lateinit var annotationSetRefListByOff: IntIntMap
    private lateinit var annotationsDirectoryByOff: IntIntMap
    private lateinit var callSiteByOff: IntIntMap

    /** Разбираемая структура — для сообщения о чтении за концом файла (строка без выделений при разборе). */
    private var ctxWhat = "header"
    private var ctxOff = 0

    fun parse(): DexFile = try {
        parseAll()
    } catch (e: IndexOutOfBoundsException) {
        // DexBuffer не проверяет границы на каждом чтении: выход за массив — обрезанный файл или смещение
        // внутри структуры, указывающее за его конец.
        throw IllegalArgumentException("$ctxWhat по смещению ${hex(ctxOff)}: чтение за концом файла (размер ${hex(bytes.size)})", e)
    }

    private fun parseAll(): DexFile {
        val header = parseHeader()
        b.header = header
        mapList = within("map_list", header.mapOff) { parseMapList(header) }
        b.mapList = mapList
        typeListByOff = IntIntMap(mapCount(MapItemType.TYPE_LIST))
        encodedArrayByOff = IntIntMap(mapCount(MapItemType.ENCODED_ARRAY_ITEM))
        debugInfoByOff = IntIntMap(mapCount(MapItemType.DEBUG_INFO_ITEM))
        codeItemByOff = IntIntMap(mapCount(MapItemType.CODE_ITEM))
        classDataByOff = IntIntMap(mapCount(MapItemType.CLASS_DATA_ITEM))
        annotationItemByOff = IntIntMap(mapCount(MapItemType.ANNOTATION_ITEM))
        annotationSetByOff = IntIntMap(mapCount(MapItemType.ANNOTATION_SET_ITEM))
        annotationSetRefListByOff = IntIntMap(mapCount(MapItemType.ANNOTATION_SET_REF_LIST))
        annotationsDirectoryByOff = IntIntMap(mapCount(MapItemType.ANNOTATIONS_DIRECTORY_ITEM))
        callSiteByOff = IntIntMap(mapCount(MapItemType.CALL_SITE_ID_ITEM))
        b.typeLists.ensureCapacity(mapCount(MapItemType.TYPE_LIST))
        b.codeItems.ensureCapacity(mapCount(MapItemType.CODE_ITEM))
        b.debugInfoItems.ensureCapacity(mapCount(MapItemType.DEBUG_INFO_ITEM))
        b.classDataItems.ensureCapacity(mapCount(MapItemType.CLASS_DATA_ITEM))
        b.annotationItems.ensureCapacity(mapCount(MapItemType.ANNOTATION_ITEM))
        b.annotationSetItems.ensureCapacity(mapCount(MapItemType.ANNOTATION_SET_ITEM))
        b.annotationsDirectoryItems.ensureCapacity(mapCount(MapItemType.ANNOTATIONS_DIRECTORY_ITEM))
        b.strings = within("string_ids", header.stringIdsOff) { parseStrings(header) }
        b.typeIds = within("type_ids", header.typeIdsOff) { parseTypeIds(header) }
        b.protoIds = within("proto_ids", header.protoIdsOff) { parseProtoIds(header) }
        b.fieldIds = within("field_ids", header.fieldIdsOff) { parseFieldIds(header) }
        b.methodIds = within("method_ids", header.methodIdsOff) { parseMethodIds(header) }
        b.methodHandles = within("method_handles", mapItem(MapItemType.METHOD_HANDLE_ITEM)?.offset ?: 0) { parseMethodHandles() }
        b.callSiteIds = within("call_site_ids", mapItem(MapItemType.CALL_SITE_ID_ITEM)?.offset ?: 0) { parseCallSiteIds() }
        b.classDefs = within("class_defs", header.classDefsOff) { parseClassDefs(header) }
        within("hiddenapi_class_data_item", mapItem(MapItemType.HIDDENAPI_CLASS_DATA_ITEM)?.offset ?: 0) { parseHiddenApiClassData(b.classDefs) }
        return b.build()
    }

    // ===== Проверки =====

    private fun hex(v: Int): String = "0x" + Integer.toHexString(v)

    /** Смещение элемента данных: внутри файла, не в заголовке, с нужным выравниванием. */
    private fun checkOffset(off: Int, align: Int, what: String) {
        if (off < 0x70 || off >= fileSize || (off and (align - 1)) != 0) {
            throw IllegalArgumentException("Некорректное смещение $what: ${hex(off)} (размер файла ${hex(fileSize)})")
        }
    }

    /** Секция из [count] элементов по [itemSize] байт целиком внутри файла. */
    private fun checkSection(off: Int, count: Int, itemSize: Int, what: String) {
        if (count == 0) return
        if (count < 0) throw IllegalArgumentException("Отрицательный размер секции $what: $count")
        checkOffset(off, 4, what)
        if (count.toLong() * itemSize > (fileSize - off).toLong()) {
            throw IllegalArgumentException("Секция $what (${hex(off)}, $count x $itemSize байт) выходит за конец файла")
        }
    }

    // ===== Заголовок и map =====

    private fun parseHeader(): DexHeader {
        if (bytes.size < 0x70) throw IllegalArgumentException("Файл слишком мал для DEX: ${bytes.size} байт")
        val h = DexHeader()
        h.magic = bytes.copyOfRange(0, 8)
        val version = DexMagic.versionOf(h.magic)
        if (version < 0) throw IllegalArgumentException("Неподдерживаемая версия DEX (magic ${DexMagic.describe(h.magic)})")
        buf.pos = 8
        h.checksum = buf.readInt()
        h.signature = buf.readBytes(20)
        h.fileSize = buf.readInt()
        h.headerSize = buf.readInt()
        h.endianTag = buf.readInt()
        h.linkSize = buf.readInt(); h.linkOff = buf.readInt()
        h.mapOff = buf.readInt()
        h.stringIdsSize = buf.readInt(); h.stringIdsOff = buf.readInt()
        h.typeIdsSize = buf.readInt(); h.typeIdsOff = buf.readInt()
        h.protoIdsSize = buf.readInt(); h.protoIdsOff = buf.readInt()
        h.fieldIdsSize = buf.readInt(); h.fieldIdsOff = buf.readInt()
        h.methodIdsSize = buf.readInt(); h.methodIdsOff = buf.readInt()
        h.classDefsSize = buf.readInt(); h.classDefsOff = buf.readInt()
        h.dataSize = buf.readInt(); h.dataOff = buf.readInt()

        if (h.endianTag == DexConstants.REVERSE_ENDIAN_CONSTANT) {
            throw IllegalArgumentException("DEX с порядком байтов big-endian не поддерживается")
        }
        if (h.endianTag != DexConstants.ENDIAN_CONSTANT) {
            throw IllegalArgumentException("Неверный endian_tag: ${hex(h.endianTag)}")
        }
        val expectedHeaderSize = DexMagic.headerSize(version)
        if (h.headerSize != expectedHeaderSize) {
            throw IllegalArgumentException("Неверный header_size ${hex(h.headerSize)} для DEX $version (ожидается ${hex(expectedHeaderSize)})")
        }
        if (version >= DexMagic.CONTAINER_VERSION) {
            if (bytes.size < 0x78) throw IllegalArgumentException("Файл слишком мал для заголовка DEX $version")
            h.containerSize = buf.readInt()
            h.headerOffset = buf.readInt()
            if (h.headerOffset != 0 || h.containerSize != h.fileSize) {
                throw IllegalArgumentException(
                    "Контейнер DEX $version с несколькими DEX не поддерживается " +
                        "(header_offset=${hex(h.headerOffset)}, container_size=${h.containerSize}, file_size=${h.fileSize})"
                )
            }
        }
        if (h.fileSize != bytes.size) {
            throw IllegalArgumentException("file_size в заголовке (${h.fileSize}) не совпадает с размером файла (${bytes.size})")
        }
        if (h.linkSize != 0) {
            // формат link-секции не специфицирован, при перезаписи она была бы потеряна
            throw IllegalArgumentException("DEX с link-секцией (link_size=${h.linkSize}) не поддерживается")
        }
        fileSize = h.fileSize
        return h
    }

    private fun parseMapList(h: DexHeader): List<MapItem> {
        checkOffset(h.mapOff, 4, "map_off")
        val count = buf.u4At(h.mapOff)
        checkSection(h.mapOff + 4, count, 12, "map_list")
        buf.pos = h.mapOff + 4
        val items = ArrayList<MapItem>(count)
        for (i in 0 until count) {
            val item = MapItem(buf.readUShort(), buf.readUShort(), buf.readInt(), buf.readInt())
            // Как dex_file_verifier ART: только известные типы, размер и смещение — в пределах файла.
            if (item.type !in KNOWN_MAP_TYPES) throw IllegalArgumentException("map_list: неизвестный тип секции ${hex(item.type)}")
            if (item.size < 0 || item.size > fileSize) {
                throw IllegalArgumentException("map_list: секция ${hex(item.type)}: некорректный размер ${Integer.toUnsignedString(item.size)}")
            }
            if (item.offset < 0 || item.offset > fileSize) {
                throw IllegalArgumentException("map_list: секция ${hex(item.type)}: смещение ${hex(item.offset)} за концом файла")
            }
            for (prev in items) {
                if (prev.type == item.type) throw IllegalArgumentException("Повторная секция типа ${hex(item.type)} в map_list")
            }
            items.add(item)
        }
        return items
    }

    private fun mapItem(type: Int): MapItem? {
        for (item in mapList) if (item.type == type) return item
        return null
    }

    /** Ожидаемое число элементов секции (для начального размера таблиц). */
    private fun mapCount(type: Int): Int = (mapItem(type)?.size ?: 0).coerceIn(0, fileSize / 4)

    // ===== Идентификаторы =====

    private fun parseStrings(h: DexHeader): List<String> {
        val n = h.stringIdsSize
        checkSection(h.stringIdsOff, n, 4, "string_ids")
        val result = ArrayList<String>(n)
        for (i in 0 until n) {
            val off = buf.u4At(h.stringIdsOff + 4 * i)
            if (off < 0x70 || off >= fileSize) checkOffset(off, 1, "string_data_off (строка #$i)")
            ctxWhat = "string_data_item"; ctxOff = off
            buf.pos = off
            val utf16Size = buf.readULEB128()
            result.add(StringPool.decodeMUTF8(bytes, buf.pos, utf16Size))
        }
        return result
    }

    private fun parseTypeIds(h: DexHeader): List<TypeIdItem> {
        checkSection(h.typeIdsOff, h.typeIdsSize, 4, "type_ids")
        val base = h.typeIdsOff
        return List(h.typeIdsSize) { TypeIdItem(buf.u4At(base + 4 * it)) }
    }

    private fun parseProtoIds(h: DexHeader): List<ProtoIdItem> {
        checkSection(h.protoIdsOff, h.protoIdsSize, 12, "proto_ids")
        val base = h.protoIdsOff
        return List(h.protoIdsSize) {
            val p = base + 12 * it
            ProtoIdItem(buf.u4At(p), buf.u4At(p + 4), parseTypeListAt(buf.u4At(p + 8)))
        }
    }

    private fun parseFieldIds(h: DexHeader): List<FieldIdItem> {
        checkSection(h.fieldIdsOff, h.fieldIdsSize, 8, "field_ids")
        val base = h.fieldIdsOff
        return List(h.fieldIdsSize) {
            val p = base + 8 * it
            FieldIdItem(buf.u2At(p), buf.u2At(p + 2), buf.u4At(p + 4))
        }
    }

    private fun parseMethodIds(h: DexHeader): List<MethodIdItem> {
        checkSection(h.methodIdsOff, h.methodIdsSize, 8, "method_ids")
        val base = h.methodIdsOff
        return List(h.methodIdsSize) {
            val p = base + 8 * it
            MethodIdItem(buf.u2At(p), buf.u2At(p + 2), buf.u4At(p + 4))
        }
    }

    private fun parseMethodHandles(): List<MethodHandleItem> {
        val item = mapItem(MapItemType.METHOD_HANDLE_ITEM) ?: return emptyList()
        checkSection(item.offset, item.size, 8, "method_handles")
        val base = item.offset
        return List(item.size) {
            val p = base + 8 * it
            MethodHandleItem(buf.u2At(p), buf.u2At(p + 2), buf.u2At(p + 4), buf.u2At(p + 6))
        }
    }

    private fun parseCallSiteIds(): List<CallSiteIdItem> {
        val item = mapItem(MapItemType.CALL_SITE_ID_ITEM) ?: return emptyList()
        checkSection(item.offset, item.size, 4, "call_site_ids")
        val base = item.offset
        return List(item.size) { CallSiteIdItem(parseCallSiteAt(buf.u4At(base + 4 * it), it)) }
    }

    private fun parseClassDefs(h: DexHeader): List<ClassDefItem> {
        checkSection(h.classDefsOff, h.classDefsSize, 32, "class_defs")
        val base = h.classDefsOff
        return List(h.classDefsSize) {
            val p = base + 32 * it
            ClassDefItem(
                classIdx = buf.u4At(p),
                accessFlags = buf.u4At(p + 4),
                superclassIdx = buf.u4At(p + 8), // NO_INDEX = -1
                interfacesTypeListIdx = parseTypeListAt(buf.u4At(p + 12)),
                sourceFileIdx = buf.u4At(p + 16), // NO_INDEX = -1
                annotationsDirectoryIdx = parseAnnotationsDirectoryAt(buf.u4At(p + 20)),
                classDataIdx = parseClassDataAt(buf.u4At(p + 24)),
                staticValuesIdx = parseEncodedArrayAt(buf.u4At(p + 28))
            )
        }
    }

    // ===== Данные =====

    /** Контекст сообщения о чтении за концом файла на время [block] (позиция чтения не меняется). */
    private inline fun <T> within(what: String, offset: Int, block: () -> T): T {
        val savedWhat = ctxWhat
        val savedOff = ctxOff
        ctxWhat = what
        ctxOff = offset
        try {
            return block()
        } finally {
            ctxWhat = savedWhat
            ctxOff = savedOff
        }
    }

    private inline fun <T> at(offset: Int, what: String, block: () -> T): T {
        val saved = buf.pos
        val savedWhat = ctxWhat
        val savedOff = ctxOff
        buf.pos = offset
        ctxWhat = what
        ctxOff = offset
        try {
            return block()
        } finally {
            buf.pos = saved
            ctxWhat = savedWhat
            ctxOff = savedOff
        }
    }

    private fun parseTypeListAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = typeListByOff[offset]
        if (cached >= 0) return cached
        return within("type_list", offset) {
            checkOffset(offset, 4, "type_list")
            val size = buf.u4At(offset)
            checkSection(offset + 4, size, 2, "type_list")
            val types = List(size) { buf.u2At(offset + 4 + 2 * it) }
            val idx = b.typeLists.size
            b.typeLists.add(TypeListItem(size, types))
            typeListByOff.put(offset, idx)
            idx
        }
    }

    private fun parseClassDataAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = classDataByOff[offset]
        if (cached >= 0) return cached
        checkOffset(offset, 1, "class_data")
        return at(offset, "class_data_item") {
            val sFieldsSize = buf.readULEB128()
            val iFieldsSize = buf.readULEB128()
            val dMethodsSize = buf.readULEB128()
            val vMethodsSize = buf.readULEB128()
            val sFields = readEncodedFields(sFieldsSize)
            val iFields = readEncodedFields(iFieldsSize)
            val dMethods = readEncodedMethods(dMethodsSize)
            val vMethods = readEncodedMethods(vMethodsSize)
            val idx = b.classDataItems.size
            b.classDataItems.add(
                ClassDataItem(sFieldsSize, iFieldsSize, dMethodsSize, vMethodsSize, sFields, iFields, dMethods, vMethods)
            )
            classDataByOff.put(offset, idx)
            idx
        }
    }

    private fun readEncodedFields(count: Int): List<EncodedField> {
        if (count == 0) return emptyList()
        // encoded_field — не меньше 2 байт: ложный счётчик не должен выделять память.
        if (count < 0 || count > (fileSize - buf.pos) / 2) {
            throw IllegalArgumentException("class_data_item: $count полей не помещаются в остаток файла (смещение ${hex(buf.pos)})")
        }
        val list = ArrayList<EncodedField>(count)
        var idx = 0
        for (i in 0 until count) {
            idx += buf.readULEB128()
            list.add(EncodedField(idx, buf.readULEB128()))
        }
        return list
    }

    private fun readEncodedMethods(count: Int): List<EncodedMethod> {
        if (count == 0) return emptyList()
        if (count < 0 || count > (fileSize - buf.pos) / 3) {
            throw IllegalArgumentException("class_data_item: $count методов не помещаются в остаток файла (смещение ${hex(buf.pos)})")
        }
        val list = ArrayList<EncodedMethod>(count)
        var idx = 0
        for (i in 0 until count) {
            idx += buf.readULEB128()
            val flags = buf.readULEB128()
            val codeOff = buf.readULEB128()
            list.add(EncodedMethod(idx, flags, parseCodeItemAt(codeOff)))
        }
        return list
    }

    private fun parseCodeItemAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = codeItemByOff[offset]
        if (cached >= 0) return cached
        checkOffset(offset, 4, "code_item")
        return at(offset, "code_item") {
            val registersSize = buf.readUShort()
            val insSize = buf.readUShort()
            val outsSize = buf.readUShort()
            val triesSize = buf.readUShort()
            val debugInfoOff = buf.readInt()
            val insnsSize = buf.readInt()
            if (insnsSize < 0 || insnsSize.toLong() * 2 > (fileSize - buf.pos).toLong()) {
                throw IllegalArgumentException("code_item по смещению ${hex(offset)}: insns_size=$insnsSize выходит за конец файла")
            }
            val insns = buf.readBytes(insnsSize * 2)

            var tries: List<TryItem> = emptyList()
            var catchHandlers: List<CatchHandler> = emptyList()
            if (triesSize > 0) {
                buf.align4()
                val starts = IntArray(triesSize)
                val counts = IntArray(triesSize)
                val handlerOffs = IntArray(triesSize)
                for (i in 0 until triesSize) {
                    starts[i] = buf.readInt()
                    counts[i] = buf.readUShort()
                    handlerOffs[i] = buf.readUShort()
                }
                val listStart = buf.pos
                val handlersCount = buf.readULEB128()
                if (handlersCount <= 0 || handlersCount > 0xFFFF) {
                    throw IllegalArgumentException("code_item по смещению ${hex(offset)}: некорректный handlers_size=$handlersCount")
                }
                val offsets = IntArray(handlersCount)
                val handlers = ArrayList<CatchHandler>(handlersCount)
                for (h in 0 until handlersCount) {
                    offsets[h] = buf.pos - listStart
                    val size = buf.readSLEB128()
                    val typedCount = if (size <= 0) -size else size
                    if (typedCount < 0 || typedCount > (fileSize - buf.pos) / 2) {
                        throw IllegalArgumentException("code_item по смещению ${hex(offset)}: некорректный размер encoded_catch_handler $size")
                    }
                    val pairs: List<TypeAddrPair> =
                        if (typedCount == 0) emptyList()
                        else List(typedCount) { TypeAddrPair(buf.readULEB128(), buf.readULEB128()) }
                    handlers.add(CatchHandler(pairs, if (size <= 0) buf.readULEB128() else -1))
                }
                val tryList = ArrayList<TryItem>(triesSize)
                for (i in 0 until triesSize) {
                    val h = java.util.Arrays.binarySearch(offsets, handlerOffs[i])
                    if (h < 0) {
                        throw IllegalArgumentException(
                            "code_item по смещению ${hex(offset)}: обработчик исключений по смещению ${hex(handlerOffs[i])} не найден"
                        )
                    }
                    tryList.add(TryItem(starts[i], counts[i], h))
                }
                tries = tryList
                catchHandlers = handlers
            }

            val debugIdx = parseDebugInfoAt(debugInfoOff)
            val idx = b.codeItems.size
            b.codeItems.add(
                CodeItem(registersSize, insSize, outsSize, triesSize, debugIdx, insnsSize, insns, tries, catchHandlers)
            )
            codeItemByOff.put(offset, idx)
            idx
        }
    }

    private fun parseDebugInfoAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = debugInfoByOff[offset]
        if (cached >= 0) return cached
        checkOffset(offset, 1, "debug_info_item")
        return at(offset, "debug_info_item") {
            val lineStart = buf.readULEB128()
            val parametersSize = buf.readULEB128()
            if (parametersSize < 0 || parametersSize > 65536) {
                throw IllegalArgumentException("debug_info_item по смещению ${hex(offset)}: некорректный parameters_size=$parametersSize")
            }
            val parameterNames = List(parametersSize) { buf.readULEB128p1() }
            val opcodes = ArrayList<DebugOpcode>()
            while (true) {
                val opcode = buf.readUByte()
                when (opcode) {
                    DebugConstants.DBG_END_SEQUENCE -> {
                        opcodes.add(DebugOpcode.EndSequence)
                        break
                    }
                    DebugConstants.DBG_ADVANCE_PC -> opcodes.add(DebugOpcodes.advancePc(buf.readULEB128()))
                    DebugConstants.DBG_ADVANCE_LINE -> opcodes.add(DebugOpcodes.advanceLine(buf.readSLEB128()))
                    DebugConstants.DBG_START_LOCAL -> opcodes.add(
                        DebugOpcode.StartLocal(buf.readULEB128(), buf.readULEB128p1(), buf.readULEB128p1())
                    )
                    DebugConstants.DBG_START_LOCAL_EXTENDED -> opcodes.add(
                        DebugOpcode.StartLocalExtended(
                            buf.readULEB128(), buf.readULEB128p1(), buf.readULEB128p1(), buf.readULEB128p1()
                        )
                    )
                    DebugConstants.DBG_END_LOCAL -> opcodes.add(DebugOpcodes.endLocal(buf.readULEB128()))
                    DebugConstants.DBG_RESTART_LOCAL -> opcodes.add(DebugOpcodes.restartLocal(buf.readULEB128()))
                    DebugConstants.DBG_SET_PROLOGUE_END -> opcodes.add(DebugOpcode.SetPrologueEnd)
                    DebugConstants.DBG_SET_EPILOGUE_BEGIN -> opcodes.add(DebugOpcode.SetEpilogueBegin)
                    DebugConstants.DBG_SET_FILE -> opcodes.add(DebugOpcode.SetFile(buf.readULEB128p1()))
                    else -> opcodes.add(DebugOpcodes.special(opcode))
                }
            }
            val idx = b.debugInfoItems.size
            b.debugInfoItems.add(DebugInfoItem(lineStart, parametersSize, parameterNames, opcodes))
            debugInfoByOff.put(offset, idx)
            idx
        }
    }

    private fun parseEncodedArrayAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = encodedArrayByOff[offset]
        if (cached >= 0) return cached
        checkOffset(offset, 1, "encoded_array_item")
        return at(offset, "encoded_array_item") {
            val values = readEncodedArray()
            val idx = b.encodedArrayItems.size
            b.encodedArrayItems.add(values)
            encodedArrayByOff.put(offset, idx)
            idx
        }
    }

    private fun parseAnnotationsDirectoryAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = annotationsDirectoryByOff[offset]
        if (cached >= 0) return cached
        return within("annotations_directory_item", offset) {
            checkOffset(offset, 4, "annotations_directory_item")
            val classOff = buf.u4At(offset)
            val fieldsSize = buf.u4At(offset + 4)
            val methodsSize = buf.u4At(offset + 8)
            val paramsSize = buf.u4At(offset + 12)
            val total = fieldsSize.toLong() + methodsSize.toLong() + paramsSize.toLong()
            if (fieldsSize < 0 || methodsSize < 0 || paramsSize < 0 || total * 8 > (fileSize - offset - 16).toLong()) {
                throw IllegalArgumentException("annotations_directory_item по смещению ${hex(offset)} выходит за конец файла")
            }
            var p = offset + 16
            val fields = List(fieldsSize) {
                val item = FieldAnnotation(buf.u4At(p), parseAnnotationSetAt(buf.u4At(p + 4)))
                p += 8
                item
            }
            val methods = List(methodsSize) {
                val item = MethodAnnotation(buf.u4At(p), parseAnnotationSetAt(buf.u4At(p + 4)))
                p += 8
                item
            }
            val params = List(paramsSize) {
                val item = ParameterAnnotation(buf.u4At(p), parseAnnotationSetRefListAt(buf.u4At(p + 4)))
                p += 8
                item
            }
            val idx = b.annotationsDirectoryItems.size
            b.annotationsDirectoryItems.add(AnnotationsDirectoryItem(parseAnnotationSetAt(classOff), fields, methods, params))
            annotationsDirectoryByOff.put(offset, idx)
            idx
        }
    }

    private fun parseAnnotationSetAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = annotationSetByOff[offset]
        if (cached >= 0) return cached
        return within("annotation_set_item", offset) {
            checkOffset(offset, 4, "annotation_set_item")
            val size = buf.u4At(offset)
            checkSection(offset + 4, size, 4, "annotation_set_item")
            val entries = List(size) { parseAnnotationItemAt(buf.u4At(offset + 4 + 4 * it)) }
            val idx = b.annotationSetItems.size
            b.annotationSetItems.add(AnnotationSetItem(entries))
            annotationSetByOff.put(offset, idx)
            idx
        }
    }

    private fun parseAnnotationSetRefListAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = annotationSetRefListByOff[offset]
        if (cached >= 0) return cached
        return within("annotation_set_ref_list", offset) {
            checkOffset(offset, 4, "annotation_set_ref_list")
            val size = buf.u4At(offset)
            checkSection(offset + 4, size, 4, "annotation_set_ref_list")
            val entries = List(size) { parseAnnotationSetAt(buf.u4At(offset + 4 + 4 * it)) }
            val idx = b.annotationSetRefLists.size
            b.annotationSetRefLists.add(AnnotationSetRefList(entries))
            annotationSetRefListByOff.put(offset, idx)
            idx
        }
    }

    private fun parseAnnotationItemAt(offset: Int): Int {
        if (offset == 0) return -1
        val cached = annotationItemByOff[offset]
        if (cached >= 0) return cached
        checkOffset(offset, 1, "annotation_item")
        return at(offset, "annotation_item") {
            val visibility = buf.readByte()
            if (visibility < 0 || visibility > 2) {
                throw IllegalArgumentException("annotation_item по смещению ${hex(offset)}: неверная visibility $visibility")
            }
            val annotation = readEncodedAnnotation()
            val idx = b.annotationItems.size
            b.annotationItems.add(AnnotationItem(visibility, annotation))
            annotationItemByOff.put(offset, idx)
            idx
        }
    }

    private fun parseCallSiteAt(offset: Int, callSiteId: Int): Int {
        if (offset == 0) throw IllegalArgumentException("call_site_id #$callSiteId: нулевое смещение call_site_item")
        val cached = callSiteByOff[offset]
        if (cached >= 0) return cached
        if (offset < 0x70 || offset >= fileSize) checkOffset(offset, 1, "call_site_item (call_site_id #$callSiteId)")
        return at(offset, "call_site_item") {
            val values = readEncodedArray()
            if (values.isEmpty() || values[0].type != EncodedValueType.VALUE_METHOD_HANDLE) {
                throw IllegalArgumentException(
                    "call_site_item по смещению ${hex(offset)}: первый элемент должен быть method handle"
                )
            }
            val idx = b.callSiteItems.size
            b.callSiteItems.add(CallSite(values[0], values.subList(1, values.size)))
            callSiteByOff.put(offset, idx)
            idx
        }
    }

    // ===== Hidden API =====

    private fun parseHiddenApiClassData(classDefs: List<ClassDefItem>) {
        val item = mapItem(MapItemType.HIDDENAPI_CLASS_DATA_ITEM) ?: return
        if (item.size != 1) throw IllegalArgumentException("hiddenapi_class_data_item: в map_list size=${item.size}, ожидается 1")
        val start = item.offset
        checkOffset(start, 4, "hiddenapi_class_data_item")
        val sectionSize = buf.u4At(start)
        val headerSize = 4L + 4L * classDefs.size
        if (sectionSize < headerSize || sectionSize > fileSize - start) {
            throw IllegalArgumentException("hiddenapi_class_data_item: некорректный размер секции $sectionSize")
        }
        val end = start + sectionSize
        val flags = ArrayList<IntArray?>(classDefs.size)
        for (i in classDefs.indices) {
            val off = buf.u4At(start + 4 + 4 * i)
            if (off == 0) {
                flags.add(null)
                continue
            }
            val classDataIdx = classDefs[i].classDataIdx
            if (classDataIdx < 0) {
                throw IllegalArgumentException("hiddenapi_class_data_item: у class_def #$i нет class_data, но смещение флагов ${hex(off)}")
            }
            if (off < headerSize || off > sectionSize) {
                throw IllegalArgumentException("hiddenapi_class_data_item: смещение флагов ${hex(off)} для class_def #$i вне секции")
            }
            val cd = b.classDataItems[classDataIdx]
            val count = cd.staticFields.size + cd.instanceFields.size + cd.directMethods.size + cd.virtualMethods.size
            buf.pos = start + off
            val arr = IntArray(count)
            for (k in 0 until count) arr[k] = buf.readULEB128()
            if (buf.pos > end) {
                throw IllegalArgumentException("hiddenapi_class_data_item: флаги class_def #$i выходят за границу секции")
            }
            flags.add(arr)
        }
        b.hiddenApiClassDataItems.add(HiddenApiClassData(flags))
    }

    // ===== Закодированные значения =====

    /** Глубина вложенности encoded_array/encoded_annotation (разбор рекурсивный — ограничена). */
    private var valueDepth = 0

    private inline fun <T> nested(block: () -> T): T {
        if (++valueDepth > MAX_VALUE_DEPTH) {
            throw IllegalArgumentException("encoded_value по смещению ${hex(buf.pos)}: вложенность больше $MAX_VALUE_DEPTH")
        }
        try {
            return block()
        } finally {
            valueDepth--
        }
    }

    private fun readEncodedArray(): List<EncodedValue> = nested {
        val size = buf.readULEB128()
        if (size < 0 || size > fileSize - buf.pos) {
            throw IllegalArgumentException("encoded_array по смещению ${hex(buf.pos)}: некорректный размер $size")
        }
        if (size == 0) return@nested emptyList()
        val list = ArrayList<EncodedValue>(size)
        for (i in 0 until size) list.add(readEncodedValue())
        list
    }

    private fun readEncodedAnnotation(): Annotation = nested {
        val typeIdx = buf.readULEB128()
        val size = buf.readULEB128()
        if (size < 0 || size > fileSize - buf.pos) {
            throw IllegalArgumentException("encoded_annotation по смещению ${hex(buf.pos)}: некорректный размер $size")
        }
        val elements: List<AnnotationElement> =
            if (size == 0) emptyList()
            else List(size) { AnnotationElement(buf.readULEB128(), readEncodedValue()) }
        Annotation(typeIdx, elements)
    }

    private fun readEncodedValue(): EncodedValue {
        val start = buf.pos
        val typeAndArg = buf.readUByte()
        val type = typeAndArg and 0x1F
        val arg = typeAndArg ushr 5
        val size = arg + 1

        fun bad(): Nothing = throw IllegalArgumentException(
            "encoded_value по смещению ${hex(start)}: недопустимый value_arg=$arg для типа ${hex(type)}"
        )

        val value: Any? = when (type) {
            EncodedValueType.VALUE_BYTE -> {
                if (arg != 0) bad()
                buf.readByte()
            }
            EncodedValueType.VALUE_SHORT -> {
                if (size > 2) bad()
                readSigned(size).toShort()
            }
            EncodedValueType.VALUE_CHAR -> {
                if (size > 2) bad()
                readUnsigned(size).toChar()
            }
            EncodedValueType.VALUE_INT -> {
                if (size > 4) bad()
                readSigned(size)
            }
            EncodedValueType.VALUE_LONG -> readSignedLong(size)
            EncodedValueType.VALUE_FLOAT -> {
                if (size > 4) bad()
                // Значение дополнено нулями справа: байты — старшие
                java.lang.Float.intBitsToFloat(readUnsigned(size) shl ((4 - size) * 8))
            }
            EncodedValueType.VALUE_DOUBLE -> {
                var bits = 0L
                for (i in 0 until size) bits = bits or (buf.readUByte().toLong() shl (i * 8))
                java.lang.Double.longBitsToDouble(bits shl ((8 - size) * 8))
            }
            EncodedValueType.VALUE_STRING, EncodedValueType.VALUE_TYPE, EncodedValueType.VALUE_FIELD,
            EncodedValueType.VALUE_METHOD, EncodedValueType.VALUE_ENUM, EncodedValueType.VALUE_METHOD_TYPE,
            EncodedValueType.VALUE_METHOD_HANDLE -> {
                if (size > 4) bad()
                readUnsigned(size)
            }
            EncodedValueType.VALUE_ARRAY -> {
                if (arg != 0) bad()
                readEncodedArray()
            }
            EncodedValueType.VALUE_ANNOTATION -> {
                if (arg != 0) bad()
                readEncodedAnnotation()
            }
            EncodedValueType.VALUE_NULL -> {
                if (arg != 0) bad()
                null
            }
            EncodedValueType.VALUE_BOOLEAN -> {
                if (arg > 1) bad()
                arg != 0
            }
            else -> throw IllegalArgumentException("encoded_value по смещению ${hex(start)}: неизвестный тип ${hex(type)}")
        }
        return EncodedValue(type, value)
    }

    private fun readUnsigned(size: Int): Int {
        var result = 0
        for (i in 0 until size) result = result or (buf.readUByte() shl (i * 8))
        return result
    }

    private fun readSigned(size: Int): Int {
        val shift = (4 - size) * 8
        return (readUnsigned(size) shl shift) shr shift
    }

    private fun readSignedLong(size: Int): Long {
        var result = 0L
        for (i in 0 until size) result = result or (buf.readUByte().toLong() shl (i * 8))
        val shift = (8 - size) * 8
        return (result shl shift) shr shift
    }
}

/** Разделяемые неизменяемые экземпляры частых отладочных опкодов (меньше аллокаций). */
private object DebugOpcodes {
    private val SPECIAL = arrayOfNulls<DebugOpcode.SpecialOpcode>(256)
    private val ADVANCE_PC = Array(256) { DebugOpcode.AdvancePc(it) }
    private val ADVANCE_LINE = Array(256) { DebugOpcode.AdvanceLine(it - 128) }
    private val END_LOCAL = Array(256) { DebugOpcode.EndLocal(it) }
    private val RESTART_LOCAL = Array(256) { DebugOpcode.RestartLocal(it) }

    init {
        for (op in DebugConstants.DBG_FIRST_SPECIAL..0xFF) {
            val adjusted = op - DebugConstants.DBG_FIRST_SPECIAL
            SPECIAL[op] = DebugOpcode.SpecialOpcode(
                op,
                DebugConstants.DBG_LINE_BASE + adjusted % DebugConstants.DBG_LINE_RANGE,
                adjusted / DebugConstants.DBG_LINE_RANGE
            )
        }
    }

    fun special(opcode: Int): DebugOpcode.SpecialOpcode = SPECIAL[opcode]!!

    fun advancePc(delta: Int): DebugOpcode.AdvancePc =
        if (delta in 0..255) ADVANCE_PC[delta] else DebugOpcode.AdvancePc(delta)

    fun advanceLine(delta: Int): DebugOpcode.AdvanceLine =
        if (delta in -128..127) ADVANCE_LINE[delta + 128] else DebugOpcode.AdvanceLine(delta)

    fun endLocal(reg: Int): DebugOpcode.EndLocal =
        if (reg in 0..255) END_LOCAL[reg] else DebugOpcode.EndLocal(reg)

    fun restartLocal(reg: Int): DebugOpcode.RestartLocal =
        if (reg in 0..255) RESTART_LOCAL[reg] else DebugOpcode.RestartLocal(reg)
}
