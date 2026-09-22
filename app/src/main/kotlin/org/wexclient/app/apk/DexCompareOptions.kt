package org.wexclient.app.apk

/**
 * Что считать «одинаковым» при сравнении кода. Пересборка того же исходника меняет номера
 * регистров, разрядность опкодов и отладочные строки — без этих поблажек изменённым
 * оказывается весь пакет целиком.
 */
data class DexCompareOptions(
    /** .line, .source, .local и прочие пометки отладчика. */
    val ignoreDebugInfo: Boolean = true,
    /** const/4 и const/16, invoke-virtual и invoke-virtual/range — одно и то же действие. */
    val ignoreCompilerOptimization: Boolean = true,
    /** Номера регистров v0, p1 — распределение регистров у компилятора своё в каждой сборке. */
    val ignoreRegisters: Boolean = true,
    /** Переставленные местами инструкции внутри метода. */
    val ignoreInstructionOrder: Boolean = true,
    /** Идентификаторы ресурсов 0x7f…: при пересборке ресурсов они смещаются все разом. */
    val ignoreResourceIds: Boolean = false
) {
    val label: String
        get() = listOfNotNull(
            "отладка".takeIf { ignoreDebugInfo },
            "оптимизация".takeIf { ignoreCompilerOptimization },
            "регистры".takeIf { ignoreRegisters },
            "порядок".takeIf { ignoreInstructionOrder },
            "id ресурсов".takeIf { ignoreResourceIds }
        ).joinToString(", ").ifEmpty { "всё учитывается" }
}

/** Приведение smali к виду, в котором мелкие различия сборки не считаются изменением. */
object SmaliNormalizer {

    private val REGISTER = Regex("""\b([vp])\d+\b""")
    private val RESOURCE_ID = Regex("""0x7f[0-9a-fA-F]{6}\b""")
    private val DEBUG_PREFIXES = listOf(
        ".line", ".source", ".local", ".end local", ".restart local", ".prologue", ".epilogue"
    )

    /** Строка кода без учёта выбранных мелочей; null — строку вообще не сравниваем. */
    fun line(raw: String, options: DexCompareOptions): String? {
        val text = raw.trim()
        if (options.ignoreDebugInfo && DEBUG_PREFIXES.any { text.startsWith(it) }) return null
        var result = text
        if (options.ignoreRegisters) result = REGISTER.replace(result) { m -> m.groupValues[1] + "#" }
        if (options.ignoreResourceIds) result = RESOURCE_ID.replace(result, "0x7fRES")
        if (options.ignoreCompilerOptimization) result = normalizeOpcodeInLine(result)
        return result
    }

    /** Весь текст класса построчно: выброшенные строки заменяются пустыми, чтобы номера не съехали. */
    fun lines(text: String, options: DexCompareOptions): List<String> =
        text.lines().map { line(it, options) ?: "" }

    /** `invoke-virtual/range` → `invoke-virtual`, `const/16` → `const`. */
    fun normalizeOpcode(opcode: String): String = opcode.substringBefore('/')

    private fun normalizeOpcodeInLine(text: String): String {
        if (text.isEmpty() || text.startsWith('.') || text.startsWith('#') || text.startsWith(':')) return text
        val space = text.indexOf(' ')
        val head = if (space < 0) text else text.substring(0, space)
        if ('/' !in head) return text
        val normalized = normalizeOpcode(head)
        return if (space < 0) normalized else normalized + text.substring(space)
    }

    /** Похоже на идентификатор ресурса (0x7f……). */
    fun isResourceId(value: Long): Boolean = value in 0x7f000000L..0x7fffffffL
}
