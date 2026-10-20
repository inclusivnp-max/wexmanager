package com.tim.d2c

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.ClassPathHierarchy
import com.tim.casm.hierarchy.CompositeHierarchy
import com.tim.casm.hierarchy.MapHierarchy
import com.tim.clazz.model.ClassFile
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.d2c.bridge.BridgeWriter
import com.tim.d2c.bridge.ClassLocation
import com.tim.d2c.bridge.ClassIndex
import com.tim.d2c.bridge.ConstructorBridges
import com.tim.d2c.bridge.ProtectedAccessors
import com.tim.d2c.meta.ClassConverter
import com.tim.d2c.meta.NestedClasses
import com.tim.d2c.model.DexView
import com.tim.dex.model.DexFile
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Параметры DEX → .class.
 *
 * @property majorVersion версия формата класса (52 — Java 8; фреймы StackMapTable считаются с 50). Класс получает
 *   более новую, если без неё конструкция не выражается или HotSpot её игнорирует: MethodHandle/MethodType/
 *   invokedynamic — 51, методы интерфейса с кодом и их вызов — 52, NestHost/NestMembers — 55, record — 60, sealed — 61
 * @property hierarchy иерархия классов вне DEX для слияния типов во фреймах; `null` — классы JDK
 *   из `jrt:/`, если среда их предоставляет
 * @property debugInfo переносить номера строк и локальные переменные
 * @property threads число потоков (классы конвертируются независимо); 1 — последовательно
 * @property strict `true` — любая невыразимая в JVM конструкция (аннотация со значением method handle,
 *   bootstrap-аргумент null, некорректный код метода, `<init>` в обход конструктора класса вне DEX) —
 *   исключение; `false` — такая аннотация пропускается, тело метода заменяется на
 *   `throw VerifyError`, вызов `<init>` остаётся как есть, и вызывается [warnings]
 * @property warnings получатель предупреждений нестрогого режима (может вызываться из разных потоков)
 * @property degraded нестрогий режим: дескриптор класса, переведённого не полностью — тело метода заменено
 *   на `throw VerifyError` или класс не переведён вовсе (его нет в результате); из разных потоков.
 *   Такой класс можно взять у другого переводчика.
 */
data class D2cOptions(
    val majorVersion: Int = 52,
    val hierarchy: ClassHierarchy? = null,
    val debugInfo: Boolean = true,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val strict: Boolean = true,
    val warnings: (String) -> Unit = {},
    val degraded: (String) -> Unit = {}
)

/**
 * Конвертер DEX → JVM-классы.
 *
 * Перевод идёт в две фазы: сначала все классы параллельно (попутно собираются конструкторы-переходники
 * для `<init>`, встроенных R8 в обход конструкторов, — см. [ConstructorBridges]), затем переходники
 * дописываются только в те классы, которым они нужны. Несколько DEX одного приложения
 * ([convertAll]) переводятся вместе: цепочка суперклассов может проходить через разные classes*.dex.
 */
object DexToClass {

    /** Классы в порядке class_defs (в нестрогом режиме — без непереведённых, см. [D2cOptions.degraded]). */
    fun convert(dex: DexFile, options: D2cOptions = D2cOptions()): List<ClassFile> = convertAll(listOf(dex), options)

    /** Внутреннее имя класса → байты .class. */
    fun convertToBytes(dex: DexFile, options: D2cOptions = D2cOptions()): Map<String, ByteArray> =
        convertAllToBytes(listOf(dex), options)

    /**
     * Классы нескольких DEX одного приложения (classes.dex, classes2.dex, … APK) в порядке DEX и
     * class_defs. Повторное определение класса (как и загрузчик ART, берётся первое) пропускается с
     * предупреждением.
     */
    fun convertAll(dexes: List<DexFile>, options: D2cOptions = D2cOptions()): List<ClassFile> =
        Session(dexes, options).run()

    /**
     * [convertAll] сразу в байты: каждый класс записывается, как только переведён, и его модель не
     * хранится — память пропорциональна размеру результата, а не моделей всех классов (большие APK).
     * Классы второй фазы (синтетические методы) перечитываются из байтов и записываются заново.
     */
    fun convertAllToBytes(dexes: List<DexFile>, options: D2cOptions = D2cOptions()): Map<String, ByteArray> {
        val classes = Session(dexes, options).runToBytes()
        val out = LinkedHashMap<String, ByteArray>(classes.size * 2)
        for ((name, bytes) in classes) out[name] = bytes
        return out
    }

    /** Один перевод: общие индекс классов, иерархия и планировщик переходников всех DEX. */
    private class Session(dexes: List<DexFile>, private val options: D2cOptions) {
        private val index: ClassIndex
        private val nested: NestedClasses
        private val hierarchy: ClassHierarchy
        private val bridges: ConstructorBridges
        private val accessors: ProtectedAccessors
        private val threads: Int
        init {
            require(options.majorVersion in 50..70) { "Версия класса ${options.majorVersion} вне 50..70" }
            index = ClassIndex(dexes.map { DexView(it) })
            nested = NestedClasses(index, options.strict)
            hierarchy = hierarchyOf(index, options)
            bridges = ConstructorBridges(index)
            accessors = ProtectedAccessors(index, hierarchy)
            for ((loc, type) in index.duplicates) {
                // В одном DEX повтор недопустим (верификатор DEX ART его отвергает); в разных — действует
                // первый по порядку classes.dex, classes2.dex, … (так ищет класс загрузчик ART).
                val first = index.byType.getValue(type)
                if (first.dex == loc.dex && options.strict) {
                    throw IllegalArgumentException("Класс $type определён повторно в DEX #${loc.dex + 1}")
                }
                options.warnings("$type: повторное определение в DEX #${loc.dex + 1} пропущено (действует первое, из DEX #${first.dex + 1}, как в ART)")
            }
            threads = options.threads.coerceIn(1, maxOf(1, index.order.size))
        }

