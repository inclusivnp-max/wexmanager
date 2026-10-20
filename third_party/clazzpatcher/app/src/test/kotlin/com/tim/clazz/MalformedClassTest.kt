package com.tim.clazz

import com.tim.clazz.model.*
import com.tim.clazz.model.attributes.*
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.writer.ClassWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Некорректный вход и несогласованная модель — громкая ошибка с местом, а не молчаливое искажение. */
class MalformedClassTest {

    private val main: ByteArray get() = File(repoRoot(), "Main.class").readBytes()

    /** Минимальный класс Foo с заданными атрибутами класса; имена атрибутов — в пуле с индекса 5. */
    private fun classWith(attributes: List<AttributeInfo>, extraPool: List<ConstantPoolEntry?> = emptyList()): ClassFile {
        val pool = listOf(
            null,
            ConstantUtf8Info.of("Foo"), ConstantClassInfo(1),
            ConstantUtf8Info.of("java/lang/Object"), ConstantClassInfo(3)
        ) + extraPool
        return ClassFile(0xCAFEBABEL, 0, 52, pool, 0x21, 2, 4, emptyList(), emptyList(), emptyList(), attributes)
    }

    private fun readError(bytes: ByteArray): String =
        assertFailsWith<ClassFormatException> { ClassReader().read(bytes, "test.class") }.message!!

    private fun writeError(cf: ClassFile): String =
        assertFailsWith<ClassFormatException> { ClassWriter(cf).toByteArray() }.message!!

