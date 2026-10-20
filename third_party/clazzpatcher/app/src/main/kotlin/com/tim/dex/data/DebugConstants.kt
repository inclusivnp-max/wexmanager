package com.tim.dex.data

/**
 * Константы для отладочной информации согласно спецификации DEX.
 */
object DebugConstants {
    // Опкоды отладки (DBG_*)
    const val DBG_END_SEQUENCE = 0x00
    const val DBG_ADVANCE_PC = 0x01
    const val DBG_ADVANCE_LINE = 0x02
    const val DBG_START_LOCAL = 0x03
    const val DBG_START_LOCAL_EXTENDED = 0x04
    const val DBG_END_LOCAL = 0x05
    const val DBG_RESTART_LOCAL = 0x06
    const val DBG_SET_PROLOGUE_END = 0x07
    const val DBG_SET_EPILOGUE_BEGIN = 0x08
    const val DBG_SET_FILE = 0x09
    
    // Первый специальный опкод
    const val DBG_FIRST_SPECIAL = 0x0A
    const val DBG_LINE_BASE = -4
    const val DBG_LINE_RANGE = 15
}
