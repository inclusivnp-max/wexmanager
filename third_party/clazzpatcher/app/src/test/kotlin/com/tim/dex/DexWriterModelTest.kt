package com.tim.dex

import com.tim.dex.DexTestUtil.classIndex
import com.tim.dex.DexTestUtil.code
import com.tim.dex.DexTestUtil.fieldName
import com.tim.dex.DexTestUtil.methodName
import com.tim.dex.DexTestUtil.typeName
import com.tim.dex.DexTestUtil.unit
import com.tim.dex.data.DexMagic
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.*
import com.tim.dex.model.Annotation
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Запись модели, пулы которой не отсортированы и содержат дубликаты (как их строит asm-слой):
 * DexWriter обязан отсортировать/склеить пулы, перенумеровать ссылки (в т.ч. в байткоде),
 * переставить static_values, флаги hidden API, элементы аннотаций и классы.
 */
class DexWriterModelTest {

    private val strings = listOf(
        "sC", "LSub;", "V", "run", "sA", "Ljava/lang/Object;", "LBase;", "J", "sB", "I", // 0..9
        "Ljava/lang/String;", "LIface;", "zzz", "helper", "VL", "sA", "Ljava/lang/Exception;", "LMyAnno;", "zeta", "alpha", // 10..19
        "LAAnno;", "value", "after", "tail", "<init>", "switcher", "x", "LSub;", "Sub.java", "\u0000nul\uD800", // 20..29
        "\u00e9\u4e2d\uD83D\uDE00", "inst" // 30..31
    )

    private val typeIds = listOf(1, 5, 6, 7, 9, 10, 11, 2, 16, 17, 20, 27).map { TypeIdItem(it) }
    // 0 LSub; 1 Object 2 LBase; 3 J 4 I 5 String 6 LIface; 7 V 8 Exception 9 LMyAnno; 10 LAAnno; 11 LSub;(дубликат)

    private val typeLists = listOf(
        TypeListItem(1, listOf(5)), // (String)
        TypeListItem(1, listOf(6)), // интерфейсы LSub
        TypeListItem(0, emptyList()) // пустой список параметров
    )

    private val protoIds = listOf(
        ProtoIdItem(14, 7, 0), // (Ljava/lang/String;)V
        ProtoIdItem(2, 7, 2), // ()V через пустой type_list
        ProtoIdItem(2, 7, -1) // ()V — дубликат
    )

    private val fieldIds = listOf(
        FieldIdItem(0, 3, 0), // LSub;->sC:J
        FieldIdItem(11, 4, 15), // LSub;->sA:I (дубликаты типа и строки)
        FieldIdItem(0, 5, 8), // LSub;->sB:String
        FieldIdItem(0, 4, 31) // LSub;->inst:I
    )

    private val methodIds = listOf(
        MethodIdItem(0, 1, 3), // 0 LSub;->run()V
        MethodIdItem(0, 0, 13), // 1 LSub;->helper(String)V
        MethodIdItem(2, 2, 24), // 2 LBase;-><init>()V
        MethodIdItem(11, 1, 24), // 3 LSub;-><init>()V
        MethodIdItem(0, 1, 25), // 4 LSub;->switcher()V
        MethodIdItem(1, 1, 24) // 5 Object-><init>()V
    )

    private val runCode = CodeItem(
        registersSize = 2, insSize = 1, outsSize = 1, triesSize = 2, debugInfoIdx = 0, insnsSize = 12,
        insns = code(
            0x001a, 12, // const-string v0, "zzz"
            0x0162, 2, // sget-object v1, LSub;->sB
            0x1071, 1, 0x0000, // invoke-static {v0}, helper
            0x0122, 2, // new-instance v1, LBase;
            0x000e, // return-void
            0x000d, // move-exception v0
            0x000e // return-void
        ),
        tries = listOf(TryItem(7, 2, 1), TryItem(0, 7, 0)), // не по порядку
        catchHandlers = listOf(
            CatchHandler(listOf(TypeAddrPair(8, 0xa)), -1),
            CatchHandler(emptyList(), 0xa)
        )
    )

