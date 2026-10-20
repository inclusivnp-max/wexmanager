package com.tim.dex.writer

import com.tim.dex.data.DebugConstants
import com.tim.dex.data.DexConstants
import com.tim.dex.data.DexMagic
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.MapItemType
import com.tim.dex.model.*
import com.tim.dex.model.Annotation
import com.tim.dex.util.ByteOut
import com.tim.dex.util.IntList
import com.tim.dex.util.IntSort
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Запись [DexFile] в DEX.
 *
 * Пулы идентификаторов сортируются и дедуплицируются ([IdPools]), все ссылки
 * (включая байткод) перенумеровываются. Пишутся только элементы данных, достижимые
 * из class_defs, call site и method handle, за один проход в порядке зависимостей,
 * так что смещения всех ссылок известны к моменту записи ссылающегося элемента.
 *
 * [compact] — и в пулы id пишется только используемое ([LiveIds]): после замены классов их прежние строки,
 * поля и методы в файл не попадают. Без него пулы пишутся целиком (побайтовый round-trip чтения и записи).
 */
class DexWriter(private val dexFile: DexFile, private val compact: Boolean = false) {
    fun write(file: File) = file.writeBytes(buildDex())

    fun buildDex(): ByteArray = DexEmitter(dexFile, compact).emit()
}

/** Состояние одной записи. */
private class DexEmitter(private val dex: DexFile, compact: Boolean) {
    private val inputVersion = DexMagic.versionOf(dex.header.magic).also {
        if (it < 0) {
            throw IllegalArgumentException("Неподдерживаемая версия DEX в заголовке модели (magic ${DexMagic.describe(dex.header.magic)})")
        }
    }
    private val headerSize = DexMagic.headerSize(inputVersion)
    private val ids = IdPools(dex, if (compact) LiveIds(dex) else null)
    private val classCount = dex.classDefs.size

    /** Интерфейсы классов в новых индексах типов (по индексу class_def модели). */
    private val classInterfaces: Array<IntArray> = Array(classCount) {
        ids.typeList(dex.classDefs[it].interfacesTypeListIdx, "class_def.interfaces_off")
    }

    /** Новая позиция -> индекс class_def в модели. */
    private val classOrder: IntArray = orderClasses()

    // Начальная ёмкость — по размеру исходного файла (если модель прочитана из DEX)
    private val out = ByteOut((dex.header.fileSize.toLong() * 9 / 8).coerceIn(1L shl 16, 1L shl 30).toInt())
    private val tmp = ByteOut(1 shl 12)

    private val codeRemapper = CodeRemapper(
        ids.stringMap, ids.typeMap, ids.fieldMap, ids.methodMap, ids.protoMap,
        dex.callSiteIds.size, dex.methodHandles.size, describe = ::describeCode
    )

    // ---- map_list ----
    private val mapType = IntList()
    private val mapOffset = IntList()
    private val mapSize = IntList()

    // ---- смещения записанных элементов (0 = нет) ----
    private val stringDataOff = IntArray(ids.strings.size)
    private val protoParamsOff = IntArray(ids.protoCount)
    private val interfacesOff = IntArray(classCount)
    private val annotationItemOff = IntArray(dex.annotationItems.size)
    private val annotationSetOff = IntArray(dex.annotationSetItems.size)
    private val refListOff = IntArray(dex.annotationSetRefLists.size)
    private val directoryOff = IntArray(dex.annotationsDirectoryItems.size)
    private val callSiteOff = IntArray(dex.callSiteIds.size)
    private val staticValuesOff = IntArray(classCount)
    private var debugInfoOff = IntArray(dex.debugInfoItems.size)
    private val codeItemOff = IntArray(dex.codeItems.size)
    private val classDataOff = IntArray(dex.classDataItems.size)

    // ---- обход достижимых элементов (порядок первого посещения) ----
    private val itemOrder = IntList()
    private val setOrder = IntList()
    private val refOrder = IntList()
    private val dirOrder = IntList()
    private val debugOrder = IntList()
    private val codeOrder = IntList()
    private val classDataOrder = IntList()
    private val itemSeen = BooleanArray(dex.annotationItems.size)
    private val setSeen = BooleanArray(dex.annotationSetItems.size)
    private val refSeen = BooleanArray(dex.annotationSetRefLists.size)
    private val dirSeen = BooleanArray(dex.annotationsDirectoryItems.size)
    private val debugSeen = BooleanArray(dex.debugInfoItems.size)
    private val codeOwner = IntArray(dex.codeItems.size) { -1 }
    private val classDataOwner = IntArray(dex.classDataItems.size) { -1 }

    /** Члены class_data, отсортированные по новым индексам. */
    private val members = arrayOfNulls<Members>(dex.classDataItems.size)
    /** Элементы annotation_set (индексы annotation_item), отсортированные по новому типу. */
    private val setEntries = arrayOfNulls<IntArray>(dex.annotationSetItems.size)
    private val directories = arrayOfNulls<Directory>(dex.annotationsDirectoryItems.size)

    /** Минимальная версия DEX по содержимому классов (default-методы интерфейсов). */
    private var featureVersion = 0

    // ---- id-секции ----
    private var stringIdsOff = 0
    private var typeIdsOff = 0
    private var protoIdsOff = 0
    private var fieldIdsOff = 0
    private var methodIdsOff = 0
    private var classDefsOff = 0
    private var callSiteIdsOff = 0
    private var methodHandlesOff = 0
    private var dataOff = 0

    fun emit(): ByteArray {
        layoutIds()
        out.zeros(dataOff)
        collect()
        widenJumboStrings()

        writeStringData()
        writeTypeLists()
        writeAnnotationItems()
        writeAnnotationSets()
        writeAnnotationSetRefLists()
        writeAnnotationsDirectories()
        writeEncodedArrays()
        writeDebugInfos()
        writeCodeItems()
        writeClassData()
        writeHiddenApi()
        val mapOff = writeMapList()

        val outVersion = maxOf(
            maxOf(inputVersion, featureVersion),
            codeRemapper.requiredVersion,
            maxOf(if (dex.callSiteIds.isNotEmpty() || dex.methodHandles.isNotEmpty()) 38 else 0, if (hasSpacedNames()) 40 else 0)
        )
        writeHeader(outVersion, mapOff)
        writeIds()

        val bytes = out.toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(bytes, 32, bytes.size - 32)
        System.arraycopy(sha1.digest(), 0, bytes, 12, 20)
        val adler = Adler32()
        adler.update(bytes, 12, bytes.size - 12)
        val checksum = adler.value.toInt()
        bytes[8] = checksum.toByte()
        bytes[9] = (checksum ushr 8).toByte()
        bytes[10] = (checksum ushr 16).toByte()
        bytes[11] = (checksum ushr 24).toByte()
        return bytes
    }

    // =====================================================================
    // Порядок классов
    // =====================================================================

