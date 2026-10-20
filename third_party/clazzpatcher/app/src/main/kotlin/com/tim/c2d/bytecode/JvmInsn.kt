package com.tim.c2d.bytecode

/**
 * Инструкция JVM после декодирования.
 *
 * Формы нормализованы, чтобы переводчику не приходилось различать варианты одной операции:
 * - `xLOAD_n` / `xSTORE_n` → `xLOAD` / `xSTORE` с номером локала в [a], префикс `wide` снят;
 * - `ICONST_*`, `BIPUSH`, `SIPUSH` — значение в [b];
 * - `LDC_W` → `LDC`, `GOTO_W` → `GOTO`, `JSR_W` → `JSR`.
 *
 * @property pc      адрес инструкции.
 * @property opcode  опкод JVM (константы [com.tim.clazz.model.opcodes.Opcodes]).
 * @property a       основной операнд: номер локала, индекс пула, `atype`, абсолютная цель перехода
 *                   или цель `default` у switch.
 * @property b       второй операнд: приращение `iinc`, значение константы, число измерений
 *                   `multianewarray`, `low` у `tableswitch`.
 * @property targets цели веток switch (абсолютные адреса).
 * @property keys    ключи `lookupswitch` (строго возрастают).
 */
class JvmInsn(
    @JvmField val pc: Int,
    @JvmField val opcode: Int,
    @JvmField val a: Int = 0,
    @JvmField val b: Int = 0,
    @JvmField val targets: IntArray? = null,
    @JvmField val keys: IntArray? = null
)

/**
 * Декодированный код метода.
 *
 * @property insns      инструкции в порядке адресов.
 * @property pcToIndex  индекс инструкции по адресу начала (`-1` — адрес не начало инструкции).
 * @property codeLength длина байткода.
 */
class DecodedCode(
    val insns: Array<JvmInsn>,
    private val pcToIndex: IntArray,
    val codeLength: Int
) {
    /** Индекс инструкции, начинающейся по адресу [pc], или `-1`. */
    fun indexOf(pc: Int): Int = if (pc in 0 until codeLength) pcToIndex[pc] else -1

    val size: Int get() = insns.size
}
