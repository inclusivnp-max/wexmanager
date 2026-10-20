package com.tim.dasm.model

/**
 * Типы method handle (`method_handle_item.method_handle_type`) и их имена в синтаксисе smali.
 */
object MethodHandleKind {
    const val STATIC_PUT = 0x00
    const val STATIC_GET = 0x01
    const val INSTANCE_PUT = 0x02
    const val INSTANCE_GET = 0x03
    const val INVOKE_STATIC = 0x04
    const val INVOKE_INSTANCE = 0x05
    const val INVOKE_CONSTRUCTOR = 0x06
    const val INVOKE_DIRECT = 0x07
    const val INVOKE_INTERFACE = 0x08

    private val NAMES = arrayOf(
        "static-put", "static-get", "instance-put", "instance-get",
        "invoke-static", "invoke-instance", "invoke-constructor", "invoke-direct", "invoke-interface"
    )
    private val BY_NAME: Map<String, Int> = NAMES.withIndex().associate { (i, n) -> n to i }

    /** Ссылается ли handle этого типа на поле (иначе — на метод). */
    fun isField(kind: Int): Boolean = kind in STATIC_PUT..INSTANCE_GET

    fun name(kind: Int): String = NAMES.getOrNull(kind)
        ?: throw IllegalArgumentException("Неизвестный тип method handle: $kind")

    fun byName(name: String): Int? = BY_NAME[name]
}
