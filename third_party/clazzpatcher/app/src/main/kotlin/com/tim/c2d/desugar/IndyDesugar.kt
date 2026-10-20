package com.tim.c2d.desugar

import com.tim.c2d.bytecode.BytecodeDecoder
import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.Descriptors
import com.tim.c2d.bytecode.InvokeDynamicRef
import com.tim.c2d.bytecode.PoolConstant
import com.tim.casm.CasmState
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.ClassPathHierarchy
import com.tim.casm.hierarchy.CompositeHierarchy
import com.tim.casm.hierarchy.MapHierarchy
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.ConstantClassInfo
import com.tim.clazz.model.ConstantDynamicInfo
import com.tim.clazz.model.ConstantMethodHandleInfo
import com.tim.clazz.model.ConstantNameAndTypeInfo
import com.tim.clazz.model.ConstantStringInfo
import com.tim.clazz.model.MethodInfo
import com.tim.clazz.model.attributes.BootstrapMethods
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Рассахаривание invokedynamic, которые ART не исполняет (как делает d8): в libcore Android
 * `LambdaMetafactory` — заглушка, а `StringConcatFactory`, `ObjectMethods` и `SwitchBootstraps`
 * отсутствуют. Каждое место вызова заменяется на месте — `invokestatic` + два `nop` (те же 5 байт:
 * смещения, таблицы исключений и фреймы не меняются) — вызовом сгенерированного метода с тем же
 * дескриптором:
 * - лямбда — синтетический класс `Хозяин$$ExternalSyntheticLambdaN` (захваченные значения в полях,
 *   преобразование аргументов и результата как у JDK, без захвата — один экземпляр);
 * - конкатенация строк — `StringBuilder`;
 * - `toString`/`equals`/`hashCode` record — статические методы в самом record;
 * - `typeSwitch`/`enumSwitch` — цепочка проверок в статическом методе хозяина.
 * Прочие bootstrap-методы остаются invoke-custom. Результат — корректные классы JVM: их можно
 * проверить в HotSpot до перевода в DEX.
 */
object IndyDesugar {

    /** Классы после рассахаривания: исходные (изменённые) в прежнем порядке, затем синтетические. */
    fun run(classes: List<ClassFile>, hierarchy: ClassHierarchy? = null): List<ClassFile> = desugar(classes, hierarchy).classes

    /** Результат рассахаривания: классы (с новыми синтетическими) и классы лямбд по внутреннему имени. */
    class Desugared(val classes: List<ClassFile>, val lambdas: Map<String, LambdaFactory>)

    fun desugar(classes: List<ClassFile>, hierarchy: ClassHierarchy? = null): Desugared {
        if (classes.none { cf -> cf.attributes.any { it is BootstrapMethods } }) return Desugared(classes, emptyMap())
        val session = Session(classes, hierarchy)
        return Desugared(session.run(), session.lambdas)
    }

    internal const val ACC_PUBLIC = 0x0001
    internal const val ACC_PRIVATE = 0x0002
    internal const val ACC_STATIC = 0x0008
    internal const val ACC_FINAL = 0x0010
    internal const val ACC_SUPER = 0x0020
    internal const val ACC_INTERFACE = 0x0200
    internal const val ACC_SYNTHETIC = 0x1000
    internal const val ACC_PROTECTED = 0x0004

    internal const val REF_GET_FIELD = 1
    internal const val REF_INVOKE_VIRTUAL = 5
    internal const val REF_INVOKE_STATIC = 6
    internal const val REF_INVOKE_SPECIAL = 7
    internal const val REF_NEW_INVOKE_SPECIAL = 8
    internal const val REF_INVOKE_INTERFACE = 9
}

/**
 * Аргументы bootstrap-метода не соответствуют его контракту: JVM загрузит класс, а исключение
 * (BootstrapMethodError) будет при выполнении invokedynamic — такое место остаётся как есть.
 */
internal class MalformedBootstrap(message: String) : IllegalArgumentException(message)

