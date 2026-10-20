package com.tim.clazz.writer

import com.tim.clazz.model.ConstantPoolEntry
import com.tim.clazz.model.attributes.*
import com.tim.clazz.model.attributes.Deprecated
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.*
import com.tim.clazz.util.Location
import com.tim.clazz.util.AttributeKinds
import com.tim.clazz.util.AttributeSpecCache
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassOutput
import com.tim.clazz.util.fastForEach

/**
 * Запись атрибутов (JVMS §4.7). attribute_length вычисляется по фактически записанным
 * байтам (резерв + дозапись), поэтому изменённая модель всегда даёт согласованные длины.
 */
internal class AttributeWriter(
    private val out: ClassOutput,
    private val pool: List<ConstantPoolEntry?>,
    private val location: Location
) {
    private val annotationWriter = AnnotationWriter(out)

    private val specs = AttributeSpecCache(pool)

    /** attributes_count + attributes[] в месте [place] (AttributeKinds.IN_*). */
    fun writeAttributes(attributes: List<AttributeInfo>, place: Int) {
        out.u2(attributes.size)
        attributes.fastForEach { attribute -> writeAttribute(attribute, place) }
    }

    private fun writeAttribute(attribute: AttributeInfo, place: Int) {
        val nameIndex = attribute.attributeNameIndex
        location.pushAttribute(nameIndex)
        checkName(attribute, place)
        out.u2(nameIndex)
        val lengthPos = out.reserveS4()
        val start = out.size
        writeBody(attribute)
        out.patchS4(lengthPos, out.size - start)
        location.pop()
    }

    /** Имя атрибута должно быть CONSTANT_Utf8 и соответствовать классу модели и месту. */
    private fun checkName(attribute: AttributeInfo, place: Int) {
        val spec = specs.specOf(attribute.attributeNameIndex)
        val expected = expectedKind(attribute)
        if (expected == AttributeKinds.UNKNOWN) return
        if (AttributeKinds.kindOf(spec) != expected) {
            throw ClassFormatException(
                "${AttributeKinds.kindName(expected)} записывается с именем #${attribute.attributeNameIndex}, " +
                    "которое не является именем этого атрибута"
            )
        }
        if (AttributeKinds.placesOf(spec) and place == 0) {
            throw ClassFormatException("${AttributeKinds.kindName(expected)} недопустим в этом месте (JVMS §4.7, таблица 4.7-C)")
        }
    }

    /** Вид атрибута по классу модели (частые — первыми). */
    private fun expectedKind(attribute: AttributeInfo): Int = when (attribute) {
        is Code -> AttributeKinds.CODE
        is LineNumberTable -> AttributeKinds.LINE_NUMBER_TABLE
        is LocalVariableTable -> AttributeKinds.LOCAL_VARIABLE_TABLE
        is StackMapTable -> AttributeKinds.STACK_MAP_TABLE
        is Signature -> AttributeKinds.SIGNATURE
        is RuntimeVisibleAnnotations -> AttributeKinds.ANNOTATIONS
        is LocalVariableTypeTable -> AttributeKinds.LOCAL_VARIABLE_TYPE_TABLE
        is Exceptions -> AttributeKinds.EXCEPTIONS
        is InnerClasses -> AttributeKinds.INNER_CLASSES
        is SourceFile -> AttributeKinds.SOURCE_FILE
        is ConstantValue -> AttributeKinds.CONSTANT_VALUE
        is BootstrapMethods -> AttributeKinds.BOOTSTRAP_METHODS
        is EnclosingMethod -> AttributeKinds.ENCLOSING_METHOD
        is SourceDebugExtension -> AttributeKinds.SOURCE_DEBUG_EXTENSION
        is MethodParameters -> AttributeKinds.METHOD_PARAMETERS
        is RuntimeVisibleParameterAnnotations -> AttributeKinds.PARAMETER_ANNOTATIONS
        is RuntimeVisibleTypeAnnotations -> AttributeKinds.TYPE_ANNOTATIONS
        is AnnotationDefault -> AttributeKinds.ANNOTATION_DEFAULT
        is Module -> AttributeKinds.MODULE
        is ModulePackages -> AttributeKinds.MODULE_PACKAGES
        is ModuleMainClass -> AttributeKinds.MODULE_MAIN_CLASS
        is NestHost -> AttributeKinds.NEST_HOST
        is NestMembers -> AttributeKinds.NEST_MEMBERS
        is Record -> AttributeKinds.RECORD
        is PermittedSubclasses -> AttributeKinds.PERMITTED_SUBCLASSES
        is Synthetic -> AttributeKinds.SYNTHETIC
        is Deprecated -> AttributeKinds.DEPRECATED
        is UnknownAttribute -> AttributeKinds.UNKNOWN
    }

    private fun writeBody(attribute: AttributeInfo) {
        val out = out
        when (attribute) {
            is Code -> writeCode(attribute)
            is Exceptions -> writeU2List(attribute.exceptionIndexTable)
            is SourceFile -> out.u2(attribute.sourceFileIndex)
            is LineNumberTable -> {
                out.u2(attribute.entries.size)
                attribute.entries.fastForEach { e ->
                    out.u2(e.startPc)
                    out.u2(e.lineNumber)
                }
            }
            is LocalVariableTable -> {
                out.u2(attribute.entries.size)
                attribute.entries.fastForEach { e ->
                    out.u2(e.startPc)
                    out.u2(e.length)
                    out.u2(e.nameIndex)
                    out.u2(e.descriptorIndex)
                    out.u2(e.index)
                }
            }
            is ConstantValue -> out.u2(attribute.constantValueIndex)
            is StackMapTable -> {
                out.u2(attribute.entries.size)
                attribute.entries.fastForEach { frame -> writeFrame(frame) }
            }
            is BootstrapMethods -> {
                out.u2(attribute.bootstrapMethods.size)
                attribute.bootstrapMethods.fastForEach { m ->
                    out.u2(m.bootstrapMethodRef)
                    writeU2List(m.bootstrapArguments)
                }
            }
            is InnerClasses -> {
                out.u2(attribute.classes.size)
                attribute.classes.fastForEach { c ->
                    out.u2(c.innerClassInfoIndex)
                    out.u2(c.outerClassInfoIndex)
                    out.u2(c.innerNameIndex)
                    out.u2(c.innerClassAccessFlags)
                }
            }
            is EnclosingMethod -> {
                out.u2(attribute.classIndex)
                out.u2(attribute.methodIndex)
            }
            is Signature -> out.u2(attribute.signatureIndex)
            is SourceDebugExtension -> out.bytes(attribute.debugExtension)
            is LocalVariableTypeTable -> {
                out.u2(attribute.entries.size)
                attribute.entries.fastForEach { e ->
                    out.u2(e.startPc)
                    out.u2(e.length)
                    out.u2(e.nameIndex)
                    out.u2(e.signatureIndex)
                    out.u2(e.index)
                }
            }
            is MethodParameters -> {
                out.u1(attribute.parameters.size)
                attribute.parameters.fastForEach { p ->
                    out.u2(p.nameIndex)
                    out.u2(p.accessFlags)
                }
            }
            is RuntimeVisibleAnnotations -> {
                out.u2(attribute.annotations.size)
                attribute.annotations.fastForEach { a -> annotationWriter.writeAnnotation(a) }
            }
            is RuntimeVisibleParameterAnnotations -> {
                out.u1(attribute.parameterAnnotations.size)
                attribute.parameterAnnotations.fastForEach { p ->
                    out.u2(p.annotations.size)
                    p.annotations.fastForEach { a -> annotationWriter.writeAnnotation(a) }
                }
            }
            is RuntimeVisibleTypeAnnotations -> {
                out.u2(attribute.annotations.size)
                attribute.annotations.fastForEach { a -> writeTypeAnnotation(a) }
            }
            is AnnotationDefault -> annotationWriter.writeElementValue(attribute.defaultValue)
            is Module -> writeModule(attribute)
            is ModulePackages -> writeU2List(attribute.packageIndex)
            is ModuleMainClass -> out.u2(attribute.mainClassIndex)
            is NestHost -> out.u2(attribute.hostClassIndex)
            is NestMembers -> writeU2List(attribute.classes)
            is Record -> {
                out.u2(attribute.components.size)
                attribute.components.fastForEach { c ->
                    out.u2(c.nameIndex)
                    out.u2(c.descriptorIndex)
                    location.pushRecordComponent(c.nameIndex)
                    writeAttributes(c.attributes, AttributeKinds.IN_RECORD_COMPONENT)
                    location.pop()
                }
            }
            is PermittedSubclasses -> writeU2List(attribute.classes)
            is Synthetic, is Deprecated -> Unit
            is UnknownAttribute -> out.bytes(attribute.data)
        }
    }

    private fun writeU2List(values: List<Int>) {
        out.u2(values.size)
        values.fastForEach { v -> out.u2(v) }
    }

    private fun writeCode(code: Code) {
        out.u2(code.maxStack)
        out.u2(code.maxLocals)
        out.s4(code.code.size)
        out.bytes(code.code)
        out.u2(code.exceptionTable.size)
        code.exceptionTable.fastForEach { e ->
            out.u2(e.startPc)
            out.u2(e.endPc)
            out.u2(e.handlerPc)
            out.u2(e.catchType)
        }
        writeAttributes(code.attributes, AttributeKinds.IN_CODE)
    }

    private fun writeFrame(frame: StackMapFrame) {
        val type = frame.frameType
        val valid = when (frame) {
            is SameFrame -> type in 0..63
            is SameLocals1StackItemFrame -> type in 64..127
            is SameLocals1StackItemFrameExtended -> type == 247
            is ChopFrame -> type in 248..250
            is SameFrameExtended -> type == 251
            is AppendFrame -> type in 252..254 && frame.locals.size == type - 251
            is FullFrame -> type == 255
        }
        if (!valid) throw ClassFormatException("некорректный фрейм StackMapTable: $frame")
        out.u1(type)
        when (frame) {
            is SameFrame -> Unit
            is SameLocals1StackItemFrame -> writeVerificationType(frame.stack)
            is SameLocals1StackItemFrameExtended -> {
                out.u2(frame.offsetDelta)
                writeVerificationType(frame.stack)
            }
            is ChopFrame -> out.u2(frame.offsetDelta)
            is SameFrameExtended -> out.u2(frame.offsetDelta)
            is AppendFrame -> {
                out.u2(frame.offsetDelta)
                frame.locals.fastForEach { v -> writeVerificationType(v) }
            }
            is FullFrame -> {
                out.u2(frame.offsetDelta)
                out.u2(frame.locals.size)
                frame.locals.fastForEach { v -> writeVerificationType(v) }
                out.u2(frame.stack.size)
                frame.stack.fastForEach { v -> writeVerificationType(v) }
            }
        }
    }

    private fun writeVerificationType(type: VerificationTypeInfo) {
        val tag = type.tag
        if (tag !in 0..8) throw ClassFormatException("неизвестный тег verification_type_info: $tag")
        out.u1(tag)
        if (type.hasValue) {
            out.u2(type.value)
        } else if (type.value != 0) {
            throw ClassFormatException("у verification_type_info с тегом $tag нет данных, а value = ${type.value}")
        }
    }

    private fun writeTypeAnnotation(annotation: TypeAnnotation) {
        val targetType = annotation.targetType
        val info = annotation.targetInfo
        if (!RuntimeVisibleTypeAnnotations.targetInfoMatches(targetType, info)) {
            throw ClassFormatException(
                "target_type 0x%02X не соответствует ${RuntimeVisibleTypeAnnotations.targetInfoName(info)}".format(targetType)
            )
        }
        out.u1(targetType)
        when (info) {
            is EmptyTarget -> Unit
            is TypeParameterTarget -> out.u1(info.typeParameterIndex)
            is SuperTypeTarget -> out.u2(info.supertypeIndex)
            is TypeParameterBoundTarget -> {
                out.u1(info.typeParameterIndex)
                out.u1(info.boundIndex)
            }
            is FormalParameterTarget -> out.u1(info.formalParameterIndex)
            is ThrowsTarget -> out.u2(info.throwsTypeIndex)
            is LocalvarTarget -> {
                out.u2(info.entries.size)
                info.entries.fastForEach { e ->
                    out.u2(e.startPc)
                    out.u2(e.length)
                    out.u2(e.index)
                }
            }
            is CatchTarget -> out.u2(info.exceptionTableIndex)
            is OffsetTarget -> out.u2(info.offset)
            is TypeArgumentTarget -> {
                out.u2(info.offset)
                out.u1(info.typeArgumentIndex)
            }
        }
        val path = annotation.typePath.entries
        out.u1(path.size)
        path.fastForEach { e ->
            out.u1(e.typePathKind)
            out.u1(e.typeArgumentIndex)
        }
        out.u2(annotation.typeIndex)
        annotationWriter.writeElementValuePairs(annotation.elementValuePairs)
    }

    private fun writeModule(module: Module) {
        out.u2(module.moduleNameIndex)
        out.u2(module.moduleFlags)
        out.u2(module.moduleVersionIndex)
        out.u2(module.requires.size)
        module.requires.fastForEach { r ->
            out.u2(r.requiresIndex)
            out.u2(r.requiresFlags)
            out.u2(r.requiresVersionIndex)
        }
        out.u2(module.exports.size)
        module.exports.fastForEach { e ->
            out.u2(e.exportsIndex)
            out.u2(e.exportsFlags)
            writeU2List(e.exportsTo)
        }
        out.u2(module.opens.size)
        module.opens.fastForEach { o ->
            out.u2(o.opensIndex)
            out.u2(o.opensFlags)
            writeU2List(o.opensTo)
        }
        writeU2List(module.uses)
        out.u2(module.provides.size)
        module.provides.fastForEach { p ->
            out.u2(p.providesIndex)
            writeU2List(p.with)
        }
    }
}
