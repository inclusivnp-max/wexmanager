package org.wexclient.app.editor.lang

internal data class WexBuiltin(val name: String, val parameters: List<String>, val result: String, val description: String) {
    val detail: String get() = "(${parameters.joinToString(", ")}) → $result · $description"
}

/** Имена и параметры соответствуют compiler/codegen.cpp языка WEX. */
internal object WexSupport {
    val modules = listOf("memory", "gui", "process")
    val builtins: List<WexBuiltin> = """
        log|value|null|Вывести значение в журнал
        print|value|null|Вывести значение в журнал
        sleep_ms|milliseconds|null|Пауза в миллисекундах
        hex|value|string|Шестнадцатеричная запись числа
        security_check||bool|Проверить окружение виртуальной машины
        memory.find_module|name|int|Адрес загруженного модуля
        memory.read_u8|addr|int|Прочитать 8-битное число
        memory.read_u16|addr|int|Прочитать 16-битное число
        memory.read_u32|addr|int|Прочитать 32-битное число
        memory.read_u64|addr|int|Прочитать 64-битное число
        memory.read_ptr|addr|int|Прочитать адрес
        memory.read_float|addr|float|Прочитать число с плавающей точкой
        memory.write_u8|addr, value|bool|Записать 8-битное число
        memory.write_u32|addr, value|bool|Записать 32-битное число
        memory.write_float|addr, value|bool|Записать число с плавающей точкой
        memory.patch|addr, bytes|bool|Применить строку шестнадцатеричных байтов
        memory.restore|addr|bool|Восстановить сохранённые байты
        memory.read_chain|base, offsets...|int|Разыменовать цепочку адресов
        memory.scan_pattern|module, pattern|int|Найти последовательность байтов с маской
        memory.dump|addr, size|string|Получить шестнадцатеричное представление памяти
        memory.alloc|size|int|Выделить блок памяти
        memory.free|addr, size|bool|Освободить блок памяти
        process.pid||int|Идентификатор текущего процесса
        gui.begin|title, width, height|bool|Открыть окно; размеры можно опустить
        gui.end||bool|Завершить окно
        gui.text|text|bool|Вывести текст
        gui.button|label|bool|Состояние нажатия кнопки
        gui.checkbox|label, state|bool|Новое состояние переключателя
        gui.slider|label, value, min, max|float|Новое значение ползунка
        gui.separator||bool|Вывести разделитель
        gui.progress|fraction, text|bool|Индикатор выполнения
        gui.draw_line|x1, y1, x2, y2, color|bool|Нарисовать линию
        gui.draw_rect|x, y, width, height, color|bool|Нарисовать прямоугольник
        gui.draw_text|x, y, text, color|bool|Нарисовать текст
    """.trimIndent().lineSequence().map { line ->
        val fields = line.split('|')
        WexBuiltin(fields[0], fields[1].split(", ").filter { it.isNotEmpty() }, fields[2], fields[3])
    }.toList()

    val spec = LexSpec(
        keywords = words("var const fn import export"),
        control = words("if else while return"),
        constants = words("true false null"),
        builtins = modules.toSet() + builtins.filter { '.' !in it.name }.map { it.name },
        strings = listOf(StringRule("\"", multiline = true)),
        blockComments = listOf(BlockComment("/*", "*/")),
        capitalizedTypes = false,
        numberSuffixes = "",
        operators = "+-*/%=<>!&|",
        declFunction = setOf("fn")
    )

    fun vocabulary() = Vocabulary(
        modules.map { VocabWord(it, ItemKind.MODULE, "модуль WEX") } +
            builtins.map { VocabWord(it.name, ItemKind.FUNCTION, it.detail) },
        listOf(
            Snippet("fn", "Функция", "fn \${1:name}(\${2:args}) {\n    \$0\n}"),
            Snippet("exportfn", "Экспортируемая функция", "export fn \${1:name}(\${2:args}) {\n    \$0\n}"),
            Snippet("if", "Условие", "if (\${1:condition}) {\n    \$0\n}"),
            Snippet("ifelse", "Условие с альтернативой", "if (\${1:condition}) {\n    \${2}\n} else {\n    \$0\n}"),
            Snippet("while", "Цикл с условием", "while (\${1:condition}) {\n    \$0\n}"),
            Snippet("var", "Переменная", "var \${1:name} = \${2:value};\$0"),
            Snippet("const", "Константа", "const \${1:NAME} = \${2:value};\$0"),
            Snippet("import", "Подключить модуль", "import \${1:memory};\$0")
        )
    )
}