    /**
     * class_defs: суперкласс и интерфейсы, определённые в этом DEX, идут раньше класса,
     * иначе сохраняется исходный порядок. Повторное определение класса — ошибка.
     */
    private fun orderClasses(): IntArray {
        val defs = dex.classDefs
        val n = defs.size
        val classType = IntArray(n)
        val defByType = IntArray(ids.typeCount) { -1 }
        for (i in 0 until n) {
            val t = ids.type(defs[i].classIdx, "class_def.class_idx")
            if (defByType[t] >= 0) {
                throw IllegalArgumentException("Класс ${ids.strings[ids.typeDescriptor[t]]} определён дважды (class_def #${defByType[t]} и #$i)")
            }
            defByType[t] = i
            classType[i] = t
        }
        val superDef = IntArray(n) {
            val s = ids.typeOrNone(defs[it].superclassIdx, "class_def.superclass_idx")
            if (s < 0) -1 else defByType[s]
        }
        val order = IntArray(n)
        var k = 0
        val state = ByteArray(n) // 0 — не посещён, 1 — в обработке, 2 — готов
        val stack = IntArray(n)
        val nextDep = IntArray(n)
        for (root in 0 until n) {
            if (state[root].toInt() != 0) continue
            var sp = 0
            stack[sp++] = root
            state[root] = 1
            nextDep[root] = 0
            while (sp > 0) {
                val c = stack[sp - 1]
                val interfaces = classInterfaces[c]
                var dep = -1
                while (dep < 0 && nextDep[c] <= interfaces.size) {
                    val d = nextDep[c]++
                    val candidate = if (d == 0) superDef[c] else defByType[interfaces[d - 1]]
                    if (candidate < 0) continue
                    when (state[candidate].toInt()) {
                        0 -> dep = candidate
                        1 -> throw IllegalArgumentException(
                            "Циклическое наследование: ${ids.strings[ids.typeDescriptor[classType[candidate]]]}"
                        )
                    }
                }
                if (dep >= 0) {
                    stack[sp++] = dep
                    state[dep] = 1
                    nextDep[dep] = 0
                } else {
                    sp--
                    state[c] = 2
                    order[k++] = c
                }
            }
        }
        return order
    }

    // =====================================================================
    // Обход достижимых элементов данных
    // =====================================================================

    private class Members(
        val staticOrder: IntArray, val staticIdx: IntArray,
        val instanceOrder: IntArray, val instanceIdx: IntArray,
        val directOrder: IntArray, val directIdx: IntArray,
        val virtualOrder: IntArray, val virtualIdx: IntArray
    )

    private class Directory(
        val classSet: Int,
        val fieldIdx: IntArray, val fieldSet: IntArray,
        val methodIdx: IntArray, val methodSet: IntArray,
        val paramIdx: IntArray, val paramRef: IntArray
    )

    private fun collect() {
        for (pos in 0 until classCount) {
            val c = classOrder[pos]
            val def = dex.classDefs[c]
            if (def.annotationsDirectoryIdx != -1) visitDirectory(checkIdx(def.annotationsDirectoryIdx, dex.annotationsDirectoryItems.size, "class_def.annotations_off"))
            if (def.classDataIdx != -1) visitClassData(checkIdx(def.classDataIdx, dex.classDataItems.size, "class_def.class_data_off"), c)
        }
    }

    private fun visitClassData(cd: Int, classDef: Int) {
        if (classDataOwner[cd] >= 0) return
        classDataOwner[cd] = classDef
        classDataOrder.add(cd)
        val data = dex.classDataItems[cd]
        if (data.staticFieldsSize != data.staticFields.size || data.instanceFieldsSize != data.instanceFields.size ||
            data.directMethodsSize != data.directMethods.size || data.virtualMethodsSize != data.virtualMethods.size
        ) {
            throw IllegalArgumentException("class_data класса ${className(classDef)}: *Size не совпадают с размерами списков")
        }
        val sIdx = IntArray(data.staticFields.size) { ids.field(data.staticFields[it].fieldIdx, "class_data.static_fields") }
        val iIdx = IntArray(data.instanceFields.size) { ids.field(data.instanceFields[it].fieldIdx, "class_data.instance_fields") }
        val dIdx = IntArray(data.directMethods.size) { ids.method(data.directMethods[it].methodIdx, "class_data.direct_methods") }
        val vIdx = IntArray(data.virtualMethods.size) { ids.method(data.virtualMethods[it].methodIdx, "class_data.virtual_methods") }
        val m = Members(
            sortMembers(sIdx, classDef, "статическое поле"), sIdx,
            sortMembers(iIdx, classDef, "поле экземпляра"), iIdx,
            sortMembers(dIdx, classDef, "direct-метод"), dIdx,
            sortMembers(vIdx, classDef, "virtual-метод"), vIdx
        )
        members[cd] = m
        if ((dex.classDefs[classDef].accessFlags and ACC_INTERFACE) != 0 && hasDefaultOrStaticMethods(data)) featureVersion = 37
        for (k in m.directOrder.indices) visitCode(data.directMethods[m.directOrder[k]].codeIdx, data.directMethods[m.directOrder[k]].methodIdx)
        for (k in m.virtualOrder.indices) visitCode(data.virtualMethods[m.virtualOrder[k]].codeIdx, data.virtualMethods[m.virtualOrder[k]].methodIdx)
    }

    /**
     * Пробелы в SimpleName (имена членов и классов) формат допускает с версии 040: ART до Android 11
     * такие файлы отвергает, d8 без `--min-api 30` их не пишет. Пробелы — U+0020, U+00A0, U+2000…U+200A,
     * U+202F (прежде исключённые из SimpleName, см. descriptors_names.cc ART).
     */
    private fun hasSpacedNames(): Boolean {
        fun spaced(idx: Int): Boolean = dex.strings.getOrNull(idx)?.any {
            it == ' ' || it == '\u00a0' || it in '\u2000'..'\u200a' || it == '\u202f'
        } == true
        val live = ids.live
        return dex.fieldIds.indices.any { (live == null || live.fields[it]) && spaced(dex.fieldIds[it].nameIdx) } ||
            dex.methodIds.indices.any { (live == null || live.methods[it]) && spaced(dex.methodIds[it].nameIdx) } ||
            dex.typeIds.indices.any { (live == null || live.types[it]) && spaced(dex.typeIds[it].descriptorIdx) }
    }

    /**
     * Интерфейс с default-методами (virtual без abstract) или со static/private-методами
     * (кроме `<clinit>`) требует DEX 037+ (верификатор ART отвергает такие классы в 035).
     */
    private fun hasDefaultOrStaticMethods(data: ClassDataItem): Boolean {
        for (m in data.virtualMethods) if ((m.accessFlags and ACC_ABSTRACT) == 0) return true
        for (m in data.directMethods) {
            if (dex.strings[dex.methodIds[m.methodIdx].nameIdx] != "<clinit>") return true
        }
        return false
    }

    /** Порядок членов по новому индексу (стабильно); повтор — ошибка. */
    private fun sortMembers(newIdx: IntArray, classDef: Int, what: String): IntArray {
        val order = IntSort.sortedIndices(newIdx.size) { a, b -> Integer.compare(newIdx[a], newIdx[b]) }
        for (k in 1 until order.size) {
            if (newIdx[order[k - 1]] == newIdx[order[k]]) {
                val name = if (what.endsWith("метод")) describeNewMethod(newIdx[order[k]]) else describeNewField(newIdx[order[k]])
                throw IllegalArgumentException("class_data класса ${className(classDef)}: $what $name указан дважды")
            }
        }
        return order
    }

    private fun visitCode(codeIdx: Int, methodIdx: Int) {
        if (codeIdx == -1) return
        checkIdx(codeIdx, dex.codeItems.size, "encoded_method.code_off")
        if (codeOwner[codeIdx] >= 0) return
        codeOwner[codeIdx] = methodIdx
        codeOrder.add(codeIdx)
        val dbg = dex.codeItems[codeIdx].debugInfoIdx
        if (dbg != -1) {
            checkIdx(dbg, dex.debugInfoItems.size, "code_item.debug_info_off")
            if (!debugSeen[dbg]) {
                debugSeen[dbg] = true
                debugOrder.add(dbg)
            }
        }
    }

