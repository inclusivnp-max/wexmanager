package com.tim.clazz.parser

import com.tim.clazz.model.*
import com.tim.clazz.model.attributes.*
import com.tim.clazz.model.attributes.Deprecated
import com.tim.clazz.model.attributes.Code.ExceptionTableEntry
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.*
import com.tim.clazz.util.AttributeKinds
import com.tim.clazz.util.AttributeSpecCache
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassInput
import com.tim.clazz.util.Location

/**
 * Чтение атрибутов (JVMS §4.7). Каждый распознанный атрибут разбирается строго в границах
 * attribute_length и должен занять её целиком; нераспознанный сохраняется как [UnknownAttribute].
 */
internal class AttributeReader(
    private val input: ClassInput,
    private val pool: List<ConstantPoolEntry?>,
    private val location: Location,
    /** StackMapTable — байтами, без разбора (см. [ClassReader]). */
    private val rawStackMaps: Boolean = false
) {
    private val annotationReader = AnnotationReader(input)

    private val specs = AttributeSpecCache(pool)

    /** attributes_count + attributes[] в месте [place] (AttributeKinds.IN_*). */
    fun readAttributes(place: Int): List<AttributeInfo> = readList(input.u2()) { readAttribute(place) }

    private fun readAttribute(place: Int): AttributeInfo {
        val nameIndex = input.u2()
        val length = input.u4()
        location.pushAttribute(nameIndex)
        val spec = specs.specOf(nameIndex)
        val size = input.checkLength(length, "attribute_length")
        val kind = AttributeKinds.kindOf(spec)
        val attribute = if (AttributeKinds.placesOf(spec) and place == 0 || (rawStackMaps && kind == AttributeKinds.STACK_MAP_TABLE)) {
            UnknownAttribute(nameIndex, length, input.bytes(size))
        } else {
            val end = input.pos + size
            val outerLimit = input.limit
            input.limit = end
            val parsed = readKnown(kind, nameIndex, length)
            if (input.pos != end) {
                throw ClassFormatException("разобрано ${size - (end - input.pos)} байт из attribute_length = $size")
            }
            input.limit = outerLimit
            parsed
        }
        location.pop()
        return attribute
    }

    private fun readKnown(kind: Int, nameIndex: Int, length: Long): AttributeInfo {
        val input = input
        return when (kind) {
            AttributeKinds.CODE -> readCode(nameIndex, length)
            AttributeKinds.EXCEPTIONS -> Exceptions(nameIndex, length, readU2List())
            AttributeKinds.SOURCE_FILE -> SourceFile(nameIndex, length, input.u2())
            AttributeKinds.LINE_NUMBER_TABLE -> LineNumberTable(nameIndex, length, readList(input.u2()) {
                LineNumberTable.LineNumberTableEntry(input.u2(), input.u2())
            })
            AttributeKinds.LOCAL_VARIABLE_TABLE -> LocalVariableTable(nameIndex, length, readList(input.u2()) {
                LocalVariableTable.LocalVariableTableEntry(input.u2(), input.u2(), input.u2(), input.u2(), input.u2())
            })
            AttributeKinds.CONSTANT_VALUE -> ConstantValue(nameIndex, length, input.u2())
            AttributeKinds.STACK_MAP_TABLE -> StackMapTable(nameIndex, length, readList(input.u2()) { readFrame() })
            AttributeKinds.BOOTSTRAP_METHODS -> BootstrapMethods(nameIndex, length, readList(input.u2()) {
                val ref = input.u2()
                BootstrapMethods.BootstrapMethod(ref, readU2List())
            })
            AttributeKinds.INNER_CLASSES -> InnerClasses(nameIndex, length, readList(input.u2()) {
                InnerClasses.InnerClassEntry(input.u2(), input.u2(), input.u2(), input.u2())
            })
            AttributeKinds.ENCLOSING_METHOD -> EnclosingMethod(nameIndex, length, input.u2(), input.u2())
            AttributeKinds.SIGNATURE -> Signature(nameIndex, length, input.u2())
            AttributeKinds.SOURCE_DEBUG_EXTENSION -> SourceDebugExtension(nameIndex, length, input.bytes(input.remaining))
            AttributeKinds.LOCAL_VARIABLE_TYPE_TABLE -> LocalVariableTypeTable(nameIndex, length, readList(input.u2()) {
                LocalVariableTypeTable.LocalVariableTypeTableEntry(input.u2(), input.u2(), input.u2(), input.u2(), input.u2())
            })
            AttributeKinds.METHOD_PARAMETERS -> MethodParameters(nameIndex, length, readList(input.u1()) {
                MethodParameters.MethodParameter(input.u2(), input.u2())
            })
            AttributeKinds.ANNOTATIONS -> RuntimeVisibleAnnotations(nameIndex, length, readList(input.u2()) {
                annotationReader.readAnnotation()
            })
            AttributeKinds.PARAMETER_ANNOTATIONS -> RuntimeVisibleParameterAnnotations(nameIndex, length, readList(input.u1()) {
                RuntimeVisibleParameterAnnotations.ParameterAnnotation(readList(input.u2()) { annotationReader.readAnnotation() })
            })
            AttributeKinds.TYPE_ANNOTATIONS -> RuntimeVisibleTypeAnnotations(nameIndex, length, readList(input.u2()) {
                readTypeAnnotation()
            })
            AttributeKinds.ANNOTATION_DEFAULT -> AnnotationDefault(nameIndex, length, annotationReader.readElementValue())
            AttributeKinds.MODULE -> readModule(nameIndex, length)
            AttributeKinds.MODULE_PACKAGES -> ModulePackages(nameIndex, length, readU2List())
            AttributeKinds.MODULE_MAIN_CLASS -> ModuleMainClass(nameIndex, length, input.u2())
            AttributeKinds.NEST_HOST -> NestHost(nameIndex, length, input.u2())
            AttributeKinds.NEST_MEMBERS -> NestMembers(nameIndex, length, readU2List())
            AttributeKinds.RECORD -> Record(nameIndex, length, readList(input.u2()) {
                val componentName = input.u2()
                val descriptor = input.u2()
                location.pushRecordComponent(componentName)
                val attributes = readAttributes(AttributeKinds.IN_RECORD_COMPONENT)
                location.pop()
                Record.RecordComponentInfo(componentName, descriptor, attributes)
            })
            AttributeKinds.PERMITTED_SUBCLASSES -> PermittedSubclasses(nameIndex, length, readU2List())
            AttributeKinds.SYNTHETIC -> Synthetic(nameIndex, length)
            AttributeKinds.DEPRECATED -> Deprecated(nameIndex, length)
            else -> throw IllegalStateException("Вид атрибута $kind не обработан")
        }
    }

    /** u2 count + u2[count] */
    private fun readU2List(): List<Int> = readList(input.u2()) { input.u2() }

    private fun readCode(nameIndex: Int, length: Long): Code {
        val maxStack = input.u2()
        val maxLocals = input.u2()
        val codeLength = input.checkLength(input.u4(), "code_length")
        val code = input.bytes(codeLength)
        val exceptionTable = readList(input.u2()) {
            ExceptionTableEntry(input.u2(), input.u2(), input.u2(), input.u2())
        }
        val attributes = readAttributes(AttributeKinds.IN_CODE)
        return Code(nameIndex, length, maxStack, maxLocals, code, exceptionTable, attributes)
    }

    private fun readFrame(): StackMapFrame {
        val frameType = input.u1()
        return when {
            frameType < 64 -> SameFrame(frameType)
            frameType < 128 -> SameLocals1StackItemFrame(frameType, readVerificationType())
            frameType < 247 -> throw ClassFormatException("зарезервированный тип фрейма StackMapTable: $frameType")
            frameType == 247 -> SameLocals1StackItemFrameExtended(frameType, input.u2(), readVerificationType())
            frameType < 251 -> ChopFrame(frameType, input.u2())
            frameType == 251 -> SameFrameExtended(frameType, input.u2())
            frameType < 255 -> {
                val offsetDelta = input.u2()
                AppendFrame(frameType, offsetDelta, readList(frameType - 251) { readVerificationType() })
            }
            else -> {
                val offsetDelta = input.u2()
                val locals = readList(input.u2()) { readVerificationType() }
                FullFrame(frameType, offsetDelta, locals, readList(input.u2()) { readVerificationType() })
            }
        }
    }

    private fun readVerificationType(): VerificationTypeInfo {
        val tag = input.u1()
        return when (tag) {
            VerificationTypeInfo.ITEM_Object, VerificationTypeInfo.ITEM_Uninitialized -> VerificationTypeInfo(tag, input.u2())
            in 0..6 -> VerificationTypeInfo.simple(tag)
            else -> throw ClassFormatException("неизвестный тег verification_type_info: $tag")
        }
    }

    private fun readTypeAnnotation(): TypeAnnotation {
        val targetType = input.u1()
        val targetInfo: TargetInfo = when (targetType) {
            0x00, 0x01 -> TypeParameterTarget(input.u1())
            0x10 -> SuperTypeTarget(input.u2())
            0x11, 0x12 -> TypeParameterBoundTarget(input.u1(), input.u1())
            0x13, 0x14, 0x15 -> EmptyTarget
            0x16 -> FormalParameterTarget(input.u1())
            0x17 -> ThrowsTarget(input.u2())
            0x40, 0x41 -> LocalvarTarget(readList(input.u2()) {
                LocalvarTarget.LocalvarEntry(input.u2(), input.u2(), input.u2())
            })
            0x42 -> CatchTarget(input.u2())
            0x43, 0x44, 0x45, 0x46 -> OffsetTarget(input.u2())
            0x47, 0x48, 0x49, 0x4A, 0x4B -> TypeArgumentTarget(input.u2(), input.u1())
            else -> throw ClassFormatException("неизвестный target_type аннотации типа: 0x%02X".format(targetType))
        }
        val pathLength = input.u1()
        val typePath = if (pathLength == 0) TypePath.EMPTY else TypePath(readList(pathLength) {
            TypePath.TypePathEntry(input.u1(), input.u1())
        })
        val typeIndex = input.u2()
        return TypeAnnotation(targetType, targetInfo, typePath, typeIndex, annotationReader.readElementValuePairs())
    }

    private fun readModule(nameIndex: Int, length: Long): Module {
        val moduleName = input.u2()
        val moduleFlags = input.u2()
        val moduleVersion = input.u2()
        val requires = readList(input.u2()) {
            Module.RequiresEntry(input.u2(), input.u2(), input.u2())
        }
        val exports = readList(input.u2()) {
            val index = input.u2()
            val flags = input.u2()
            Module.ExportsEntry(index, flags, readU2List())
        }
        val opens = readList(input.u2()) {
            val index = input.u2()
            val flags = input.u2()
            Module.OpensEntry(index, flags, readU2List())
        }
        val uses = readU2List()
        val provides = readList(input.u2()) {
            val index = input.u2()
            Module.ProvidesEntry(index, readU2List())
        }
        return Module(nameIndex, length, moduleName, moduleFlags, moduleVersion, requires, exports, opens, uses, provides)
    }
}