/** Метка typeSwitch/enumSwitch. */
internal sealed class SwitchLabel {
    class Type(val descriptor: String) : SwitchLabel()
    class Str(val value: String) : SwitchLabel()
    class Int(val value: kotlin.Int) : SwitchLabel()
    /** Константа enum: EnumDesc (класс и имя) или имя в enumSwitch (класс — тип селектора). */
    class EnumConst(val enumClass: String?, val name: String) : SwitchLabel()
}

/** Метод доступа к private-реализации лямбды (или invokespecial) в классе [owner]. */
internal class Accessor(val owner: String, val ownerIsInterface: Boolean, val name: String, val desc: String,
                        val kind: Int, val target: String, val targetName: String, val targetDesc: String, val targetIsInterface: Boolean)

private class Session(private val input: List<ClassFile>, external: ClassHierarchy?) {
    private val byName = LinkedHashMap<String, ClassFile>().also { m -> for (cf in input) m.putIfAbsent(cf.thisClassName, cf) }
    private val names = HashSet(byName.keys)
    val hierarchy: ClassHierarchy = CompositeHierarchy(listOf(
        MapHierarchy().also { h ->
            for (cf in input) h.add(cf.thisClassName, if (cf.superClass == 0) null else cf.className(cf.superClass), cf.accessFlags and IndyDesugar.ACC_INTERFACE != 0)
        },
        external ?: ClassPathHierarchy.jdk()
    ))

    private val out = ArrayList<ClassFile>(input)
    private val synthetic = ArrayList<ClassFile>()
    /** Созданные классы лямбд. */
    val lambdas = HashMap<String, LambdaFactory>()
    /** Класс → методы доступа (ключ — вид и цель). */
    private val accessors = LinkedHashMap<String, LinkedHashMap<String, Accessor>>()

    fun run(): List<ClassFile> {
        for ((i, cf) in input.withIndex()) {
            try {
                out[i] = desugar(cf)
            } catch (e: Exception) {
                throw IllegalStateException("Класс ${cf.thisClassName}: рассахаривание invokedynamic: ${e.message}", e)
            }
        }
        if (accessors.isNotEmpty()) {
            for ((i, cf) in out.withIndex()) {
                val list = accessors[cf.thisClassName] ?: continue
                out[i] = addAccessors(cf, list.values.toList())
            }
        }
        return out + synthetic
    }

    /** Уникальное имя нового класса или члена. */
    private fun freshClass(base: String): String {
        var k = 0
        while ("$base$k" in names) k++
        return "$base$k".also { names.add(it) }
    }

    private fun isInterface(name: String): Boolean =
        byName[name]?.let { it.accessFlags and IndyDesugar.ACC_INTERFACE != 0 } ?: (hierarchy.isInterface(name) == true)

    // ---- Класс с invokedynamic ----

    private class Site(val method: Int, val pc: Int, val indy: InvokeDynamicRef)

