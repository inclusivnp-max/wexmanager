package com.tim.c2d

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.Descriptors
import com.tim.c2d.bytecode.JsrInliner
import com.tim.c2d.meta.AccessFlagsMapper
import com.tim.c2d.meta.AnnotationConverter
import com.tim.c2d.meta.NestAccess
import com.tim.c2d.meta.StaticValues
import com.tim.c2d.meta.SystemAnnotations
import com.tim.c2d.translate.ClassContext
import com.tim.c2d.translate.MethodTranslator
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.FieldInfo
import com.tim.clazz.model.MethodInfo
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.SourceFile
import com.tim.dasm.DexAsm
import com.tim.dasm.dsl.ClassDsl
import com.tim.dasm.model.AccessFlags
import com.tim.dasm.model.ClassInfo
import com.tim.dex.data.Opcode

/**
 * Один класс JVM → собранный класс DexAsm ([ClassInfo] в пулах [asm]): заголовок, поля, методы, аннотации.
 *
 * Атрибуты без представления в DEX не переносятся: типовые аннотации, SourceDebugExtension, Deprecated,
 * NestHost/NestMembers (доступ внутри гнезда переводится через [NestAccess], как у d8).
 */
internal class ClassConverter(
    private val asm: DexAsm,
    private val cf: ClassFile,
    private val options: C2dOptions,
    private val nest: NestAccess = NestAccess.NONE,
    /** Классы лямбд из рассахаривания (см. [com.tim.c2d.desugar.LambdaFactory]). */
    private val lambdas: Map<String, com.tim.c2d.desugar.LambdaFactory> = emptyMap()
) {

    private val pool = ConstantPoolView(cf)
    private val annotations = AnnotationConverter(asm, pool, options.invisibleAnnotations)
    private val system = SystemAnnotations(asm, pool, annotations)
    private val className = cf.thisClassName
    private val isInterface = cf.accessFlags and AccessFlags.INTERFACE != 0

    fun convert(): ClassInfo {
        val privateMethods = HashSet<String>()
        for (m in cf.methods) {
            if (m.accessFlags and AccessFlags.PRIVATE != 0) privateMethods.add(cf.utf8(m.nameIndex) + cf.utf8(m.descriptorIndex))
        }
        val specialCopies = specialSelfCalls()
        val context = ClassContext(asm, pool, options, privateMethods, nest, specialCopies, lambdas)
        // Фабрика класса лямбды встроена во все места вызова (единственное — в хозяине): в DEX не нужна.
        val isLambda = className in lambdas
        return asm.buildClass(Descriptors.ofClass(className)) {
            superClass(cf.superClassName?.let { Descriptors.ofClass(it) })
            for (i in cf.interfaces) implements(pool.classDescriptor(i))
            accessFlags(AccessFlagsMapper.classFlags(cf.accessFlags, cf.attributes))
            if (options.debugInfo) {
                (cf.attributes.firstOrNull { it is SourceFile } as SourceFile?)?.let { sourceFile(cf.utf8(it.sourceFileIndex)) }
            }
            for (a in annotations.annotations(cf.attributes)) annotation(a)
            system.signature(cf.attributes)?.let { annotation(it) }
            for (a in system.classAnnotations()) annotation(a)

            for (f in cf.fields) field(f)
            for (m in cf.methods) {
                if (isLambda && cf.utf8(m.nameIndex) == com.tim.c2d.desugar.LambdaFactory.FACTORY) continue
                method(m, context)
            }
            for (m in cf.methods) {
                val copy = specialCopies[cf.utf8(m.nameIndex) + cf.utf8(m.descriptorIndex)] ?: continue
                method(m, context, copyName = copy)
            }
            for (b in nest.bridgesOf(className)) bridge(b)
        }
    }

    private fun ClassDsl.field(f: FieldInfo) {
        val name = cf.utf8(f.nameIndex)
        val type = cf.utf8(f.descriptorIndex)
        try {
            val items = annotations.annotations(f.attributes) + listOfNotNull(system.signature(f.attributes))
            val isStatic = f.accessFlags and AccessFlags.STATIC != 0
            val initial = if (isStatic) StaticValues.of(asm, cf, type, f.attributes) else null
            var flags = AccessFlagsMapper.fieldFlags(f.accessFlags, f.attributes)
            if (nest.isFieldWidened(className, name, type)) flags = widened(flags)
            field(name, type, flags, items, initial)
        } catch (e: RuntimeException) {
            throw ConversionException("Класс $className, поле $name:$type: ${e.message}", e)
        }
    }

    /**
     * Методы класса с кодом, которые сам класс вызывает через invokespecial (не `<init>`, не private,
     * не static): `имя + дескриптор` → имя private-копии (свободное в классе). Так делает и d8.
     */
    private fun specialSelfCalls(): Map<String, String> {
        val declared = HashMap<String, MethodInfo>()
        for (m in cf.methods) declared[cf.utf8(m.nameIndex) + cf.utf8(m.descriptorIndex)] = m
        val out = LinkedHashMap<String, String>()
        for (m in cf.methods) {
            val code = m.attributes.firstOrNull { it is Code } as Code? ?: continue
            for (insn in com.tim.c2d.bytecode.BytecodeDecoder.decode(code.code).insns) {
                if (insn.opcode != com.tim.clazz.model.opcodes.Opcodes.INVOKESPECIAL) continue
                val ref = pool.member(insn.a)
                if (ref.owner != className || ref.name == "<init>") continue
                val key = ref.name + ref.descriptor
                val target = declared[key] ?: continue
                if (target.accessFlags and (AccessFlags.PRIVATE or AccessFlags.STATIC) != 0) continue
                if (target.attributes.none { it is Code } || key in out) continue
                var copy = "-\$\$Special\$" + ref.name
                while ((copy + ref.descriptor) in declared) copy += "$"
                out[key] = copy
            }
        }
        return out
    }

    private fun ClassDsl.method(m: MethodInfo, context: ClassContext, copyName: String? = null) {
        val name = cf.utf8(m.nameIndex)
        val desc = cf.utf8(m.descriptorIndex)
        try {
            val type = Descriptors.method(desc)
            // jsr/ret возможны только до версии 51 (JVMS §4.9.1): подпрограммы встраиваются.
            val code = (m.attributes.firstOrNull { it is Code } as Code?)
                ?.let { if (cf.majorVersion < 51 && JsrInliner.uses(it)) JsrInliner.inline(it) else it }
            val isStatic = m.accessFlags and AccessFlags.STATIC != 0
            var flags = AccessFlagsMapper.methodFlags(m.accessFlags, name, m.attributes)
            if (nest.isMethodWidened(className, name, desc)) flags = widened(flags)
            // Копия для invokespecial: private synthetic (прочие флаги доступа и аннотации — только у оригинала).
            if (copyName != null) {
                flags = (flags and (AccessFlags.PUBLIC or AccessFlags.PROTECTED or AccessFlags.PRIVATE or AccessFlags.FINAL).inv()) or
                    AccessFlags.PRIVATE or AccessFlags.SYNTHETIC
            }
            method(copyName ?: name) {
                returnType(type.returnType)
                for (p in type.params) param(p)
                accessFlags(flags)
                if (copyName == null) {
                    for (a in annotations.annotations(m.attributes)) annotation(a)
                    system.signature(m.attributes)?.let { annotation(it) }
                    system.throwsOf(m.attributes)?.let { annotation(it) }
                    system.methodParameters(m.attributes)?.let { annotation(it) }
                    for ((index, items) in annotations.parameterAnnotations(m.attributes, type.params.size)) {
                        for (item in items) paramAnnotation(index, item)
                    }
                }
                if (code != null) {
                    MethodTranslator(
                        context, name, desc, isStatic,
                        AccessFlagsMapper.isSynchronizedCode(m.accessFlags), code, this
                    ).translate()
                }
            }
        } catch (e: ConversionException) {
            throw e
        } catch (e: RuntimeException) {
            throw ConversionException("Класс $className, метод $name$desc: ${e.message}", e)
        }
    }

    /** Доступ к члену открыт соседям по гнезду: без private (у интерфейса — public). */
    private fun widened(flags: Int): Int {
        val open = flags and AccessFlags.PRIVATE.inv()
        return if (isInterface) open or AccessFlags.PUBLIC else open
    }

    /**
     * Статический мост `-$$Nest$m<имя>(владелец, параметры…)` к private-методу экземпляра:
     * invoke-direct изнутри класса и возврат результата.
     */
    private fun ClassDsl.bridge(b: NestAccess.Bridge) {
        val target = Descriptors.method(b.descriptor)
        val ownerDesc = Descriptors.ofClass(b.owner)
        val ins = 1 + target.paramWords
        val result = Descriptors.width(target.returnType)
        method(b.bridgeName) {
            returnType(target.returnType)
            param(ownerDesc)
            for (p in target.params) param(p)
            accessFlags(AccessFlags.STATIC or AccessFlags.SYNTHETIC or if (isInterface) AccessFlags.PUBLIC else 0)
            registers(result + ins)
            val methodIdx = asm.methods.add(ownerDesc, b.name, target.params, target.returnType)
            instructions {
                if (ins <= 5 && result + ins <= 16) invoke(Opcode.INVOKE_DIRECT, methodIdx, *IntArray(ins) { result + it })
                else invokeRange(Opcode.INVOKE_DIRECT_RANGE, methodIdx, result, ins)
                when (target.returnType[0]) {
                    'V' -> returnVoid()
                    'J', 'D' -> { moveResultWide(0); returnWide(0) }
                    'L', '[' -> { moveResultObject(0); returnObject(0) }
                    else -> { moveResult(0); returnValue(0) }
                }
            }
        }
    }
}