    private val switcherCode = CodeItem(
        registersSize = 1, insSize = 0, outsSize = 0, triesSize = 0, debugInfoIdx = -1, insnsSize = 21,
        insns = code(
            0x1012, // const/4 v0, 1
            0x002c, 7, 0, // sparse-switch v0, +7
            0x001a, 22, // const-string v0, "after"
            0x000e, // return-void
            0x0000, // nop
            0x0200, 2, 1, 0, 5, 0, 5, 0, 3, 0, // sparse-switch-payload
            0x001a, 23, // const-string v0, "tail" (после payload)
            0x000e
        ),
        tries = emptyList(), catchHandlers = emptyList()
    )

    private fun model(magic: ByteArray = DexMagic.DEX_039): DexFile {
        val b = DexFileBuilder()
        b.header = DexHeader(magic = magic)
        b.strings = strings
        b.typeIds = typeIds
        b.protoIds = protoIds
        b.fieldIds = fieldIds
        b.methodIds = methodIds
        b.typeLists.addAll(typeLists)
        b.classDefs = listOf(
            ClassDefItem(0, 0x1, 2, 1, 28, 0, 0, 0), // LSub; extends LBase implements LIface — раньше родителей
            ClassDefItem(2, 0x1, 1, -1, -1, -1, 1, -1), // LBase;
            ClassDefItem(6, 0x601, 1, -1, -1, -1, -1, -1) // LIface;
        )
        b.classDataItems.add(
            ClassDataItem(
                3, 1, 3, 1,
                staticFields = listOf(EncodedField(0, 0x9), EncodedField(1, 0x9), EncodedField(2, 0x9)),
                instanceFields = listOf(EncodedField(3, 0x1)),
                directMethods = listOf(EncodedMethod(4, 0x9, 3), EncodedMethod(3, 0x10001, 2), EncodedMethod(1, 0x9, 1)),
                virtualMethods = listOf(EncodedMethod(0, 0x1, 0))
            )
        )
        b.classDataItems.add(ClassDataItem(0, 0, 1, 0, emptyList(), emptyList(), listOf(EncodedMethod(2, 0x10001, 4)), emptyList()))
        b.codeItems.add(runCode)
        b.codeItems.add(CodeItem(1, 1, 0, 0, -1, 1, code(0x000e), emptyList(), emptyList())) // helper
        b.codeItems.add(CodeItem(1, 1, 1, 0, -1, 4, code(0x1070, 2, 0, 0x000e), emptyList(), emptyList())) // LSub.<init>
        b.codeItems.add(switcherCode)
        b.codeItems.add(CodeItem(1, 1, 1, 0, -1, 4, code(0x1070, 5, 0, 0x000e), emptyList(), emptyList())) // LBase.<init>
        b.debugInfoItems.add(
            DebugInfoItem(
                5, 0, emptyList(),
                listOf(
                    DebugOpcode.AdvancePc(2),
                    DebugOpcode.StartLocal(0, 26, 5),
                    DebugOpcode.SpecialOpcode(45, 1, 2),
                    DebugOpcode.EndLocal(0),
                    DebugOpcode.EndSequence
                )
            )
        )
        // static_values в порядке модели: sC=5L, sA=1 (sB без значения)
        b.encodedArrayItems.add(listOf(EncodedValue(EncodedValueType.VALUE_LONG, 5L), EncodedValue(EncodedValueType.VALUE_INT, 1)))
        val nested = Annotation(
            10, listOf(
                AnnotationElement(18, EncodedValue(EncodedValueType.VALUE_BOOLEAN, true)),
                AnnotationElement(19, EncodedValue(EncodedValueType.VALUE_NULL, null))
            )
        )
        b.annotationItems.add(
            AnnotationItem(
                1, Annotation(
                    9, listOf(
                        AnnotationElement(18, EncodedValue(EncodedValueType.VALUE_INT, 7)),
                        AnnotationElement(19, EncodedValue(EncodedValueType.VALUE_STRING, 12)),
                        AnnotationElement(
                            21, EncodedValue(
                                EncodedValueType.VALUE_ARRAY, listOf(
                                    EncodedValue(EncodedValueType.VALUE_TYPE, 11),
                                    EncodedValue(EncodedValueType.VALUE_ANNOTATION, nested),
                                    EncodedValue(EncodedValueType.VALUE_CHAR, 'é'),
                                    EncodedValue(EncodedValueType.VALUE_FLOAT, -0.0f),
                                    EncodedValue(EncodedValueType.VALUE_DOUBLE, 1.0E-300),
                                    EncodedValue(EncodedValueType.VALUE_LONG, Long.MIN_VALUE),
                                    EncodedValue(EncodedValueType.VALUE_SHORT, (-129).toShort()),
                                    EncodedValue(EncodedValueType.VALUE_BYTE, (-1).toByte()),
                                    EncodedValue(EncodedValueType.VALUE_STRING, 29),
                                    EncodedValue(EncodedValueType.VALUE_STRING, 30)
                                )
                            )
                        )
                    )
                )
            )
        )
        b.annotationItems.add(AnnotationItem(0, Annotation(10, listOf(AnnotationElement(21, EncodedValue(EncodedValueType.VALUE_METHOD, 1))))))
        b.annotationItems.add(AnnotationItem(1, Annotation(9, emptyList())))
        b.annotationSetItems.add(AnnotationSetItem(listOf(0, -1, 1))) // LMyAnno; раньше LAAnno;
        b.annotationSetItems.add(AnnotationSetItem(listOf(2)))
        b.annotationSetRefLists.add(AnnotationSetRefList(listOf(1)))
        b.annotationsDirectoryItems.add(
            AnnotationsDirectoryItem(
                0,
                listOf(FieldAnnotation(2, 1), FieldAnnotation(1, 1)), // sB раньше sA
                listOf(MethodAnnotation(0, 1)),
                listOf(ParameterAnnotation(1, 0))
            )
        )
        // hidden API в порядке членов class_data модели
        b.hiddenApiClassDataItems.add(
            HiddenApiClassData(
                listOf(
                    intArrayOf(3, 1, 2, 0, 4, 0, 5, 1), // LSub: sC sA sB | inst | switcher <init> helper | run
                    intArrayOf(2), // LBase: <init>
                    null // LIface: нет class_data
                )
            )
        )
        return b.build()
    }