    private fun visitDirectory(d: Int) {
        if (dirSeen[d]) return
        dirSeen[d] = true
        dirOrder.add(d)
        val dir = dex.annotationsDirectoryItems[d]

        // Записи без набора аннотаций (-1) ничего не описывают и не пишутся (смещение 0 недопустимо)
        val fields = dir.fieldAnnotations.filter { it.annotationsSetIdx != -1 }
        val fIdx = IntArray(fields.size) { ids.field(fields[it].fieldIdx, "annotations_directory.field_annotations") }
        val fOrder = sortDirectoryEntries(fIdx, "поля", ::describeNewField)

        val methods = dir.methodAnnotations.filter { it.annotationsSetIdx != -1 }
        val mIdx = IntArray(methods.size) { ids.method(methods[it].methodIdx, "annotations_directory.method_annotations") }
        val mOrder = sortDirectoryEntries(mIdx, "метода", ::describeNewMethod)

        val params = dir.parameterAnnotations.filter { it.annotationsSetRefListIdx != -1 }
        val pIdx = IntArray(params.size) { ids.method(params[it].methodIdx, "annotations_directory.parameter_annotations") }
        val pOrder = sortDirectoryEntries(pIdx, "параметров метода", ::describeNewMethod)

        val sorted = Directory(
            classSet = if (dir.classAnnotationsSetIdx == -1) -1
            else checkIdx(dir.classAnnotationsSetIdx, dex.annotationSetItems.size, "annotations_directory.class_annotations_off"),
            fieldIdx = IntArray(fOrder.size) { fIdx[fOrder[it]] },
            fieldSet = IntArray(fOrder.size) {
                checkIdx(fields[fOrder[it]].annotationsSetIdx, dex.annotationSetItems.size, "field_annotation.annotations_off")
            },
            methodIdx = IntArray(mOrder.size) { mIdx[mOrder[it]] },
            methodSet = IntArray(mOrder.size) {
                checkIdx(methods[mOrder[it]].annotationsSetIdx, dex.annotationSetItems.size, "method_annotation.annotations_off")
            },
            paramIdx = IntArray(pOrder.size) { pIdx[pOrder[it]] },
            paramRef = IntArray(pOrder.size) {
                checkIdx(params[pOrder[it]].annotationsSetRefListIdx, dex.annotationSetRefLists.size, "parameter_annotation.annotations_off")
            }
        )
        directories[d] = sorted
        if (sorted.classSet >= 0) visitSet(sorted.classSet)
        for (s in sorted.fieldSet) visitSet(s)
        for (s in sorted.methodSet) visitSet(s)
        for (r in sorted.paramRef) visitRefList(r)
    }

    private fun sortDirectoryEntries(newIdx: IntArray, what: String, describe: (Int) -> String): IntArray {
        val order = IntSort.sortedIndices(newIdx.size) { a, b -> Integer.compare(newIdx[a], newIdx[b]) }
        for (k in 1 until order.size) {
            if (newIdx[order[k - 1]] == newIdx[order[k]]) {
                throw IllegalArgumentException("annotations_directory: аннотации $what ${describe(newIdx[order[k]])} указаны дважды")
            }
        }
        return order
    }

    private fun visitRefList(r: Int) {
        if (refSeen[r]) return
        refSeen[r] = true
        refOrder.add(r)
        for (s in dex.annotationSetRefLists[r].annotationSetIdxs) {
            if (s != -1) visitSet(checkIdx(s, dex.annotationSetItems.size, "annotation_set_ref_list"))
        }
    }

    private fun visitSet(s: Int) {
        if (setSeen[s]) return
        setSeen[s] = true
        setOrder.add(s)
        val raw = dex.annotationSetItems[s].annotationIdxs
        var count = 0
        for (a in raw) if (a != -1) count++
        val items = IntArray(count)
        val types = IntArray(count)
        var k = 0
        for (a in raw) {
            if (a == -1) continue
            checkIdx(a, dex.annotationItems.size, "annotation_set_item")
            items[k] = a
            types[k] = ids.type(dex.annotationItems[a].annotation.typeIdx, "annotation.type_idx")
            k++
        }
        val order = IntSort.sortedIndices(count) { x, y -> Integer.compare(types[x], types[y]) }
        for (i in 1 until count) {
            if (types[order[i - 1]] == types[order[i]]) {
                throw IllegalArgumentException(
                    "annotation_set_item: аннотация ${ids.strings[ids.typeDescriptor[types[order[i]]]]} встречается дважды"
                )
            }
        }
        val sorted = IntArray(count) { items[order[it]] }
        setEntries[s] = sorted
        for (a in sorted) {
            if (!itemSeen[a]) {
                itemSeen[a] = true
                itemOrder.add(a)
            }
        }
    }

    // =====================================================================
    // Секции
    // =====================================================================

    private fun addMap(type: Int, offset: Int, size: Int) {
        if (size == 0) return
        mapType.add(type)
        mapOffset.add(offset)
        mapSize.add(size)
    }

    /** Секция данных: начало фиксируется на первом записанном элементе. */
    private inner class Section(private val type: Int, private val align4: Boolean) {
        private var start = -1
        private var count = 0

        /** Выравнивает позицию и начинает новый элемент; возвращает его смещение. */
        fun begin(): Int {
            if (align4) out.align4()
            if (start < 0) start = out.pos
            count++
            return out.pos
        }

        fun end() = addMap(type, start, count)
    }

    /** Ключ дедупликации по содержимому. */
    private class Blob(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is Blob && other.hash == hash && other.bytes.contentEquals(bytes)
    }

    /** Записывает содержимое [tmp] как новый элемент секции или возвращает смещение такого же. */
    private fun writeDeduplicated(section: Section, dedup: HashMap<Blob, Int>): Int {
        val key = Blob(tmp.toByteArray())
        val existing = dedup[key]
        if (existing != null) return existing
        val off = section.begin()
        out.bytes(key.bytes)
        dedup[key] = off
        return off
    }

    private fun layoutIds() {
        var off = headerSize
        stringIdsOff = if (ids.strings.isEmpty()) 0 else off; off += 4 * ids.strings.size
        typeIdsOff = if (ids.typeCount == 0) 0 else off; off += 4 * ids.typeCount
        protoIdsOff = if (ids.protoCount == 0) 0 else off; off += 12 * ids.protoCount
        fieldIdsOff = if (ids.fieldCount == 0) 0 else off; off += 8 * ids.fieldCount
        methodIdsOff = if (ids.methodCount == 0) 0 else off; off += 8 * ids.methodCount
        classDefsOff = if (classCount == 0) 0 else off; off += 32 * classCount
        callSiteIdsOff = if (dex.callSiteIds.isEmpty()) 0 else off; off += 4 * dex.callSiteIds.size
        methodHandlesOff = if (dex.methodHandles.isEmpty()) 0 else off; off += 8 * dex.methodHandles.size
        dataOff = off

        addMap(MapItemType.HEADER_ITEM, 0, 1)
        addMap(MapItemType.STRING_ID_ITEM, stringIdsOff, ids.strings.size)
        addMap(MapItemType.TYPE_ID_ITEM, typeIdsOff, ids.typeCount)
        addMap(MapItemType.PROTO_ID_ITEM, protoIdsOff, ids.protoCount)
        addMap(MapItemType.FIELD_ID_ITEM, fieldIdsOff, ids.fieldCount)
        addMap(MapItemType.METHOD_ID_ITEM, methodIdsOff, ids.methodCount)
        addMap(MapItemType.CLASS_DEF_ITEM, classDefsOff, classCount)
        addMap(MapItemType.CALL_SITE_ID_ITEM, callSiteIdsOff, dex.callSiteIds.size)
        addMap(MapItemType.METHOD_HANDLE_ITEM, methodHandlesOff, dex.methodHandles.size)
    }

