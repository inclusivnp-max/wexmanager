package com.tim.dsmali.disasm

import com.tim.dasm.model.AccessFlags
import com.tim.dex.model.AnnotationsDirectoryItem
import com.tim.dex.model.DexFile
import com.tim.dex.model.EncodedField
import com.tim.dex.model.EncodedMethod
import com.tim.dex.model.EncodedValue
import com.tim.dsmali.SmaliFormatter
import com.tim.dsmali.model.SmaliAnnotation
import com.tim.dsmali.model.SmaliAnnotationElement
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliField
import com.tim.dsmali.model.SmaliMethod
import com.tim.dsmali.model.SmaliTree
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * DEX → smali-модель. Классы обрабатываются параллельно (DexFile только читается),
 * порядок классов в дереве — как в class_defs.
 */
class DexToTree {
    fun build(dex: DexFile): SmaliTree = build(dex, hiddenApiFlags(dex))

    /** [hiddenApi] — флаги hidden API по индексу class_def (см. [hiddenApiFlagsOf]). */
    internal fun build(dex: DexFile, hiddenApi: Array<IntArray?>): SmaliTree {
        if (hiddenApi.size != dex.classDefs.size) throw IllegalArgumentException("Hidden API: ${hiddenApi.size} != ${dex.classDefs.size}")
        val legacy = com.tim.dsmali.HiddenApi.isLegacy(hiddenApi.asSequence().filterNotNull().flatMap { it.asSequence() })
        val names = DexNames(dex)
        // Элементы аннотаций по annotation_item: наборы аннотаций общие у многих членов классов.
        val annotations = AtomicReferenceArray<List<SmaliAnnotationElement>>(dex.annotationItems.size)
        val classes = Parallel.map(dex.classDefs.size) { i ->
            ClassBuilder(names, annotations, i, hiddenApi[i], legacy).build()
        }
        val seen = HashSet<String>(classes.size * 2)
        for (cls in classes) {
            if (!seen.add(cls.descriptor)) throw IllegalStateException("Класс ${cls.descriptor} определён в DEX дважды")
        }
        val tree = SmaliTree()
        tree.classes.addAll(classes)
        return tree
    }

    /**
     * Флаги hidden API по индексу class_def: по одному значению на каждый член класса
     * в порядке class_data (статические поля, поля экземпляра, direct- и virtual-методы).
     */
    internal fun hiddenApiFlags(dex: DexFile): Array<IntArray?> =
        hiddenApiFlagsOf(dex.hiddenApiClassDataItems.map { it.flags }, dex.classDefs.size)

    /**
     * Модель секции переделывается в dex-core: принимаем и список флагов по индексу class_def
     * (`null` — у класса нет флагов), и Map «индекс class_def → флаги».
     */
    internal fun hiddenApiFlagsOf(sections: List<Any>, classDefCount: Int): Array<IntArray?> {
        val out = arrayOfNulls<IntArray>(classDefCount)
        for (flags in sections) {
            when (flags) {
                is List<*> -> {
                    if (flags.size != out.size) {
                        throw IllegalStateException("Hidden API: флаги для ${flags.size} классов, а class_def ${out.size}")
                    }
                    flags.forEachIndexed { i, f -> out[i] = hiddenApiList(f) }
                }
                is Map<*, *> -> for ((k, f) in flags) {
                    val classDefIdx = k as? Int ?: throw IllegalStateException("Hidden API: неожиданный ключ $k")
                    if (classDefIdx < 0 || classDefIdx >= out.size) {
                        throw IllegalStateException("Hidden API: индекс class_def $classDefIdx вне диапазона [0, ${out.size})")
                    }
                    out[classDefIdx] = hiddenApiList(f)
                }
                else -> throw IllegalStateException("Hidden API: неизвестная форма флагов: $flags")
            }
        }
        return out
    }

    private fun hiddenApiList(f: Any?): IntArray? = when (f) {
        null -> null
        is IntArray -> f
        is List<*> -> IntArray(f.size) { f[it] as? Int ?: throw IllegalStateException("Hidden API: неожиданный флаг ${f[it]}") }
        else -> throw IllegalStateException("Hidden API: неизвестная форма флагов: $f")
    }

