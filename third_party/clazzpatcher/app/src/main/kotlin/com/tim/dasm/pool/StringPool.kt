package com.tim.dasm.pool

import com.tim.dasm.DexAsmState

/**
 * Пул строк (хэш-индекс в [DexAsmState.stringIndex]).
 *
 * Сортировка и дедупликация `string_data_item` — ответственность
 * [com.tim.dex.writer.DexWriter]. Здесь — только модель.
 */
class StringPool(private val state: DexAsmState) {

    /** Первое вхождение строки. */
    fun find(value: String): Int? = state.stringIndex[value]

    /** Добавить строку. Если уже есть — вернуть её индекс. */
    fun add(value: String): Int {
        state.stringIndex[value]?.let { return it }
        val idx = state.strings.size
        state.strings.add(value)
        state.stringIndex[value] = idx
        return idx
    }

    /** Строка или `null`, если индекс `-1` (NO_INDEX). */
    fun addNullable(value: String?): Int = if (value == null) -1 else add(value)

    fun get(idx: Int): String? = state.strings.getOrNull(idx)
}