    private fun writeStringData() {
        val section = Section(MapItemType.STRING_DATA_ITEM, false)
        val strings = ids.strings
        for (i in strings.indices) {
            val s = strings[i]
            stringDataOff[i] = section.begin()
            out.uleb(s.length)
            out.mutf8(s)
            out.u1(0)
        }
        section.end()
    }

    private fun writeTypeLists() {
        val section = Section(MapItemType.TYPE_LIST, true)
        val dedup = HashMap<Blob, Int>()
        fun put(types: IntArray): Int {
            if (types.isEmpty()) return 0
            tmp.pos = 0
            tmp.u4(types.size)
            for (t in types) tmp.u2(t)
            return writeDeduplicated(section, dedup)
        }
        for (p in 0 until ids.protoCount) protoParamsOff[p] = put(ids.protoParams[p])
        for (pos in 0 until classCount) interfacesOff[pos] = put(classInterfaces[classOrder[pos]])
        section.end()
    }

    // Аннотации пишутся по одной на достижимый элемент модели (без дедупликации по содержимому)

    private fun writeAnnotationItems() {
        val section = Section(MapItemType.ANNOTATION_ITEM, false)
        for (k in 0 until itemOrder.size) {
            val a = itemOrder[k]
            val item = dex.annotationItems[a]
            val visibility = item.visibility.toInt()
            if (visibility < 0 || visibility > 2) throw IllegalArgumentException("annotation_item #$a: неверная visibility $visibility")
            annotationItemOff[a] = section.begin()
            out.u1(visibility)
            encodeAnnotation(out, item.annotation)
        }
        section.end()
    }

    private fun writeAnnotationSets() {
        val section = Section(MapItemType.ANNOTATION_SET_ITEM, true)
        for (k in 0 until setOrder.size) {
            val s = setOrder[k]
            val entries = setEntries[s]!!
            annotationSetOff[s] = section.begin()
            out.u4(entries.size)
            for (a in entries) out.u4(annotationItemOff[a])
        }
        section.end()
    }

    private fun writeAnnotationSetRefLists() {
        val section = Section(MapItemType.ANNOTATION_SET_REF_LIST, true)
        for (k in 0 until refOrder.size) {
            val r = refOrder[k]
            val sets = dex.annotationSetRefLists[r].annotationSetIdxs
            refListOff[r] = section.begin()
            out.u4(sets.size)
            for (s in sets) out.u4(if (s == -1) 0 else annotationSetOff[s])
        }
        section.end()
    }

    private fun writeAnnotationsDirectories() {
        val section = Section(MapItemType.ANNOTATIONS_DIRECTORY_ITEM, true)
        for (k in 0 until dirOrder.size) {
            val d = dirOrder[k]
            val dir = directories[d]!!
            directoryOff[d] = section.begin()
            out.u4(if (dir.classSet >= 0) annotationSetOff[dir.classSet] else 0)
            out.u4(dir.fieldIdx.size)
            out.u4(dir.methodIdx.size)
            out.u4(dir.paramIdx.size)
            for (i in dir.fieldIdx.indices) {
                out.u4(dir.fieldIdx[i])
                out.u4(annotationSetOff[dir.fieldSet[i]])
            }
            for (i in dir.methodIdx.indices) {
                out.u4(dir.methodIdx[i])
                out.u4(annotationSetOff[dir.methodSet[i]])
            }
            for (i in dir.paramIdx.indices) {
                out.u4(dir.paramIdx[i])
                out.u4(refListOff[dir.paramRef[i]])
            }
        }
        section.end()
    }

    private fun writeEncodedArrays() {
        val section = Section(MapItemType.ENCODED_ARRAY_ITEM, false)
        // call site: по одному массиву на call_site_id в их порядке (call_site_off возрастает)
        for (i in dex.callSiteIds.indices) {
            val csIdx = dex.callSiteIds[i].callSiteIdx
            if (csIdx == -1) throw IllegalArgumentException("call_site_id #$i без данных call site")
            val cs = dex.callSiteItems[checkIdx(csIdx, dex.callSiteItems.size, "call_site_id.call_site_off")]
            if (cs.methodHandle.type != EncodedValueType.VALUE_METHOD_HANDLE) {
                throw IllegalArgumentException("call site #$i: первый элемент должен быть method handle, а не тип 0x${Integer.toHexString(cs.methodHandle.type)}")
            }
            callSiteOff[i] = section.begin()
            out.uleb(1 + cs.arguments.size)
            encodeValue(out, cs.methodHandle)
            for (arg in cs.arguments) encodeValue(out, arg)
        }
        // static_values (с перестановкой под отсортированные статические поля), дедупликация по содержимому
        val dedup = HashMap<Blob, Int>()
        for (pos in 0 until classCount) {
            val c = classOrder[pos]
            val values = staticValues(c) ?: continue
            tmp.pos = 0
            tmp.uleb(values.size)
            for (v in values) encodeValue(tmp, v)
            staticValuesOff[pos] = writeDeduplicated(section, dedup)
        }
        section.end()
    }

    /** static_values класса в порядке отсортированных статических полей; `null` — нет. */
    private fun staticValues(classDef: Int): List<EncodedValue>? {
        val def = dex.classDefs[classDef]
        if (def.staticValuesIdx == -1) return null
        val values = dex.encodedArrayItems[checkIdx(def.staticValuesIdx, dex.encodedArrayItems.size, "class_def.static_values_off")]
        if (def.classDataIdx == -1) {
            if (values.isEmpty()) return values
            throw IllegalArgumentException("Класс ${className(classDef)}: static_values без class_data")
        }
        val data = dex.classDataItems[def.classDataIdx]
        val m = members[def.classDataIdx]!!
        if (values.size > data.staticFields.size) {
            throw IllegalArgumentException(
                "Класс ${className(classDef)}: значений static_values (${values.size}) больше, чем статических полей (${data.staticFields.size})"
            )
        }
        val order = m.staticOrder
        var identity = true
        for (k in order.indices) {
            if (order[k] != k) {
                identity = false
                break
            }
        }
        if (identity) return values
        // Поля пересортированы: переставляем значения, недостающие заполняем значениями по умолчанию,
        // хвост из добавленных значений по умолчанию отбрасываем.
        var last = -1
        for (k in order.indices) if (order[k] < values.size) last = k
        return List(last + 1) { k ->
            val orig = order[k]
            if (orig < values.size) values[orig] else defaultValue(data.staticFields[orig].fieldIdx)
        }
    }

    private fun defaultValue(fieldIdx: Int): EncodedValue {
        val desc = ids.strings[ids.typeDescriptor[ids.fieldType[ids.field(fieldIdx, "encoded_field.field_idx")]]]
        return when (desc[0]) {
            'Z' -> DEFAULT_BOOLEAN
            'B' -> DEFAULT_BYTE
            'S' -> DEFAULT_SHORT
            'C' -> DEFAULT_CHAR
            'I' -> DEFAULT_INT
            'J' -> DEFAULT_LONG
            'F' -> DEFAULT_FLOAT
            'D' -> DEFAULT_DOUBLE
            else -> DEFAULT_NULL
        }
    }

    // =====================================================================
    // const-string/jumbo
    // =====================================================================

    /** Переложенный код (см. [JumboStrings]) по индексу code_item. */
    private val widened = HashMap<Int, JumboStrings.Result>()