    private fun desugar(cf: ClassFile): ClassFile {
        if (cf.attributes.none { it is BootstrapMethods }) return cf
        val pool = ConstantPoolView(cf)
        val sites = ArrayList<Site>()
        for ((mi, m) in cf.methods.withIndex()) {
            val code = m.attributes.firstOrNull { it is Code } as Code? ?: continue
            for (insn in BytecodeDecoder.decode(code.code).insns) {
                if (insn.opcode == Op.INVOKEDYNAMIC) sites.add(Site(mi, insn.pc, pool.invokeDynamic(insn.a)))
            }
        }
        if (sites.isEmpty()) return cf
        val host = cf.thisClassName
        val hostIsInterface = cf.accessFlags and IndyDesugar.ACC_INTERFACE != 0
        val state = CasmState(cf).also { it.hierarchy = hierarchy }
        val helpers = ArrayList<MethodInfo>()
        val taken = HashSet<String>()
        for (m in cf.methods) taken.add(cf.utf8(m.nameIndex) + cf.utf8(m.descriptorIndex))
        val counters = HashMap<String, Int>()
        fun helperName(kind: String, desc: String): String {
            while (true) {
                val k = counters.getOrDefault(kind, 0)
                counters[kind] = k + 1
                val name = "-\$\$Desugar\$$kind$k"
                if (taken.add(name + desc)) return name
            }
        }
        val helperFlags = if (hostIsInterface) IndyDesugar.ACC_PUBLIC or IndyDesugar.ACC_STATIC or IndyDesugar.ACC_SYNTHETIC
        else IndyDesugar.ACC_PRIVATE or IndyDesugar.ACC_STATIC or IndyDesugar.ACC_SYNTHETIC
        val patches = HashMap<Int, MutableList<Pair<Int, Int>>>()

        for (site in sites) {
            val bsm = pool.constant(site.indy.bootstrap.bootstrapMethodRef) as? PoolConstant.MethodHandleValue ?: continue
            val owner = bsm.member.owner
            val name = bsm.member.name
            val desc = site.indy.descriptor
            val where = "invokedynamic ${site.indy.name}$desc (${owner.substringAfterLast('/')}.$name)"
            val ref: Int = try {
                when {
                    owner == "java/lang/invoke/LambdaMetafactory" && (name == "metafactory" || name == "altMetafactory") -> {
                        val lambda = lambdaClass(cf, pool, site.indy, name == "altMetafactory")
                        state.pool.methodref(lambda, LambdaClass.FACTORY, desc)
                    }
                    owner == "java/lang/invoke/StringConcatFactory" && (name == "makeConcatWithConstants" || name == "makeConcat") -> {
                        val h = helperName("concat", desc)
                        helpers.add(Helpers.concat(state, hierarchy, host, h, desc, helperFlags,
                            if (name == "makeConcat") null else bootstrapArgs(cf, pool, site.indy)))
                        helperRef(state, host, h, desc, hostIsInterface)
                    }
                    owner == "java/lang/runtime/ObjectMethods" && name == "bootstrap" -> {
                        val h = helperName("record", desc)
                        helpers.add(Helpers.recordMethod(state, hierarchy, host, h, desc, helperFlags, site.indy.name, bootstrapArgs(cf, pool, site.indy)))
                        helperRef(state, host, h, desc, hostIsInterface)
                    }
                    owner == "java/lang/runtime/SwitchBootstraps" && (name == "typeSwitch" || name == "enumSwitch") -> {
                        val h = helperName("switch", desc)
                        helpers.add(Helpers.switchMethod(state, hierarchy, host, h, desc, helperFlags, name == "enumSwitch", switchLabels(cf, pool, site.indy)))
                        helperRef(state, host, h, desc, hostIsInterface)
                    }
                    else -> continue
                }
            } catch (e: MalformedBootstrap) {
                continue
            } catch (e: Exception) {
                throw IllegalStateException("$where: ${e.message}", e)
            }
            patches.getOrPut(site.method) { ArrayList() }.add(site.pc to ref)
        }
        if (patches.isEmpty()) return cf

        val methods = cf.methods.mapIndexed { mi, m ->
            val list = patches[mi] ?: return@mapIndexed m
            m.copy(attributes = m.attributes.map { a ->
                if (a !is Code) return@map a
                val bytes = a.code.copyOf()
                for ((pc, ref) in list) {
                    bytes[pc] = Op.INVOKESTATIC.toByte()
                    bytes[pc + 1] = (ref ushr 8).toByte()
                    bytes[pc + 2] = ref.toByte()
                    bytes[pc + 3] = Op.NOP.toByte()
                    bytes[pc + 4] = Op.NOP.toByte()
                }
                a.copy(code = bytes)
            })
        }
        return cf.copy(constantPool = state.pool.build(), methods = methods + helpers)
    }

    private fun helperRef(state: CasmState, host: String, name: String, desc: String, hostIsInterface: Boolean): Int =
        if (hostIsInterface) state.pool.interfaceMethodref(host, name, desc) else state.pool.methodref(host, name, desc)

    /** Статические аргументы bootstrap-метода (без CONSTANT_Dynamic). */
    private fun bootstrapArgs(cf: ClassFile, pool: ConstantPoolView, indy: InvokeDynamicRef): List<PoolConstant> =
        indy.bootstrap.bootstrapArguments.map { pool.constant(it) }

    // ---- Метки switch ----

