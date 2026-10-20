package com.tim.casm

import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.dsl.CodeDsl
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.ClassPathHierarchy
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.opcodes.Opcodes
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.testutil.JvmVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Классы, собранные DSL casm, запускаются в отдельной JVM (верификатор HotSpot проверяет
 * каждый загруженный класс) и печатают результаты — вывод сравнивается с ожидаемым.
 */
class CasmDslTest {

    @TempDir
    lateinit var tmp: File

    private val jdk: ClassHierarchy = ClassPathHierarchy.jdk()

    /** Класс `t/<name>` с `main`, тело которого строит [body]; возвращает вывод программы. */
    private fun runMain(
        name: String,
        hierarchy: ClassHierarchy = jdk,
        tries: List<CodeAssembler.TrySpec> = emptyList(),
        body: CodeDsl.(Casm) -> Unit
    ): String {
        val casm = Casm.create(52, hierarchy)
        casm.useDsl {
            createClass("t/$name") {
                public()
                method("main") {
                    public(); static()
                    param("[Ljava/lang/String;")
                    returnType("V")
                    code { body(casm) }
                    for (t in tries) tryCatch(t.startLabel, t.endLabel, t.handlerLabel, t.exceptionType)
                }
            }
        }
        val bytes = casm.toByteArray()
        ClassReader().read(bytes, name) // запись читается нашим же парсером
        JvmVerifier.writeClasses(tmp, mapOf("t/$name" to bytes))
        val (code, out) = JvmVerifier.runMain(listOf(tmp), "t.$name")
        assertEquals(0, code) { "t.$name завершился с кодом $code:\n$out" }
        return out.trim()
    }

    private fun CodeDsl.printInt(casm: Casm) {
        val out = casm.state.pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;")
        getstatic(out)
        swap()
        invokevirtual(casm.state.pool.methodref("java/io/PrintStream", "println", "(I)V"))
    }