    /** Отладочная информация переложенного кода: индексы — после [DexFile.debugInfoItems]. */
    private val extraDebug = ArrayList<DebugInfoItem>()

    /** debug_info переложенного code_item (индекс в общей нумерации с [extraDebug]). */
    private val widenedDebug = HashMap<Int, Int>()

    /**
     * Строк больше 65536: `const-string`, чей индекс после сортировки не помещается в 16 бит,
     * становится `const-string/jumbo`, код метода перекладывается. Отладочная информация такого
     * метода пишется отдельным элементом (адреса в ней другие).
     */
    private fun widenJumboStrings() {
        if (ids.strings.size <= 0x10000) return
        for (k in 0 until codeOrder.size) {
            val ci = codeOrder[k]
            val code = dex.codeItems[ci]
            if (!JumboStrings.needed(code.insns, ids.stringMap)) continue
            val debug = if (code.debugInfoIdx == -1) null else dex.debugInfoItems[code.debugInfoIdx]
            val result = JumboStrings.widen(code.insns, ids.stringMap, code.tries, code.catchHandlers, debug) { describeCode(ci) }
            widened[ci] = result
            if (result.debug != null) {
                val d = dex.debugInfoItems.size + extraDebug.size
                extraDebug.add(result.debug)
                debugOrder.add(d)
                widenedDebug[ci] = d
            }
        }
        if (extraDebug.isNotEmpty()) {
            debugInfoOff = debugInfoOff.copyOf(dex.debugInfoItems.size + extraDebug.size)
            // Прежняя отладочная информация расширенных методов больше не нужна, если её не делят с другими.
            val stillUsed = BooleanArray(dex.debugInfoItems.size)
            for (k in 0 until codeOrder.size) {
                val ci = codeOrder[k]
                val dbg = dex.codeItems[ci].debugInfoIdx
                if (dbg != -1 && ci !in widenedDebug) stillUsed[dbg] = true
            }
            val kept = debugOrder.toArray()
            debugOrder.clear()
            for (d in kept) if (d >= dex.debugInfoItems.size || stillUsed[d]) debugOrder.add(d)
        }
    }

    private fun writeDebugInfos() {
        val section = Section(MapItemType.DEBUG_INFO_ITEM, false)
        for (k in 0 until debugOrder.size) {
            val d = debugOrder[k]
            debugInfoOff[d] = section.begin()
            writeDebugInfo(d, if (d < dex.debugInfoItems.size) dex.debugInfoItems[d] else extraDebug[d - dex.debugInfoItems.size])
        }
        section.end()
    }

    private fun writeDebugInfo(d: Int, dbg: DebugInfoItem) {
        val names = dbg.parameterNames
        if (dbg.parametersSize != names.size) {
            throw IllegalArgumentException("debug_info #$d: parametersSize=${dbg.parametersSize}, а имён параметров ${names.size}")
        }
        out.uleb(dbg.lineStart)
        out.uleb(names.size)
        for (name in names) out.ulebp1(ids.stringOrNone(name, "debug_info.parameter_names"))
        val ops = dbg.opcodes
        for (i in ops.indices) {
            when (val op = ops[i]) {
                DebugOpcode.EndSequence -> {
                    if (i != ops.size - 1) throw IllegalArgumentException("debug_info #$d: опкоды после DBG_END_SEQUENCE")
                }
                is DebugOpcode.AdvancePc -> {
                    out.u1(DebugConstants.DBG_ADVANCE_PC)
                    out.uleb(op.addressDelta)
                }
                is DebugOpcode.AdvanceLine -> {
                    out.u1(DebugConstants.DBG_ADVANCE_LINE)
                    out.sleb(op.lineDelta)
                }
                is DebugOpcode.StartLocal -> {
                    out.u1(DebugConstants.DBG_START_LOCAL)
                    out.uleb(op.registerNum)
                    out.ulebp1(ids.stringOrNone(op.nameIdx, "DBG_START_LOCAL.name_idx"))
                    out.ulebp1(ids.typeOrNone(op.typeIdx, "DBG_START_LOCAL.type_idx"))
                }
                is DebugOpcode.StartLocalExtended -> {
                    out.u1(DebugConstants.DBG_START_LOCAL_EXTENDED)
                    out.uleb(op.registerNum)
                    out.ulebp1(ids.stringOrNone(op.nameIdx, "DBG_START_LOCAL_EXTENDED.name_idx"))
                    out.ulebp1(ids.typeOrNone(op.typeIdx, "DBG_START_LOCAL_EXTENDED.type_idx"))
                    out.ulebp1(ids.stringOrNone(op.sigIdx, "DBG_START_LOCAL_EXTENDED.sig_idx"))
                }
                is DebugOpcode.EndLocal -> {
                    out.u1(DebugConstants.DBG_END_LOCAL)
                    out.uleb(op.registerNum)
                }
                is DebugOpcode.RestartLocal -> {
                    out.u1(DebugConstants.DBG_RESTART_LOCAL)
                    out.uleb(op.registerNum)
                }
                DebugOpcode.SetPrologueEnd -> out.u1(DebugConstants.DBG_SET_PROLOGUE_END)
                DebugOpcode.SetEpilogueBegin -> out.u1(DebugConstants.DBG_SET_EPILOGUE_BEGIN)
                is DebugOpcode.SetFile -> {
                    out.u1(DebugConstants.DBG_SET_FILE)
                    out.ulebp1(ids.stringOrNone(op.nameIdx, "DBG_SET_FILE.name_idx"))
                }
                is DebugOpcode.SpecialOpcode -> {
                    val adjusted = op.opcode - DebugConstants.DBG_FIRST_SPECIAL
                    if (op.opcode > 0xFF || adjusted < 0 ||
                        op.lineDelta != DebugConstants.DBG_LINE_BASE + adjusted % DebugConstants.DBG_LINE_RANGE ||
                        op.addressDelta != adjusted / DebugConstants.DBG_LINE_RANGE
                    ) {
                        throw IllegalArgumentException(
                            "debug_info #$d: специальный опкод ${op.opcode} не соответствует line+${op.lineDelta}, pc+${op.addressDelta}"
                        )
                    }
                    out.u1(op.opcode)
                }
            }
        }
        out.u1(DebugConstants.DBG_END_SEQUENCE)
    }

    private fun writeCodeItems() {
        val section = Section(MapItemType.CODE_ITEM, true)
        for (k in 0 until codeOrder.size) {
            val ci = codeOrder[k]
            val code = dex.codeItems[ci]
            if (code.insnsSize != code.insns.size ushr 1) {
                throw IllegalArgumentException("code_item ${describeCode(ci)}: insnsSize=${code.insnsSize}, а длина insns ${code.insns.size ushr 1} юнитов")
            }
            if (code.triesSize != code.tries.size) {
                throw IllegalArgumentException("code_item ${describeCode(ci)}: triesSize=${code.triesSize}, а блоков try ${code.tries.size}")
            }
            val wide = widened[ci]
            val insns = codeRemapper.remap(wide?.insns ?: code.insns, ci)
            val units = insns.size ushr 1
            val tries = splitTries(ci, wide?.tries ?: code.tries, insns, units)
            val debug = if (wide != null) widenedDebug[ci] ?: -1 else code.debugInfoIdx
            codeItemOff[ci] = section.begin()
            out.u2(checkU2(code.registersSize, ci, "registers_size"))
            out.u2(checkU2(code.insSize, ci, "ins_size"))
            out.u2(checkU2(code.outsSize, ci, "outs_size"))
            out.u2(checkU2(tries.size, ci, "tries_size"))
            out.u4(if (debug == -1) 0 else debugInfoOff[debug])
            out.u4(units)
            out.bytes(insns)
            if (tries.isNotEmpty()) writeTries(ci, wide?.handlers ?: code.catchHandlers, tries, units)
        }
        section.end()
    }

