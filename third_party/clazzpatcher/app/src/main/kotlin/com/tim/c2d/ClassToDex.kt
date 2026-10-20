package com.tim.c2d

import com.tim.c2d.meta.NestAccess
import com.tim.clazz.model.ClassFile
import com.tim.clazz.parser.ClassReader
import com.tim.dasm.DexAsm
import com.tim.dasm.model.ClassInfo
import com.tim.dasm.util.ClassTransfer
import com.tim.dasm.util.OrderedParallel
import com.tim.dex.data.DexMagic
import com.tim.dex.model.DexFile
import com.tim.dex.model.DexFileBuilder
import com.tim.dex.model.DexHeader

/**
 * Параметры преобразования.
 *
 * @property debugInfo            переносить номера строк, локальные переменные, имена параметров и SourceFile.
 * @property invisibleAnnotations переносить аннотации с RetentionPolicy.CLASS (видимость `build`, как dx);
 *                                `false` — отбрасывать, как d8 (в рантайме они не видны).
 * @property maxIdsPerDex         предел method_id/field_id/type_id/proto_id и строк в одном DEX для
 *                                [ClassToDex.convertMultiDex] (по формату — 65535; меньшее значение полезно для тестов и запаса).
 * @property desugarIndy          рассахаривать invokedynamic, которых нет в ART (лямбды, конкатенация строк,
 *                                методы record, pattern switch) — см. [com.tim.c2d.desugar.IndyDesugar];
 *                                `false` — оставить invoke-custom как есть.
 * @property threads              потоков перевода (вместе с вызывающим); `0` — по числу процессоров.
 *                                Результат от числа потоков не зависит.
 */
data class C2dOptions(
    val debugInfo: Boolean = true,
    val invisibleAnnotations: Boolean = true,
    val maxIdsPerDex: Int = 0xFFFF,
    val desugarIndy: Boolean = true,
    val threads: Int = 0
) {
    init {
        require(maxIdsPerDex in 1..0xFFFF) { "maxIdsPerDex должен быть в диапазоне 1..65535: $maxIdsPerDex" }
        require(threads >= 0) { "threads не может быть отрицательным: $threads" }
    }
}

/**
 * Преобразование классов JVM (.class) в DEX.
 *
 * Байткод каждого метода переводится в регистровый код DEX напрямую (без промежуточного smali),
 * метаданные — в аннотации `dalvik.annotation.*`. Версия DEX поднимается автоматически:
 * 037 для методов интерфейсов с кодом и их невиртуальных вызовов, 038 для invokedynamic и
 * MethodHandle.invoke, 039 для `ldc` MethodHandle/MethodType. Доступ к private-членам соседей
 * по гнезду (Java 11+) переводится так, чтобы ART его разрешал (см. [NestAccess]).
 * `module-info` пропускается — у модулей нет представления в DEX.
 *
 * Классы переводятся параллельно, каждый в собственные пулы, и сливаются в DEX по порядку входа
 * ([ClassTransfer]) — результат детерминирован и от числа потоков не зависит.
 *
 * Результат пишется через [com.tim.dex.writer.DexWriter].
 */
object ClassToDex {

    private const val ACC_MODULE = 0x8000

    /** Все классы — в один DEX (при превышении 65535 ссылок запись завершится ошибкой, см. [convertMultiDex]). */
    fun convert(input: List<ClassFile>, options: C2dOptions = C2dOptions()): DexFile {
        val plan = prepare(input, options)
        val asm = DexAsm.load(emptyDex())
        asm.useDsl {
            convertAll(plan, options) { cf, built -> addClass(transfer(cf, built, ClassTransfer(built.asm, asm), options, plan)) }
        }
        return asm.build()
    }

    fun convertBytes(classBytes: List<ByteArray>, options: C2dOptions = C2dOptions()): DexFile =
        convert(classBytes.map { ClassReader(rawStackMaps = true).read(it) }, options)

