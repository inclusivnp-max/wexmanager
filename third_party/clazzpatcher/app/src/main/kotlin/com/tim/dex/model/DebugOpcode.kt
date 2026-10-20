package com.tim.dex.model

/**
 * Опкоды отладки (DBG_*) согласно спецификации DEX.
 */
sealed class DebugOpcode {
    /** DBG_END_SEQUENCE (0x00) - Конец последовательности отладки */
    object EndSequence : DebugOpcode()
    
    /** DBG_ADVANCE_PC (0x01) - Увеличить счётчик команд */
    data class AdvancePc(
        val addressDelta: Int
    ) : DebugOpcode()
    
    /** DBG_ADVANCE_LINE (0x02) - Увеличить счётчик строк */
    data class AdvanceLine(
        val lineDelta: Int
    ) : DebugOpcode()
    
    /** DBG_START_LOCAL (0x03) - Начало локальной переменной */
    data class StartLocal(
        val registerNum: Int,
        val nameIdx: Int,
        val typeIdx: Int
    ) : DebugOpcode()
    
    /** DBG_START_LOCAL_EXTENDED (0x04) - Начало локальной переменной с сигнатурой */
    data class StartLocalExtended(
        val registerNum: Int,
        val nameIdx: Int,
        val typeIdx: Int,
        val sigIdx: Int
    ) : DebugOpcode()
    
    /** DBG_END_LOCAL (0x05) - Конец локальной переменной */
    data class EndLocal(
        val registerNum: Int
    ) : DebugOpcode()
    
    /** DBG_RESTART_LOCAL (0x06) - Перезапуск локальной переменной */
    data class RestartLocal(
        val registerNum: Int
    ) : DebugOpcode()
    
    /** DBG_SET_PROLOGUE_END (0x07) - Конец пролога метода */
    object SetPrologueEnd : DebugOpcode()
    
    /** DBG_SET_EPILOGUE_BEGIN (0x08) - Начало эпилога метода */
    object SetEpilogueBegin : DebugOpcode()
    
    /** DBG_SET_FILE (0x09) - Установить файл исходного кода */
    data class SetFile(
        val nameIdx: Int
    ) : DebugOpcode()
    
    /** Специальные опкоды (0x0A-0xFF) - комбинация AdvanceLine + AdvancePc */
    data class SpecialOpcode(
        val opcode: Int,
        val lineDelta: Int,
        val addressDelta: Int
    ) : DebugOpcode()
}