    /**
     * Диапазоны try по возрастанию; длиннее 65535 code units (insn_count — u2) делятся на части с тем же
     * обработчиком по границам инструкций (ART проверяет, что начало и конец try — начала инструкций).
     */
    private fun splitTries(ci: Int, tries: List<TryItem>, insns: ByteArray, units: Int): List<TryItem> {
        if (tries.none { it.insnCount > 0xFFFF }) return tries
        val starts = codeRemapper.instructionStarts(insns, ci)
        val out = ArrayList<TryItem>(tries.size + 2)
        for (t in tries) {
            var s = t.startAddr
            val end = t.startAddr.toLong() + t.insnCount
            if (t.insnCount < 0 || s < 0 || end > units) {
                throw IllegalArgumentException("code_item ${describeCode(ci)}: некорректный try [${t.startAddr}, +${t.insnCount}) (insns $units юнитов)")
            }
            while (end - s > 0xFFFF) {
                var cut = s + 0xFFFF
                while (cut > s && !starts[cut]) cut--
                if (cut == s) throw IllegalArgumentException("code_item ${describeCode(ci)}: try нельзя разделить по границам инструкций")
                out.add(TryItem(s, cut - s, t.handlerIdx))
                s = cut
            }
            out.add(TryItem(s, (end - s).toInt(), t.handlerIdx))
        }
        return out
    }

    private fun writeTries(ci: Int, handlers: List<CatchHandler>, tries: List<TryItem>, units: Int) {
        if (handlers.isEmpty()) throw IllegalArgumentException("code_item ${describeCode(ci)}: есть try, но нет обработчиков")
        if ((units and 1) != 0) out.u2(0)
        val order = IntSort.sortedIndices(tries.size) { a, b -> Integer.compare(tries[a].startAddr, tries[b].startAddr) }
        val triesPos = out.pos
        out.zeros(tries.size * 8)
        val listStart = out.pos
        val handlerOff = IntArray(handlers.size)
        out.uleb(handlers.size)
        for (h in handlers.indices) {
            handlerOff[h] = out.pos - listStart
            val handler = handlers[h]
            val typed = handler.handlers
            val catchAll = handler.catchAllAddr
            if (catchAll < -1) throw IllegalArgumentException("code_item ${describeCode(ci)}: неверный catchAllAddr $catchAll")
            if (typed.isEmpty() && catchAll == -1) {
                throw IllegalArgumentException("code_item ${describeCode(ci)}: пустой обработчик исключений #$h")
            }
            out.sleb(if (catchAll != -1) -typed.size else typed.size)
            for (pair in typed) {
                out.uleb(ids.type(pair.typeIdx, "encoded_type_addr_pair.type_idx"))
                out.uleb(pair.addr)
            }
            if (catchAll != -1) out.uleb(catchAll)
        }
        var p = triesPos
        var prevEnd = 0
        for (k in order) {
            val t = tries[k]
            if (t.handlerIdx < 0 || t.handlerIdx >= handlers.size) {
                throw IllegalArgumentException("code_item ${describeCode(ci)}: try ссылается на обработчик #${t.handlerIdx} из ${handlers.size}")
            }
            if (t.startAddr < prevEnd || t.startAddr >= units || t.insnCount < 0 || t.startAddr.toLong() + t.insnCount > units) {
                throw IllegalArgumentException(
                    "code_item ${describeCode(ci)}: некорректный или перекрывающийся try [${t.startAddr}, +${t.insnCount}) (insns $units юнитов)"
                )
            }
            prevEnd = t.startAddr + t.insnCount
            out.setU4(p, t.startAddr)
            out.setU2(p + 4, checkU2(t.insnCount, ci, "try_item.insn_count"))
            out.setU2(p + 6, checkU2(handlerOff[t.handlerIdx], ci, "try_item.handler_off"))
            p += 8
        }
    }

    private fun writeClassData() {
        val section = Section(MapItemType.CLASS_DATA_ITEM, false)
        for (k in 0 until classDataOrder.size) {
            val cd = classDataOrder[k]
            val data = dex.classDataItems[cd]
            val m = members[cd]!!
            classDataOff[cd] = section.begin()
            out.uleb(m.staticIdx.size)
            out.uleb(m.instanceIdx.size)
            out.uleb(m.directIdx.size)
            out.uleb(m.virtualIdx.size)
            var last = 0
            for (i in m.staticOrder) {
                val idx = m.staticIdx[i]
                out.uleb(idx - last)
                out.uleb(data.staticFields[i].accessFlags)
                last = idx
            }
            last = 0
            for (i in m.instanceOrder) {
                val idx = m.instanceIdx[i]
                out.uleb(idx - last)
                out.uleb(data.instanceFields[i].accessFlags)
                last = idx
            }
            last = 0
            for (i in m.directOrder) {
                val idx = m.directIdx[i]
                val method = data.directMethods[i]
                out.uleb(idx - last)
                out.uleb(method.accessFlags)
                out.uleb(if (method.codeIdx == -1) 0 else codeItemOff[method.codeIdx])
                last = idx
            }
            last = 0
            for (i in m.virtualOrder) {
                val idx = m.virtualIdx[i]
                val method = data.virtualMethods[i]
                out.uleb(idx - last)
                out.uleb(method.accessFlags)
                out.uleb(if (method.codeIdx == -1) 0 else codeItemOff[method.codeIdx])
                last = idx
            }
        }
        section.end()
    }

    private fun writeHiddenApi() {
        val sections = dex.hiddenApiClassDataItems
        if (sections.isEmpty()) return
        if (sections.size > 1) throw IllegalArgumentException("hiddenapi_class_data_item: в DEX допускается одна секция, а их ${sections.size}")
        val flags = sections[0].flags
        if (flags.size != classCount) {
            throw IllegalArgumentException("hiddenapi_class_data_item: флаги для ${flags.size} классов, а class_def $classCount")
        }
        out.align4()
        val start = out.pos
        out.zeros(4 + 4 * classCount)
        for (pos in 0 until classCount) {
            val c = classOrder[pos]
            val classFlags = flags[c] ?: continue
            val def = dex.classDefs[c]
            if (def.classDataIdx == -1) {
                throw IllegalArgumentException("hiddenapi_class_data_item: у класса ${className(c)} есть флаги, но нет class_data")
            }
            val data = dex.classDataItems[def.classDataIdx]
            val m = members[def.classDataIdx]!!
            val sCount = data.staticFields.size
            val iCount = data.instanceFields.size
            val dCount = data.directMethods.size
            val total = sCount + iCount + dCount + data.virtualMethods.size
            if (classFlags.size != total) {
                throw IllegalArgumentException(
                    "hiddenapi_class_data_item: у класса ${className(c)} ${classFlags.size} флагов, а членов class_data $total"
                )
            }
            out.setU4(start + 4 + 4 * pos, out.pos - start)
            for (i in m.staticOrder) out.uleb(classFlags[i])
            for (i in m.instanceOrder) out.uleb(classFlags[sCount + i])
            for (i in m.directOrder) out.uleb(classFlags[sCount + iCount + i])
            for (i in m.virtualOrder) out.uleb(classFlags[sCount + iCount + dCount + i])
        }
        out.setU4(start, out.pos - start)
        addMap(MapItemType.HIDDENAPI_CLASS_DATA_ITEM, start, 1)
    }

