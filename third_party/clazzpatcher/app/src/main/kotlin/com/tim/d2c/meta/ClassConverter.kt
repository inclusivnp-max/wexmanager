package com.tim.d2c.meta

import com.tim.casm.CasmState
import com.tim.casm.builder.ClassFileBuilder
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.model.ClassInfo
import com.tim.casm.model.FieldInfo
import com.tim.casm.model.InnerClassSpec
import com.tim.casm.model.MethodInfo
import com.tim.casm.model.MethodParameterSpec
import com.tim.casm.model.RecordComponentSpec
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.SourceDebugExtension
import com.tim.clazz.util.ModifiedUtf8
import com.tim.d2c.D2cOptions
import com.tim.d2c.bridge.ClassIndex
import com.tim.d2c.bridge.ConstructorBridges
import com.tim.d2c.bridge.ProtectedAccessors
import com.tim.d2c.model.DexView
import com.tim.d2c.translate.MethodTranslator
import com.tim.d2c.types.MethodContext
import com.tim.d2c.types.TypeOps
import com.tim.dasm.disassembler.DexDisassembler
import com.tim.dasm.model.Ins21c
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.Opcode
import com.tim.dex.model.ClassDataItem
import com.tim.dex.model.EncodedField
import com.tim.dex.model.EncodedMethod
import com.tim.dex.model.EncodedValue