    /**
     * Классы по одному, по запросу (редактор): имена и кэш аннотаций общие для всех вызовов, остальные
     * классы не разбираются. Потокобезопасен.
     */
    internal class Single(dex: DexFile) {
        private val names = DexNames(dex)
        private val hiddenApi = DexToTree().hiddenApiFlags(dex)
        private val legacy = com.tim.dsmali.HiddenApi.isLegacy(hiddenApi.asSequence().filterNotNull().flatMap { it.asSequence() })
        private val annotations = AtomicReferenceArray<List<SmaliAnnotationElement>>(dex.annotationItems.size)

        fun build(classDefIdx: Int): SmaliClass =
            ClassBuilder(names, annotations, classDefIdx, hiddenApi[classDefIdx], legacy).build()
    }

    private class ClassBuilder(
        private val names: DexNames,
        private val annotationCache: AtomicReferenceArray<List<SmaliAnnotationElement>>,
        private val classDefIdx: Int,
        private val hiddenApi: IntArray?,
        private val legacyHiddenApi: Boolean
    ) {
        private val dex = names.dex
        private val cd = dex.classDefs[classDefIdx]
        private var hiddenApiPos = 0

        fun build(): SmaliClass {
            val descriptor = names.type(cd.classIdx)
            try {
                return buildClass(descriptor)
            } catch (e: Exception) {
                throw IllegalStateException("Класс $descriptor: ${e.message}", e)
            }
        }

        private fun buildClass(descriptor: String): SmaliClass {
            checkFlags(cd.accessFlags, CLASS_FLAGS, "класса")
            val cls = SmaliClass(descriptor = descriptor)
            cls.accessFlags = AccessFlags.classAccessFlags(cd.accessFlags)
            cls.superClass = if (cd.superclassIdx != -1) names.type(cd.superclassIdx) else null
            if (cd.sourceFileIdx != -1) cls.sourceFile = names.string(cd.sourceFileIdx)
            if (cd.interfacesTypeListIdx != -1) {
                for (t in names.typeList(cd.interfacesTypeListIdx)) cls.interfaces.add(names.type(t))
            }

            val dir: AnnotationsDirectoryItem? = if (cd.annotationsDirectoryIdx != -1) {
                dex.annotationsDirectoryItems.getOrNull(cd.annotationsDirectoryIdx)
                    ?: throw IllegalStateException("Ссылка на отсутствующий annotations_directory #${cd.annotationsDirectoryIdx}")
            } else null
            if (dir != null && dir.classAnnotationsSetIdx != -1) readAnnotationSet(dir.classAnnotationsSetIdx, cls.annotations)
            val fieldAnns = HashMap<Int, Int>()
            val methodAnns = HashMap<Int, Int>()
            val paramAnns = HashMap<Int, Int>()
            if (dir != null) {
                for (a in dir.fieldAnnotations) fieldAnns[a.fieldIdx] = a.annotationsSetIdx
                for (a in dir.methodAnnotations) methodAnns[a.methodIdx] = a.annotationsSetIdx
                for (a in dir.parameterAnnotations) paramAnns[a.methodIdx] = a.annotationsSetRefListIdx
            }

            if (cd.classDataIdx == -1) {
                if (cd.staticValuesIdx != -1) throw IllegalStateException("static_values без class_data")
                checkHiddenApiConsumed()
                return cls
            }
            val data = dex.classDataItems.getOrNull(cd.classDataIdx)
                ?: throw IllegalStateException("Ссылка на отсутствующий class_data #${cd.classDataIdx}")
            val staticValues: List<EncodedValue> = if (cd.staticValuesIdx != -1) {
                dex.encodedArrayItems.getOrNull(cd.staticValuesIdx)
                    ?: throw IllegalStateException("Ссылка на отсутствующий encoded_array #${cd.staticValuesIdx}")
            } else emptyList()
            if (staticValues.size > data.staticFields.size) {
                throw IllegalStateException("static_values содержит ${staticValues.size} значений на ${data.staticFields.size} статических полей")
            }
            val total = data.staticFields.size + data.instanceFields.size + data.directMethods.size + data.virtualMethods.size
            if (hiddenApi != null && hiddenApi.size != total) {
                throw IllegalStateException("Hidden API: ${hiddenApi.size} флагов на $total членов класса")
            }

            data.staticFields.forEachIndexed { i, f ->
                val field = buildField(f, fieldAnns, true)
                if (i < staticValues.size) field.initialValue = names.encodedValue(staticValues[i])
                cls.fields.add(field)
            }
            for (f in data.instanceFields) cls.fields.add(buildField(f, fieldAnns, false))
            for (m in data.directMethods) cls.methods.add(buildMethod(m, methodAnns, paramAnns, true))
            for (m in data.virtualMethods) cls.methods.add(buildMethod(m, methodAnns, paramAnns, false))
            checkHiddenApiConsumed()
            return cls
        }

        private fun checkHiddenApiConsumed() {
            if (hiddenApi != null && hiddenApiPos != hiddenApi.size) {
                throw IllegalStateException("Hidden API: ${hiddenApi.size} флагов, а членов класса $hiddenApiPos")
            }
        }

        private fun buildField(f: EncodedField, anns: Map<Int, Int>, static: Boolean): SmaliField {
            val fid = dex.fieldIds.getOrNull(f.fieldIdx)
                ?: throw IllegalStateException("Индекс поля ${f.fieldIdx} вне диапазона")
            val name = names.string(fid.nameIdx)
            try {
                checkFlags(f.accessFlags, FIELD_FLAGS, "поля")
                if (AccessFlags.isStatic(f.accessFlags) != static) {
                    throw IllegalStateException(if (static) "поле из списка static_fields без флага static"
                    else "поле экземпляра с флагом static")
                }
                val field = SmaliField(
                    accessFlags = AccessFlags.fieldAccessFlags(f.accessFlags),
                    name = name,
                    type = names.type(fid.typeIdx)
                )
                addHiddenApi(field.accessFlags)
                anns[f.fieldIdx]?.let { if (it != -1) readAnnotationSet(it, field.annotations) }
                return field
            } catch (e: Exception) {
                throw IllegalStateException("Поле $name: ${e.message}", e)
            }
        }

        private fun buildMethod(
            m: EncodedMethod,
            methodAnns: Map<Int, Int>,
            paramAnns: Map<Int, Int>,
            direct: Boolean
        ): SmaliMethod {
            val mid = dex.methodIds.getOrNull(m.methodIdx)
                ?: throw IllegalStateException("Индекс метода ${m.methodIdx} вне диапазона")
            val name = names.string(mid.nameIdx)
            try {
                checkFlags(m.accessFlags, METHOD_FLAGS, "метода")
                val isDirect = m.accessFlags and (AccessFlags.STATIC or AccessFlags.PRIVATE or AccessFlags.CONSTRUCTOR) != 0
                if (isDirect != direct) {
                    throw IllegalStateException(if (direct) "direct-метод без флагов static/private/constructor"
                    else "virtual-метод с флагом static/private/constructor")
                }
                val method = SmaliMethod(
                    accessFlags = AccessFlags.methodAccessFlags(m.accessFlags),
                    name = name,
                    returnType = names.protoReturnType(mid.protoIdx)
                )
                method.paramTypes.addAll(names.protoParamTypes(mid.protoIdx))
                addHiddenApi(method.accessFlags)
                methodAnns[m.methodIdx]?.let { if (it != -1) readAnnotationSet(it, method.annotations) }
                paramAnns[m.methodIdx]?.let { if (it != -1) readParameterAnnotations(it, method) }
                if (m.codeIdx != -1) {
                    val code = dex.codeItems.getOrNull(m.codeIdx)
                        ?: throw IllegalStateException("Ссылка на отсутствующий code_item #${m.codeIdx}")
                    method.registers = code.registersSize
                    MethodBodyBuilder(names, code, AccessFlags.isStatic(m.accessFlags), method.paramTypes).build(method)
                }
                return method
            } catch (e: Exception) {
                throw IllegalStateException("Метод $name${names.protoRef(mid.protoIdx)}: ${e.message}", e)
            }
        }

        private fun addHiddenApi(flags: MutableList<String>) {
            val list = hiddenApi ?: return
            if (hiddenApiPos >= list.size) throw IllegalStateException("Hidden API: флагов меньше, чем членов класса")
            flags.addAll(com.tim.dsmali.HiddenApi.words(list[hiddenApiPos++], legacyHiddenApi))
        }

        private fun readParameterAnnotations(refListIdx: Int, method: SmaliMethod) {
            val refList = dex.annotationSetRefLists.getOrNull(refListIdx)
                ?: throw IllegalStateException("Ссылка на отсутствующий annotation_set_ref_list #$refListIdx")
            refList.annotationSetIdxs.forEachIndexed { i, setIdx ->
                if (setIdx == -1) return@forEachIndexed
                val list = ArrayList<SmaliAnnotation>()
                readAnnotationSet(setIdx, list)
                if (list.isEmpty()) return@forEachIndexed
                if (i >= method.paramTypes.size) {
                    throw IllegalStateException("Аннотации для несуществующего параметра #$i (параметров ${method.paramTypes.size})")
                }
                method.parameterAnnotations[i] = list
            }
        }

        private fun readAnnotationSet(setIdx: Int, out: MutableList<SmaliAnnotation>) {
            val set = dex.annotationSetItems.getOrNull(setIdx)
                ?: throw IllegalStateException("Ссылка на отсутствующий annotation_set #$setIdx")
            for (itemIdx in set.annotationIdxs) {
                val item = dex.annotationItems.getOrNull(itemIdx)
                    ?: throw IllegalStateException("annotation_set #$setIdx ссылается на отсутствующую аннотацию #$itemIdx")
                val elements = annotationCache.get(itemIdx) ?: item.annotation.elements.map {
                    SmaliAnnotationElement(names.string(it.nameIdx), names.encodedValue(it.value))
                }.also { annotationCache.set(itemIdx, it) }
                out.add(SmaliAnnotation(
                    visibility = SmaliFormatter.visibility(item.visibility),
                    type = names.type(item.annotation.typeIdx),
                    elements = elements.mapTo(ArrayList(elements.size)) { it.copy() }
                ))
            }
        }

        private fun checkFlags(flags: Int, allowed: Int, what: String) {
            val unknown = flags and allowed.inv()
            if (unknown != 0) {
                throw IllegalStateException("Флаги доступа $what 0x${Integer.toHexString(flags)} содержат " +
                    "невыразимые в smali биты 0x${Integer.toHexString(unknown)}")
            }
        }
    }

