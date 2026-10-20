package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.assembler.CodeItemBuilder
import com.tim.dasm.model.AccessFlags
import com.tim.dasm.model.ClassInfo
import com.tim.dasm.model.FieldInfo
import com.tim.dasm.model.MethodInfo
import com.tim.dasm.util.HierarchyOrder
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.ClassDataItem
import com.tim.dex.model.ClassDefItem
import com.tim.dex.model.EncodedField
import com.tim.dex.model.EncodedMethod
import com.tim.dex.model.EncodedValue


/**
 * Значение hidden API членов без явных флагов в классе, где флаги есть: 0 (whitelist/sdk — открытый API).
 * 7 означало бы «нет списка» в Android 10/11 (такой файл верификатор отвергает), а в 12+ — max-target-s.
 */
private const val HIDDEN_API_NO_RESTRICTIONS = 0

class DexDslContext(private val asm: DexAsm) {

    private val classes = mutableListOf<ClassInfo>()
    private val replacements = mutableListOf<ClassInfo>()
    private val patches = mutableListOf<() -> Unit>()

    fun createClass(name: String, block: ClassDsl.() -> Unit) {
        val dsl = ClassDsl(name, asm)
        dsl.block()
        classes.add(dsl.build())
    }

    /** Класс, собранный заранее ([DexAsm.buildClass]) в этом же DexAsm или перенесённый в него. */
    fun addClass(info: ClassInfo) {
        classes.add(info)
    }

    /**
     * Класс ([DexAsm.buildClass] или перенесённый) вместо уже определённого в DEX одноимённого: class_def
     * остаётся на своём месте, прежние class_data, код, аннотации и static_values становятся недостижимыми
     * и в файл не пишутся ([com.tim.dex.writer.DexWriter] пишет только достижимое).
     */
    fun replaceClass(info: ClassInfo) {
        requireNotNull(asm.classes.find(info.name)) { "Класс ${info.name} не определён — заменять нечего" }
        replacements.add(info)
    }

    fun patchClass(name: String, block: ClassPatchDsl.() -> Unit) {
        val dsl = ClassPatchDsl(name, asm)
        dsl.block()
        patches.add { dsl.apply() }
    }

    internal fun apply() {
        val ordered = HierarchyOrder.sort(classes, { it.name }, { listOfNotNull(it.superClass) + it.interfaces })
        for (info in ordered) {
            try {
                materializeClass(info)
            } catch (e: Exception) {
                throw IllegalStateException("Класс ${info.name}: ${e.message}", e)
            }
        }
        for (info in replacements) {
            try {
                materializeClass(info, asm.classes.find(info.name))
            } catch (e: Exception) {
                throw IllegalStateException("Класс ${info.name}: ${e.message}", e)
            }
        }
        for (patch in patches) patch()
    }

