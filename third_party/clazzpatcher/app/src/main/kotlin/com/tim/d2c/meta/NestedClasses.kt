package com.tim.d2c.meta

import com.tim.d2c.bridge.ClassIndex
import com.tim.d2c.bridge.ClassLocation
import com.tim.dex.data.AnnotationVisibility
import java.util.concurrent.ConcurrentHashMap

/**
 * Вложенность классов всех DEX сеанса (у APK — всех classes*.dex): системные аннотации InnerClass,
 * EnclosingClass/EnclosingMethod и MemberClasses любого класса и обратное отображение «класс →
 * объявленные в нём». Внешний и вложенный классы могут лежать в разных DEX. Потокобезопасно.
 */
internal class NestedClasses(private val index: ClassIndex, strict: Boolean) {

    // Ошибки системных аннотаций сообщает перевод самого класса; здесь в нестрогом режиме — молча.
    private val converters = index.views.map { AnnotationConverter(it, if (strict) null else { _ -> }) }
    private val infos = ConcurrentHashMap<String, SystemInfo>()
    private val enclosed: Map<String, List<String>>

    init {
        val map = HashMap<String, MutableList<String>>()
        for (loc in index.order) {
            val view = index.view(loc)
            val type = view.type(view.classDef(loc.defIdx).classIdx)
            val s = systemOf(type) ?: continue
            if (!s.isInner) continue
            val outer = s.enclosingMethod?.owner ?: s.enclosingClass ?: continue
            map.getOrPut(outer) { ArrayList() }.add(type)
        }
        enclosed = map
    }

    /** Системные сведения класса из любого DEX сеанса (null — такого класса нет). */
    fun systemOf(type: String): SystemInfo? {
        val loc = index.byType[type] ?: return null
        return infos.getOrPut(type) { read(type, loc) }
    }

    /** Вложенные классы, объявленные в [type] (члены, локальные, анонимные), в порядке DEX и class_defs. */
    fun enclosedIn(type: String): List<String> = enclosed[type].orEmpty()

    private fun read(type: String, loc: ClassLocation): SystemInfo {
        val s = SystemInfo()
        val view = index.view(loc)
        val dir = view.annotationsDirectory(view.classDef(loc.defIdx)) ?: return s
        val items = view.annotationSet(dir.classAnnotationsSetIdx).filter { it.visibility.toInt() == AnnotationVisibility.VISIBILITY_SYSTEM }
        try {
            converters[loc.dex].convert(items, s)
        } catch (e: Exception) {
            throw IllegalStateException("Класс $type: ${e.message}", e)
        }
        return s
    }
}