    @Test
    fun writesSortedDexFromUnsortedModel() {
        val bytes = DexWriter(model()).buildDex()
        DexTestUtil.verifyWithDexdump(bytes, "model")
        val dex = DexReader().read(bytes)

        // строки строго упорядочены и без дубликатов
        for (i in 1 until dex.strings.size) assertTrue(dex.strings[i - 1] < dex.strings[i], "порядок строк на $i")
        assertEquals(strings.toSet().size, dex.strings.size)
        assertTrue("\u0000nul\uD800" in dex.strings && "\u00e9\u4e2d\uD83D\uDE00" in dex.strings)
        assertEquals(11, dex.typeIds.size)
        assertEquals(2, dex.protoIds.size)

        // суперкласс и интерфейс раньше подкласса
        assertEquals(listOf("LBase;", "LIface;", "LSub;"), dex.classDefs.map { typeName(dex, it.classIdx) })

        val sub = dex.classDefs[classIndex(dex, "LSub;")]
        val data = dex.classDataItems[sub.classDataIdx]
        assertEquals(listOf("sA", "sB", "sC"), data.staticFields.map { fieldName(dex, it.fieldIdx) })
        assertEquals(listOf("<init>", "helper", "switcher"), data.directMethods.map { methodName(dex, it.methodIdx) })

        // static_values переставлены под отсортированные поля, пропуск заполнен null
        val values = dex.encodedArrayItems[sub.staticValuesIdx]
        assertEquals(
            listOf(EncodedValue(EncodedValueType.VALUE_INT, 1), EncodedValue(EncodedValueType.VALUE_NULL, null), EncodedValue(EncodedValueType.VALUE_LONG, 5L)),
            values
        )

        // hidden API: флаги переехали вместе с членами и классами
        val hidden = dex.hiddenApiClassDataItems.single().flags
        assertContentEquals(intArrayOf(2), hidden[classIndex(dex, "LBase;")])
        assertNull(hidden[classIndex(dex, "LIface;")])
        assertContentEquals(intArrayOf(1, 2, 3, 0, 0, 5, 4, 1), hidden[classIndex(dex, "LSub;")])

        // байткод: ссылки указывают на те же сущности
        val run = data.virtualMethods.single()
        val runCode = dex.codeItems[run.codeIdx]
        assertEquals("zzz", dex.strings[unit(runCode.insns, 1)])
        assertEquals("sB", fieldName(dex, unit(runCode.insns, 3)))
        assertEquals("helper", methodName(dex, unit(runCode.insns, 5)))
        assertEquals("LBase;", typeName(dex, unit(runCode.insns, 8)))
        assertEquals(listOf(0, 7), runCode.tries.map { it.startAddr })
        val firstHandler = runCode.catchHandlers[runCode.tries[0].handlerIdx]
        assertEquals("Ljava/lang/Exception;", typeName(dex, firstHandler.handlers.single().typeIdx))
        assertEquals(0xa, runCode.catchHandlers[runCode.tries[1].handlerIdx].catchAllAddr)
        val local = dex.debugInfoItems[runCode.debugInfoIdx].opcodes.filterIsInstance<DebugOpcode.StartLocal>().single()
        assertEquals("x", dex.strings[local.nameIdx])
        assertEquals("Ljava/lang/String;", typeName(dex, local.typeIdx))

        val switcher = dex.codeItems[data.directMethods[2].codeIdx]
        assertEquals("after", dex.strings[unit(switcher.insns, 5)])
        assertEquals("tail", dex.strings[unit(switcher.insns, 0x13)])

        // аннотации: набор по типу, элементы по имени (в т.ч. во вложенной)
        val dir = dex.annotationsDirectoryItems[sub.annotationsDirectoryIdx]
        val classSet = dex.annotationSetItems[dir.classAnnotationsSetIdx].annotationIdxs.map { dex.annotationItems[it] }
        assertEquals(listOf("LAAnno;", "LMyAnno;"), classSet.map { typeName(dex, it.annotation.typeIdx) })
        val myAnno = classSet[1].annotation
        assertEquals(listOf("alpha", "value", "zeta"), myAnno.elements.map { dex.strings[it.nameIdx] })
        @Suppress("UNCHECKED_CAST")
        val array = myAnno.elements[1].value.value as List<EncodedValue>
        assertEquals("LSub;", typeName(dex, array[0].value as Int))
        val nested = array[1].value as Annotation
        assertEquals(listOf("alpha", "zeta"), nested.elements.map { dex.strings[it.nameIdx] })
        assertEquals('é', array[2].value)
        assertEquals((-0.0f).toRawBits(), (array[3].value as Float).toRawBits())
        assertEquals(1.0E-300, array[4].value)
        assertEquals(Long.MIN_VALUE, array[5].value)
        assertEquals((-129).toShort(), array[6].value)
        assertEquals((-1).toByte(), array[7].value)
        assertEquals("\u0000nul\uD800", dex.strings[array[8].value as Int])
        assertEquals("\u00e9\u4e2d\uD83D\uDE00", dex.strings[array[9].value as Int])
        assertEquals(listOf("sA", "sB"), dir.fieldAnnotations.map { fieldName(dex, it.fieldIdx) })
        assertEquals("helper", methodName(dex, dir.parameterAnnotations.single().methodIdx))

        // повторная запись прочитанного результата побайтно совпадает
        assertContentEquals(bytes, DexWriter(dex).buildDex())
    }

