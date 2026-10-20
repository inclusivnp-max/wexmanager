package com.tim.clazz

import com.tim.clazz.model.*
import com.tim.clazz.model.attributes.*
import com.tim.clazz.parser.AttributeReader
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.util.ModifiedUtf8
import com.tim.clazz.writer.AttributeWriter
import com.tim.clazz.writer.ClassWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Запись модели, построенной или изменённой программно: длины пересчитываются, JVM принимает класс. */
class ClassWriterTest {

    private fun code(max: Int, locals: Int, vararg bytecode: Int, attributes: List<AttributeInfo> = emptyList()) =
        Code(7, 0, max, locals, ByteArray(bytecode.size) { bytecode[it].toByte() }, emptyList(), attributes)

    /** p/Gen: add(II)I, max(II)I со StackMapTable, str() с \0 и суррогатами, Long/Float/Double в пуле. */
    private fun generatedClass(): ClassFile {
        val pool = listOf(
            null,
            ConstantUtf8Info.of("p/Gen"),                  // 1
            ConstantClassInfo(1),                          // 2
            ConstantUtf8Info.of("java/lang/Object"),       // 3
            ConstantClassInfo(3),                          // 4
            ConstantUtf8Info.of("add"),                    // 5
            ConstantUtf8Info.of("(II)I"),                  // 6
            ConstantUtf8Info.of("Code"),                   // 7
            ConstantUtf8Info.of("max"),                    // 8
            ConstantUtf8Info.of("StackMapTable"),          // 9
            ConstantUtf8Info.of("BIG"),                    // 10
            ConstantUtf8Info.of("J"),                      // 11
            ConstantUtf8Info.of("ConstantValue"),          // 12
            ConstantLongInfo(0x123456789L),                // 13
            null,                                          // 14 — второй слот Long
            ConstantUtf8Info.of("SNAN"),                   // 15
            ConstantUtf8Info.of("F"),                      // 16
            ConstantFloatInfo(0x7F800001),                 // 17 — сигнальный NaN
            ConstantDoubleInfo(0x7FF0000000000001L),       // 18
            null,                                          // 19
            ConstantUtf8Info.of(TEXT),                     // 20
            ConstantStringInfo(20),                        // 21
            ConstantUtf8Info.of("str"),                    // 22
            ConstantUtf8Info.of("()Ljava/lang/String;"),   // 23
            ConstantUtf8Info.of("SourceFile"),             // 24
            ConstantUtf8Info.of("Gen.java"),               // 25
            ConstantDoubleInfo(-0.0),                      // 26
            null                                           // 27
        )
        val methods = listOf(
            MethodInfo(0x0009, 5, 6, listOf(code(2, 2, 0x1A, 0x1B, 0x60, 0xAC))),
            MethodInfo(
                0x0009, 8, 6, listOf(
                    code(
                        2, 2, 0x1A, 0x1B, 0xA1, 0x00, 0x05, 0x1A, 0xAC, 0x1B, 0xAC,
                        attributes = listOf(StackMapTable(9, 0, listOf(SameFrame(7))))
                    )
                )
            ),
            MethodInfo(0x0009, 22, 23, listOf(code(1, 0, 0x12, 21, 0xB0)))
        )
        val fields = listOf(
            FieldInfo(0x0019, 10, 11, listOf(ConstantValue(12, 0, 13))),
            FieldInfo(0x0019, 15, 16, listOf(ConstantValue(12, 0, 17)))
        )
        return ClassFile(
            0xCAFEBABEL, 0, 52, pool, 0x0021, 2, 4, emptyList(), fields, methods,
            listOf(SourceFile(24, 0, 25))
        )
    }