    @Test
    fun `повреждённый файл`() {
        val bytes = main
        assertContains(readError(bytes.copyOf(bytes.size / 2)), "неожиданный конец данных")
        assertContains(readError(bytes + 0), "лишние 1 байт")
        assertContains(readError(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBF.toByte(), 0, 0, 0, 50)), "магическое число")
        val badTag = bytes.copyOf().also { it[10] = 2 } // тег первой записи пула
        assertContains(readError(badTag), "неизвестный тег пула констант: 2")
    }

    @Test
    fun `длина атрибута должна совпадать с разобранной`() {
        val extra = listOf(ConstantUtf8Info.of("SourceFile"), ConstantUtf8Info.of("Synthetic"))
        // SourceFile из 3 байт: разобраны 2 — ошибка с указанием атрибута
        val sourceFile = ClassWriter(classWith(listOf(UnknownAttribute(5, 3, byteArrayOf(0, 1, 2))), extra)).toByteArray()
        val message = readError(sourceFile)
        assertContains(message, "attribute_length = 3")
        assertContains(message, "атрибут SourceFile")
        assertContains(message, "класс Foo")
        // Synthetic с данными
        val synthetic = ClassWriter(classWith(listOf(UnknownAttribute(6, 1, byteArrayOf(0))), extra)).toByteArray()
        assertContains(readError(synthetic), "атрибут Synthetic")
    }

    @Test
    fun `стандартное имя вне своего места сохраняется как есть`() {
        // LineNumberTable допустим только в Code; на уровне класса JVM его игнорирует (JVMS 4.7.1)
        val extra = listOf(ConstantUtf8Info.of("LineNumberTable"))
        val attribute = UnknownAttribute(5, 1, byteArrayOf(42))
        val bytes = ClassWriter(classWith(listOf(attribute), extra)).toByteArray()
        val model = assertRoundTrip(bytes, "Foo")
        assertEquals(attribute, model.attributes.single())
    }

    @Test
    fun `неизвестные теги внутри атрибутов`() {
        val extra = listOf(ConstantUtf8Info.of("RuntimeVisibleTypeAnnotations"), ConstantUtf8Info.of("RuntimeVisibleAnnotations"))
        // target_type 0x20 не существует
        val typeAnn = ClassWriter(classWith(listOf(UnknownAttribute(5, 3, byteArrayOf(0, 1, 0x20))), extra)).toByteArray()
        assertContains(readError(typeAnn), "target_type аннотации типа: 0x20")
        // element_value с тегом 'x'
        val ann = byteArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 'x'.code.toByte(), 0, 1)
        val annBytes = ClassWriter(classWith(listOf(UnknownAttribute(6, ann.size.toLong(), ann)), extra)).toByteArray()
        assertContains(readError(annBytes), "неизвестный тег element_value: 0x78")
    }

    @Test
    fun `атрибут с именем не из CONSTANT_Utf8`() {
        val cf = classWith(listOf(UnknownAttribute(2, 0)))
        assertContains(writeError(cf), "attribute_name_index #2 не указывает на CONSTANT_Utf8")
    }

    @Test
    fun `несогласованная модель при записи`() {
        val extra = listOf(ConstantUtf8Info.of("SourceFile"), ConstantUtf8Info.of("Code"))
        // пустой слот в середине пула
        val holes = classWith(emptyList()).let { it.copy(constantPool = it.constantPool + null + ConstantUtf8Info.of("x")) }
        assertContains(writeError(holes), "пустой слот #5")
        // Long без второго слота
        val long = classWith(emptyList(), listOf(ConstantLongInfo(1), ConstantUtf8Info.of("x")))
        assertContains(writeError(long), "второй слот Long/Double #5 должен быть null")
        // значение не помещается в u2
        assertContains(writeError(classWith(emptyList()).copy(accessFlags = 0x10000)), "не помещается в u2")
        // SourceFile записывается с именем Code
        assertContains(writeError(classWith(listOf(SourceFile(6, 2, 1)), extra)), "не является именем этого атрибута")
        // Code на уровне класса
        val code = Code(6, 0, 0, 0, byteArrayOf(0xB1.toByte()), emptyList(), emptyList())
        assertContains(writeError(classWith(listOf(code), extra)), "недопустим в этом месте")
    }

    @Test
    fun `несогласованные фреймы и аннотации типов`() {
        val extra = listOf(ConstantUtf8Info.of("Code"), ConstantUtf8Info.of("StackMapTable"), ConstantUtf8Info.of("m"), ConstantUtf8Info.of("()V"))
        fun method(frame: StackMapFrame): ClassFile {
            val code = Code(5, 0, 0, 0, byteArrayOf(0xB1.toByte()), emptyList(), listOf(StackMapTable(6, 0, listOf(frame))))
            return classWith(emptyList(), extra).copy(methods = listOf(MethodInfo(0x9, 7, 8, listOf(code))))
        }
        val message = writeError(method(AppendFrame(253, 0, listOf(VerificationTypeInfo(VerificationTypeInfo.ITEM_Integer)))))
        assertContains(message, "некорректный фрейм StackMapTable")
        assertContains(message, "метод #0 m()V")
        assertContains(writeError(method(SameFrame(64))), "некорректный фрейм")
        assertContains(writeError(method(FullFrame(255, 0, listOf(VerificationTypeInfo(9)), emptyList()))), "verification_type_info")

        val typeExtra = listOf(ConstantUtf8Info.of("RuntimeInvisibleTypeAnnotations"), ConstantUtf8Info.of("LA;"))
        val wrongTarget = RuntimeVisibleTypeAnnotations(
            5, 0, listOf(
                RuntimeVisibleTypeAnnotations.TypeAnnotation(
                    0x47, RuntimeVisibleTypeAnnotations.OffsetTarget(0), RuntimeVisibleTypeAnnotations.TypePath.EMPTY, 6, emptyList()
                )
            )
        )
        assertContains(writeError(classWith(listOf(wrongTarget), typeExtra)), "target_type 0x47 не соответствует offset_target")
    }

    @Test
    fun `исходное место ошибки в сообщении`() {
        val model = ClassReader().read(main)
        val code = model.methods[1].attributes.filterIsInstance<Code>().single()
        val badCode = code.copy(maxStack = 70000)
        val bad = model.copy(methods = model.methods.toMutableList().also { it[1] = it[1].copy(attributes = listOf(badCode)) })
        val message = writeError(bad)
        assertContains(message, "класс Main")
        assertContains(message, "метод #1 findKtFiles(Ljava/io/File;Ljava/util/List;)V → атрибут Code")
    }

    /** Случайные искажения: либо ClassFormatException, либо чтение и запись байт-в-байт — других исходов нет. */
    @Test
    fun `искажённые файлы не приводят к посторонним исключениям`() {
        val originals = listOf(main, classBytes(ClassWriter::class.java), classBytes(ModifiedUtf8Test::class.java))
        val random = java.util.Random(42)
        var rejected = 0
        repeat(3000) {
            val original = originals[random.nextInt(originals.size)]
            val b = when (random.nextInt(3)) {
                0 -> original.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> original.copyOf(random.nextInt(original.size))
                else -> original.copyOf().also { it[random.nextInt(it.size)] = (it[random.nextInt(it.size)].toInt() xor 0x80).toByte() }
            }
            val model = try {
                ClassReader().read(b)
            } catch (e: ClassFormatException) {
                rejected++
                return@repeat
            }
            assertEquals(b.toList(), ClassWriter(model).toByteArray().toList())
        }
        assertTrue(rejected > 0)
    }
}