    private fun writeMapList(): Int {
        out.align4()
        val mapOff = out.pos
        addMap(MapItemType.MAP_LIST, mapOff, 1)
        out.u4(mapType.size)
        for (i in 0 until mapType.size) {
            out.u2(mapType[i])
            out.u2(0)
            out.u4(mapSize[i])
            out.u4(mapOffset[i])
        }
        return mapOff
    }

    // =====================================================================
    // Заголовок и id-секции
    // =====================================================================

    private fun writeHeader(version: Int, mapOff: Int) {
        val fileSize = out.pos
        System.arraycopy(DexMagic.magicOf(version), 0, out.buf, 0, 8)
        out.setU4(32, fileSize)
        out.setU4(36, headerSize)
        out.setU4(40, DexConstants.ENDIAN_CONSTANT)
        out.setU4(44, 0) // link_size
        out.setU4(48, 0) // link_off
        out.setU4(52, mapOff)
        out.setU4(56, ids.strings.size); out.setU4(60, stringIdsOff)
        out.setU4(64, ids.typeCount); out.setU4(68, typeIdsOff)
        out.setU4(72, ids.protoCount); out.setU4(76, protoIdsOff)
        out.setU4(80, ids.fieldCount); out.setU4(84, fieldIdsOff)
        out.setU4(88, ids.methodCount); out.setU4(92, methodIdsOff)
        out.setU4(96, classCount); out.setU4(100, classDefsOff)
        if (headerSize >= 0x78) {
            // DEX 041+: data_size/data_off не используются (как у d8), контейнер из одного DEX
            out.setU4(104, 0); out.setU4(108, 0)
            out.setU4(0x70, fileSize) // container_size
            out.setU4(0x74, 0) // header_offset
        } else {
            out.setU4(104, fileSize - dataOff); out.setU4(108, dataOff)
        }
    }

    private fun writeIds() {
        for (i in ids.strings.indices) out.setU4(stringIdsOff + 4 * i, stringDataOff[i])
        for (i in 0 until ids.typeCount) out.setU4(typeIdsOff + 4 * i, ids.typeDescriptor[i])
        for (i in 0 until ids.protoCount) {
            val p = protoIdsOff + 12 * i
            out.setU4(p, ids.protoShorty[i])
            out.setU4(p + 4, ids.protoReturn[i])
            out.setU4(p + 8, protoParamsOff[i])
        }
        for (i in 0 until ids.fieldCount) {
            val p = fieldIdsOff + 8 * i
            out.setU2(p, ids.fieldClass[i])
            out.setU2(p + 2, ids.fieldType[i])
            out.setU4(p + 4, ids.fieldName[i])
        }
        for (i in 0 until ids.methodCount) {
            val p = methodIdsOff + 8 * i
            out.setU2(p, ids.methodClass[i])
            out.setU2(p + 2, ids.methodProto[i])
            out.setU4(p + 4, ids.methodName[i])
        }
        for (pos in 0 until classCount) {
            val def = dex.classDefs[classOrder[pos]]
            val p = classDefsOff + 32 * pos
            out.setU4(p, ids.type(def.classIdx, "class_def.class_idx"))
            out.setU4(p + 4, def.accessFlags)
            out.setU4(p + 8, if (def.superclassIdx == -1) DexConstants.NO_INDEX else ids.type(def.superclassIdx, "class_def.superclass_idx"))
            out.setU4(p + 12, interfacesOff[pos])
            out.setU4(p + 16, if (def.sourceFileIdx == -1) DexConstants.NO_INDEX else ids.string(def.sourceFileIdx, "class_def.source_file_idx"))
            out.setU4(p + 20, if (def.annotationsDirectoryIdx == -1) 0 else directoryOff[def.annotationsDirectoryIdx])
            out.setU4(p + 24, if (def.classDataIdx == -1) 0 else classDataOff[def.classDataIdx])
            out.setU4(p + 28, staticValuesOff[pos])
        }
        for (i in dex.callSiteIds.indices) out.setU4(callSiteIdsOff + 4 * i, callSiteOff[i])
        for (i in dex.methodHandles.indices) {
            val mh = dex.methodHandles[i]
            val p = methodHandlesOff + 8 * i
            val member = when (mh.methodHandleType) {
                in 0x00..0x03 -> ids.field(mh.fieldOrMethodId, "method_handle.field_or_method_id")
                in 0x04..0x08 -> ids.method(mh.fieldOrMethodId, "method_handle.field_or_method_id")
                else -> throw IllegalArgumentException("method_handle #$i: неизвестный тип ${mh.methodHandleType}")
            }
            if (member > 0xFFFF) throw IllegalArgumentException("method_handle #$i: индекс $member не помещается в 16 бит")
            out.setU2(p, mh.methodHandleType)
            out.setU2(p + 2, 0)
            out.setU2(p + 4, member)
            out.setU2(p + 6, 0)
        }
    }

    // =====================================================================
    // encoded_value / encoded_annotation
    // =====================================================================

    private fun encodeAnnotation(o: ByteOut, annotation: Annotation) {
        o.uleb(ids.type(annotation.typeIdx, "encoded_annotation.type_idx"))
        val elements = annotation.elements
        val n = elements.size
        o.uleb(n)
        if (n == 0) return
        val names = IntArray(n) { ids.string(elements[it].nameIdx, "annotation_element.name_idx") }
        val order = IntSort.sortedIndices(n) { a, b -> Integer.compare(names[a], names[b]) }
        for (k in 0 until n) {
            val i = order[k]
            if (k > 0 && names[order[k - 1]] == names[i]) {
                throw IllegalArgumentException(
                    "Аннотация ${ids.strings[ids.typeDescriptor[ids.type(annotation.typeIdx, "encoded_annotation.type_idx")]]}: " +
                        "элемент ${ids.strings[names[i]]} указан дважды"
                )
            }
            o.uleb(names[i])
            encodeValue(o, elements[i].value)
        }
    }

