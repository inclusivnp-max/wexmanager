package com.tim.dex.data

/**
 * Константы видимости аннотаций.
 * Согласно спецификации DEX.
 */
object AnnotationVisibility {
    /** Видимость только во время компиляции */
    const val VISIBILITY_BUILD = 0x00
    
    /** Видимость во время выполнения (через reflection) */
    const val VISIBILITY_RUNTIME = 0x01
    
    /** Системная видимость (используется внутри платформы) */
    const val VISIBILITY_SYSTEM = 0x02
}