    @Test
    fun `класс из модели загружается и выполняется`() {
        val bytes = ClassWriter(generatedClass()).toByteArray()
        val cls = BytesClassLoader(mapOf("p.Gen" to bytes)).loadClass("p.Gen")
        assertEquals(5, cls.getMethod("add", Int::class.java, Int::class.java).invoke(null, 2, 3))
        val max = cls.getMethod("max", Int::class.java, Int::class.java)
        assertEquals(7, max.invoke(null, 3, 7))
        assertEquals(9, max.invoke(null, 9, 4))
        assertEquals(TEXT, cls.getMethod("str").invoke(null))
        assertEquals(0x123456789L, cls.getField("BIG").getLong(null))

        // Повторное чтение: биты NaN и -0.0 сохранены, длины атрибутов посчитаны писателем
        val reread = ClassReader().read(bytes)
        assertEquals(0x7F800001, (reread.constantPool[17] as ConstantFloatInfo).bits)
        assertEquals(0x7FF0000000000001L, (reread.constantPool[18] as ConstantDoubleInfo).bits)
        assertEquals((-0.0).toRawBits(), (reread.constantPool[26] as ConstantDoubleInfo).bits)
        val maxCode = reread.methods[1].attributes.single() as Code
        // max_stack, max_locals, code_length + 9 байт кода + пустая таблица исключений + StackMapTable
        assertEquals(8L + 9 + 2 + 2 + (6 + 2 + 1), maxCode.attributeLength)
        assertEquals(listOf<StackMapFrame>(SameFrame(7)), (maxCode.attributes.single() as StackMapTable).entries)
        assertEquals(bytes.toList(), ClassWriter(reread).toByteArray().toList())
    }

    /** Версия 45.0: раскладка Code та же, что в JVMS §4.7.3 (HotSpot читает её так же). */
    @Test
    fun `класс версии 45_0`() {
        val bytes = ClassWriter(generatedClass().copy(majorVersion = 45, minorVersion = 0)).toByteArray()
        assertEquals(bytes.toList(), ClassWriter(ClassReader().read(bytes)).toByteArray().toList())
        val cls = BytesClassLoader(mapOf("p.Gen" to bytes)).loadClass("p.Gen")
        assertEquals(9, cls.getMethod("max", Int::class.java, Int::class.java).invoke(null, 9, 4))
        assertEquals(TEXT, cls.getMethod("str").invoke(null))
    }

    /**
     * Изменённая модель реального класса (с ветвлениями и StackMapTable): удаление отладочных
     * атрибутов и новый атрибут со ссылкой на новую запись в конце пула. JVM верифицирует результат.
     */
    @Test
    fun `изменённая модель проходит верификацию JVM`() {
        for (cls in listOf(ModifiedUtf8::class.java, AttributeReader::class.java, AttributeWriter::class.java, TestClass::class.java)) {
            val original = ClassReader().read(classBytes(cls))
            val debug = setOf("LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable")
            val markerName = original.constantPool.size
            val stripped = original.copy(
                constantPool = original.constantPool + ConstantUtf8Info.of("clazzpatcher.Marker"),
                methods = original.methods.map { m ->
                    m.copy(attributes = m.attributes.map { a ->
                        if (a is Code) a.copy(attributes = a.attributes.filter { original.attributeName(it) !in debug }) else a
                    })
                },
                attributes = original.attributes + UnknownAttribute(markerName, 3, byteArrayOf(1, 2, 3))
            )
            val bytes = ClassWriter(stripped).toByteArray()
            val reread = ClassReader().read(bytes)
            assertEquals(stripped.constantPool, reread.constantPool)
            assertTrue(reread.methods.all { m ->
                m.attributes.filterIsInstance<Code>().all { c -> c.attributes.none { reread.attributeName(it) in debug } }
            })
            assertEquals(UnknownAttribute(markerName, 3, byteArrayOf(1, 2, 3)), reread.attributes.last())

            // getDeclaredMethods связывает класс — выполняется верификация байткода со StackMapTable
            val defined = BytesClassLoader(mapOf(cls.name to bytes)).define(cls.name)
            assertTrue(defined.declaredMethods.isNotEmpty(), cls.name)
        }
        // Копия ModifiedUtf8 без отладочной информации работает так же
        val copy = BytesClassLoader(mapOf(ModifiedUtf8::class.java.name to ClassWriter(ClassReader().read(classBytes(ModifiedUtf8::class.java))).toByteArray()))
            .define(ModifiedUtf8::class.java.name)
        val instance = copy.getField("INSTANCE").get(null)
        val encoded = copy.getMethod("encode", String::class.java).invoke(instance, TEXT) as ByteArray
        assertEquals(ModifiedUtf8.encode(TEXT).toList(), encoded.toList())
    }

    private companion object {
        const val TEXT = "\u0000 😀 Ж"
    }
}