    @Test
    fun upgradesVersionForNewOpcodes() {
        val base = model(DexMagic.DEX_035).copy(hiddenApiClassDataItems = emptyList())
        val plain = DexWriter(base).buildDex()
        assertEquals("035", DexMagic.getVersion(DexReader().read(plain).header.magic))
        // const-method-type требует DEX 039
        val codes = base.codeItems.toMutableList()
        codes[1] = CodeItem(1, 1, 0, 0, -1, 3, code(0x00ff, 0, 0x000e), emptyList(), emptyList())
        val upgraded = DexWriter(base.copy(codeItems = codes)).buildDex()
        assertEquals("039", DexMagic.getVersion(DexReader().read(upgraded).header.magic))
        DexTestUtil.verifyWithDexdump(upgraded, "upgrade")
    }

    @Test
    fun upgradesVersionForDefaultMethods() {
        val base = model(DexMagic.DEX_035).copy(hiddenApiClassDataItems = emptyList())
        // LIface;->run()V с кодом (default-метод)
        val methods = base.methodIds + MethodIdItem(6, 1, 3)
        val codes = base.codeItems + CodeItem(1, 1, 0, 0, -1, 1, code(0x000e), emptyList(), emptyList())
        val data = base.classDataItems + ClassDataItem(0, 0, 0, 1, emptyList(), emptyList(), emptyList(), listOf(EncodedMethod(methods.size - 1, 0x1, codes.size - 1)))
        val defs = base.classDefs.toMutableList()
        defs[2] = defs[2].copy(classDataIdx = data.size - 1)
        val out = DexWriter(base.copy(methodIds = methods, codeItems = codes, classDataItems = data, classDefs = defs)).buildDex()
        assertEquals("037", DexMagic.getVersion(DexReader().read(out).header.magic))
        DexTestUtil.verifyWithDexdump(out, "default-method")
    }