    private fun switchLabels(cf: ClassFile, pool: ConstantPoolView, indy: InvokeDynamicRef): List<SwitchLabel> =
        indy.bootstrap.bootstrapArguments.map { idx ->
            when (val e = cf.constantPool.getOrNull(idx)) {
                is ConstantClassInfo -> SwitchLabel.Type(Descriptors.ofClass(cf.utf8(e.nameIndex)))
                is ConstantStringInfo -> SwitchLabel.Str(cf.utf8(e.stringIndex))
                is ConstantDynamicInfo -> enumDesc(cf, e)
                else -> when (val c = pool.constant(idx)) {
                    is PoolConstant.IntValue -> SwitchLabel.Int(c.value)
                    else -> throw MalformedBootstrap("метка switch #$idx: ${kindOf(c)}")
                }
            }
        }

    /** `EnumDesc.of(ClassDesc.of("p.E"), "A")` через ConstantBootstraps.invoke (как пишет javac 21). */
    private fun enumDesc(cf: ClassFile, e: ConstantDynamicInfo): SwitchLabel.EnumConst {
        val pool = ConstantPoolView(cf)
        fun entry(idx: Int) = cf.constantPool.getOrNull(idx)
        fun invokeArgs(d: ConstantDynamicInfo, factoryOwner: String, factory: String): List<Int> {
            val bsms = cf.attributes.filterIsInstance<BootstrapMethods>().firstOrNull()?.bootstrapMethods.orEmpty()
            val bsm = bsms.getOrNull(d.bootstrapMethodAttrIndex) ?: throw MalformedBootstrap("condy: нет bootstrap-метода")
            val mh = entry(bsm.bootstrapMethodRef) as? ConstantMethodHandleInfo ?: throw MalformedBootstrap("condy: bootstrap — не MethodHandle")
            val bsmRef = pool.member(mh.referenceIndex)
            if (bsmRef.owner != "java/lang/invoke/ConstantBootstraps" || bsmRef.name != "invoke") throw MalformedBootstrap("condy ${bsmRef.owner}.${bsmRef.name}")
            val args = bsm.bootstrapArguments
            val fn = args.firstOrNull()?.let { entry(it) } as? ConstantMethodHandleInfo ?: throw MalformedBootstrap("condy: нет фабрики")
            val fnRef = pool.member(fn.referenceIndex)
            if (fnRef.owner != factoryOwner || fnRef.name != factory) throw MalformedBootstrap("condy: ожидался $factoryOwner.$factory, а ${fnRef.owner}.${fnRef.name}")
            return args.drop(1)
        }
        fun string(idx: Int?): String = (idx?.let { entry(it) } as? ConstantStringInfo)?.let { cf.utf8(it.stringIndex) }
            ?: throw MalformedBootstrap("EnumDesc: ожидалась строка")
        val type = (entry(e.nameAndTypeIndex) as? ConstantNameAndTypeInfo)?.let { cf.utf8(it.descriptorIndex) }
        if (type != "Ljava/lang/Enum\$EnumDesc;") throw MalformedBootstrap("condy: не EnumDesc ($type)")
        val args = invokeArgs(e, "java/lang/Enum\$EnumDesc", "of")
        val classDesc = args.getOrNull(0)?.let { entry(it) } as? ConstantDynamicInfo ?: throw MalformedBootstrap("EnumDesc: ожидался ClassDesc")
        val binary = string(invokeArgs(classDesc, "java/lang/constant/ClassDesc", "of").firstOrNull())
        val constName = string(args.getOrNull(1))
        return SwitchLabel.EnumConst("L" + binary.replace('.', '/') + ";", constName)
    }

    // ---- Лямбды ----

    /** Аргумент bootstrap-метода №[k] ожидаемого вида; иначе [MalformedBootstrap]. */
    private inline fun <reified V : PoolConstant> arg(args: List<PoolConstant>, k: Int, what: String): V =
        args.getOrNull(k) as? V ?: throw MalformedBootstrap("аргумент #$k ($what): ${kindOf(args.getOrNull(k))}")

