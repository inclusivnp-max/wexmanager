package com.tim

import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Adler32
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Командная строка clazzpatcher.
 *
 * [run] не вызывает System.exit и пишет только в переданные потоки — удобно для тестов.
 */
object Main {
    const val VERSION = "1.0"

    private const val DEFAULT_ASM_OUTPUT = "out.dex"

    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args, System.out, System.err)
        System.out.flush()
        System.err.flush()
        exitProcess(code)
    }

    /** Выполняет команду, возвращает код выхода (0 — успех, 1 — ошибка). */
    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        val stacktrace = args.contains("--stacktrace")
        val argv = args.filter { it != "--stacktrace" }
        if (argv.isEmpty()) {
            printHelp(out)
            out.flush()
            return 1
        }
        return try {
            execute(argv, out, err)
            out.flush()
            0
        } catch (e: UsageException) {
            out.flush()
            err.println("Ошибка: ${e.message}")
            err.println("Справка: clazzpatcher --help")
            1
        } catch (e: Throwable) {
            out.flush()
            printError(e, err, stacktrace)
            1
        }
    }

    // ===== Разбор аргументов =====

    /** Ошибка в аргументах командной строки: печатается без стека, с подсказкой про --help. */
    class UsageException(message: String) : RuntimeException(message)

    private class Options(val command: String, argv: List<String>, valueOptions: Set<String>, flagOptions: Set<String>) {
        val positional = mutableListOf<String>()
        private val values = mutableMapOf<String, String>()
        private val flags = mutableSetOf<String>()

        init {
            var i = 0
            while (i < argv.size) {
                val a = argv[i]
                when {
                    a in valueOptions -> {
                        if (i + 1 >= argv.size) throw UsageException("параметр $a команды '$command' требует значения")
                        if (values.put(canonical(a), argv[i + 1]) != null) throw UsageException("параметр $a указан дважды")
                        i += 2
                        continue
                    }
                    a in flagOptions -> flags.add(canonical(a))
                    a.startsWith("-") && a.length > 1 -> throw UsageException("неизвестный параметр '$a' для команды '$command'")
                    else -> positional.add(a)
                }
                i++
            }
        }

        fun value(name: String): String? = values[name]
        fun flag(name: String): Boolean = name in flags

        fun intValue(name: String, default: Int, min: Int): Int {
            val raw = values[name] ?: return default
            val v = raw.toIntOrNull() ?: throw UsageException("значение $name должно быть целым числом: '$raw'")
            if (v < min) throw UsageException("значение $name должно быть не меньше $min: $v")
            return v
        }

        fun single(what: String): String {
            if (positional.isEmpty()) throw UsageException("команда '$command': не указан $what")
            if (positional.size > 1) throw UsageException("команда '$command': лишние аргументы: ${positional.drop(1).joinToString(" ")}")
            return positional[0]
        }

        private fun canonical(opt: String): String = when (opt) {
            "-o", "--output" -> "-o"
            "-n", "--iterations" -> "-n"
            "-w", "--warmup" -> "-w"
            "-f", "--force" -> "-f"
            else -> opt
        }
    }

    private fun execute(argv: List<String>, out: PrintStream, err: PrintStream) {
        val command = argv[0]
        val rest = argv.drop(1)
        when (command) {
            "-h", "--help", "help" -> printHelp(out)
            "-v", "--version", "version" -> out.println("clazzpatcher $VERSION")
            "info" -> info(Options(command, rest, emptySet(), emptySet()), out, err)
            "d", "disasm" -> disassemble(Options(command, rest, setOf("-o", "--output"), setOf("-f", "--force")), out, err)
            "a", "asm" -> assemble(Options(command, rest, setOf("-o", "--output"), emptySet()), out)
            "rt" -> dexRoundTrip(Options(command, rest, setOf("-o", "--output"), emptySet()), out)
            "class-rt" -> classRoundTrip(Options(command, rest, setOf("-o", "--output"), emptySet()), out)
            "cd", "class-disasm" -> classDisassemble(Options(command, rest, setOf("-o", "--output"), setOf("-f", "--force")), out)
            "ca", "class-asm" -> classAssemble(Options(command, rest, setOf("-o", "--output", "--cp"), emptySet()), out)
            "c2d" -> classToDex(Options(command, rest, setOf("-o", "--output"), setOf("--no-debug", "--no-invisible", "--no-desugar")), out)
            "d2c" -> dexToClass(Options(command, rest, setOf("-o", "--output", "--cp", "--target"), setOf("--lenient", "--no-debug")), out, err)
            "bench" -> bench(Options(command, rest, setOf("-n", "--iterations", "-w", "--warmup", "-o", "--output"), emptySet()), out)
            else -> throw UsageException("неизвестная команда '$command'")
        }
    }

    private fun printHelp(out: PrintStream) {
        out.println(
            """
            |clazzpatcher $VERSION — DEX/smali/.class без сторонних библиотек
            |
            |Использование: clazzpatcher <команда> [параметры]
            |
            |Команды:
            |  info <file.dex|file.apk>               версия, количество элементов, секции (map_list)
            |  d, disasm <file.dex|file.apk> [-o dir] [-f]
            |                                         DEX -> smali; для APK/ZIP каждый classes*.dex
            |                                         в smali, smali_classes2, ... (как apktool).
            |                                         По умолчанию dir = ./<имя входного файла без расширения>;
            |                                         -f: очистить непустой каталог
            |  a, asm <smaliDir> [-o out.dex]         smali -> DEX (по умолчанию ./$DEFAULT_ASM_OUTPUT)
            |  rt <file.dex> [-o out.dex]             чтение и запись DEX без изменений (DexReader -> DexWriter);
            |                                         по умолчанию ./<имя>_rt.dex
            |  class-rt <in.class> [-o out.class]     чтение и запись .class (ClassReader -> ClassWriter);
            |                                         по умолчанию ./<имя>_rt.class
            |  cd, class-disasm <in.class|dir|jar> [-o dir] [-f]
            |                                         .class -> csmali (smali-синтаксис для JVM-байткода);
            |                                         по умолчанию dir = ./<имя входа>_csmali
            |  ca, class-asm <dir|file.smali> [-o dir|out.jar] [--cp пути]
            |                                         csmali -> .class, фреймы StackMapTable считаются заново;
            |                                         --cp: jar/каталоги (через ${File.pathSeparator}) для иерархии классов
            |                                         (JDK и собираемые классы учитываются всегда); по умолчанию ./classes
            |  c2d <in.class|dir|jar>... [-o out.dex|dir] [--no-debug] [--no-invisible] [--no-desugar]
            |                                         .class -> DEX; -o *.dex — один файл, каталог — classes.dex,
            |                                         classes2.dex, … (по пределу 65535 ссылок); по умолчанию ./classes.dex.
            |                                         --no-debug: без строк/локалов; --no-invisible: без аннотаций CLASS;
            |                                         --no-desugar: invokedynamic (лямбды, конкатенация, record, switch)
            |                                         оставить invoke-custom — в ART они не работают
            |  d2c <file.dex|file.apk> [-o out.jar|dir] [--cp пути] [--target N] [--lenient] [--no-debug]
            |                                         DEX -> .class (classes.dex, classes2.dex, … из APK — как в ART); по умолчанию ./<имя>.jar.
            |                                         --cp: jar/каталоги (например android.jar) для иерархии классов;
            |                                         --target: версия class-файла (по умолчанию 52; повышается
            |                                         у классов, которым нужна более новая);
            |                                         --lenient: невыразимое в JVM — предупреждение, а не ошибка
            |  bench <file.dex> [-n N] [-w N] [-o dir]
            |                                         замер фаз: чтение, запись, дизассемблирование, сохранение
            |                                         и загрузка smali, ассемблирование, запись; -n замеров
            |                                         (по умолчанию 3), -w прогревов (по умолчанию 1),
            |                                         -o каталог для временных smali-файлов
            |
            |Общие параметры:
            |  -h, --help        эта справка
            |  -v, --version     версия
            |  --stacktrace      печатать полный стек при ошибке
            """.trimMargin()
        )
    }

    private fun printError(e: Throwable, err: PrintStream, stacktrace: Boolean) {
        val lines = mutableListOf<String>()
        var t: Throwable? = e
        val seen = HashSet<Throwable>()
        while (t != null && seen.add(t)) {
            val text = describe(t)
            // Обёртки часто повторяют сообщение причины целиком — не дублируем
            if (lines.isEmpty() || !lines.last().contains(text)) lines.add(text)
            t = t.cause
        }
        err.println("Ошибка: ${lines[0]}")
        lines.drop(1).forEach { err.println("  причина: $it") }
        if (e is OutOfMemoryError) err.println("Не хватило памяти JVM: увеличьте -Xmx (например, CLAZZPATCHER_JAVA_OPTS=-Xmx8g ./clazzpatcher ...)")
        if (stacktrace) e.printStackTrace(err) else err.println("(полный стек: --stacktrace)")
    }

    private fun describe(t: Throwable): String {
        val msg = t.message?.takeIf { it.isNotBlank() }
        return when {
            msg == null -> t.toString()
            t is IllegalStateException || t is IllegalArgumentException || t is UsageException -> msg
            else -> t.toString()
        }
    }

    // ===== Входные файлы =====

    private class DexInput(val name: String, val bytes: ByteArray)

    /** Содержимое входного файла: один DEX или classes*.dex из архива. */
    private class DexInputs(val fromArchive: Boolean, val dexes: List<DexInput>)

    private val CLASSES_DEX = Regex("""classes(\d*)\.dex""")

    private fun existingFile(path: String): File {
        val f = File(path)
        if (!f.exists()) throw IllegalArgumentException("файл не найден: ${f.path}")
        if (!f.isFile) throw IllegalArgumentException("не файл: ${f.path}")
        return f
    }

    private fun isZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
    bytes[2].toInt() == 3 && bytes[3].toInt() == 4

    private fun isDex(bytes: ByteArray): Boolean =
    bytes.size >= 8 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
    bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    /**
     * DEX-файл или classes*.dex из APK/ZIP так, как их загружает ART: classes.dex, classes2.dex, … до первого
     * пропуска в нумерации. Прочие похожие записи (classes1.dex, classes02.dex, после пропуска) не входят
     * в приложение — они пропускаются с предупреждением.
     */
    private fun readDexInputs(file: File, err: PrintStream): DexInputs {
        val bytes = file.readBytes()
        if (isDex(bytes)) return DexInputs(false, listOf(DexInput(file.name, bytes)))
        if (!isZip(bytes)) throw IllegalArgumentException("${file.path}: не DEX и не APK/ZIP (сигнатура ${hexPrefix(bytes)})")
        val result = mutableListOf<DexInput>()
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.filter { CLASSES_DEX.matches(it) }.toSet()
            var n = 1
            while (true) {
                val name = if (n == 1) "classes.dex" else "classes$n.dex"
                if (name !in names) break
                val data = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                if (!isDex(data)) throw IllegalArgumentException("${file.path}!$name: не DEX-файл (сигнатура ${hexPrefix(data)})")
                result.add(DexInput(name, data))
                n++
            }
            val skipped = names - result.map { it.name }.toSet()
            if (skipped.isNotEmpty()) {
                err.println("Предупреждение: ${file.path}: ART не загружает ${skipped.sorted().joinToString()} (нумерация classes.dex, classes2.dex, … без пропусков) — пропущено")
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException("${file.path}: в архиве нет classes.dex")
        return DexInputs(true, result)
    }

    private fun readDexModels(file: File, err: PrintStream): List<com.tim.dex.model.DexFile> =
        readDexInputs(file, err).dexes.map { input ->
            try { DexReader().read(input.bytes) } catch (e: Exception) { throw IllegalStateException("${input.name}: ошибка чтения DEX", e) }
        }

    private fun readSingleDex(path: String): DexInput {
        val file = existingFile(path)
        val bytes = file.readBytes()
        if (!isDex(bytes)) throw IllegalArgumentException("${file.path}: не DEX-файл (сигнатура ${hexPrefix(bytes)})")
        return DexInput(file.name, bytes)
    }

    private fun hexPrefix(bytes: ByteArray): String =
    bytes.take(8).joinToString(" ") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }

    /** Имя smali-каталога для classesN.dex в раскладке apktool. */
    private fun smaliDirName(dexName: String): String {
        val m = CLASSES_DEX.matchEntire(dexName) ?: throw IllegalStateException("неожиданное имя DEX в архиве: $dexName")
        return if (m.groupValues[1].isEmpty() || m.groupValues[1] == "1") "smali" else "smali_classes${m.groupValues[1]}"
    }

    // ===== info =====

    private val MAP_TYPE_NAMES = mapOf(
        0x0000 to "header_item", 0x0001 to "string_id_item", 0x0002 to "type_id_item", 0x0003 to "proto_id_item",
        0x0004 to "field_id_item", 0x0005 to "method_id_item", 0x0006 to "class_def_item", 0x0007 to "call_site_id_item",
        0x0008 to "method_handle_item", 0x1000 to "map_list", 0x1001 to "type_list", 0x1002 to "annotation_set_ref_list",
        0x1003 to "annotation_set_item", 0x2000 to "class_data_item", 0x2001 to "code_item", 0x2002 to "string_data_item",
        0x2003 to "debug_info_item", 0x2004 to "annotation_item", 0x2005 to "encoded_array_item",
        0x2006 to "annotations_directory_item", 0xF000 to "hiddenapi_class_data_item"
    )

    private fun info(opts: Options, out: PrintStream, err: PrintStream) {
        val file = existingFile(opts.single("входной файл"))
        val inputs = readDexInputs(file, err)
        out.println("Файл: ${file.path} (${file.length()} байт)")
        for (input in inputs.dexes) {
            if (inputs.fromArchive) out.println("\n== ${input.name}")
            printDexInfo(input, out)
        }
    }

    private fun printDexInfo(input: DexInput, out: PrintStream) {
        val bytes = input.bytes
        if (bytes.size < 0x70) throw IllegalArgumentException("${input.name}: файл короче заголовка DEX (${bytes.size} байт)")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = String(bytes, 4, 3, Charsets.US_ASCII)
        val checksum = b.getInt(8)
        val fileSize = b.getInt(32)
        val headerSize = b.getInt(36)
        val endian = b.getInt(40)
        if (endian != 0x12345678) throw IllegalArgumentException("${input.name}: неподдерживаемый endian_tag 0x${Integer.toHexString(endian)}")
        val mapOff = b.getInt(52)
        fun count(off: Int) = b.getInt(off)

        val adler = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt()
        val sha1 = MessageDigest.getInstance("SHA-1").apply { update(bytes, 32, bytes.size - 32) }.digest()
        val signatureOk = sha1.contentEquals(bytes.copyOfRange(12, 32))

        val map = readMapList(input.name, b, mapOff)
        fun mapCount(type: Int) = map.firstOrNull { it[0] == type }?.get(1) ?: 0

        fun line(label: String, value: Any) = out.println(String.format(Locale.ROOT, "%-19s%s", "$label:", value))
        line("Версия DEX", version)
        line("Размер", "$fileSize байт" + if (fileSize != bytes.size) " (фактически ${bytes.size}!)" else "")
        line("Контрольная сумма", if (adler == checksum) "верна" else "НЕВЕРНА (в заголовке 0x${hex32(checksum)}, вычислено 0x${hex32(adler)})")
        line("Подпись SHA-1", if (signatureOk) "верна" else "НЕВЕРНА")
        line("Строк", count(56))
        line("Типов", count(64))
        line("Прототипов", count(72))
        line("Полей", count(80))
        line("Методов", count(88))
        line("Классов", count(96))
        line("Call site", mapCount(0x0007))
        line("Method handle", mapCount(0x0008))
        line("Код методов", mapCount(0x2001))
        line("Заголовок", "$headerSize байт; данные: ${count(104)} байт с 0x${hex32(count(108))}; map_list: 0x${hex32(mapOff)}")
        out.println("Секции (map_list):")
        out.println(String.format(Locale.ROOT, "  %-28s %10s %12s", "тип", "элементов", "смещение"))
        for (item in map) {
            val name = MAP_TYPE_NAMES[item[0]] ?: "0x${Integer.toHexString(item[0])} (неизвестный тип)"
            out.println(String.format(Locale.ROOT, "  %-28s %10d   0x%08x", name, item[1], item[2]))
        }
    }

    /** Элементы map_list: [тип, количество, смещение]. */
    private fun readMapList(name: String, b: ByteBuffer, mapOff: Int): List<IntArray> {
        if (mapOff <= 0 || mapOff + 4 > b.limit()) throw IllegalArgumentException("$name: map_off 0x${hex32(mapOff)} вне файла")
        val size = b.getInt(mapOff)
        if (size < 0 || mapOff + 4 + size.toLong() * 12 > b.limit()) {
            throw IllegalArgumentException("$name: map_list ($size элементов) выходит за пределы файла")
        }
        return List(size) { i ->
            val p = mapOff + 4 + i * 12
            intArrayOf(b.getShort(p).toInt() and 0xFFFF, b.getInt(p + 4), b.getInt(p + 8))
        }
    }

    private fun hex32(v: Int): String = String.format(Locale.ROOT, "%08x", v)

    // ===== d / a =====

    private fun disassemble(opts: Options, out: PrintStream, err: PrintStream) {
        val file = existingFile(opts.single("входной файл (.dex или .apk)"))
        val inputs = readDexInputs(file, err)
        val outDir = File(opts.value("-o") ?: file.name.substringBeforeLast('.'))
        prepareOutputDir(outDir, opts.flag("-f"), file)

        for (input in inputs.dexes) {
            val target = if (inputs.fromArchive) File(outDir, smaliDirName(input.name)) else outDir
            val t0 = System.nanoTime()
            val tree = try {
                Smali.disassemble(DexReader().read(input.bytes))
            } catch (e: Exception) {
                throw IllegalStateException("${input.name}: ошибка дизассемблирования", e)
            }
            try {
                Smali.save(tree, target)
            } catch (e: Exception) {
                throw IllegalStateException("${input.name}: ошибка записи smali в ${target.path}", e)
            }
            out.println("${input.name} -> ${target.path}: ${tree.classes.size} классов за ${formatSeconds(System.nanoTime() - t0)}")
        }
    }

    /** Создаёт каталог; непустой каталог очищается только с -f и только если это безопасно. */
    private fun prepareOutputDir(dir: File, force: Boolean, input: File) {
        if (dir.exists()) {
            if (!dir.isDirectory) throw IllegalArgumentException("${dir.path} существует и не является каталогом")
            val children = dir.list() ?: throw IllegalStateException("не удалось прочитать каталог ${dir.path}")
            if (children.isNotEmpty()) {
                if (!force) throw IllegalArgumentException("каталог ${dir.path} не пуст (для перезаписи укажите -f)")
                checkSafeToDelete(dir, input)
                children.forEach { name ->
                    val child = File(dir, name)
                    if (!child.deleteRecursively()) throw IllegalStateException("не удалось удалить ${child.path}")
                }
            }
        } else if (!dir.mkdirs()) {
            throw IllegalStateException("не удалось создать каталог ${dir.path}")
        }
    }

    private fun checkSafeToDelete(dir: File, input: File) {
        val d = dir.canonicalFile
        val protected = listOf(
            File(".").canonicalFile to "текущий каталог",
            File(System.getProperty("user.home")).canonicalFile to "домашний каталог",
            input.canonicalFile to "входной файл"
        )
        if (d.parentFile == null) throw IllegalArgumentException("отказ очищать корень файловой системы: ${d.path}")
        for ((p, what) in protected) {
            var cur: File? = p
            while (cur != null) {
                if (cur == d) throw IllegalArgumentException("отказ очищать ${d.path}: он содержит $what (${p.path})")
                cur = cur!!.parentFile
            }
        }
    }

    private val SMALI_CLASSES_DIR = Regex("""smali_classes\d+""")

    private fun assemble(opts: Options, out: PrintStream) {
        val dir = File(opts.single("каталог со smali"))
        if (!dir.isDirectory) throw IllegalArgumentException("каталог не найден: ${dir.path}")
        val multidex = dir.listFiles()?.filter { it.isDirectory && it.name.matches(SMALI_CLASSES_DIR) }.orEmpty()
        if (multidex.isNotEmpty()) {
            throw IllegalArgumentException(
                "${dir.path} — разборка multidex APK (${multidex.joinToString { it.name }}): " +
                "соберите каждый каталог (smali, smali_classes2, ...) отдельной командой"
            )
        }
        val output = File(opts.value("-o") ?: DEFAULT_ASM_OUTPUT)
        val t0 = System.nanoTime()
        val tree = Smali.load(dir)
        if (tree.classes.isEmpty()) throw IllegalArgumentException("в ${dir.path} нет .smali-файлов")
        val t1 = System.nanoTime()
        output.absoluteFile.parentFile?.mkdirs()
        Smali.assemble(tree, output)
        val t2 = System.nanoTime()
        out.println(
            "${dir.path} -> ${output.path}: ${tree.classes.size} классов, ${output.length()} байт " +
            "(загрузка ${formatSeconds(t1 - t0)}, сборка ${formatSeconds(t2 - t1)})"
        )
    }

    // ===== rt / class-rt =====

    private fun dexRoundTrip(opts: Options, out: PrintStream) {
        val input = readSingleDex(opts.single("входной DEX"))
        val output = File(opts.value("-o") ?: input.name.substringBeforeLast('.') + "_rt.dex")
        val t0 = System.nanoTime()
        val dex = DexReader().read(input.bytes)
        val t1 = System.nanoTime()
        val bytes = DexWriter(dex).buildDex()
        val t2 = System.nanoTime()
        output.absoluteFile.parentFile?.mkdirs()
        output.writeBytes(bytes)
        out.println("${input.name} -> ${output.path}: ${bytes.size} байт (чтение ${formatSeconds(t1 - t0)}, запись ${formatSeconds(t2 - t1)})")
        out.println(compareBytes(input.bytes, bytes))
    }

    private fun classRoundTrip(opts: Options, out: PrintStream) {
        val file = existingFile(opts.single("входной .class"))
        val input = file.readBytes()
        val output = File(opts.value("-o") ?: file.name.substringBeforeLast('.') + "_rt.class")
        val classFile = ClassReader().read(input)
        output.absoluteFile.parentFile?.mkdirs()
        ClassWriter(classFile).write(output)
        val written = output.readBytes()
        out.println("${file.name} -> ${output.path}: ${written.size} байт")
        out.println(compareBytes(input, written))
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): String {
        if (a.contentEquals(b)) return "Побайтно совпадает с исходным"
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return "Отличается от исходного: первое расхождение по смещению 0x${Integer.toHexString(i)} (размеры ${a.size} и ${b.size})"
    }

    // ===== cd / ca (csmali) =====

    private fun classDisassemble(opts: Options, out: PrintStream) {
        val input = File(opts.single("вход (.class, каталог или jar)"))
        val outDir = File(opts.value("-o") ?: input.name.substringBeforeLast('.') + "_csmali")
        prepareOutputDir(outDir, opts.flag("-f"), input)
        val t0 = System.nanoTime()
        val classes = ClassIo.read(listOf(input))
        val models = classes.map { (name, bytes) ->
            try { ClassReader().read(bytes, name) } catch (e: Exception) { throw IllegalStateException("$name: ошибка чтения .class", e) }
        }
        val tree = com.tim.csmali.Smali.disassembleAll(models)
        val files = com.tim.csmali.Smali.saveDir(tree, outDir)
        out.println("${input.path} -> ${outDir.path}: $files классов за ${formatSeconds(System.nanoTime() - t0)}")
    }

    private fun classAssemble(opts: Options, out: PrintStream) {
        val input = File(opts.single("каталог или файл .smali"))
        val output = File(opts.value("-o") ?: "classes")
        val t0 = System.nanoTime()
        val tree = when {
            input.isDirectory -> com.tim.csmali.Smali.loadDir(input)
            input.isFile -> com.tim.csmali.Smali.load(input)
            else -> throw IllegalArgumentException("не найден: ${input.path}")
        }
        if (tree.classes.isEmpty()) throw IllegalArgumentException("в ${input.path} нет .smali-файлов")
        val t1 = System.nanoTime()
        val own = com.tim.casm.hierarchy.MapHierarchy()
        for (c in tree.classes) {
            own.add(c.descriptor.substring(1, c.descriptor.length - 1), c.superClass?.let { it.substring(1, it.length - 1) }, "interface" in c.accessFlags)
        }
        val parts = mutableListOf<com.tim.casm.hierarchy.ClassHierarchy>(own)
        val classPath = opts.value("--cp")?.let { cp -> com.tim.casm.hierarchy.ClassPathHierarchy(cp.split(File.pathSeparator).map { File(it) }, includeJdk = false) }
        classPath?.let { parts.add(it) }
        parts.add(com.tim.csmali.Smali.defaultHierarchy)
        val classes = try {
            com.tim.csmali.Smali.assembleAll(tree, com.tim.casm.hierarchy.CompositeHierarchy(parts))
        } finally {
            classPath?.close()
        }
        val bytes = LinkedHashMap<String, ByteArray>()
        for (cf in classes) bytes[cf.thisClassName] = ClassWriter(cf).toByteArray()
        val t2 = System.nanoTime()
        ClassIo.write(bytes, output)
        val t3 = System.nanoTime()
        out.println(
            "${input.path} -> ${output.path}: ${bytes.size} классов за ${formatSeconds(t3 - t0)} " +
                "(загрузка ${formatSeconds(t1 - t0)}, сборка ${formatSeconds(t2 - t1)}, запись ${formatSeconds(t3 - t2)})"
        )
    }

    // ===== c2d / d2c =====

    private fun classToDex(opts: Options, out: PrintStream) {
        if (opts.positional.isEmpty()) throw UsageException("команда 'c2d': не указаны входные .class, каталоги или jar")
        val t0 = System.nanoTime()
        val classes = readClassModels(opts.positional.map { File(it) })
        val t1 = System.nanoTime()
        val options = com.tim.c2d.C2dOptions(
            debugInfo = !opts.flag("--no-debug"), invisibleAnnotations = !opts.flag("--no-invisible"), desugarIndy = !opts.flag("--no-desugar")
        )
        val output = File(opts.value("-o") ?: "classes.dex")
        var converted = 0
        val t2: Long
        val written = if (output.name.endsWith(".dex")) {
            var dexes = com.tim.c2d.ClassToDex.convertMultiDex(classes, options)
            if (dexes.size > 1) {
                // Деление шло и по строкам (чтобы обойтись без /jumbo). В одном DEX строк может быть больше
                // 65 535 — const-string/jumbo, как у d8; предел — только у методов, полей, типов и прототипов.
                val single = com.tim.c2d.ClassToDex.convert(classes, options)
                if (single.methodIds.size > 0xFFFF || single.fieldIds.size > 0xFFFF ||
                    single.typeIds.size > 0xFFFF || single.protoIds.size > 0xFFFF
                ) {
                    throw IllegalArgumentException("классы не помещаются в один DEX (нужно ${dexes.size}): укажите каталог в -o")
                }
                dexes = listOf(single)
            }
            t2 = System.nanoTime()
            output.absoluteFile.parentFile?.mkdirs()
            DexWriter(dexes[0]).write(output)
            converted = dexes[0].classDefs.size
            listOf(output)
        } else {
            if (!output.isDirectory && !output.mkdirs()) throw IllegalStateException("не удалось создать каталог ${output.path}")
            // Готовый DEX пишется в фоне, пока переводятся следующие классы.
            val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dex-writer").apply { isDaemon = true } }
            try {
                val pending = ArrayList<java.util.concurrent.Future<File>>()
                com.tim.c2d.ClassToDex.convertMultiDex(classes, options) { dex ->
                    converted += dex.classDefs.size
                    val file = File(output, if (pending.isEmpty()) "classes.dex" else "classes${pending.size + 1}.dex")
                    pending.add(writer.submit(java.util.concurrent.Callable { DexWriter(dex).write(file); file }))
                }
                t2 = System.nanoTime()
                pending.map { f ->
                    try {
                        f.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            } finally {
                writer.shutdownNow()
            }
        }
        val t3 = System.nanoTime()
        out.println(
            "${classes.size} классов (в DEX: $converted) -> ${written.joinToString { it.path }} за ${formatSeconds(t3 - t0)} " +
                "(чтение ${formatSeconds(t1 - t0)}, перевод ${formatSeconds(t2 - t1)}, запись ${formatSeconds(t3 - t2)})"
        )
    }

    /**
     * Классы для c2d: байты читаются и разбираются параллельно, после разбора не хранятся;
     * StackMapTable не разбирается — c2d выводит типы сам.
     */
    private fun readClassModels(inputs: List<File>): List<com.tim.clazz.model.ClassFile> {
        val entries = ClassIo.read(inputs).entries.toList()
        val reader = ClassReader(rawStackMaps = true)
        return com.tim.dsmali.disasm.Parallel.map(entries.size) { i ->
            val (name, data) = entries[i]
            try { reader.read(data, name) } catch (e: Exception) { throw IllegalStateException("$name: ошибка чтения .class", e) }
        }
    }

    private fun dexToClass(opts: Options, out: PrintStream, err: PrintStream) {
        val file = existingFile(opts.single("входной файл (.dex или .apk)"))
        val t0 = System.nanoTime()
        // Байты DEX не нужны после разбора: функция не держит их до конца перевода.
        val dexes = readDexModels(file, err)
        // Классы самих DEX конвертер учитывает сам; снаружи — --cp и JDK.
        val classPath = opts.value("--cp")?.let { cp -> com.tim.casm.hierarchy.ClassPathHierarchy(cp.split(File.pathSeparator).map { File(it) }, includeJdk = false) }
        val parts = ArrayList<com.tim.casm.hierarchy.ClassHierarchy>()
        classPath?.let { parts.add(it) }
        parts.add(com.tim.casm.hierarchy.ClassPathHierarchy.jdk())
        val target = opts.intValue("--target", 52, 50)
        val warnings = java.util.concurrent.atomic.AtomicInteger()
        val options = com.tim.d2c.D2cOptions(
            majorVersion = target,
            hierarchy = com.tim.casm.hierarchy.CompositeHierarchy(parts),
            debugInfo = !opts.flag("--no-debug"),
            strict = !opts.flag("--lenient"),
            warnings = { w -> warnings.incrementAndGet(); synchronized(err) { err.println("Предупреждение: $w") } }
        )
        val t1 = System.nanoTime()
        // Все classes*.dex APK — одним сеансом: цепочки суперклассов могут проходить через разные DEX.
        val classes = try {
            com.tim.d2c.DexToClass.convertAllToBytes(dexes, options)
        } catch (e: Exception) {
            throw IllegalStateException("${file.name}: ошибка перевода в .class", e)
        } finally {
            classPath?.close()
        }
        val t2 = System.nanoTime()
        val output = File(opts.value("-o") ?: file.name.substringBeforeLast('.') + ".jar")
        ClassIo.write(classes, output)
        val t3 = System.nanoTime()
        out.println(
            "${file.path} -> ${output.path}: ${classes.size} классов за ${formatSeconds(t3 - t0)} " +
                "(чтение ${formatSeconds(t1 - t0)}, перевод ${formatSeconds(t2 - t1)}, запись ${formatSeconds(t3 - t2)})" +
                if (warnings.get() > 0) ", предупреждений: ${warnings.get()}" else ""
        )
    }

    // ===== bench =====

    private val BENCH_PHASES = listOf(
        "чтение DEX", "запись DEX", "дизассемблирование", "сохранение smali",
        "загрузка smali", "ассемблирование", "запись DEX (из smali)"
    )

    private fun bench(opts: Options, out: PrintStream) {
        val input = readSingleDex(opts.single("входной DEX"))
        val iterations = opts.intValue("-n", 3, 1)
        val warmup = opts.intValue("-w", 1, 0)
        val base = File(opts.value("-o") ?: System.getProperty("java.io.tmpdir"))
        if (!base.isDirectory && !base.mkdirs()) throw IllegalStateException("не удалось создать каталог ${base.path}")
        val work = Files.createTempDirectory(base.toPath(), "clazzpatcher-bench-").toFile()

        out.println("Замер: ${input.name} (${input.bytes.size} байт), прогревов $warmup, замеров $iterations; smali: ${work.path}")
        val samples = List(BENCH_PHASES.size) { mutableListOf<Long>() }
        try {
            for (i in 0 until warmup + iterations) {
                val measured = i >= warmup
                val times = benchIteration(input.bytes, File(work, "smali"))
                if (measured) times.forEachIndexed { p, t -> samples[p].add(t) }
                out.println((if (measured) "замер ${i - warmup + 1}" else "прогрев ${i + 1}") + ": " + formatSeconds(times.sum()))
            }
        } finally {
            work.deleteRecursively()
        }

        out.println(String.format(Locale.ROOT, "%-24s %12s %12s %12s", "фаза", "мин, мс", "сред, мс", "макс, мс"))
        fun row(name: String, values: List<Long>) {
            out.println(
                String.format(
                    Locale.ROOT, "%-24s %12.1f %12.1f %12.1f", name,
                    values.minOrNull()!! / 1e6, values.average() / 1e6, values.maxOrNull()!! / 1e6
                )
            )
        }
        BENCH_PHASES.forEachIndexed { p, name -> row(name, samples[p]) }
        row("итого", List(iterations) { i -> samples.sumOf { it[i] } })
    }

    /** Одна итерация всех фаз; возвращает время каждой фазы в наносекундах. */
    private fun benchIteration(bytes: ByteArray, smaliDir: File): LongArray {
        val times = LongArray(BENCH_PHASES.size)
        var phase = 0
        fun <T> timed(block: () -> T): T {
            val t0 = System.nanoTime()
            val r = try {
                block()
            } catch (e: Exception) {
                throw IllegalStateException("фаза '${BENCH_PHASES[phase]}' завершилась ошибкой", e)
            }
            times[phase++] = System.nanoTime() - t0
            return r
        }
        if (smaliDir.exists() && !smaliDir.deleteRecursively()) throw IllegalStateException("не удалось очистить ${smaliDir.path}")
        val dex = timed { DexReader().read(bytes) }
        timed { DexWriter(dex).buildDex() }
        val tree = timed { Smali.disassemble(dex) }
        timed { Smali.save(tree, smaliDir) }
        val loaded = timed { Smali.load(smaliDir) }
        val assembled = timed { Smali.assemble(loaded) }
        timed { DexWriter(assembled).buildDex() }
        return times
    }

    private fun formatSeconds(nanos: Long): String = String.format(Locale.ROOT, "%.2f с", nanos / 1e9)
}
