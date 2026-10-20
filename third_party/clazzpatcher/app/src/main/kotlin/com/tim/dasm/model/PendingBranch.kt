package com.tim.dasm.model

import com.tim.dex.data.Opcode

/**
 * Ветвление на метку (goto/if/switch/fill-array-data), которое разрешается
 * после сборки всех инструкций метода.
 *
 * @property index  индекс placeholder'а в списке инструкций метода.
 * @property regA   регистр vA (для if-*, switch, fill-array-data).
 * @property regB   регистр vB (для if-* с двумя регистрами).
 */
data class PendingBranch(
    val label: String,
    val index: Int,
    val opcode: Opcode,
    val regA: Int = 0,
    val regB: Int = 0
)