    private fun kindOf(c: PoolConstant?): String = when (c) {
        null -> "нет"
        is PoolConstant.IntValue -> "int"
        is PoolConstant.FloatValue -> "float"
        is PoolConstant.LongValue -> "long"
        is PoolConstant.DoubleValue -> "double"
        is PoolConstant.StringValue -> "строка"
        is PoolConstant.ClassValue -> "класс"
        is PoolConstant.MethodTypeValue -> "MethodType"
        is PoolConstant.MethodHandleValue -> "MethodHandle"
    }

    private fun lambdaClass(cf: ClassFile, pool: ConstantPoolView, indy: InvokeDynamicRef, alt: Boolean): String {
        val args = bootstrapArgs(cf, pool, indy)
        val samType = arg<PoolConstant.MethodTypeValue>(args, 0, "samMethodType").descriptor
        val impl = arg<PoolConstant.MethodHandleValue>(args, 1, "implMethod")
        val instantiated = arg<PoolConstant.MethodTypeValue>(args, 2, "instantiatedMethodType").descriptor
        var flags = 0
        val markers = ArrayList<String>()
        val bridges = ArrayList<String>()
        if (alt) {
            flags = arg<PoolConstant.IntValue>(args, 3, "flags").value
            var k = 4
            if (flags and 2 != 0) {
                val n = arg<PoolConstant.IntValue>(args, k++, "число маркеров").value
                if (n < 0) throw MalformedBootstrap("число маркеров $n")
                repeat(n) { markers.add(Emit.internal(arg<PoolConstant.ClassValue>(args, k++, "маркер").descriptor)) }
            }
            if (flags and 4 != 0) {
                val n = arg<PoolConstant.IntValue>(args, k++, "число мостов").value
                if (n < 0) throw MalformedBootstrap("число мостов $n")
                repeat(n) { bridges.add(arg<PoolConstant.MethodTypeValue>(args, k++, "мост").descriptor) }
            }
            if (k != args.size) throw MalformedBootstrap("лишние аргументы altMetafactory")
        } else if (args.size != 3) throw MalformedBootstrap("metafactory ожидает 3 аргумента, а их ${args.size}")
        if (Descriptors.method(samType).params.size != Descriptors.method(instantiated).params.size) {
            throw MalformedBootstrap("samMethodType $samType и instantiatedMethodType $instantiated не согласованы")
        }
        val host = cf.thisClassName
        val factory = Descriptors.method(indy.descriptor)
        val fi = Emit.internal(factory.returnType)
        val m = impl.member
        // Тип MethodHandle.type(): у методов экземпляра получатель — первым параметром.
        val implType = Descriptors.method(m.descriptor)
        val implParams = when (impl.referenceKind) {
            IndyDesugar.REF_INVOKE_STATIC, IndyDesugar.REF_NEW_INVOKE_SPECIAL -> implType.params
            IndyDesugar.REF_INVOKE_SPECIAL -> listOf("L$host;") + implType.params
            IndyDesugar.REF_INVOKE_VIRTUAL, IndyDesugar.REF_INVOKE_INTERFACE -> listOf(Descriptors.ofClass(m.owner)) + implType.params
            else -> throw IllegalArgumentException("implMethod вида ${impl.referenceKind} не поддерживается")
        }
        val implReturn = if (impl.referenceKind == IndyDesugar.REF_NEW_INVOKE_SPECIAL) Descriptors.ofClass(m.owner) else implType.returnType
        // Получатель метода экземпляра: первый захваченный аргумент (bound) или первый параметр SAM.
        val receiver = if (factory.params.isNotEmpty()) factory.params[0] else Descriptors.method(instantiated).params.firstOrNull()
        val protectedSuper = protectedInSuperclass(cf, impl, receiver)
        val call = if (protectedSuper) hostAccessor(cf, impl) else implCall(host, impl)
        // Метод доступа в хозяине принимает получатель типа хозяина (иначе protected-доступ из хозяина запрещён).
        val params = if (protectedSuper) listOf("L$host;") + implType.params else implParams
        val interfaces = LinkedHashSet<String>().apply {
            add(fi)
            addAll(markers)
            if (flags and 1 != 0) add("java/io/Serializable")
        }
        val name = freshClass("$host\$\$ExternalSyntheticLambda")
        lambdas[name] = LambdaFactory(factory.params.isNotEmpty(), LambdaClass.constructorDescriptor(factory.params))
        synthetic.add(LambdaClass(hierarchy).build(
            LambdaClass.Spec(
                name = name, version = cf.majorVersion, interfaces = interfaces.toList(),
                samName = indy.name, samType = samType, bridges = bridges.filter { it != samType }.distinct(),
                instantiated = instantiated, captures = factory.params, factoryDesc = indy.descriptor,
                implParams = params, implReturn = implReturn, call = call
            )
        ))
        return name
    }