/** Один class_def → [ClassFile] (флаги, поля, методы, атрибуты из системных аннотаций). */
internal class ClassConverter(
    private val view: DexView,
    private val hierarchy: ClassHierarchy,
    private val options: D2cOptions,
    /** Классы всех DEX сеанса (суперклассы). */
    private val index: ClassIndex,
    /** Вложенность классов всех DEX сеанса (InnerClasses, EnclosingMethod). */
    private val nested: NestedClasses,
    /** Планировщик конструкторов-переходников общего для всех DEX сеанса (null — без переходников). */
    private val bridges: ConstructorBridges? = null,
    /** Методы доступа к protected-членам (null — не строить). */
    private val accessors: ProtectedAccessors? = null
) {
    private var currentClass = ""
    private val annotations = AnnotationConverter(view, if (options.strict) null else { m -> options.warnings("$currentClass: $m") })

    fun convert(defIdx: Int): ClassFile {
        val def = view.classDef(defIdx)
        val descriptor = view.type(def.classIdx)
        currentClass = descriptor
        try {
            return build(defIdx, descriptor)
        } catch (e: Exception) {
            throw IllegalStateException("Класс $descriptor: ${e.message}", e)
        }
    }

    private fun build(defIdx: Int, descriptor: String): ClassFile {
        val def = view.classDef(defIdx)
        val name = DexView.internalName(descriptor)
        val state = CasmState(emptyClass(options.majorVersion)).also { it.hierarchy = hierarchy }
        val lenient: ((String) -> Unit)? = if (options.strict) null else options.warnings
        val translator = MethodTranslator(view, state, TypeOps(hierarchy), options.debugInfo, bridges, accessors, lenient,
            warn = { options.warnings(it) })

        val dir = view.annotationsDirectory(def)
        val classSystem = SystemInfo()
        val classAnnotations = annotations.convert(view.annotationSet(dir?.classAnnotationsSetIdx ?: -1), classSystem)
        val fieldSets = dir?.fieldAnnotations?.associate { it.fieldIdx to it.annotationsSetIdx } ?: emptyMap()
        val methodSets = dir?.methodAnnotations?.associate { it.methodIdx to it.annotationsSetIdx } ?: emptyMap()
        val paramSets = dir?.parameterAnnotations?.associate { it.methodIdx to it.annotationsSetRefListIdx } ?: emptyMap()

        val data = if (def.classDataIdx >= 0) view.dex.classDataItems[def.classDataIdx] else null
        val staticValues = if (def.staticValuesIdx >= 0) view.dex.encodedArrayItems[def.staticValuesIdx] else emptyList()

        // Порядок как у Class.getDeclaredFields в ART: сначала поля экземпляра, затем статические.
        val fields = ArrayList<FieldInfo>()
        val assigned = if (staticValues.isEmpty()) emptySet() else assignedInClinit(data, descriptor)
        data?.instanceFields?.forEach { f -> fields.add(field(f, fieldSets, null, static = false, assigned)) }
        data?.staticFields?.forEachIndexed { k, f -> fields.add(field(f, fieldSets, staticValues.getOrNull(k), static = true, assigned)) }

        val isInterface = def.accessFlags and ACC_INTERFACE != 0
        val methods = ArrayList<MethodInfo>()
        val declared = HashSet<Int>()
        for (m in (data?.directMethods.orEmpty() + data?.virtualMethods.orEmpty())) {
            // Повтор method_idx: ART берёт первое определение, JVM отвергла бы класс (ClassFormatError).
            if (!declared.add(m.methodIdx)) {
                val ref = view.method(m.methodIdx)
                options.warnings("$descriptor: повторное определение ${ref.name}${ref.proto.descriptor} пропущено (действует первое, как в ART)")
                continue
            }
            methods.add(method(m, descriptor, methodSets, paramSets, classSystem, translator, isInterface, state))
        }

        // Компактный fill-array-data (очень большие методы): методы раскладки данных — в сам класс.
        val helpers = translator.fillHelpers.map { com.tim.d2c.translate.FillHelpers.method(state, name, it, isInterface) }

        val superName = if (def.superclassIdx >= 0) DexView.internalName(view.type(def.superclassIdx)) else null
        val info = ClassInfo(
            name = name,
            superClass = superName,
            accessFlags = classFlags(def.accessFlags),
            sourceFile = if (def.sourceFileIdx >= 0) view.string(def.sourceFileIdx) else null,
            signature = classSystem.signature,
            interfaces = (view.typeList(def.interfacesTypeListIdx).map { DexView.internalName(it) } + translator.extraInterfaces).distinct(),
            annotations = classAnnotations,
            typeAnnotations = emptyList(),
            fields = fields,
            methods = methods + helpers,
            recordComponents = classSystem.recordNames.mapIndexed { k, n ->
                RecordComponentSpec(
                    n, classSystem.recordTypes.getOrElse(k) { "Ljava/lang/Object;" },
                    classSystem.recordSignatures.getOrNull(k), classSystem.recordAnnotations.getOrNull(k).orEmpty()
                )
            },
            isRecord = classSystem.isRecord,
            nestHost = classSystem.nestHost,
            nestMembers = classSystem.nestMembers,
            permittedSubclasses = classSystem.permittedSubclasses,
            enclosingMethod = enclosingMethod(descriptor, classSystem),
            innerClasses = innerClasses(descriptor, classSystem)
        )
        // Атрибуты, которые HotSpot читает только с версии своего появления (в более старой — игнорирует).
        if (info.nestHost != null || info.nestMembers.isNotEmpty()) state.requireVersion(55)
        if (classSystem.isRecord) state.requireVersion(60)
        if (info.permittedSubclasses.isNotEmpty()) state.requireVersion(61)
        val sdeName = classSystem.sourceDebugExtension?.let { state.pool.utf8("SourceDebugExtension") }
        val cf = ClassFileBuilder(state).materializeClass(info)
        if (sdeName == null) return cf
        // SourceDebugExtension (SMAP Kotlin и т. п.) — атрибут без модели в casm: добавляем после сборки.
        val bytes = ModifiedUtf8.encode(classSystem.sourceDebugExtension!!)
        return cf.copy(attributes = cf.attributes + SourceDebugExtension(sdeName, bytes.size.toLong(), bytes))
    }

    // ---- Поля ----

    private fun field(f: EncodedField, sets: Map<Int, Int>, value: EncodedValue?, static: Boolean, assigned: Set<String>): FieldInfo {
        val ref = view.field(f.fieldIdx)
        val system = SystemInfo()
        val anns = annotations.convert(view.annotationSet(sets[f.fieldIdx] ?: -1), system)
        // static_values идут подряд: d8 дополняет нулями поля до последней настоящей константы. Нуль — это
        // константа только у final-поля, которое <clinit> не присваивает; иначе ConstantValue 0 подставил бы
        // javac в код, собранный против результата (у static final), и показали бы декомпиляторы.
        val keep = value != null && (!isDefault(value) || (f.accessFlags and ACC_FINAL != 0 && "${ref.name}:${ref.type}" !in assigned))
        val constant = if (static && keep) {
            try {
                constantValue(value, ref.type, ref.name)
            } catch (e: IllegalStateException) {
                if (options.strict) throw e
                options.warnings("$currentClass: ${e.message} — значение не перенесено")
                null
            }
        } else null
        return FieldInfo(ref.name, ref.type, f.accessFlags and FIELD_FLAGS, anns, emptyList(), constant, system.signature)
    }

    /** Значение по умолчанию поля JVM (null, 0, false, +0.0). */
    private fun isDefault(v: EncodedValue): Boolean = when (v.type) {
        EncodedValueType.VALUE_NULL -> true
        EncodedValueType.VALUE_BOOLEAN -> v.value == false
        EncodedValueType.VALUE_BYTE, EncodedValueType.VALUE_SHORT, EncodedValueType.VALUE_INT,
        EncodedValueType.VALUE_LONG -> (v.value as Number).toLong() == 0L
        EncodedValueType.VALUE_CHAR -> (v.value as Char).code == 0
        EncodedValueType.VALUE_FLOAT -> (v.value as Float).toRawBits() == 0
        EncodedValueType.VALUE_DOUBLE -> (v.value as Double).toRawBits() == 0L
        else -> false
    }

    /** Статические поля класса, которые <clinit> присваивает (`имя:тип`). */
    private fun assignedInClinit(data: ClassDataItem?, owner: String): Set<String> {
        val clinit = data?.directMethods?.firstOrNull { view.method(it.methodIdx).name == "<clinit>" } ?: return emptySet()
        if (clinit.codeIdx < 0) return emptySet()
        val out = HashSet<String>()
        for (insn in DexDisassembler().decode(view.dex.codeItems[clinit.codeIdx].insns).instructions) {
            if (insn.opcode.value !in Opcode.SPUT.value..Opcode.SPUT_SHORT.value) continue
            val f = view.field((insn as Ins21c).index)
            if (f.owner == owner) out.add("${f.name}:${f.type}")
        }
        return out
    }

    /** static_values → значение ConstantValue по типу поля (null — значение по умолчанию). */
    private fun constantValue(v: EncodedValue, type: String, name: String): Any? {
        if (v.type == EncodedValueType.VALUE_NULL) return null
        val x = v.value
        val result: Any? = when (type) {
            "Z", "B", "S", "C", "I" -> when (x) {
                is Int -> x
                is Byte -> x.toInt()
                is Short -> x.toInt()
                is Char -> x.code
                is Boolean -> if (x) 1 else 0
                else -> null
            }
            "J" -> x as? Long ?: (x as? Int)?.toLong()
            "F" -> x as? Float
            "D" -> x as? Double
            "Ljava/lang/String;" -> if (v.type == EncodedValueType.VALUE_STRING) view.string(x as Int) else null
            else -> null
        }
        return result ?: throw IllegalStateException(
            "поле $name:$type: начальное значение типа 0x${Integer.toHexString(v.type)} не выражается через ConstantValue"
        )
    }

    // ---- Методы ----

    private fun method(
        m: EncodedMethod,
        owner: String,
        sets: Map<Int, Int>,
        paramSets: Map<Int, Int>,
        classSystem: SystemInfo,
        translator: MethodTranslator,
        ownerIsInterface: Boolean,
        state: CasmState
    ): MethodInfo {
        val ref = view.method(m.methodIdx)
        val where = "${ref.name}${ref.proto.descriptor}"
        try {
            val system = SystemInfo()
            val anns = annotations.convert(view.annotationSet(sets[m.methodIdx] ?: -1), system)
            val paramAnns = view.annotationSetRefList(paramSets[m.methodIdx] ?: -1).map { set ->
                annotations.convert(set, SystemInfo())
            }
            val flags = methodFlags(m.accessFlags)
            val params = system.parameterNames?.let { names ->
                names.mapIndexed { k, n -> MethodParameterSpec(n, system.parameterFlags?.getOrNull(k) ?: 0) }
            } ?: emptyList()
            val annotationDefault = classSystem.annotationDefaults[ref.name]
            if (m.codeIdx < 0) {
                if (flags and (ACC_ABSTRACT or ACC_NATIVE) == 0) throw IllegalStateException("метод без кода не abstract и не native")
                return MethodInfo(
                    name = ref.name, descriptor = ref.proto.descriptor, accessFlags = flags,
                    annotations = anns, parameterAnnotations = paramAnns, signature = system.signature,
                    exceptions = system.exceptions, annotationDefault = annotationDefault,
                    methodParameters = params, hasCode = false
                )
            }
            if (ownerIsInterface) state.requireVersion(52) // default/static/private методы интерфейсов — Java 8+
            val ctx = MethodContext(owner, ref.name, ref.proto, flags and ACC_STATIC != 0, index.superOf(owner))
            val a = try {
                translator.translate(ctx, view.dex.codeItems[m.codeIdx])
            } catch (e: Exception) {
                if (options.strict) throw e
                options.warnings("$owner->$where: ${e.message} — тело заменено на throw VerifyError")
                options.degraded(owner)
                translator.unsupported(ctx, e.message ?: e.toString())
            }
            return MethodInfo(
                name = ref.name, descriptor = ref.proto.descriptor, accessFlags = flags,
                code = a.code, maxStack = a.maxStack, maxLocals = a.maxLocals,
                exceptionTable = a.exceptionTable, lineNumbers = a.lineNumbers,
                localVariables = a.localVariables
                    .filter { it.startPc < a.code.size && it.length >= 0 }
                    .distinctBy { listOf(it.startPc, it.length, it.name, it.index) },
                annotations = anns, parameterAnnotations = paramAnns, signature = system.signature,
                exceptions = system.exceptions, annotationDefault = annotationDefault,
                stackMapFrames = a.stackMapFrames, methodParameters = params, hasCode = true
            )
        } catch (e: Exception) {
            throw IllegalStateException("Метод $where: ${e.message}", e)
        }
    }

    // ---- Вложенные классы ----

    /**
     * EnclosingMethod: для локальных и анонимных классов. Член другого класса (есть в его
     * MemberClasses) атрибута не получает.
     */
    private fun enclosingMethod(descriptor: String, s: SystemInfo): Pair<String, String?>? {
        s.enclosingMethod?.let { m -> return DexView.internalName(m.owner) to (m.name + m.proto.descriptor) }
        val outer = s.enclosingClass ?: return null
        if (s.isInner && s.innerName != null && isMemberOf(descriptor, outer) != false) return null
        return DexView.internalName(outer) to null
    }

    /** true/false — известно по MemberClasses внешнего класса, null — внешнего класса нет среди DEX сеанса. */
    private fun isMemberOf(descriptor: String, outer: String): Boolean? =
        nested.systemOf(outer)?.let { descriptor in it.memberClasses }

    /** Внешний класс, членом которого является [type] (null — локальный, анонимный или не вложенный). */
    private fun memberOuter(type: String, s: SystemInfo): String? =
        s.enclosingClass?.takeIf { s.innerName != null && isMemberOf(type, it) != false }

    /** Запись InnerClasses о классе [type] — такая же, как в его собственном атрибуте (null — не вложенный). */
    private fun entryOf(type: String, s: SystemInfo): InnerClassSpec? {
        if (!s.isInner) return null
        val outer = memberOuter(type, s)
        return InnerClassSpec(DexView.internalName(type), outer?.let { DexView.internalName(it) }, s.innerName, innerFlags(s.innerFlags))
    }

    private fun innerClasses(descriptor: String, s: SystemInfo): List<InnerClassSpec> {
        val out = LinkedHashMap<String, InnerClassSpec>()
        // Сам класс и цепочка его внешних классов.
        var cur = descriptor
        var info = s
        var guard = 0
        while (guard++ < 64) {
            out[cur] = entryOf(cur, info) ?: break
            val next = memberOuter(cur, info) ?: info.enclosingMethod?.owner ?: info.enclosingClass ?: break
            cur = next
            info = nested.systemOf(next) ?: break
        }
        // Классы-члены.
        for (member in s.memberClasses) {
            if (member in out) continue
            val ms = nested.systemOf(member)
            out[member] = ms?.let { entryOf(member, it) } ?: InnerClassSpec(
                DexView.internalName(member), DexView.internalName(descriptor),
                member.substringAfterLast('$').removeSuffix(";"), innerFlags(0)
            )
        }
        // Локальные и анонимные классы (и члены, которых нет в MemberClasses): для getDeclaringClass,
        // getEnclosingClass и т. п. HotSpot требует, чтобы и внешний класс перечислял их (как у javac).
        for (inner in nested.enclosedIn(descriptor)) {
            if (inner in out) continue
            out[inner] = nested.systemOf(inner)?.let { entryOf(inner, it) } ?: continue
        }
        return out.values.toList()
    }

    // ---- Флаги ----

    private fun classFlags(dexFlags: Int): Int {
        var f = dexFlags and CLASS_FLAGS
        if (f and ACC_INTERFACE == 0) f = f or ACC_SUPER
        else f = (f or ACC_ABSTRACT) and (ACC_FINAL or ACC_SUPER or ACC_ENUM).inv() // JVMS §4.1: интерфейс всегда abstract
        return f
    }

    /** Флаги записи InnerClasses (JVMS §4.7.6); у интерфейса обязателен ACC_ABSTRACT. */
    private fun innerFlags(flags: Int): Int {
        var f = flags and INNER_FLAGS
        if (f and ACC_INTERFACE != 0) f = (f or ACC_ABSTRACT) and (ACC_FINAL or ACC_ENUM).inv()
        return f
    }

    /**
     * Флаги метода: ACC_CONSTRUCTOR и ACC_DECLARED_SYNCHRONIZED — только DEX. У «declared synchronized»
     * методов тело уже содержит monitor-enter/exit (так компилируют dx/d8), поэтому ACC_SYNCHRONIZED
     * не ставится: иначе JVM захватила бы монитор дважды. У native ACC_SYNCHRONIZED сохраняется.
     */
    private fun methodFlags(dexFlags: Int): Int {
        var f = dexFlags and METHOD_FLAGS
        if (f and ACC_NATIVE == 0) f = f and ACC_SYNCHRONIZED.inv()
        return f
    }

    private fun emptyClass(major: Int) = ClassFile(
        magic = 0xCAFEBABEL, minorVersion = 0, majorVersion = major, constantPool = listOf(null),
        accessFlags = 0, thisClass = 0, superClass = 0, interfaces = emptyList(),
        fields = emptyList(), methods = emptyList(), attributes = emptyList()
    )

    private companion object {
        const val ACC_FINAL = 0x0010
        const val ACC_ENUM = 0x4000
        const val ACC_STATIC = 0x0008
        const val ACC_SUPER = 0x0020
        const val ACC_SYNCHRONIZED = 0x0020
        const val ACC_NATIVE = 0x0100
        const val ACC_INTERFACE = 0x0200
        const val ACC_ABSTRACT = 0x0400
        /** public, final, interface, abstract, synthetic, annotation, enum. */
        const val CLASS_FLAGS = 0x7611
        /** public … enum (без DEX-флагов 0x10000/0x20000). */
        const val FIELD_FLAGS = 0x50DF
        /** Флаги вложенного класса: доступ, static, final, interface, abstract, synthetic, annotation, enum. */
        const val INNER_FLAGS = 0x761F
        const val METHOD_FLAGS = 0x1DFF
    }
}
