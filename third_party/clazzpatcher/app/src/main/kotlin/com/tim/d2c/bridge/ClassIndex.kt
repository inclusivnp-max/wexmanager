package com.tim.d2c.bridge

import com.tim.d2c.model.DexView
import java.util.concurrent.ConcurrentHashMap

/** Где определён класс: номер DEX во входном списке и class_def в нём. */
internal class ClassLocation(val dex: Int, val defIdx: Int)

/**
 * Классы всех переводимых DEX (у APK — всех classes*.dex): при повторном определении класса
 * действует первое, как у загрузчика ART. Потокобезопасно после построения.
 */
internal class ClassIndex(val views: List<DexView>) {

    /** Дескриптор класса → первое определение. */
    val byType: Map<String, ClassLocation>

    /** Повторные определения (номер DEX, class_def) — не переводятся. */
    val duplicates: List<Pair<ClassLocation, String>>

    /** Переводимые классы (первые определения) в порядке DEX и class_defs. */
    val order: List<ClassLocation>

    /** Дескриптор класса → номер в [order]. */
    val position: Map<String, Int>

    private val superByType: Map<String, String?>
    private val constructors = ConcurrentHashMap<String, Set<String>>()

    init {
        val map = HashMap<String, ClassLocation>()
        val supers = HashMap<String, String?>()
        val dups = ArrayList<Pair<ClassLocation, String>>()
        val list = ArrayList<ClassLocation>()
        val pos = HashMap<String, Int>()
        for ((d, view) in views.withIndex()) {
            for ((i, c) in view.dex.classDefs.withIndex()) {
                val type = view.type(c.classIdx)
                val loc = ClassLocation(d, i)
                if (map.containsKey(type)) {
                    dups.add(loc to type)
                    continue
                }
                map[type] = loc
                pos[type] = list.size
                list.add(loc)
                supers[type] = if (c.superclassIdx >= 0) view.type(c.superclassIdx) else null
            }
        }
        byType = map
        superByType = supers
        duplicates = dups
        order = list
        position = pos
    }

    fun contains(type: String): Boolean = byType.containsKey(type)

    /** Дескриптор суперкласса класса из DEX (null — нет или класс не из DEX). */
    fun superOf(type: String): String? = superByType[type]

    fun view(loc: ClassLocation): DexView = views[loc.dex]

    /** Член класса из DEX: объявляющий класс и флаги доступа. */
    class Member(val owner: String, val flags: Int)

    private val methods = ConcurrentHashMap<String, Map<String, Int>>()
    private val fields = ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * Метод `name` + дескриптор, как его находит разрешение ссылки JVM: в [type] и его суперклассах.
     * null — не найден среди классов DEX (цепочка ушла во внешний класс) — флаги неизвестны.
     */
    fun findMethod(type: String, name: String, descriptor: String): Member? =
        find(type, "$name$descriptor") { t -> methodsOf(t) }

    /** Поле `name:type` в [type] и его суперклассах (интерфейсы для instance-полей не нужны). */
    fun findField(type: String, name: String, fieldType: String): Member? =
        find(type, "$name:$fieldType") { t -> fieldsOf(t) }

    /** Все сигнатуры методов класса (`имя(…)R`) — для проверки занятости имён. */
    fun methodSignatures(type: String): Set<String> = methodsOf(type).keys

    private inline fun find(type: String, key: String, table: (String) -> Map<String, Int>): Member? {
        var cur: String? = type
        var guard = 0
        while (cur != null && contains(cur) && guard++ < 64) {
            table(cur)[key]?.let { return Member(cur!!, it) }
            cur = superOf(cur)
        }
        return null
    }

    private fun methodsOf(type: String): Map<String, Int> = methods.getOrPut(type) {
        val data = classData(type) ?: return@getOrPut emptyMap()
        val view = views[byType[type]!!.dex]
        val out = HashMap<String, Int>()
        for (m in data.directMethods + data.virtualMethods) {
            val ref = view.method(m.methodIdx)
            out[ref.name + ref.proto.descriptor] = m.accessFlags
        }
        out
    }

    private fun fieldsOf(type: String): Map<String, Int> = fields.getOrPut(type) {
        val data = classData(type) ?: return@getOrPut emptyMap()
        val view = views[byType[type]!!.dex]
        val out = HashMap<String, Int>()
        for (f in data.staticFields + data.instanceFields) {
            val ref = view.field(f.fieldIdx)
            out[ref.name + ":" + ref.type] = f.accessFlags
        }
        out
    }

    private fun classData(type: String): com.tim.dex.model.ClassDataItem? {
        val loc = byType[type] ?: return null
        val view = views[loc.dex]
        val def = view.classDef(loc.defIdx)
        return if (def.classDataIdx < 0) null else view.dex.classDataItems[def.classDataIdx]
    }

    /** Класс из DEX — интерфейс. */
    fun isInterface(type: String): Boolean {
        val loc = byType[type] ?: return false
        return views[loc.dex].classDef(loc.defIdx).accessFlags and 0x0200 != 0
    }

    /** Прямые суперинтерфейсы класса из DEX (пусто — нет или класс не из DEX). */
    fun interfacesOf(type: String): List<String> {
        val loc = byType[type] ?: return emptyList()
        val view = views[loc.dex]
        return view.typeList(view.classDef(loc.defIdx).interfacesTypeListIdx)
    }

    /** Дескрипторы уже объявленных конструкторов класса (`(…)V`). */
    fun constructorDescriptors(type: String): Set<String> = constructors.getOrPut(type) {
        val loc = byType[type] ?: return@getOrPut emptySet()
        val view = views[loc.dex]
        val def = view.classDef(loc.defIdx)
        if (def.classDataIdx < 0) return@getOrPut emptySet()
        val data = view.dex.classDataItems[def.classDataIdx]
        val out = HashSet<String>()
        for (m in data.directMethods) {
            val ref = view.method(m.methodIdx)
            if (ref.name == "<init>") out.add(ref.proto.descriptor)
        }
        out
    }
}
