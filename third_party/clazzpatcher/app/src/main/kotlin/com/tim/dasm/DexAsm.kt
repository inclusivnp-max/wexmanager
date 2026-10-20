package com.tim.dasm

import com.tim.dasm.builder.DexFileBuilder
import com.tim.dasm.dsl.ClassDsl
import com.tim.dasm.dsl.DexDslContext
import com.tim.dasm.model.ClassInfo
import com.tim.dasm.materializer.DeferredAnnotations
import com.tim.dasm.pool.*
import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import java.io.File

class DexAsm private constructor(val state: DexAsmState) {

    // ---- Пулы ----
    val strings = StringPool(state)
    val types = TypePool(state, strings)
    val typeLists = TypeListPool(state)
    val protos = ProtoPool(state, strings, types, typeLists)
    val fields = FieldPool(state, strings, types)
    val methods = MethodPool(state, strings, types, protos)
    val methodHandles = MethodHandlePool(state, fields, methods)
    val callSites = CallSitePool(state, strings, protos)
    val classes = ClassPool(state, types)
    val classData = ClassDataPool(state)
    val codes = CodePool(state)
    val debugInfo = DebugInfoPool(state)
    val annotations = AnnotationPool(state)
    val encodedArrays = EncodedArrayPool(state)
    val misc = MiscPool(state)

    // ---- Отложенные аннотации ----
    internal val deferred = DeferredAnnotations(annotations)

    // ---- Загрузка ----

    companion object {
        fun load(file: File): DexAsm =
            DexAsm(DexAsmState(DexReader().read(file)))

        fun load(dexFile: DexFile): DexAsm =
            DexAsm(DexAsmState(dexFile))
    }

    // ---- Сборка ----

    fun build(): DexFile =
        DexFileBuilder(state, deferred, annotations, classData).build()

    fun save(file: File) {
        DexWriter(build()).write(file)
    }

    // ---- DSL ----

    fun useDsl(block: DexDslContext.() -> Unit) {
        val ctx = DexDslContext(this)
        ctx.block()
        ctx.apply()
    }

    /**
     * Собрать класс, не добавляя его в DEX: индексы результата — в пулах этого DexAsm, в другой
     * DexAsm класс переносит [com.tim.dasm.util.ClassTransfer], добавляет — [DexDslContext.addClass].
     * Так классы собираются параллельно, каждый в своём DexAsm. В пулы сразу попадают и собственные
     * id класса (тип, суперкласс, интерфейсы, SourceFile, поля, методы): пулы содержат всё, что
     * класс добавит в DEX, — по ним [com.tim.dasm.util.ClassTransfer] считает новые id.
     */
    fun buildClass(name: String, block: ClassDsl.() -> Unit): ClassInfo {
        val built = ClassDsl(name, this).apply(block).build()
        types.add(built.name)
        built.superClass?.let { types.add(it) }
        for (i in built.interfaces) types.add(i)
        built.sourceFile?.let { strings.add(it) }
        for (f in built.fields) fields.add(built.name, f.name, f.type)
        for (m in built.methods) methods.add(built.name, m.name, m.paramTypes, m.returnType)
        // Код собирается здесь же (в потоке сборки класса), инструкции больше не нужны.
        val info = built.copy(methods = built.methods.map { m ->
            if (!m.hasCode || m.code != null) m
            else m.copy(code = buildCode(m, built.name), instructions = emptyList(), hasCode = true)
        })
        // Хэши строк — тоже здесь: при переносе пулов они понадобятся уже посчитанными.
        for (s in state.strings) s.hashCode()
        return info
    }

    private fun buildCode(m: com.tim.dasm.model.MethodInfo, className: String): com.tim.dex.model.CodeItem =
        try {
            com.tim.dasm.assembler.CodeItemBuilder.build(
                m.instructions, m.registers, m.paramTypes, com.tim.dasm.model.AccessFlags.isStatic(m.accessFlags),
                m.debugInfoIdx, m.tries, m.catchHandlers
            )
        } catch (e: Exception) {
            throw IllegalStateException("Класс $className: Метод ${m.name}(${m.paramTypes.joinToString("")})${m.returnType}: ${e.message}", e)
        }
}