    /**
     * Классы — в несколько DEX (`classes.dex`, `classes2.dex`, …): новый файл начинается, когда
     * id очередного класса не помещаются в [C2dOptions.maxIdsPerDex] — методы, поля, типы, прототипы
     * и строки (строки ограничены, чтобы `const-string` не требовал формы /jumbo). Число новых id
     * класса известно точно (см. [ClassTransfer]).
     */
    fun convertMultiDex(input: List<ClassFile>, options: C2dOptions = C2dOptions()): List<DexFile> {
        val out = ArrayList<DexFile>()
        convertMultiDex(input, options) { out.add(it) }
        return out
    }

    /**
     * То же, но каждый готовый DEX сразу отдаётся [sink] (в вызывающем потоке, по порядку) — например,
     * чтобы записывать его, пока переводятся следующие классы, и не держать в памяти все модели.
     */
    fun convertMultiDex(input: List<ClassFile>, options: C2dOptions, sink: (DexFile) -> Unit) {
        val plan = prepare(input, options)
        var asm = DexAsm.load(emptyDex())
        var empty = true
        convertAll(plan, options) { cf, built ->
            var transfer = ClassTransfer(built.asm, asm)
            if (!empty && !transfer.fits(options.maxIdsPerDex)) {
                sink(asm.build())
                asm = DexAsm.load(emptyDex())
                transfer = ClassTransfer(built.asm, asm)
            }
            val target = asm
            val info = transfer(cf, built, transfer, options, plan)
            target.useDsl { addClass(info) }
            empty = false
        }
        sink(asm.build())
    }

    /** Класс, собранный в собственном DexAsm. */
    private class Built(val asm: DexAsm, val info: ClassInfo)

    /** Классы после рассахаривания и сведения обо всём наборе, нужные при переводе каждого класса. */
    private class Plan(
        val classes: List<ClassFile>,
        val nest: NestAccess,
        /** Классы лямбд из рассахаривания: их фабрики встраиваются в места вызова. */
        val lambdas: Map<String, com.tim.c2d.desugar.LambdaFactory>
    )

    /** Перевод классов параллельно; [consume] получает их в вызывающем потоке по порядку. */
    private fun convertAll(plan: Plan, options: C2dOptions, consume: (ClassFile, Built) -> Unit) {
        val threads = if (options.threads > 0) options.threads else Runtime.getRuntime().availableProcessors()
        OrderedParallel.run(plan.classes, threads, produce = { cf ->
            val local = DexAsm.load(emptyDex())
            Built(local, ClassConverter(local, cf, options, plan.nest, plan.lambdas).convert())
        }, consume = consume)
    }

    /**
     * Перенос собранного класса в общий DexAsm. Если строк в DEX больше 65536 и индекс строки
     * `const-string` не помещается в 16 бит, класс собирается заново прямо в общих пулах — там
     * перевод сам выбирает форму /jumbo.
     */
    private fun transfer(cf: ClassFile, built: Built, transfer: ClassTransfer, options: C2dOptions, plan: Plan): ClassInfo =
        try {
            transfer.transfer(built.info)
        } catch (e: ClassTransfer.StringIndexOverflow) {
            ClassConverter(transfer.target, cf, options, plan.nest, plan.lambdas).convert()
        } catch (e: RuntimeException) {
            throw ConversionException("Класс ${cf.thisClassName}: ${e.message}", e)
        }

    private fun prepare(input: List<ClassFile>, options: C2dOptions): Plan {
        val desugared = if (options.desugarIndy) com.tim.c2d.desugar.IndyDesugar.desugar(input) else null
        val classes = (desugared?.classes ?: input).filter { it.accessFlags and ACC_MODULE == 0 }
        return Plan(classes, NestAccess.plan(classes), desugared?.lambdas ?: emptyMap())
    }

    private fun emptyDex(): DexFile {
        val b = DexFileBuilder()
        b.header = DexHeader(magic = DexMagic.DEX_035.copyOf())
        return b.build()
    }
}
