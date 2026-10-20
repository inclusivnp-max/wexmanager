package com.tim.clazz.writer

import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.FieldInfo
import com.tim.clazz.model.MethodInfo
import com.tim.clazz.util.Location
import com.tim.clazz.util.AttributeKinds
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassOutput
import java.io.File
import java.io.OutputStream

/**
 * Запись модели [ClassFile] в байты class-файла (JVMS §4). Модель, прочитанная ClassReader,
 * записывается байт-в-байт. Длины атрибутов вычисляются заново, значения проверяются на диапазон;
 * несогласованная модель — [ClassFormatException] с указанием места.
 */
class ClassWriter(
    val classFile: ClassFile
) {
    fun write(file: File) {
        file.writeBytes(toByteArray())
    }

    fun write(output: OutputStream) {
        output.write(toByteArray())
    }

    fun toByteArray(): ByteArray {
        val cf = classFile
        val location = Location(cf.constantPool)
        val out = SCRATCH.get()
        out.reset()
        try {
            if (cf.magic != 0xCAFEBABEL) throw ClassFormatException("magic 0x${cf.magic.toString(16)} вместо 0xcafebabe")
            out.s4(cf.magic.toInt())
            out.u2(cf.minorVersion)
            out.u2(cf.majorVersion)
            ConstantPoolWriter.write(out, cf.constantPool)
            out.u2(cf.accessFlags)
            out.u2(cf.thisClass)
            out.u2(cf.superClass)
            val interfaces = cf.interfaces
            out.u2(interfaces.size)
            for (i in interfaces.indices) out.u2(interfaces[i])

            val attributeWriter = AttributeWriter(out, cf.constantPool, location)
            val fields = cf.fields
            out.u2(fields.size)
            for (i in fields.indices) writeField(out, attributeWriter, location, i, fields[i])
            val methods = cf.methods
            out.u2(methods.size)
            for (i in methods.indices) writeMethod(out, attributeWriter, location, i, methods[i])
            location.clearMember()
            attributeWriter.writeAttributes(cf.attributes, AttributeKinds.IN_CLASS)
            return out.toByteArray()
        } catch (e: ClassFormatException) {
            val name = location.className(cf.thisClass)
            throw ClassFormatException(
                "Ошибка записи class-файла${if (name != null) " (класс $name)" else ""}: ${e.message} [${location.describe()}]",
                e
            )
        } finally {
            if (out.capacity > MAX_RETAINED_BUFFER) SCRATCH.remove()
        }
    }

    private fun writeField(out: ClassOutput, attributes: AttributeWriter, location: Location, index: Int, field: FieldInfo) {
        location.member("поле", index, field.nameIndex, field.descriptorIndex)
        out.u2(field.accessFlags)
        out.u2(field.nameIndex)
        out.u2(field.descriptorIndex)
        attributes.writeAttributes(field.attributes, AttributeKinds.IN_FIELD)
    }

    private fun writeMethod(out: ClassOutput, attributes: AttributeWriter, location: Location, index: Int, method: MethodInfo) {
        location.member("метод", index, method.nameIndex, method.descriptorIndex)
        out.u2(method.accessFlags)
        out.u2(method.nameIndex)
        out.u2(method.descriptorIndex)
        attributes.writeAttributes(method.attributes, AttributeKinds.IN_METHOD)
    }

    private companion object {
        /** Буфер записи на поток: без перевыделений и копирований при росте для каждого класса. */
        val SCRATCH: ThreadLocal<ClassOutput> = object : ThreadLocal<ClassOutput>() {
            override fun initialValue() = ClassOutput(64 * 1024)
        }

        /** Буфер больше этого размера не удерживается между вызовами. */
        const val MAX_RETAINED_BUFFER = 4 * 1024 * 1024
    }
}
