package com.tim.dsmali

/**
 * Флаги hidden API членов класса (секция hiddenapi_class_data) в синтаксисе smali. Раскладок две:
 * - Android 10/11 (и smali/dexlib2): значение — 3 бита (whitelist … greylist-max-r, 7 — «нет списка»),
 *   домены core-platform-api = 0x8, test-api = 0x10;
 * - Android 12+ (hiddenapi_flags.h ART): значение — 4 бита (sdk … max-target-s, 0xF — «нет списка»),
 *   домены core-platform-api = 0x10, test-api = 0x20.
 * В DEX раскладка не записана: она выбирается по флагам файла ([isLegacy]), а в тексте определяется
 * словом значения (старые имена — старая раскладка). Невыразимое значение — `hiddenapi-0x…`.
 */
object HiddenApi {
    private val MODERN = listOf("sdk", "unsupported", "blocked", "max-target-o", "max-target-p", "max-target-q", "max-target-r", "max-target-s")
    private val LEGACY = listOf("whitelist", "greylist", "blacklist", "greylist-max-o", "greylist-max-p", "greylist-max-q", "greylist-max-r")
    private const val CORE_PLATFORM = "core-platform-api"
    private const val TEST = "test-api"
    private const val RAW = "hiddenapi-0x"

    /** Слово флагов hidden API (не access_flags). */
    fun isWord(w: String): Boolean = w in MODERN || w in LEGACY || w == CORE_PLATFORM || w == TEST || w.startsWith(RAW)

    /**
     * Раскладка флагов DEX: новая, если есть домен 0x20 (test-api Android 12+; в старой раскладке
     * больше 0x1F не бывает); иначе старая, если в значении есть бит 0x8 (в новой значения 8…14
     * недопустимы). Остальное неоднозначно — новая (текущие версии Android); повторная сборка точна
     * при любом выборе, различаются только имена.
     */
    fun isLegacy(flags: Sequence<Int>): Boolean {
        var legacy = false
        for (v in flags) {
            if (v and 0x20 != 0) return false
            if (v and 0x8 != 0 && v and 0xF != 0xF) legacy = true
        }
        return legacy
    }

    /** Слова для значения [v] в раскладке [legacy]. */
    fun words(v: Int, legacy: Boolean): List<String> {
        val (names, valueMask, core, test) = if (legacy) Layout(LEGACY, 0x7, 0x8, 0x10) else Layout(MODERN, 0xF, 0x10, 0x20)
        val value = v and valueMask
        if (value >= names.size || v and (valueMask or core or test).inv() != 0) return listOf(RAW + Integer.toHexString(v))
        val out = ArrayList<String>(3)
        out.add(names[value])
        if (v and core != 0) out.add(CORE_PLATFORM)
        if (v and test != 0) out.add(TEST)
        return out
    }

    /** Значение по словам списка флагов; -1 — слов hidden API нет. */
    fun parse(words: List<String>): Int {
        var value = -1
        var legacy: Boolean? = null
        var core = false
        var test = false
        var raw = -1
        for (w in words) {
            when {
                w == CORE_PLATFORM -> core = true
                w == TEST -> test = true
                w.startsWith(RAW) -> {
                    require(raw < 0) { "Два значения hidden API: ${RAW}… дважды" }
                    raw = w.substring(RAW.length).toLongOrNull(16)?.takeIf { it in 0..0xFFFFFFFFL }?.toInt()
                        ?: throw IllegalArgumentException("Некорректное значение hidden API: $w")
                }
                w in MODERN || w in LEGACY -> {
                    val isLegacy = w in LEGACY
                    require(value < 0) { "Два ограничения hidden API в одном списке флагов (второе — $w)" }
                    value = if (isLegacy) LEGACY.indexOf(w) else MODERN.indexOf(w)
                    legacy = isLegacy
                }
            }
        }
        if (raw >= 0) {
            require(value < 0 && !core && !test) { "$RAW… нельзя сочетать с другими словами hidden API" }
            return raw
        }
        if (value < 0) {
            require(!core && !test) { "Флаги $CORE_PLATFORM/$TEST без ограничения hidden API" }
            return -1
        }
        val l = legacy == true
        return value or (if (core) (if (l) 0x8 else 0x10) else 0) or (if (test) (if (l) 0x10 else 0x20) else 0)
    }

    private data class Layout(val names: List<String>, val valueMask: Int, val core: Int, val test: Int)
}