    private fun encodeValue(o: ByteOut, v: EncodedValue) {
        val type = v.type
        when (type) {
            EncodedValueType.VALUE_BYTE -> {
                val x = number(v).toLong()
                if (x < Byte.MIN_VALUE || x > Byte.MAX_VALUE) badValue(v)
                o.u1(type)
                o.u1(x.toInt())
            }
            EncodedValueType.VALUE_SHORT -> {
                val x = number(v).toLong()
                if (x < Short.MIN_VALUE || x > Short.MAX_VALUE) badValue(v)
                writeSigned(o, type, x)
            }
            EncodedValueType.VALUE_CHAR -> {
                val raw = v.value
                val x = if (raw is Char) raw.code.toLong() else number(v).toLong()
                if (x < 0 || x > 0xFFFF) badValue(v)
                writeUnsigned(o, type, x)
            }
            EncodedValueType.VALUE_INT -> {
                val x = number(v).toLong()
                if (x < Int.MIN_VALUE || x > Int.MAX_VALUE) badValue(v)
                writeSigned(o, type, x)
            }
            EncodedValueType.VALUE_LONG -> writeSigned(o, type, number(v).toLong())
            EncodedValueType.VALUE_FLOAT -> {
                val bits = java.lang.Float.floatToRawIntBits(number(v).toFloat())
                writeRightZeroExtended(o, type, bits.toLong() shl 32, 4)
            }
            EncodedValueType.VALUE_DOUBLE ->
                writeRightZeroExtended(o, type, java.lang.Double.doubleToRawLongBits(number(v).toDouble()), 8)
            EncodedValueType.VALUE_STRING -> writeUnsigned(o, type, ids.string(index(v), "VALUE_STRING").toLong())
            EncodedValueType.VALUE_TYPE -> writeUnsigned(o, type, ids.type(index(v), "VALUE_TYPE").toLong())
            EncodedValueType.VALUE_FIELD -> writeUnsigned(o, type, ids.field(index(v), "VALUE_FIELD").toLong())
            EncodedValueType.VALUE_ENUM -> writeUnsigned(o, type, ids.field(index(v), "VALUE_ENUM").toLong())
            EncodedValueType.VALUE_METHOD -> writeUnsigned(o, type, ids.method(index(v), "VALUE_METHOD").toLong())
            EncodedValueType.VALUE_METHOD_TYPE -> writeUnsigned(o, type, ids.proto(index(v), "VALUE_METHOD_TYPE").toLong())
            EncodedValueType.VALUE_METHOD_HANDLE -> {
                val idx = index(v)
                if (idx < 0 || idx >= dex.methodHandles.size) badIndex("VALUE_METHOD_HANDLE", idx, dex.methodHandles.size)
                writeUnsigned(o, type, idx.toLong())
            }
            EncodedValueType.VALUE_ARRAY -> {
                val list = v.value as? List<*> ?: badValue(v)
                o.u1(type)
                o.uleb(list.size)
                for (e in list) encodeValue(o, e as? EncodedValue ?: badValue(v))
            }
            EncodedValueType.VALUE_ANNOTATION -> {
                o.u1(type)
                encodeAnnotation(o, v.value as? Annotation ?: badValue(v))
            }
            EncodedValueType.VALUE_NULL -> {
                if (v.value != null) badValue(v)
                o.u1(type)
            }
            EncodedValueType.VALUE_BOOLEAN -> {
                val b = v.value as? Boolean ?: badValue(v)
                o.u1(((if (b) 1 else 0) shl 5) or type)
            }
            else -> throw IllegalArgumentException("Неизвестный тип encoded_value: 0x${Integer.toHexString(type)}")
        }
    }

    private fun number(v: EncodedValue): Number = v.value as? Number ?: badValue(v)

    private fun index(v: EncodedValue): Int = (v.value as? Number ?: badValue(v)).toInt()

    private fun badValue(v: EncodedValue): Nothing = throw IllegalArgumentException(
        "Недопустимое значение encoded_value типа 0x${Integer.toHexString(v.type)}: ${v.value} (${kindOf(v.value)})"
    )

    /** Знаковое значение минимальным числом байт (1..8). */
    private fun writeSigned(o: ByteOut, type: Int, value: Long) {
        var size = 1
        while (size < 8) {
            val shift = 64 - 8 * size
            if ((value shl shift) shr shift == value) break
            size++
        }
        o.u1(((size - 1) shl 5) or type)
        for (i in 0 until size) o.u1((value ushr (8 * i)).toInt())
    }

    /** Беззнаковое значение минимальным числом байт (1..8). */
    private fun writeUnsigned(o: ByteOut, type: Int, value: Long) {
        var size = 1
        while (size < 8 && (value ushr (8 * size)) != 0L) size++
        o.u1(((size - 1) shl 5) or type)
        for (i in 0 until size) o.u1((value ushr (8 * i)).toInt())
    }

    /** float/double: младшие нулевые байты отбрасываются (значение дополняется нулями справа). */
    private fun writeRightZeroExtended(o: ByteOut, type: Int, bits: Long, maxSize: Int) {
        var v = bits
        var size = 8
        // для float биты сдвинуты в старшие 4 байта: младшие 4 нулевые, размер не превысит 4
        while (size > 1 && (v and 0xFF) == 0L) {
            v = v ushr 8
            size--
        }
        if (size > maxSize) throw IllegalStateException("encoded_value: размер $size > $maxSize")
        o.u1(((size - 1) shl 5) or type)
        for (i in 0 until size) o.u1((v ushr (8 * i)).toInt())
    }

    // =====================================================================
    // Вспомогательное
    // =====================================================================

    private fun checkIdx(idx: Int, size: Int, what: String): Int {
        if (idx < 0 || idx >= size) badIndex(what, idx, size)
        return idx
    }

    private fun checkU2(value: Int, codeIdx: Int, what: String): Int {
        if (value < 0 || value > 0xFFFF) {
            throw IllegalArgumentException("code_item ${describeCode(codeIdx)}: $what=$value не помещается в u2")
        }
        return value
    }

    private fun className(classDef: Int): String =
        describe { dex.strings[dex.typeIds[dex.classDefs[classDef].classIdx].descriptorIdx] } ?: "class_def #$classDef"

    private fun describeCode(codeIdx: Int): String {
        val owner = codeOwner[codeIdx]
        return if (owner >= 0) "метода ${describeMethod(owner)}" else "#$codeIdx"
    }

    /** Метод модели в виде `Lcls;->name(params)ret`. */
    private fun describeMethod(methodIdx: Int): String = describe {
        val m = dex.methodIds[methodIdx]
        val p = dex.protoIds[m.protoIdx]
        val params = if (p.parametersTypeListIdx == -1) "" else dex.typeLists[p.parametersTypeListIdx].typeIdx
            .joinToString("") { dex.strings[dex.typeIds[it].descriptorIdx] }
        dex.strings[dex.typeIds[m.classIdx].descriptorIdx] + "->" + dex.strings[m.nameIdx] + "(" + params + ")" +
            dex.strings[dex.typeIds[p.returnTypeIdx].descriptorIdx]
    } ?: "method #$methodIdx"

    private fun describeNewMethod(newIdx: Int): String =
        ids.strings[ids.typeDescriptor[ids.methodClass[newIdx]]] + "->" + ids.strings[ids.methodName[newIdx]]

    private fun describeNewField(newIdx: Int): String =
        ids.strings[ids.typeDescriptor[ids.fieldClass[newIdx]]] + "->" + ids.strings[ids.fieldName[newIdx]]

    /** Описание для сообщения об ошибке; битая модель не должна маскировать исходную ошибку. */
    private inline fun describe(block: () -> String): String? =
        try {
            block()
        } catch (e: IndexOutOfBoundsException) {
            null
        }

    private companion object {
        const val ACC_INTERFACE = 0x200
        const val ACC_ABSTRACT = 0x400

        val DEFAULT_BOOLEAN = EncodedValue(EncodedValueType.VALUE_BOOLEAN, false)
        val DEFAULT_BYTE = EncodedValue(EncodedValueType.VALUE_BYTE, 0.toByte())
        val DEFAULT_SHORT = EncodedValue(EncodedValueType.VALUE_SHORT, 0.toShort())
        val DEFAULT_CHAR = EncodedValue(EncodedValueType.VALUE_CHAR, 0.toChar())
        val DEFAULT_INT = EncodedValue(EncodedValueType.VALUE_INT, 0)
        val DEFAULT_LONG = EncodedValue(EncodedValueType.VALUE_LONG, 0L)
        val DEFAULT_FLOAT = EncodedValue(EncodedValueType.VALUE_FLOAT, 0f)
        val DEFAULT_DOUBLE = EncodedValue(EncodedValueType.VALUE_DOUBLE, 0.0)
        val DEFAULT_NULL = EncodedValue(EncodedValueType.VALUE_NULL, null)
    }

    /** Вид значения для сообщений (без рефлексии). */
    private fun kindOf(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> "Boolean"
        is Byte -> "Byte"
        is Short -> "Short"
        is Char -> "Char"
        is Int -> "Int"
        is Long -> "Long"
        is Float -> "Float"
        is Double -> "Double"
        is String -> "String"
        is List<*> -> "List"
        else -> "другой тип"
    }
}
