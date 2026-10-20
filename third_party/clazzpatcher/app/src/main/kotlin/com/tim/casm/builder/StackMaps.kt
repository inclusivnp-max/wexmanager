package com.tim.casm.builder

import com.tim.casm.model.RawStackMapFrame
import com.tim.casm.model.RawVerificationType
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.clazz.model.attributes.*

/** Перевод фреймов [RawStackMapFrame] (типы — дескрипторами) в атрибут StackMapTable с индексами пула. */
object StackMaps {

    fun toAttribute(pool: ConstantPoolBuilder, frames: List<RawStackMapFrame>): StackMapTable =
        StackMapTable(pool.utf8("StackMapTable"), 0, frames.map { toFrame(pool, it) })

    fun toFrame(pool: ConstantPoolBuilder, f: RawStackMapFrame): StackMapFrame {
        val t = f.frameType
        return when {
            t in 0..63 -> SameFrame(t)
            t in 64..127 -> SameLocals1StackItemFrame(t, type(pool, f.stack.single()))
            t == 247 -> SameLocals1StackItemFrameExtended(t, f.offsetDelta, type(pool, f.stack.single()))
            t in 248..250 -> ChopFrame(t, f.offsetDelta)
            t == 251 -> SameFrameExtended(t, f.offsetDelta)
            t in 252..254 -> AppendFrame(t, f.offsetDelta, f.locals.map { type(pool, it) })
            t == 255 -> FullFrame(t, f.offsetDelta, f.locals.map { type(pool, it) }, f.stack.map { type(pool, it) })
            else -> throw IllegalArgumentException("Некорректный тип фрейма StackMapTable: $t")
        }
    }

    private fun type(pool: ConstantPoolBuilder, v: RawVerificationType): VerificationTypeInfo = when (v.tag) {
        VerificationTypeInfo.ITEM_Object -> {
            val d = v.descriptor ?: throw IllegalArgumentException("ITEM_Object без дескриптора")
            VerificationTypeInfo(v.tag, pool.classInfo(if (d.startsWith("L")) d.substring(1, d.length - 1) else d))
        }
        VerificationTypeInfo.ITEM_Uninitialized -> VerificationTypeInfo(v.tag, v.value)
        else -> VerificationTypeInfo.simple(v.tag)
    }
}