    private fun CodeDsl.printString(casm: Casm, s: String) {
        getstatic(casm.state.pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;"))
        ldc(casm.state.pool.string(s))
        invokevirtual(casm.state.pool.methodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V"))
    }

    @Test
    fun farConditionalBranchIsExpanded() {
        val out = runMain("Far") { casm ->
            iconst(1)
            ifne("far")                 // > 32 КБ вперёд: if_eq +8; goto_w
            for (i in 0 until 12000) { iconst(0); pop(); nop() }
            printString(casm, "мимо")
            label("far")
            printString(casm, "далеко")
            returnVoid()
        }
        assertEquals("далеко", out)
    }

    @Test
    fun wideLocalsAndIinc() {
        val out = runMain("Wide") { casm ->
            ldc(casm.state.pool.integer(100_000)); istore(300)
            iinc(300, 1000)            // wide iinc: индекс > 255 и приращение вне байта
            iinc(300, -3)
            ldc2W(casm.state.pool.long(1L shl 40)); lstore(400)
            lload(400); l2i(); iload(300); iadd()
            printInt(casm)
            returnVoid()
        }
        assertEquals((100_000 + 1000 - 3).toString(), out)
    }

    @Test
    fun switchesAndLoop() {
        val out = runMain("Sw") { casm ->
            iconst(0); istore(1)
            label("loop")
            iload(1)
            tableswitch(0, 2, "def", listOf("c0", "c1", "c2"))
            label("c0"); printString(casm, "ноль"); goto("next")
            label("c1"); iload(1); lookupswitch(intArrayOf(-5, 1, 1000), "def", listOf("def", "one", "def"))
            label("one"); printString(casm, "один"); goto("next")
            label("c2"); printString(casm, "два"); goto("next")
            label("def"); printString(casm, "другое")
            label("next")
            iinc(1, 1)
            iload(1); iconst(4); ifIcmplt("loop")
            returnVoid()
        }
        assertEquals(listOf("ноль", "один", "два", "другое"), out.lines())
    }

    @Test
    fun tryCatchFinallyAndDeadCode() {
        val casm = Casm.create(52, jdk)
        val pool = casm.state.pool
        casm.useDsl {
            createClass("t/Tc") {
                public()
                method("main") {
                    public(); static(); param("[Ljava/lang/String;"); returnType("V")
                    code {
                        label("start")
                        iconst(1); iconst(0); idiv(); pop()
                        label("end")
                        returnVoid()
                        // недостижимо: casm заменит на nop…athrow и вырежет из диапазона try
                        iconst(5); pop(); goto("start")
                        label("handler")
                        astore(1)
                        getstatic(pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;"))
                        aload(1)
                        invokevirtual(pool.methodref("java/lang/Throwable", "getMessage", "()Ljava/lang/String;"))
                        invokevirtual(pool.methodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V"))
                        returnVoid()
                    }
                    tryCatch("start", "end", "handler", "java/lang/ArithmeticException")
                    lineNumber("start", 10)
                    lineNumber("handler", 20)
                }
            }
        }
        val bytes = casm.toByteArray()
        val cf = ClassReader().read(bytes, "Tc")
        val code = cf.methods[0].attributes.filterIsInstance<Code>().single()
        assertTrue(code.code.contains(0xBF.toByte())) { "недостижимый код должен стать nop…athrow" }
        JvmVerifier.writeClasses(tmp, mapOf("t/Tc" to bytes))
        val (exit, out) = JvmVerifier.runMain(listOf(tmp), "t.Tc")
        assertEquals(0, exit, out)
        assertEquals("/ by zero", out.trim())
    }

    @Test
    fun verifiableDeadCodeIsKept() {
        // ECJ: goto после return (к цели, достижимой только проваливанием); javac (ANTLR 3): обработчик
        // пустого finally, покрытый своим же диапазоном. Оба проверяемы без анализа и остаются как есть.
        // goto из try к цели вне диапазона — нет (локалы цели могут не подойти обработчику): nop…athrow.
        val casm = Casm.create(52, jdk)
        casm.useDsl {
            createClass("t/Dk") {
                public()
                method("main") {
                    public(); static(); param("[Ljava/lang/String;"); returnType("V")
                    code {
                        aload(0); arraylength(); ifeq("zero")
                        printString(casm, "не ноль")
                        returnVoid()
                        goto("after")
                        label("zero")
                        printString(casm, "ноль")
                        label("after")
                        goto("fin")
                        label("h"); astore(1)
                        label("h1"); aload(1); athrow()
                        label("fin")
                        label("t0")
                        aload(0); arraylength(); pop()
                        goto("out")
                        goto("outside")
                        label("t1")
                        label("out")
                        printString(casm, "конец")
                        label("outside")
                        returnVoid()
                        label("catch")
                        pop()
                        returnVoid()
                    }
                    tryCatch("h", "h1", "h", null)
                    tryCatch("t0", "t1", "catch", "java/lang/RuntimeException")
                }
            }
        }
        val bytes = casm.toByteArray()
        val code = ClassReader().read(bytes, "Dk").methods[0].attributes.filterIsInstance<Code>().single()
        val b = com.tim.casm.frames.Bytecode.decode(code.code)
        val ops = (0 until b.count).map { b.op[it] }
        assertEquals(3, ops.count { it == Opcodes.GOTO }, "мёртвый goto после return сохранён, из try — заменён")
        assertEquals(2, ops.count { it == Opcodes.NOP })
        assertEquals(listOf(Opcodes.ASTORE, Opcodes.ALOAD, Opcodes.ATHROW), ops.subList(ops.indexOf(Opcodes.ASTORE), ops.indexOf(Opcodes.ASTORE) + 3))
        assertEquals(2, code.exceptionTable.size)
        JvmVerifier.writeClasses(tmp, mapOf("t/Dk" to bytes))
        val (exit, out) = JvmVerifier.runMain(listOf(tmp), "t.Dk")
        assertEquals(0, exit, out)
        assertEquals(listOf("ноль", "конец"), out.trim().lines())
    }

    @Test
    fun uninitializedInLocalsAndCommonSuperclass() {
        // new ArrayList → astore → aload → invokespecial <init> (uninitialized в локале),
        // затем слияние ArrayList/LinkedList: с иерархией JDK общий предок — AbstractList.
        val out = runMain("Lub") { casm ->
            val pool = casm.state.pool
            aload(0); arraylength(); ifne("linked")
            new(pool.classInfo("java/util/ArrayList")); astore(2)
            aload(2); invokespecial(pool.methodref("java/util/ArrayList", "<init>", "()V"))
            aload(2); astore(1)
            goto("join")
            label("linked")
            new(pool.classInfo("java/util/LinkedList")); dup()
            invokespecial(pool.methodref("java/util/LinkedList", "<init>", "()V"))
            astore(1)
            label("join")
            aload(1)
            invokevirtual(pool.methodref("java/util/AbstractList", "size", "()I"))
            printInt(casm)
            returnVoid()
        }
        assertEquals("0", out)
    }

    /**
     * `new; dup; astore; invokespecial <init>` внутри try: HotSpot проверяет обработчик и с состоянием
     * до инициализации — в нём локал uninitialized, и во фрейме обработчика он должен стать top.
     */
    @Test
    fun uninitializedLocalInsideTry() {
        val out = runMain("InitTry", tries = listOf(CodeAssembler.TrySpec("start", "end", "handler", null))) { casm ->
            val pool = casm.state.pool
            label("start")
            new(pool.classInfo("java/lang/StringBuilder")); dup(); astore(1)
            ldc(pool.string("ок"))
            invokespecial(pool.methodref("java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V"))
            aload(1)
            invokevirtual(pool.methodref("java/lang/StringBuilder", "toString", "()Ljava/lang/String;"))
            astore(2)
            label("end")
            getstatic(pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;"))
            aload(2)
            invokevirtual(pool.methodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V"))
            returnVoid()
            label("handler")
            pop()
            returnVoid()
        }
        assertEquals("ок", out)
    }

    @Test
    fun longDoubleStackShuffles() {
        val out = runMain("Dup") { casm ->
            val pool = casm.state.pool
            ldc2W(pool.double(2.5.toRawBits())); iconst(3)          // D I
            dupX2()                                   // I D I
            pop()                                     // I D
            d2i()                                     // I I
            iadd()                                    // 5
            ldc2W(pool.long(7)); dup2(); ladd()        // 5 14L
            l2i(); iadd()                             // 19
            printInt(casm)
            returnVoid()
        }
        assertEquals("19", out)
    }

    @Test
    fun helloCreatePatchAndRun() {
        val casm = Casm.create(52, jdk)
        val pool = casm.state.pool
        casm.useDsl {
            createClass("t/Hello") {
                public()
                sourceFile("Hello.java")
                field("greeting") { private(); type("java.lang.String") }
                field("VERSION") { public(); static(); final(); type("java.lang.String"); constant("1.0.0") }
                method("<init>") {
                    public()
                    code {
                        aload0()
                        invokespecial(pool.methodref("java/lang/Object", "<init>", "()V"))
                        aload0(); ldc(pool.string("Привет")); putfield(pool.fieldref("t/Hello", "greeting", "Ljava/lang/String;"))
                        returnVoid()
                    }
                }
                method("greet") {
                    public(); returnType("Ljava/lang/String;")
                    code { aload0(); getfield(pool.fieldref("t/Hello", "greeting", "Ljava/lang/String;")); areturn() }
                }
                method("max") {
                    public(); static(); params("I", "I"); returnType("I")
                    code { iload(0); iload(1); ifIcmple("b"); iload(0); ireturn(); label("b"); iload(1); ireturn() }
                }
                method("main") {
                    public(); static(); param("[Ljava/lang/String;")
                    code {
                        getstatic(pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;"))
                        new(pool.classInfo("t/Hello")); dup()
                        invokespecial(pool.methodref("t/Hello", "<init>", "()V"))
                        invokevirtual(pool.methodref("t/Hello", "greet", "()Ljava/lang/String;"))
                        invokevirtual(pool.methodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V"))
                        getstatic(pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;"))
                        iconst(3); bipush(42)
                        invokestatic(pool.methodref("t/Hello", "max", "(II)I"))
                        invokevirtual(pool.methodref("java/io/PrintStream", "println", "(I)V"))
                        returnVoid()
                    }
                }
            }
        }
        val original = casm.toByteArray()

        // Патч загруженного класса: greet() возвращает другую строку, max() — минимум.
        val patched = Casm.load(ClassReader().read(original, "Hello"), jdk)
        val pp = patched.state.pool
        patched.useDsl {
            patchClass("t/Hello") {
                method("greet", "()Ljava/lang/String;") { code { ldc(pp.string("Патч")); areturn() } }
                method("max", "(II)I") { code { iload(0); iload(1); ifIcmpge("b"); iload(0); ireturn(); label("b"); iload(1); ireturn() } }
            }
        }
        val out1 = File(tmp, "a").also { JvmVerifier.writeClasses(it, mapOf("t/Hello" to original)) }
        val out2 = File(tmp, "b").also { JvmVerifier.writeClasses(it, mapOf("t/Hello" to ClassWriter(patched.build()).toByteArray())) }
        assertEquals(listOf("Привет", "42"), JvmVerifier.runMain(listOf(out1), "t.Hello").second.trim().lines())
        assertEquals(listOf("Патч", "3"), JvmVerifier.runMain(listOf(out2), "t.Hello").second.trim().lines())
    }
}
