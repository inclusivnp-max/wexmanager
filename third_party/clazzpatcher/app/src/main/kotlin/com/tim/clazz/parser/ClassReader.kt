package com.tim.clazz.parser

import com.tim.clazz.model.*
import com.tim.clazz.util.AttributeKinds
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassInput
import com.tim.clazz.util.Location
import java.io.File
import java.io.InputStream

/**
 * Чтение class-файла (JVMS §4) в модель [ClassFile]. Не хранит состояния между вызовами,
 * один экземпляр можно использовать из нескольких потоков.
 * Некорректный файл — [ClassFormatException] с указанием места ошибки.
 *
 * @param rawStackMaps StackMapTable не разбирается, а хранится байтами ([com.tim.clazz.model.attributes.UnknownAttribute]):
 *                     для тех, кому кадры не нужны (c2d строит типы сам), — меньше памяти и времени;
 *                     запись класса обратно по-прежнему байт в байт.
 */
class ClassReader(private val rawStackMaps: Boolean = false) {

    fun read(file: File): ClassFile = read(file.readBytes(), file.path)

    fun read(input: InputStream): ClassFile = read(input.readBytes())

    fun read(bytes: ByteArray): ClassFile = read(bytes, null)

    /** [sourceName] (путь/имя записи в архиве) попадает в сообщения об ошибках. */
    fun read(bytes: ByteArray, sourceName: String?): ClassFile = ClassParser(bytes, sourceName, rawStackMaps).parse()
}

private class ClassParser(bytes: ByteArray, private val sourceName: String?, private val rawStackMaps: Boolean) {
    private val input = ClassInput(bytes)
    private var location = Location(null)
    private var thisClass = 0

    fun parse(): ClassFile {
        try {
            return parseClass()
        } catch (e: ClassFormatException) {
            val name = location.className(thisClass)
            val what = listOfNotNull(sourceName, name?.let { "класс $it" }).joinToString(", ")
            throw ClassFormatException(
                "Некорректный class-файл${if (what.isEmpty()) "" else " ($what)"}: ${e.message} " +
                    "[${location.describe()}, смещение ${input.pos}]",
                e
            )
        }
    }

    private fun parseClass(): ClassFile {
        val input = input
        val magic = input.u4()
        if (magic != 0xCAFEBABEL) {
            throw ClassFormatException("неверное магическое число 0x${magic.toString(16).uppercase()}")
        }
        val minorVersion = input.u2()
        val majorVersion = input.u2()

        val constantPool = ConstantPoolReader.read(input)
        location = Location(constantPool)

        val accessFlags = input.u2()
        thisClass = input.u2()
        val superClass = input.u2()
        val interfaces = readList(input.u2()) { input.u2() }

        val attributeReader = AttributeReader(input, constantPool, location, rawStackMaps)
        val fields = readList(input.u2()) { index ->
            val flags = input.u2()
            val name = input.u2()
            val descriptor = input.u2()
            location.member("поле", index, name, descriptor)
            FieldInfo(flags, name, descriptor, attributeReader.readAttributes(AttributeKinds.IN_FIELD))
        }
        val methods = readList(input.u2()) { index ->
            val flags = input.u2()
            val name = input.u2()
            val descriptor = input.u2()
            location.member("метод", index, name, descriptor)
            MethodInfo(flags, name, descriptor, attributeReader.readAttributes(AttributeKinds.IN_METHOD))
        }
        location.clearMember()
        val attributes = attributeReader.readAttributes(AttributeKinds.IN_CLASS)

        if (input.pos != input.bytes.size) {
            throw ClassFormatException("лишние ${input.bytes.size - input.pos} байт в конце class-файла")
        }
        return ClassFile(
            magic = magic,
            minorVersion = minorVersion,
            majorVersion = majorVersion,
            constantPool = constantPool,
            accessFlags = accessFlags,
            thisClass = thisClass,
            superClass = superClass,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            attributes = attributes
        )
    }
}
