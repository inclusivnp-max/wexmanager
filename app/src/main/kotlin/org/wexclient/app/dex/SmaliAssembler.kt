package org.wexclient.app.dex

import com.tim.dsmali.DexClassEditor
import com.tim.dsmali.lineOf
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.reasonOf

/** Ошибка текста smali; [line] — строка (с 1), 0 — место неизвестно. */
class SmaliSyntaxException(val errors: List<String>, val line: Int = 0) :
    Exception(errors.take(8).joinToString("\n").ifEmpty { "Ошибка синтаксиса smali" })

/** Сборка smali одного класса (clazzpatcher: синтаксис baksmali/smali 3, без сторонних библиотек). */
object SmaliAssembler {

    /**
     * Разбор и пробная сборка класса: ошибка — [SmaliSyntaxException] с номером строки. Результат ставится
     * в DEX через [DexClassEditor.rebuild].
     */
    fun assemble(smali: String): SmaliClass =
        try {
            DexClassEditor.compile(smali)
        } catch (e: Exception) {
            val line = lineOf(e)
            val reason = reasonOf(e)
            throw SmaliSyntaxException(listOf(if (line > 0) "строка $line: $reason" else reason), line)
        } catch (_: StackOverflowError) {
            throw SmaliSyntaxException(listOf("Слишком глубокая вложенность значений"))
        }
}
