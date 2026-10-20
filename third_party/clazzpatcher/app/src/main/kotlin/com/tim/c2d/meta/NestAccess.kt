package com.tim.c2d.meta

import com.tim.c2d.bytecode.BytecodeDecoder
import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.NestHost
import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Доступ к private-членам соседей по гнезду (NestHost/NestMembers, Java 11+).
 *
 * JVM разрешает классам одного гнезда обращаться к private-членам друг друга, ART — нет
 * (IllegalAccessError во время работы). Как и d8, план по всем входным классам:
 * - поля, конструкторы и статические методы, к которым обращаются соседи, теряют ACC_PRIVATE
 *   (у интерфейсов — становятся public): для них расширение доступа не меняет смысла;
 * - private-методы экземпляра получают статический мост `-$$Nest$m<имя>(владелец, параметры…)`
 *   в своём классе, а вызовы из соседей переписываются на него (виртуальная диспетчеризация
 *   не появляется).
 */
class NestAccess private constructor(
    private val widenedFields: Set<String>,
    private val widenedMethods: Set<String>,
    private val bridges: Map<String, List<Bridge>>
) {
    /** Мост к private-методу экземпляра [name][descriptor] класса [owner]. */
    class Bridge(val owner: String, val name: String, val descriptor: String) {
        val bridgeName: String get() = "-\$\$Nest\$m$name"

        /** Дескриптор моста: получатель первым параметром. */
        val bridgeDescriptor: String get() = "(L$owner;" + descriptor.substring(1)
    }

    fun isFieldWidened(owner: String, name: String, descriptor: String) = key(owner, name, descriptor) in widenedFields

    fun isMethodWidened(owner: String, name: String, descriptor: String) = key(owner, name, descriptor) in widenedMethods

    /** Мосты, которые нужно добавить в класс [owner]. */
    fun bridgesOf(owner: String): List<Bridge> = bridges[owner] ?: emptyList()

    /** Мост для вызова из [caller] метода [owner].[name][descriptor], если он нужен. */
    fun bridgeFor(caller: String, owner: String, name: String, descriptor: String): Bridge? {
        if (caller == owner) return null
        return bridges[owner]?.firstOrNull { it.name == name && it.descriptor == descriptor }
    }

    companion object {
        val NONE = NestAccess(emptySet(), emptySet(), emptyMap())

        private const val ACC_PRIVATE = 0x0002
        private const val ACC_STATIC = 0x0008

        private fun key(owner: String, name: String, descriptor: String) = "$owner.$name:$descriptor"

        fun plan(classes: List<ClassFile>): NestAccess {
            val byName = HashMap<String, ClassFile>(classes.size * 2)
            for (cf in classes) byName[cf.thisClassName] = cf
            if (classes.none { cf -> cf.attributes.any { it is NestHost } }) return NONE

            val fields = HashSet<String>()
            val methods = HashSet<String>()
            val bridges = LinkedHashMap<String, LinkedHashMap<String, Bridge>>()
            for (cf in classes) {
                val caller = cf.thisClassName
                val host = hostOf(cf)
                val pool = ConstantPoolView(cf)
                for (m in cf.methods) {
                    val code = m.attributes.firstOrNull { it is Code } as Code? ?: continue
                    for (insn in BytecodeDecoder.decode(code.code).insns) {
                        val isField = when (insn.opcode) {
                            Op.GETFIELD, Op.PUTFIELD, Op.GETSTATIC, Op.PUTSTATIC -> true
                            Op.INVOKEVIRTUAL, Op.INVOKESPECIAL, Op.INVOKESTATIC, Op.INVOKEINTERFACE -> false
                            else -> continue
                        }
                        val ref = pool.member(insn.a)
                        if (ref.owner == caller) continue
                        val target = byName[ref.owner] ?: continue
                        if (hostOf(target) != host) continue
                        if (isField) {
                            val f = target.fields.firstOrNull {
                                target.utf8(it.nameIndex) == ref.name && target.utf8(it.descriptorIndex) == ref.descriptor
                            } ?: continue
                            if (f.accessFlags and ACC_PRIVATE != 0) fields.add(key(ref.owner, ref.name, ref.descriptor))
                        } else {
                            val tm = target.methods.firstOrNull {
                                target.utf8(it.nameIndex) == ref.name && target.utf8(it.descriptorIndex) == ref.descriptor
                            } ?: continue
                            if (tm.accessFlags and ACC_PRIVATE == 0) continue
                            if (ref.name == "<init>" || tm.accessFlags and ACC_STATIC != 0) {
                                methods.add(key(ref.owner, ref.name, ref.descriptor))
                            } else {
                                bridges.getOrPut(ref.owner) { LinkedHashMap() }
                                    .getOrPut(ref.name + ref.descriptor) { Bridge(ref.owner, ref.name, ref.descriptor) }
                            }
                        }
                    }
                }
            }
            return NestAccess(fields, methods, bridges.mapValues { it.value.values.toList() })
        }

        private fun hostOf(cf: ClassFile): String {
            val attr = cf.attributes.firstOrNull { it is NestHost } as NestHost? ?: return cf.thisClassName
            return cf.className(attr.hostClassIndex)
        }
    }
}