        fun run(): List<ClassFile> {
            val results = phase1 { it }.toMutableList()
            phase2({ results[it] }) { k, cf -> results[k] = cf }
            return results.filterNotNull()
        }

        /** Имя и байты каждого класса; модели живут только пока класс переводится. */
        fun runToBytes(): List<Pair<String, ByteArray>> {
            val results = phase1 { cf -> cf.thisClassName to ClassWriter(cf).toByteArray() }.toMutableList()
            phase2({ results[it]?.let { r -> ClassReader().read(r.second) } }) { k, cf -> results[k] = cf.thisClassName to ClassWriter(cf).toByteArray() }
            return results.filterNotNull()
        }

        /**
         * Фаза 1: все классы; места вызовов сразу ссылаются на переходники (дескрипторы детерминированы).
         * В нестрогом режиме класс, который не переводится, — null (и [D2cOptions.degraded]), остальные идут дальше.
         */
        private fun <T : Any> phase1(finish: (ClassFile) -> T): List<T?> {
            val order = index.order
            return parallelChunks(order.size) { from, to ->
                val converters = HashMap<Int, ClassConverter>()
                (from until to).map { k ->
                    val loc = order[k]
                    try {
                        finish(converters.getOrPut(loc.dex) { ClassConverter(index.views[loc.dex], hierarchy, options, index, nested, bridges, accessors) }.convert(loc.defIdx))
                    } catch (e: Exception) {
                        if (options.strict) throw e
                        skipped(loc, e)
                    } catch (e: StackOverflowError) {
                        if (options.strict) throw e
                        skipped(loc, e)
                    }
                }
            }
        }

        private fun skipped(loc: ClassLocation, e: Throwable): Nothing? {
            val view = index.views[loc.dex]
            val type = view.type(view.classDef(loc.defIdx).classIdx)
            options.warnings("$type: не переведён: ${e.message ?: e}")
            options.degraded(type)
            return null
        }

        /** Фаза 2: синтетические методы дописываются только в классы, которым они нужны. */
        private fun phase2(load: (Int) -> ClassFile?, store: (Int, ClassFile) -> Unit) {
            val constructors = bridges.requests()
            val access = accessors.requests()
            if (constructors.isEmpty() && access.isEmpty()) return
            val writer = BridgeWriter(bridges, accessors, index, hierarchy)
            val targets = (constructors.keys + access.keys).toSortedSet().toList()
            val patched = parallel(targets.size) { t ->
                val type = targets[t]
                val k = index.position[type] ?: throw IllegalStateException("Класс $type для синтетических методов не переводился")
                // Класс не переведён в фазе 1 (нестрогий режим) — дописывать некуда
                val cf = load(k) ?: return@parallel null
                try {
                    k to writer.addTo(cf, type, constructors[type].orEmpty(), access[type].orEmpty())
                } catch (e: Exception) {
                    if (options.strict) throw IllegalStateException("Класс $type: синтетические методы: ${e.message}", e)
                    // Класс остаётся без переходников: вызовы к ним из других классов не разрешатся
                    options.warnings("$type: синтетические методы не добавлены: ${e.message}")
                    options.degraded(type)
                    null
                }
            }
            for (p in patched) if (p != null) store(p.first, p.second)
        }

        fun <T> parallel(count: Int, work: (Int) -> T): List<T> = parallelChunks(count) { from, to -> (from until to).map(work) }

        /** Порции [from, to) параллельно; результат — в исходном порядке. */
        private fun <T> parallelChunks(count: Int, work: (Int, Int) -> List<T>): List<T> {
            val n = threads.coerceIn(1, maxOf(1, count))
            if (n == 1 || count < 2) return work(0, count)
            // Стек как у -Xss16m: перевод вложенных значений и графов методов рекурсивен
            val pool: ExecutorService = Executors.newFixedThreadPool(n) { r ->
                Thread(null, r, "d2c-worker", 16L shl 20).apply { isDaemon = true }
            }
            try {
                val chunk = maxOf(1, (count + n * 4 - 1) / (n * 4))
                val futures = (0 until count step chunk).map { from ->
                    pool.submit(Callable { work(from, minOf(count, from + chunk)) })
                }
                val out = ArrayList<T>(count)
                for (f in futures) {
                    try {
                        out.addAll(f.get())
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                }
                return out
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun hierarchyOf(index: ClassIndex, options: D2cOptions): ClassHierarchy {
        val own = MapHierarchy()
        for ((type, loc) in index.byType) {
            val c = index.view(loc).classDef(loc.defIdx)
            own.add(DexView.internalName(type), index.superOf(type)?.let { DexView.internalName(it) }, c.accessFlags and 0x0200 != 0,
                index.interfacesOf(type).map { DexView.internalName(it) })
        }
        val external = options.hierarchy ?: ClassPathHierarchy.jdk()
        return CompositeHierarchy(listOf(own, external))
    }
}