    /** [replaceIdx] — class_def, на место которого встаёт класс; null — новый class_def. */
    private fun materializeClass(info: ClassInfo, replaceIdx: Int? = null) {
        val interfacesListIdx = if (info.interfaces.isNotEmpty()) {
            asm.typeLists.add(info.interfaces.map { asm.types.add(it) })
        } else -1
        val sourceFileIdx = info.sourceFile?.let { asm.strings.add(it) } ?: -1
        val isInterface = AccessFlags.hasFlag(info.accessFlags, AccessFlags.INTERFACE)

        // ---- поля ----
        // Статические поля — в порядке будущих field_idx (имя, затем тип), чтобы static_values
        // были позиционными относительно окончательного порядка в class_data.
        val staticInfos = info.fields.filter { AccessFlags.isStatic(it.accessFlags) }
            .sortedWith(compareBy<FieldInfo>({ it.name }, { it.type }))
        val instanceInfos = info.fields.filter { !AccessFlags.isStatic(it.accessFlags) }

        val seenFields = HashSet<Int>()
        fun addField(f: FieldInfo): EncodedField {
            val fieldIdx = asm.fields.add(info.name, f.name, f.type)
            require(seenFields.add(fieldIdx)) { "Поле ${f.name}:${f.type} объявлено повторно" }
            for (ann in f.annotations) asm.deferred.addFieldAnnotation(fieldIdx, ann)
            return EncodedField(fieldIdx, f.accessFlags)
        }
        val staticFields = staticInfos.map { addField(it) }
        val instanceFields = instanceInfos.map {
            require(it.initialValue == null) { "Начальное значение у нестатического поля ${it.name}:${it.type}" }
            addField(it)
        }
        val staticValues = buildStaticValues(staticInfos)

        // ---- методы ----
        val seenMethods = HashSet<Int>()
        val directMethods = ArrayList<EncodedMethod>()
        val virtualMethods = ArrayList<EncodedMethod>()
        val directInfos = ArrayList<MethodInfo>()
        val virtualInfos = ArrayList<MethodInfo>()
        for (m in info.methods) {
            val methodIdx = asm.methods.add(info.name, m.name, m.paramTypes, m.returnType)
            fun signature() = "${m.name}(${m.paramTypes.joinToString("")})${m.returnType}"
            require(seenMethods.add(methodIdx)) { "Метод ${signature()} объявлен повторно" }
            val isStatic = AccessFlags.isStatic(m.accessFlags)
            val isDirect = isStatic || AccessFlags.isConstructor(m.accessFlags) || AccessFlags.isPrivate(m.accessFlags)
            if (isInterface && m.name != "<clinit>" &&
                (isDirect || !AccessFlags.isAbstract(m.accessFlags) || !AccessFlags.isPublic(m.accessFlags))) {
                asm.state.requireDexVersion(37)
            }

            val codeIdx = if (m.hasCode) asm.codes.add(m.code ?: buildCode(m, isStatic, signature())) else -1

            (if (isDirect) directMethods else virtualMethods).add(EncodedMethod(methodIdx, m.accessFlags, codeIdx))
            (if (isDirect) directInfos else virtualInfos).add(m)
            for (ann in m.annotations) asm.deferred.addMethodAnnotation(methodIdx, ann)
            for ((p, anns) in m.parameterAnnotations) {
                for (ann in anns) asm.deferred.addParameterAnnotation(methodIdx, p, ann)
            }
        }

        val hasMembers = staticFields.isNotEmpty() || instanceFields.isNotEmpty() ||
            directMethods.isNotEmpty() || virtualMethods.isNotEmpty()
        val classDataIdx = if (hasMembers) {
            asm.classData.add(
                ClassDataItem(
                    staticFieldsSize = staticFields.size,
                    instanceFieldsSize = instanceFields.size,
                    directMethodsSize = directMethods.size,
                    virtualMethodsSize = virtualMethods.size,
                    staticFields = staticFields,
                    instanceFields = instanceFields,
                    directMethods = directMethods,
                    virtualMethods = virtualMethods
                )
            )
        } else -1

        val classDefIdx = if (replaceIdx == null) {
            asm.classes.add(
                className = info.name,
                superClass = info.superClass,
                accessFlags = info.accessFlags,
                interfacesTypeListIdx = interfacesListIdx,
                sourceFileIdx = sourceFileIdx,
                classDataIdx = classDataIdx
            )
        } else {
            val superIdx = info.superClass?.let { asm.types.add(it) } ?: -1
            asm.classes.replace(replaceIdx) { old ->
                ClassDefItem(
                    classIdx = old.classIdx,
                    accessFlags = info.accessFlags,
                    superclassIdx = superIdx,
                    interfacesTypeListIdx = interfacesListIdx,
                    sourceFileIdx = sourceFileIdx,
                    annotationsDirectoryIdx = -1,
                    classDataIdx = classDataIdx,
                    staticValuesIdx = -1
                )
            }
            replaceIdx
        }

        // hidden API: по значению на член class_data в порядке кодирования; без явных флагов — «нет ограничений».
        if (info.fields.any { it.hiddenApi >= 0 } || info.methods.any { it.hiddenApi >= 0 }) {
            val flags = ArrayList<Int>()
            fun add(h: Int) { flags.add(if (h >= 0) h else HIDDEN_API_NO_RESTRICTIONS) }
            staticInfos.forEach { add(it.hiddenApi) }
            instanceInfos.forEach { add(it.hiddenApi) }
            directInfos.forEach { add(it.hiddenApi) }
            virtualInfos.forEach { add(it.hiddenApi) }
            asm.state.newHiddenApi[classDefIdx] = flags.toIntArray()
        } else if (replaceIdx != null && asm.state.hiddenApiClassDataItems.firstOrNull()?.flags?.getOrNull(replaceIdx) != null) {
            // Флаги прежнего класса считались по его членам: у замены их может быть другое число
            val members = staticInfos.size + instanceInfos.size + directInfos.size + virtualInfos.size
            asm.state.newHiddenApi[classDefIdx] = IntArray(members) { HIDDEN_API_NO_RESTRICTIONS }
        }

        if (staticValues.isNotEmpty()) {
            val staticValuesIdx = asm.encodedArrays.add(staticValues)
            asm.classes.replace(classDefIdx) { it.copy(staticValuesIdx = staticValuesIdx) }
        }

        for (ann in info.annotations) {
            asm.deferred.addClassAnnotation(classDefIdx, ann)
        }
    }

    private fun buildCode(m: MethodInfo, isStatic: Boolean, signature: String): com.tim.dex.model.CodeItem =
        try {
            CodeItemBuilder.build(
                instructions = m.instructions,
                registers = m.registers,
                paramTypes = m.paramTypes,
                isStatic = isStatic,
                debugInfoIdx = m.debugInfoIdx,
                tries = m.tries,
                catchHandlers = m.catchHandlers
            )
        } catch (e: Exception) {
            throw IllegalStateException("Метод $signature: ${e.message}", e)
        }

    /**
     * static_values: позиционно по статическим полям (в порядке field_idx) до последнего
     * поля с явно заданным значением (явное значение по умолчанию тоже сохраняется — как
     * в исходном DEX); поля без значения получают значение по умолчанию своего типа.
     */
    private fun buildStaticValues(statics: List<FieldInfo>): List<EncodedValue> {
        val last = statics.indexOfLast { it.initialValue != null }
        if (last < 0) return emptyList()
        val out = ArrayList<EncodedValue>(last + 1)
        for (i in 0..last) {
            val f = statics[i]
            out.add(f.initialValue ?: defaultValueFor(f.type))
        }
        return out
    }

    companion object {
        /** Значение по умолчанию для типа поля (static_values). */
        fun defaultValueFor(type: String): EncodedValue = when (type[0]) {
            'Z' -> EncodedValue(EncodedValueType.VALUE_BOOLEAN, false)
            'B' -> EncodedValue(EncodedValueType.VALUE_BYTE, 0.toByte())
            'S' -> EncodedValue(EncodedValueType.VALUE_SHORT, 0.toShort())
            'C' -> EncodedValue(EncodedValueType.VALUE_CHAR, 0.toChar())
            'I' -> EncodedValue(EncodedValueType.VALUE_INT, 0)
            'J' -> EncodedValue(EncodedValueType.VALUE_LONG, 0L)
            'F' -> EncodedValue(EncodedValueType.VALUE_FLOAT, 0f)
            'D' -> EncodedValue(EncodedValueType.VALUE_DOUBLE, 0.0)
            'L', '[' -> EncodedValue(EncodedValueType.VALUE_NULL, null)
            else -> throw IllegalArgumentException("Некорректный тип поля: $type")
        }
    }
}