    @Test
    fun failsOnDuplicateClass() {
        val m = model()
        val defs = m.classDefs + m.classDefs[2].copy(classIdx = 6)
        val e = assertFailsWith<IllegalArgumentException> { DexWriter(m.copy(classDefs = defs, hiddenApiClassDataItems = emptyList())).buildDex() }
        assertTrue(e.message!!.contains("LIface;"), e.message)
    }

    @Test
    fun failsOnDuplicateMember() {
        val m = model()
        val data = m.classDataItems.toMutableList()
        data[1] = data[1].copy(directMethodsSize = 2, directMethods = data[1].directMethods + data[1].directMethods)
        val e = assertFailsWith<IllegalArgumentException> { DexWriter(m.copy(classDataItems = data, hiddenApiClassDataItems = emptyList())).buildDex() }
        assertTrue(e.message!!.contains("<init>"), e.message)
    }

    @Test
    fun failsOnUnknownOpcode() {
        val m = model()
        val codes = m.codeItems.toMutableList()
        codes[1] = CodeItem(1, 1, 0, 0, -1, 2, code(0x0073, 0x000e), emptyList(), emptyList())
        val e = assertFailsWith<IllegalArgumentException> { DexWriter(m.copy(codeItems = codes)).buildDex() }
        assertTrue(e.message!!.contains("helper") && e.message!!.contains("0x73"), e.message)
    }

    @Test
    fun constStringIndexOverflowBecomesJumbo() {
        val m = model()
        // 70000 строк, отсортированных раньше "zzz": индекс "zzz" перестаёт помещаться в 16 бит —
        // const-string становится const-string/jumbo (подробно — JumboStringsTest).
        val extra = List(70000) { "a%06d".format(it) }
        val out = DexReader().read(DexWriter(m.copy(strings = m.strings + extra)).buildDex())
        val zzz = out.strings.indexOf("zzz")
        assertTrue(zzz > 0xFFFF)
        val jumbo = out.codeItems.any { c ->
            com.tim.dasm.disassembler.DexDisassembler().disassemble(c.insns)
                .any { it is com.tim.dasm.model.Ins31c && it.opcode == com.tim.dex.data.Opcode.CONST_STRING_JUMBO && it.index == zzz }
        }
        assertTrue(jumbo, "const-string/jumbo на \"zzz\"")
    }
}
