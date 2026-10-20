package com.tim.d2c.cfg

import com.tim.dex.data.Opcode

/** Классификация опкодов Dalvik для построения графа управления. */
internal object Insns {

    /** Может ли инструкция бросить исключение (по правилам верификатора ART). */
    fun canThrow(op: Opcode): Boolean = THROWING[op.ordinal]

    /** Инструкция не передаёт управление следующей по адресу. */
    fun endsFlow(op: Opcode): Boolean = when (op) {
        Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32,
        Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT,
        Opcode.THROW -> true
        else -> false
    }

    fun isGoto(op: Opcode): Boolean = op == Opcode.GOTO || op == Opcode.GOTO_16 || op == Opcode.GOTO_32

    fun isIf(op: Opcode): Boolean = op.value in Opcode.IF_EQ.value..Opcode.IF_LEZ.value

    fun isSwitch(op: Opcode): Boolean = op == Opcode.PACKED_SWITCH || op == Opcode.SPARSE_SWITCH

    fun isInvoke(op: Opcode): Boolean =
        op.value in Opcode.INVOKE_VIRTUAL.value..Opcode.INVOKE_INTERFACE_RANGE.value ||
            op.value in Opcode.INVOKE_POLYMORPHIC.value..Opcode.INVOKE_CUSTOM_RANGE.value

    fun isMoveResult(op: Opcode): Boolean =
        op == Opcode.MOVE_RESULT || op == Opcode.MOVE_RESULT_WIDE || op == Opcode.MOVE_RESULT_OBJECT

    private val THROWING = BooleanArray(Opcode.entries.size).also { t ->
        val ops = listOf(
            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO, Opcode.CONST_CLASS,
            Opcode.CONST_METHOD_HANDLE, Opcode.CONST_METHOD_TYPE,
            Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT, Opcode.CHECK_CAST, Opcode.INSTANCE_OF,
            Opcode.ARRAY_LENGTH, Opcode.NEW_INSTANCE, Opcode.NEW_ARRAY,
            Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE, Opcode.FILL_ARRAY_DATA, Opcode.THROW,
            Opcode.DIV_INT, Opcode.REM_INT, Opcode.DIV_LONG, Opcode.REM_LONG,
            Opcode.DIV_INT_2ADDR, Opcode.REM_INT_2ADDR, Opcode.DIV_LONG_2ADDR, Opcode.REM_LONG_2ADDR,
            Opcode.DIV_INT_LIT16, Opcode.REM_INT_LIT16, Opcode.DIV_INT_LIT8, Opcode.REM_INT_LIT8
        )
        for (op in ops) t[op.ordinal] = true
        for (op in Opcode.entries) {
            val v = op.value
            if (v in Opcode.AGET.value..Opcode.SPUT_SHORT.value) t[op.ordinal] = true
            if (isInvoke(op)) t[op.ordinal] = true
        }
    }
}