    private companion object {
        const val CLASS_FLAGS = AccessFlags.PUBLIC or AccessFlags.PRIVATE or AccessFlags.PROTECTED or
            AccessFlags.STATIC or AccessFlags.FINAL or AccessFlags.INTERFACE or AccessFlags.ABSTRACT or
            AccessFlags.SYNTHETIC or AccessFlags.ANNOTATION or AccessFlags.ENUM
        const val FIELD_FLAGS = AccessFlags.PUBLIC or AccessFlags.PRIVATE or AccessFlags.PROTECTED or
            AccessFlags.STATIC or AccessFlags.FINAL or AccessFlags.VOLATILE or AccessFlags.TRANSIENT or
            AccessFlags.SYNTHETIC or AccessFlags.ENUM
        const val METHOD_FLAGS = AccessFlags.PUBLIC or AccessFlags.PRIVATE or AccessFlags.PROTECTED or
            AccessFlags.STATIC or AccessFlags.FINAL or AccessFlags.SYNCHRONIZED or AccessFlags.BRIDGE or
            AccessFlags.VARARGS or AccessFlags.NATIVE or AccessFlags.ABSTRACT or AccessFlags.STRICT or
            AccessFlags.SYNTHETIC or AccessFlags.CONSTRUCTOR or AccessFlags.DECLARED_SYNCHRONIZED

    }
}
