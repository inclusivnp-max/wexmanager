package com.tim.d2c.translate

/**
 * Имена меток `aN` (начало инструкции DEX с индексом N), общие для всех методов и потоков:
 * строка создаётся один раз, её хеш кэшируется — метки не стоят сборки строк на каждую инструкцию.
 */
internal object Labels {
    @Volatile
    private var names: Array<String?> = arrayOfNulls(4096)

    fun a(i: Int): String {
        val arr = names
        if (i < arr.size) {
            arr[i]?.let { return it }
            return "a$i".also { arr[i] = it } // гонка безвредна: строки равны
        }
        synchronized(this) {
            if (i >= names.size) names = names.copyOf(maxOf(names.size * 2, i + 1))
        }
        return a(i)
    }
}
