package com.tim.d2c.bridge

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.d2c.model.DexView
import java.util.concurrent.ConcurrentHashMap

/**
 * Методы доступа к protected-членам, которые ART разрешает, а JVM — нет.
 *
 * ART пускает к protected-члену класса D другого пакета любой код подкласса C класса D. HotSpot строже:
 * 1. JVMS §4.10.1.8 (верификатор): при обращении через ссылку на суперкласс C объект обязан иметь
 *    тип C (или его подкласса);
 * 2. JVMS §5.4.4 (связывание): класс в ссылке должен быть C, его подклассом или суперклассом —
 *    ссылка на «соседа» (другой подкласс D) даёт IllegalAccessError при любом объекте.
 * После переупаковки R8 встречается делегирование `other.m()`, где `other` — не C. Если объявляющий
 * класс D лежит в переводимых DEX, в него добавляется `public static synthetic access$d2c$…(LD;…)`,
 * внутри D ограничения нет, а диспетчеризация остаётся виртуальной. Если D не из DEX, флаги члена
 * неизвестны и вызов не меняется.
 */
internal class ProtectedAccessors(private val index: ClassIndex, private val hierarchy: ClassHierarchy) {

    /** Префиксы не являются префиксами друг друга: методы доступа разных видов не совпадают по имени. */
    enum class Kind(val prefix: String) {
        INVOKE("access\$d2c\$call\$"), GET("access\$d2c\$get\$"), PUT("access\$d2c\$put\$"),
        /** invoke-super default-метода интерфейса, который не прямой суперинтерфейс вызывающего. */
        SUPER("access\$d2c\$super\$"),
        /** `Object.clone()` на значении типа Object (у d8 — массив): выбор clone() массива по типу. */
        CLONE("access\$d2c\$clone")
    }

    /** Метод доступа в объявляющем классе: вид, имя и дескриптор члена (метода или тип поля). */
    data class Accessor(val kind: Kind, val member: String, val memberDescriptor: String) {
        val key: String get() = "${kind.ordinal}$member$memberDescriptor"
    }

    /** Вызов метода доступа `owner.name(descriptor)` (static). */
    class Call(val owner: String, val name: String, val descriptor: String)

    private val pending = ConcurrentHashMap<String, MutableSet<Accessor>>()

    /**
     * Нужен ли метод доступа для [kind]-обращения из класса [current] к члену [name]/[descriptor]
     * через ссылку на класс [referenced]. [objectIsCurrent] — объект доказуемо типа [current] или его
     * подкласса. null — ограничения JVM не срабатывают или неизвестны.
     */
    fun plan(
        current: String, referenced: String, kind: Kind, name: String, descriptor: String,
        objectIsCurrent: Boolean, record: Boolean
    ): Call? {
        if (referenced == current || referenced[0] != 'L') return null
        val viaSuper = isSuperclass(current, referenced)
        // Через суперкласс — только проверка верификатора (тип объекта); через подкласс C — ограничений нет.
        if (viaSuper && objectIsCurrent) return null
        if (!viaSuper && isSuperclass(referenced, current)) return null
        val member = if (kind == Kind.INVOKE) index.findMethod(referenced, name, descriptor)
        else index.findField(referenced, name, descriptor)
        member ?: return null
        if (member.flags and ACC_PROTECTED == 0 || member.flags and ACC_STATIC != 0) return null
        if (packageOf(member.owner) == packageOf(current)) return null
        // Через «соседа»: доступ есть и у ART, только если C — подкласс объявляющего класса.
        if (!viaSuper && !isSuperclass(current, member.owner)) return null
        val accessor = Accessor(kind, name, descriptor)
        if (record) pending.getOrPut(member.owner) { ConcurrentHashMap.newKeySet() }.add(accessor)
        val staticDescriptor = staticDescriptor(member.owner, accessor)
        return Call(member.owner, accessorName(member.owner, accessor, staticDescriptor), staticDescriptor)
    }

    /** [iface] — сам [current] или его прямой суперинтерфейс (только к ним JVM допускает invokespecial). */
    fun isDirectSuperinterface(current: String, iface: String): Boolean = iface == current || iface in index.interfacesOf(current)

    /**
     * invoke-super `iface.name(descriptor)`, где [iface] — не прямой суперинтерфейс: ART вызывает
     * default-реализацию, видимую из [iface]; в JVM — статический метод доступа в самом [iface]
     * с invokespecial. null — [iface] не из переводимых DEX.
     */
    fun planSuper(iface: String, name: String, descriptor: String, record: Boolean): Call? {
        if (!index.contains(iface)) return null
        val accessor = Accessor(Kind.SUPER, name, descriptor)
        if (record) pending.getOrPut(iface) { ConcurrentHashMap.newKeySet() }.add(accessor)
        val staticDescriptor = staticDescriptor(iface, accessor)
        return Call(iface, accessorName(iface, accessor, staticDescriptor), staticDescriptor)
    }

    /**
     * Метод доступа в классе [host] из переводимых DEX к члену его суперкласса вне DEX (Object.clone и т. п.):
     * внутри [host] на получателе типа [host] protected-доступ разрешён. null — [host] не из DEX.
     */
    fun planIn(host: String, kind: Kind, name: String, descriptor: String, record: Boolean): Call? {
        if (!index.contains(host)) return null
        val accessor = Accessor(kind, name, descriptor)
        if (record) pending.getOrPut(host) { ConcurrentHashMap.newKeySet() }.add(accessor)
        val staticDescriptor = staticDescriptor(host, accessor)
        return Call(host, accessorName(host, accessor, staticDescriptor), staticDescriptor)
    }

    /** Методы доступа, которые нужно добавить: класс → список в детерминированном порядке. */
    fun requests(): Map<String, List<Accessor>> =
        pending.entries.sortedBy { it.key }.associate { (type, set) -> type to set.sortedBy { it.key } }

    fun staticDescriptor(owner: String, a: Accessor): String = when (a.kind) {
        Kind.INVOKE, Kind.SUPER -> "(" + owner + a.memberDescriptor.substring(1)
        Kind.CLONE -> "(Ljava/lang/Object;)Ljava/lang/Object;"
        Kind.GET -> "($owner)${a.memberDescriptor}"
        Kind.PUT -> "($owner${a.memberDescriptor})V"
    }

    /** Имя метода доступа; если в классе уже есть такой метод — дописывается `$`. */
    fun accessorName(owner: String, a: Accessor, staticDescriptor: String): String {
        val taken = index.methodSignatures(owner)
        var name = a.kind.prefix + a.member
        while ((name + staticDescriptor) in taken) name += "$"
        return name
    }

    private val superclasses = ConcurrentHashMap<String, Set<String>>()

    /** [ancestor] — суперкласс [type] (цепочка суперклассов класса считается один раз). */
    private fun isSuperclass(type: String, ancestor: String): Boolean =
        DexView.internalName(ancestor) in superclasses.getOrPut(type) {
            val out = HashSet<String>()
            var cur = hierarchy.superClass(DexView.internalName(type))
            var guard = 0
            while (cur != null && guard++ < 256) {
                out.add(cur)
                cur = hierarchy.superClass(cur)
            }
            out
        }

    private fun packageOf(descriptor: String): String {
        val slash = descriptor.lastIndexOf('/')
        return if (slash < 0) "" else descriptor.substring(1, slash)
    }

    private companion object {
        const val ACC_PROTECTED = 0x0004
        const val ACC_STATIC = 0x0008
    }
}
