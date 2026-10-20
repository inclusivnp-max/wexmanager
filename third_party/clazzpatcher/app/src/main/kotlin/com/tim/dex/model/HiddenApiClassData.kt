package com.tim.dex.model

/**
 * Секция hiddenapi_class_data_item (флаги ограничений скрытого API).
 *
 * В DEX секция одна: `DexFile.hiddenApiClassDataItems` содержит 0 или 1 элемент.
 *
 * @property flags Флаги по индексу class_def (`flags.size == classDefs.size`).
 *   `null` — у класса нет флагов (смещение 0 в секции). Иначе — по одному значению
 *   на каждый член class_data в порядке кодирования: static-поля, instance-поля,
 *   direct-методы, virtual-методы (размер массива = число членов class_data).
 */
data class HiddenApiClassData(
    val flags: List<IntArray?>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HiddenApiClassData || other.flags.size != flags.size) return false
        for (i in flags.indices) {
            val a = flags[i]
            val b = other.flags[i]
            if (a == null || b == null) {
                if (a !== b) return false
            } else if (!a.contentEquals(b)) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (f in flags) result = 31 * result + (f?.contentHashCode() ?: 0)
        return result
    }
}
