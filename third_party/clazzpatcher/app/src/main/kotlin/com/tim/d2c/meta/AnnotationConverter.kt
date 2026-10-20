package com.tim.d2c.meta

import com.tim.casm.model.AnnotationElementInfo
import com.tim.casm.model.AnnotationInfo
import com.tim.casm.model.AnnotationValue
import com.tim.d2c.model.DexView
import com.tim.d2c.model.MethodRef
import com.tim.dex.data.AnnotationVisibility
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.Annotation
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue

/**
 * Сведения из системных аннотаций `dalvik/annotation/…` — в JVM это атрибуты класса, поля или метода.
 */
internal class SystemInfo {
    var signature: String? = null
    var exceptions: List<String> = emptyList()
    /** InnerClass: имя (null — анонимный) и флаги. */
    var innerName: String? = null
    var innerFlags: Int = -1
    var enclosingClass: String? = null
    var enclosingMethod: MethodRef? = null
    var memberClasses: List<String> = emptyList()
    var annotationDefaults: Map<String, AnnotationValue> = emptyMap()
    var parameterNames: List<String?>? = null
    var parameterFlags: List<Int>? = null
    var sourceDebugExtension: String? = null
    var nestHost: String? = null
    var nestMembers: List<String> = emptyList()
    var permittedSubclasses: List<String> = emptyList()
    /** Есть dalvik/annotation/Record (у record без компонент списки пусты). */
    var isRecord: Boolean = false
    var recordNames: List<String> = emptyList()
    var recordTypes: List<String> = emptyList()
    /** Сигнатуры и аннотации компонент record (по номеру компоненты; null — сигнатуры нет). */
    var recordSignatures: List<String?> = emptyList()
    var recordAnnotations: List<List<AnnotationInfo>> = emptyList()

    val isInner: Boolean get() = innerFlags >= 0
}

/**
 * Аннотации DEX → модель casm; системные — в [SystemInfo].
 * [skip] — нестрогий режим: вызывается для аннотации, невыразимой в JVM (она пропускается); null — исключение.
 */
internal class AnnotationConverter(private val view: DexView, private val skip: ((String) -> Unit)?) {

    /** Обычные аннотации набора (visibility build/runtime) и сведения из системных. */
    fun convert(items: List<AnnotationItem>, system: SystemInfo): List<AnnotationInfo> {
        val out = ArrayList<AnnotationInfo>(items.size)
        for (item in items) {
            val visibility = item.visibility.toInt()
            if (visibility == AnnotationVisibility.VISIBILITY_SYSTEM) {
                try {
                    system(item.annotation, system)
                } catch (e: RuntimeException) {
                    // Неожиданная форма значения (ClassCastException и т. п.) или значение, невыразимое в JVM.
                    val type = runCatching { view.type(item.annotation.typeIdx) }.getOrElse { "#${item.annotation.typeIdx}" }
                    val message = "системная аннотация $type: ${e.message ?: e}"
                    skip?.invoke("$message — пропущена") ?: throw IllegalStateException(message, e)
                }
                continue
            }
            try {
                out.add(annotation(item.annotation, visible = visibility == AnnotationVisibility.VISIBILITY_RUNTIME))
            } catch (e: IllegalStateException) {
                val message = "аннотация ${view.type(item.annotation.typeIdx)}: ${e.message}"
                skip?.invoke("$message — пропущена") ?: throw IllegalStateException(message, e)
            }
        }
        return out
    }

    fun annotation(a: Annotation, visible: Boolean): AnnotationInfo =
        AnnotationInfo(view.type(a.typeIdx), visible, a.elements.map { AnnotationElementInfo(view.string(it.nameIdx), value(it.value)) })

    fun value(v: EncodedValue): AnnotationValue = when (v.type) {
        EncodedValueType.VALUE_BYTE, EncodedValueType.VALUE_SHORT, EncodedValueType.VALUE_CHAR,
        EncodedValueType.VALUE_INT, EncodedValueType.VALUE_LONG, EncodedValueType.VALUE_FLOAT,
        EncodedValueType.VALUE_DOUBLE, EncodedValueType.VALUE_BOOLEAN -> AnnotationValue.Const(v.value)
        EncodedValueType.VALUE_STRING -> AnnotationValue.Const(view.string(v.value as Int))
        EncodedValueType.VALUE_TYPE -> AnnotationValue.ClassRef(view.type(v.value as Int))
        EncodedValueType.VALUE_ENUM -> {
            val f = view.field(v.value as Int)
            AnnotationValue.Enum(f.owner, f.name)
        }
        EncodedValueType.VALUE_ARRAY -> {
            @Suppress("UNCHECKED_CAST")
            AnnotationValue.Array((v.value as List<EncodedValue>).map { value(it) })
        }
        EncodedValueType.VALUE_ANNOTATION -> AnnotationValue.Nested(annotation(v.value as Annotation, visible = true))
        else -> throw IllegalStateException("значение аннотации типа 0x${Integer.toHexString(v.type)} невозможно в JVM")
    }