    /**
     * Реализация — метод экземпляра суперкласса хозяина из другого пакета, который может быть protected
     * (флаги классов вне входных неизвестны): класс лямбды — не подкласс, JVM и ART запретят доступ.
     * javac такие ссылки сам превращает в лямбды, Kotlin — нет; JDK обходит через MethodHandle.
     */
    private fun protectedInSuperclass(cf: ClassFile, impl: PoolConstant.MethodHandleValue, receiver: String?): Boolean {
        if (impl.referenceKind != IndyDesugar.REF_INVOKE_VIRTUAL) return false
        val host = cf.thisClassName
        val owner = impl.member.owner
        if (owner == host || cf.accessFlags and IndyDesugar.ACC_INTERFACE != 0) return false
        if (owner.substringBeforeLast('/', "") == host.substringBeforeLast('/', "")) return false
        // У Object защищены только clone и finalize.
        if (owner == "java/lang/Object" && impl.member.name != "clone" && impl.member.name != "finalize") return false
        // Защищённый метод доступен из хозяина только на получателе типа хозяина (JVMS §4.10.1.8): при другом
        // получателе javac не дал бы такой ссылки — метод открытый, вызов прямой (иначе метод доступа сломал бы тип).
        if (receiver != null && !isHostOrSubclass(receiver, host)) return false
        var c: String? = hierarchy.superClass(host)
        var guard = 0
        while (c != null && c != owner && guard++ < 256) c = hierarchy.superClass(c)
        if (c != owner) return false
        val declared = byName[owner] ?: return true
        val m = declared.methods.firstOrNull { declared.utf8(it.nameIndex) == impl.member.name && declared.utf8(it.descriptorIndex) == impl.member.descriptor }
        return m == null || m.accessFlags and IndyDesugar.ACC_PROTECTED != 0
    }

    private fun isHostOrSubclass(descriptor: String, host: String): Boolean {
        if (!descriptor.startsWith("L")) return false
        var c: String? = descriptor.substring(1, descriptor.length - 1)
        var guard = 0
        while (c != null && guard++ < 256) {
            if (c == host) return true
            c = hierarchy.superClass(c)
        }
        return false
    }

    /** Статический метод доступа в хозяине: `invokevirtual` на получателе типа хозяина. */
    private fun hostAccessor(cf: ClassFile, impl: PoolConstant.MethodHandleValue): LambdaClass.Call {
        val host = cf.thisClassName
        val m = impl.member
        val implType = Descriptors.method(m.descriptor)
        val desc = "(L$host;" + implType.params.joinToString("") + ")" + implType.returnType
        val list = accessors.getOrPut(host) { LinkedHashMap() }
        val key = "protected ${m.owner}.${m.name}${m.descriptor}"
        val a = list.getOrPut(key) {
            Accessor(host, false, accessorName(host, desc, list), desc, IndyDesugar.REF_INVOKE_VIRTUAL, m.owner, m.name, m.descriptor, false)
        }
        return LambdaClass.Call(IndyDesugar.REF_INVOKE_STATIC, a.owner, a.name, a.desc, false)
    }

    /** Свободное имя метода доступа в [owner] (не совпадает с методами класса и другими методами доступа). */
    private fun accessorName(owner: String, desc: String, list: Map<String, Accessor>): String {
        val cf = byName[owner]
        val taken = cf?.methods?.mapTo(HashSet()) { cf.utf8(it.nameIndex) + cf.utf8(it.descriptorIndex) } ?: emptySet()
        var k = list.size
        while (true) {
            val name = "-\$\$Lambda\$access\$$k"
            if (name + desc !in taken && list.values.none { it.name == name && it.desc == desc }) return name
            k++
        }
    }

    /** Вызов реализации лямбды: напрямую или через метод доступа (private, invokespecial). */
    private fun implCall(host: String, impl: PoolConstant.MethodHandleValue): LambdaClass.Call {
        val m = impl.member
        val kind = impl.referenceKind
        val target = byName[m.owner]
        val flags = target?.methods?.firstOrNull { target.utf8(it.nameIndex) == m.name && target.utf8(it.descriptorIndex) == m.descriptor }?.accessFlags
        val isPrivate = flags != null && flags and IndyDesugar.ACC_PRIVATE != 0
        if (kind != IndyDesugar.REF_INVOKE_SPECIAL && !isPrivate) {
            return LambdaClass.Call(kind, m.owner, m.name, m.descriptor, m.isInterface)
        }
        // invokespecial и private-члены — только из своего класса: метод доступа там.
        val owner = if (kind == IndyDesugar.REF_INVOKE_SPECIAL && !isPrivate) host else m.owner
        if (owner !in byName) return LambdaClass.Call(kind, m.owner, m.name, m.descriptor, m.isInterface)
        val implType = Descriptors.method(m.descriptor)
        val desc = when (kind) {
            IndyDesugar.REF_INVOKE_STATIC -> m.descriptor
            IndyDesugar.REF_NEW_INVOKE_SPECIAL -> "(" + implType.params.joinToString("") + ")L${m.owner};"
            else -> "(L$owner;" + implType.params.joinToString("") + ")" + implType.returnType
        }
        val list = accessors.getOrPut(owner) { LinkedHashMap() }
        val key = "$kind ${m.owner}.${m.name}${m.descriptor}"
        val a = list.getOrPut(key) {
            Accessor(owner, isInterface(owner), accessorName(owner, desc, list), desc,
                kind, m.owner, m.name, m.descriptor, m.isInterface)
        }
        return LambdaClass.Call(IndyDesugar.REF_INVOKE_STATIC, a.owner, a.name, a.desc, a.ownerIsInterface)
    }

    private fun addAccessors(cf: ClassFile, list: List<Accessor>): ClassFile {
        val state = CasmState(cf).also { it.hierarchy = hierarchy }
        val taken = cf.methods.map { cf.utf8(it.nameIndex) + cf.utf8(it.descriptorIndex) }.toSet()
        val methods = list.map { a ->
            require(a.name + a.desc !in taken) { "метод доступа ${a.name}${a.desc} уже есть в ${cf.thisClassName}" }
            val code = CodeDsl()
            val e = Emit(code, state)
            val params = Descriptors.method(a.desc).params
            when (a.kind) {
                IndyDesugar.REF_INVOKE_STATIC -> {
                    e.loadAll(params, 0)
                    e.invokestatic(a.target, a.targetName, a.targetDesc, a.targetIsInterface)
                }
                IndyDesugar.REF_NEW_INVOKE_SPECIAL -> {
                    e.newObject(a.target)
                    e.loadAll(params, 0)
                    e.invokespecial(a.target, a.targetName, a.targetDesc)
                }
                IndyDesugar.REF_INVOKE_VIRTUAL -> {
                    e.loadAll(params, 0)
                    e.invokevirtual(a.target, a.targetName, a.targetDesc)
                }
                else -> {
                    e.loadAll(params, 0)
                    e.invokespecial(a.target, a.targetName, a.targetDesc, a.targetIsInterface)
                }
            }
            e.ret(Descriptors.method(a.desc).returnType)
            val flags = IndyDesugar.ACC_STATIC or IndyDesugar.ACC_SYNTHETIC or (if (a.ownerIsInterface) IndyDesugar.ACC_PUBLIC else 0)
            assembleMethod(state, hierarchy, cf.thisClassName, a.name, a.desc, flags, code)
        }
        return cf.copy(constantPool = state.pool.build(), methods = cf.methods + methods)
    }
}