    private fun system(a: Annotation, s: SystemInfo) {
        fun element(name: String): EncodedValue? = a.elements.firstOrNull { view.string(it.nameIdx) == name }?.value
        // Значения проверяются по типу: у некорректного DEX вместо строки может оказаться, скажем, byte.
        fun expect(v: EncodedValue, type: Int, what: String) {
            if (v.type != type) throw IllegalStateException("ожидался $what, а значение типа 0x${Integer.toHexString(v.type)}")
        }
        @Suppress("UNCHECKED_CAST")
        fun list(v: EncodedValue?): List<EncodedValue> {
            if (v == null) return emptyList()
            expect(v, EncodedValueType.VALUE_ARRAY, "массив")
            return v.value as List<EncodedValue>
        }
        fun type(v: EncodedValue): String { expect(v, EncodedValueType.VALUE_TYPE, "тип"); return view.type(v.value as Int) }
        fun string(v: EncodedValue): String { expect(v, EncodedValueType.VALUE_STRING, "строка"); return view.string(v.value as Int) }
        fun int(v: EncodedValue): Int { expect(v, EncodedValueType.VALUE_INT, "int"); return v.value as Int }
        fun types(v: EncodedValue?): List<String> = list(v).map { type(it) }
        fun internals(v: EncodedValue?): List<String> = types(v).map { DexView.internalName(it) }
        fun str(v: EncodedValue?): String? = if (v == null || v.type == EncodedValueType.VALUE_NULL) null else string(v)

        when (view.type(a.typeIdx)) {
            "Ldalvik/annotation/Signature;" -> s.signature = list(element("value")).joinToString("") { string(it) }
            "Ldalvik/annotation/Throws;" -> s.exceptions = internals(element("value"))
            "Ldalvik/annotation/InnerClass;" -> {
                s.innerName = str(element("name"))
                s.innerFlags = (element("accessFlags")?.value as? Int) ?: 0
            }
            "Ldalvik/annotation/EnclosingClass;" -> s.enclosingClass = element("value")?.let { type(it) }
            "Ldalvik/annotation/EnclosingMethod;" -> {
                val v = element("value")
                if (v != null && v.type == EncodedValueType.VALUE_METHOD) s.enclosingMethod = view.method(v.value as Int)
            }
            "Ldalvik/annotation/MemberClasses;" -> s.memberClasses = types(element("value"))
            "Ldalvik/annotation/AnnotationDefault;" -> {
                val nested = element("value")?.value as? Annotation
                if (nested != null) {
                    s.annotationDefaults = nested.elements.associate { view.string(it.nameIdx) to value(it.value) }
                }
            }
            "Ldalvik/annotation/MethodParameters;" -> {
                s.parameterNames = list(element("names")).map { str(it) }
                s.parameterFlags = list(element("accessFlags")).map { int(it) }
            }
            "Ldalvik/annotation/SourceDebugExtension;" -> s.sourceDebugExtension = str(element("value"))
            "Ldalvik/annotation/NestHost;" -> s.nestHost = element("host")?.let { DexView.internalName(type(it)) }
            "Ldalvik/annotation/NestMembers;" -> s.nestMembers = internals(element("classes"))
            "Ldalvik/annotation/PermittedSubclasses;" -> s.permittedSubclasses = internals(element("value"))
            "Ldalvik/annotation/Record;" -> {
                s.isRecord = true
                s.recordNames = list(element("componentNames")).map { string(it) }
                s.recordTypes = types(element("componentTypes"))
                // Сигнатура компоненты — вложенная dalvik/annotation/Signature или null.
                s.recordSignatures = list(element("componentSignatures")).map { v ->
                    (v.value as? Annotation)?.elements?.firstOrNull { view.string(it.nameIdx) == "value" }
                        ?.let { sig -> list(sig.value).joinToString("") { string(it) } }
                }
                val visibilities = list(element("componentAnnotationVisibilities")).map { v ->
                    list(v).map { b -> expect(b, EncodedValueType.VALUE_BYTE, "byte"); (b.value as Number).toInt() }
                }
                s.recordAnnotations = list(element("componentAnnotations")).mapIndexed { k, v ->
                    list(v).mapIndexedNotNull { j, a ->
                        val visibility = visibilities.getOrNull(k)?.getOrNull(j) ?: AnnotationVisibility.VISIBILITY_RUNTIME
                        if (visibility == AnnotationVisibility.VISIBILITY_SYSTEM) null
                        else {
                            expect(a, EncodedValueType.VALUE_ANNOTATION, "аннотация")
                            annotation(a.value as Annotation, visible = visibility == AnnotationVisibility.VISIBILITY_RUNTIME)
                        }
                    }
                }
            }
            // Прочие системные аннотации (dalvik/annotation/optimization/…, внутренние аннотации
            // компилятора и т. п.) управляют только поведением ART и в JVM смысла не имеют.
            else -> {}
        }
    }
}
