package com.tim.dasm

import com.tim.dasm.model.AccessFlags
import com.tim.dex.data.DexMagic
import com.tim.dex.model.DexFile
import com.tim.dex.model.DexFileBuilder
import com.tim.dex.model.DexHeader
import com.tim.dex.parser.DexReader
import java.io.File

/**
 * Пример использования DSL: добавляет в DEX два класса (аннотации, try/catch, switch, массивы,
 * отладочная информация).
 *
 * Аргументы: `[выход.dex] [вход.dex]`; выход по умолчанию — `classes_upd.dex` в текущем
 * каталоге, без входного файла классы создаются в пустом DEX.
 */
object TestAsm {
    @JvmStatic
    fun main(args: Array<String>) {
        val outputFile = File(args.getOrNull(0) ?: "classes_upd.dex")
        val inputFile = args.getOrNull(1)?.let { File(it) }

        println("\n" + "=".repeat(40))
        println("Патчинг DEX через DSL: ${inputFile?.path ?: "(пустой DEX)"}")
        println("=".repeat(40))

        val dexAsm = if (inputFile != null) {
            printDexInfo(DexReader().read(inputFile))
            DexAsm.load(inputFile)
        } else {
            DexAsm.load(emptyDex())
        }

        buildScenario(dexAsm)

        println("\n" + "=".repeat(40))
        println("Запись DEX: ${outputFile.path}")
        println("=".repeat(40))
        dexAsm.save(outputFile)
        printDexInfo(DexReader().read(outputFile))
    }

    /** Пустой DEX (версия 035) — основа для сборки с нуля. */
    fun emptyDex(): DexFile {
        val b = DexFileBuilder()
        b.header = DexHeader(magic = DexMagic.DEX_035.copyOf())
        return b.build()
    }

    /** Сценарий: классы `Lcom/example/asm/AnnotatedClass;` и `Lcom/example/asm/Calculator;`. */
    fun buildScenario(dexAsm: DexAsm) {
        dexAsm.useDsl {
            createClass("Lcom/example/asm/AnnotatedClass;") {
                superClass("Ljava/lang/Object;")
                public()
                sourceFile("AnnotatedClass.java")

                // ---- Поля ----

                // 1. Поле С аннотацией @Signature — как в реальном DEX
                val itemsField = field(
                    name = "items",
                    type = "Ljava/util/List;",
                    accessFlags = AccessFlags.PRIVATE or AccessFlags.STATIC or AccessFlags.FINAL
                )
                fieldAnnotation(itemsField) {
                    type("Ldalvik/annotation/Signature;")
                    systemVisibility()
                    stringArray("value", listOf(
                            "Ljava/util/List<",
                            "Ljava/lang/String;",
                            ">;"
                    ))
                }

                // 2. Поле БЕЗ аннотации — проверяем, что не получит чужие
                val counterField = field(
                    name = "counter",
                    type = "I",
                    accessFlags = AccessFlags.PRIVATE
                )
                // (без аннотаций — counterField не используется)

                // ---- Методы ----

                // 3. Метод С аннотацией @Override (runtime)
                method("toString") {
                    returnType("Ljava/lang/String;")
                    public()
                    registers(1)
                    val p0 = thisRegister()
                    paramNames()
                    prologueEnd()
                    lineNumber(10)

                    annotation {
                        type("Ljava/lang/Override;")
                        runtimeVisibility()
                    }

                    instructions {
                        constString(0, "AnnotatedClass")
                        returnObject(0)
                    }
                }

                // 4. Метод С аннотацией @Signature — как в реальном DEX
                method("process") {
                    returnType("V")
                    params("Ljava/util/List;")
                    public()
                    registers(2)
                    val p0 = thisRegister()
                    val p1 = paramRegister(0)
                    paramNames("items")
                    prologueEnd()
                    lineNumber(15)

                    annotation {
                        type("Ldalvik/annotation/Signature;")
                        systemVisibility()
                        stringArray("value", listOf(
                                "(",
                                "Ljava/util/List<",
                                "Ljava/lang/String;",
                                ">;",
                                ")V"
                        ))
                    }

                    instructions {
                        returnVoid()
                    }
                }

                // 5. Метод БЕЗ аннотации — проверяем, что не получит чужие
                method("plainMethod") {
                    returnType("V")
                    public()
                    registers(1)
                    val p0 = thisRegister()
                    prologueEnd()
                    lineNumber(20)

                    instructions {
                        returnVoid()
                    }
                }
            }
            createClass("Lcom/example/asm/Calculator;") {
                superClass("Ljava/lang/Object;")
                public()
                sourceFile("Calculator.java")

                privateField("counter", "I")
                privateStaticFinalField("TAG", "Ljava/lang/String;")

                constructor {
                    returnType("V")
                    registers(2)
                    val p0 = thisRegister()
                    val v0 = 0
                    prologueEnd()
                    lineNumber(5)
                    instructions {
                        invokeDirectByName(
                            className = "Ljava/lang/Object;",
                            methodName = "<init>",
                            returnType = "V",
                            paramTypes = emptyList(),
                            regs = intArrayOf(p0)
                        )
                        const4(v0, 0)
                        iput(p0, v0, "Lcom/example/asm/Calculator;", "counter", "I")
                    }
                    lineNumber(6)
                    instructions {
                        returnVoid()
                    }
                }

                staticConstructor {
                    returnType("V")
                    registers(1)
                    val v0 = 0
                    prologueEnd()
                    lineNumber(9)
                    instructions {
                        constString(v0, "Calculator")
                        sputObject(v0, "Lcom/example/asm/Calculator;", "TAG", "Ljava/lang/String;")
                    }
                    lineNumber(10)
                    instructions {
                        returnVoid()
                    }
                }

                method("main") {
                    returnType("V")
                    params("[Ljava/lang/String;")
                    public()
                    static()
                    registers(2)
                    val p0 = paramRegister(0)
                    val v0 = 0
                    paramNames("args")
                    prologueEnd()
                    signature("([Ljava/lang/String;)V")
                    startLocal(p0, "args", "[Ljava/lang/String;")
                    lineNumber(13)
                    instructions {
                        sgetObject(p0, "Ljava/lang/System;", "out", "Ljava/io/PrintStream;")
                        constString(v0, "Hello World!")
                        invokeVirtualByName(
                            className = "Ljava/io/PrintStream;",
                            methodName = "println",
                            returnType = "V",
                            paramTypes = listOf("Ljava/lang/String;"),
                            regs = intArrayOf(p0, v0)
                        )
                    }
                    endLocal(p0)
                    lineNumber(14)
                    instructions {
                        returnVoid()
                    }
                }

                method("addNumbers") {
                    returnType("I")
                    params("I", "I")
                    public()
                    static()
                    registers(3)
                    val v0 = 0
                    val p0 = paramRegister(0)
                    val p1 = paramRegister(1)
                    paramNames("a", "b")
                    prologueEnd()
                    startLocal(p0, "a", "I")
                    startLocal(p1, "b", "I")
                    startLocal(v0, "result", "I")
                    lineNumber(17)
                    instructions {
                        addInt(v0, p0, p1)
                    }
                    endLocal(v0)
                    endLocal(p0)
                    endLocal(p1)
                    lineNumber(17)
                    instructions {
                        returnValue(v0)
                    }
                }

                method("factorial") {
                    returnType("I")
                    params("I")
                    public()
                    static()
                    registers(3)
                    val v0 = 0
                    val v1 = 1
                    val p0 = paramRegister(0)
                    paramNames("n")
                    prologueEnd()
                    startLocal(v0, "temp", "I")
                    startLocal(v1, "result", "I")
                    startLocal(p0, "n", "I")
                    lineNumber(21)
                    instructions {
                        const4(v0, 1)
                    }
                    lineNumber(22)
                    instructions {
                        const4(v1, 1)
                    }
                    lineNumber(23)
                    instructions {
                        ifGt(p0, v0, "loop_body")
                    }
                    lineNumber(26)
                    instructions {
                        returnValue(v1)
                        label("loop_body")
                    }
                    lineNumber(24)
                    instructions {
                        mulInt(v1, v1, p0)
                    }
                    lineNumber(25)
                    instructions {
                        addIntLit8(p0, p0, -1)
                        goto("loop_body")
                    }
                    endLocal(v0)
                    endLocal(v1)
                    endLocal(p0)
                }

                method("getCounter") {
                    returnType("I")
                    public()
                    registers(2)
                    val v0 = 0
                    val p0 = thisRegister()
                    paramNames()
                    prologueEnd()
                    startLocal(p0, "this", "Lcom/example/asm/Calculator;")
                    lineNumber(29)
                    instructions {
                        iget(v0, p0, "Lcom/example/asm/Calculator;", "counter", "I")
                        returnValue(v0)
                    }
                    endLocal(p0)
                }

                method("setCounter") {
                    returnType("V")
                    params("I")
                    public()
                    registers(2)
                    val p0 = thisRegister()
                    val p1 = paramRegister(0)
                    paramNames("value")
                    prologueEnd()
                    startLocal(p0, "this", "Lcom/example/asm/Calculator;")
                    startLocal(p1, "value", "I")
                    lineNumber(33)
                    instructions {
                        iput(p0, p1, "Lcom/example/asm/Calculator;", "counter", "I")
                        returnVoid()
                    }
                    endLocal(p0)
                    endLocal(p1)
                }

                method("safeDivide") {
                    returnType("I")
                    params("I", "I")
                    public()
                    static()
                    registers(4)
                    val v0 = 0
                    val v1 = 1
                    val p0 = paramRegister(0)
                    val p1 = paramRegister(1)
                    paramNames("a", "b")
                    prologueEnd()
                    startLocal(p0, "a", "I")
                    startLocal(p1, "b", "I")
                    startLocal(v0, "result", "I")

                    lineNumber(40)
                    instructions {
                        label("try_start")
                        divInt(v0, p0, p1)
                    }
                    instructions {
                        label("try_end")
                    }
                    lineNumber(41)
                    instructions {
                        returnValue(v0)
                    }

                    instructions {
                        label("catch_handler")
                    }
                    lineNumber(42)
                    instructions {
                        moveException(v1)
                        const4(v0, 0)
                    }
                    lineNumber(43)
                    instructions {
                        returnValue(v0)
                    }

                    tryCatch(
                        start = "try_start",
                        end = "try_end",
                        handler = "catch_handler",
                        exceptionType = "Ljava/lang/ArithmeticException;"
                    )

                    endLocal(v0)
                    endLocal(p0)
                    endLocal(p1)
                }

                method("longMath") {
                    returnType("J")
                    params("J", "J")
                    public()
                    static()
                    registers(8)
                    val p0 = paramRegister(0)
                    val p1 = paramRegister(1)
                    paramNames("a", "b")
                    prologueEnd()
                    startLocal(p0, "a", "J")
                    startLocal(p1, "b", "J")
                    startLocal(0, "result", "J")

                    lineNumber(50)
                    instructions {
                        addLong(0, p0, p1)
                    }
                    lineNumber(51)
                    instructions {
                        returnWide(0)
                    }
                    endLocal(0)
                    endLocal(p0)
                    endLocal(p1)
                }

                method("doubleMath") {
                    returnType("D")
                    params("D", "I")
                    public()
                    static()
                    registers(8)
                    val p0 = paramRegister(0)
                    val p1 = paramRegister(1)
                    paramNames("d", "n")
                    prologueEnd()
                    startLocal(p0, "d", "D")
                    startLocal(p1, "n", "I")
                    startLocal(0, "result", "D")

                    lineNumber(55)
                    instructions {
                        mulDouble(0, p0, p0)
                    }
                    lineNumber(56)
                    instructions {
                        intToDouble(2, p1)
                    }
                    lineNumber(57)
                    instructions {
                        addDouble(0, 0, 2)
                    }
                    lineNumber(58)
                    instructions {
                        returnWide(0)
                    }
                    endLocal(0)
                    endLocal(p0)
                    endLocal(p1)
                }

                method("wideEdge") {
                    returnType("V")
                    params("J", "D", "J")
                    static()
                    registers(10)
                    val v0 = 0
                    val v2 = 2
                    instructions {
                        constWide(v0, 1L)
                        constWide(v2, 2L)
                        returnVoid()
                    }
                }

                method("multiCatch") {
                    returnType("I")
                    public()
                    static()
                    registers(2)
                    val v0 = 0

                    lineNumber(60)
                    instructions {
                        label("try_start")
                        invokeStaticByName(
                            className = "Lcom/example/asm/Calculator;",
                            methodName = "risky",
                            returnType = "V",
                            paramTypes = emptyList(),
                            regs = intArrayOf()
                        )
                        label("try_end")
                    }
                    lineNumber(61)
                    instructions {
                        returnValue(v0)
                        label("catch_npe")
                    }
                    lineNumber(62)
                    instructions {
                        moveException(v0)
                        const4(v0, -1)
                        returnValue(v0)
                        label("catch_arith")
                    }
                    lineNumber(63)
                    instructions {
                        moveException(v0)
                        const4(v0, -2)
                        returnValue(v0)
                        label("catch_iae")
                    }
                    lineNumber(64)
                    instructions {
                        moveException(v0)
                        const4(v0, -3)
                        returnValue(v0)
                        label("catch_all")
                    }
                    lineNumber(65)
                    instructions {
                        moveException(v0)
                        const16(v0, -99)
                        returnValue(v0)
                    }

                    tryCatch("try_start", "try_end", "catch_npe", "Ljava/lang/NullPointerException;")
                    tryCatch("try_start", "try_end", "catch_arith", "Ljava/lang/ArithmeticException;")
                    tryCatch("try_start", "try_end", "catch_iae", "Ljava/lang/IllegalArgumentException;")
                    catchAll("try_start", "try_end", "catch_all")
                }

                method("nestedTry") {
                    returnType("I")
                    params("I", "I")
                    public()
                    static()
                    registers(4)
                    val v0 = 0
                    val v1 = 1
                    val p0 = paramRegister(0)
                    val p1 = paramRegister(1)

                    lineNumber(70)
                    instructions {
                        label("outer_start")
                        label("inner_start")
                        divInt(v0, p0, p1)
                        label("inner_end")
                    }
                    lineNumber(71)
                    instructions {
                        returnValue(v0)
                        label("catch_inner")
                    }
                    lineNumber(72)
                    instructions {
                        moveException(v1)
                        const4(v0, -1)
                        returnValue(v0)
                        label("catch_outer")
                    }
                    lineNumber(73)
                    instructions {
                        moveException(v1)
                        const4(v0, -2)
                        returnValue(v0)
                        label("outer_end")
                    }

                    tryCatch("inner_start", "inner_end", "catch_inner", "Ljava/lang/ArithmeticException;")
                    tryCatch("outer_start", "outer_end", "catch_outer", "Ljava/lang/Throwable;")
                }

                method("cascade") {
                    returnType("I")
                    params("I", "I")
                    public()
                    static()
                    registers(4)
                    val v0 = 0
                    val v1 = 1
                    val v2 = 2
                    val p0 = paramRegister(0)
                    val p1 = paramRegister(1)

                    lineNumber(80)
                    instructions {
                        label("outer_start")
                        label("inner_start")
                        divInt(v0, p0, p1)
                        label("inner_end")
                        returnValue(v0)
                        label("catch_arith")
                    }
                    lineNumber(81)
                    instructions {
                        moveException(v1)
                        newInstance(v2, "Ljava/lang/RuntimeException;")
                        invokeDirectByName(
                            className = "Ljava/lang/RuntimeException;",
                            methodName = "<init>",
                            returnType = "V",
                            paramTypes = listOf("Ljava/lang/Throwable;"),
                            regs = intArrayOf(v2, v1)
                        )
                        throwException(v2)
                    }
                    instructions {
                        label("outer_end")
                        label("catch_rt")
                    }
                    lineNumber(82)
                    instructions {
                        moveException(v1)
                        const4(v0, -1)
                        returnValue(v0)
                    }

                    tryCatch("inner_start", "inner_end", "catch_arith", "Ljava/lang/ArithmeticException;")
                    tryCatch("outer_start", "outer_end", "catch_rt", "Ljava/lang/RuntimeException;")
                }

                method("risky") {
                    returnType("V")
                    public()
                    static()
                    registers(1)
                    prologueEnd()
                    instructions {
                        returnVoid()
                    }
                }

                method("packedSwitchBasic") {
                    returnType("I")
                    params("I")
                    static()
                    registers(2)
                    val p0 = paramRegister(0)

                    instructions {
                        label("switch_insn")
                        packedSwitch(p0, "pswitch_data")
                    }
                    // default — сразу после switch
                    instructions {
                        const4(0, -1)
                        returnValue(0)
                    }
                    instructions {
                        label("case_0")
                        const16(0, 10)
                        returnValue(0)
                    }
                    instructions {
                        label("case_1")
                        const16(0, 20)
                        returnValue(0)
                    }
                    instructions {
                        label("case_2")
                        const16(0, 30)
                        returnValue(0)
                    }
                    instructions {
                        label("pswitch_data")
                        packedSwitchPayload(0, "switch_insn", "case_0", "case_1", "case_2")
                    }
                }

                method("sparseSwitchBasic") {
                    returnType("I")
                    params("I")
                    static()
                    registers(2)
                    val p0 = paramRegister(0)

                    instructions {
                        label("sswitch_insn")
                        sparseSwitch(p0, "sswitch_data")
                    }
                    // default — сразу после switch
                    instructions {
                        const4(0, -1)
                        returnValue(0)
                    }
                    instructions {
                        label("scase_0")
                        const16(0, 100)
                        returnValue(0)
                    }
                    instructions {
                        label("scase_1")
                        const16(0, 200)
                        returnValue(0)
                    }
                    instructions {
                        label("scase_2")
                        const16(0, 300)
                        returnValue(0)
                    }
                    instructions {
                        label("sswitch_data")
                        sparseSwitchPayload(
                            intArrayOf(-1, 5, 1000),
                            "sswitch_insn",
                            "scase_0", "scase_1", "scase_2"
                        )
                    }
                }

                method("fillArrayDataInt") {
                    returnType("[I")
                    static()
                    registers(2)
                    val v0 = 0

                    instructions {
                        const16(v0, 10)
                        newArray(v0, v0, "[I")
                        fillArrayData(v0, "int_data")
                        returnObject(v0)
                    }
                    instructions {
                        label("int_data")
                        val data = ByteArray(40)
                        for (i in 0 until 10) {
                            data[i * 4] = (i + 1).toByte()
                        }
                        fillArrayDataPayload(4, 10, data)
                    }
                }

                method("fillArrayDataByte") {
                    returnType("[B")
                    static()
                    registers(2)
                    val v0 = 0

                    instructions {
                        const16(v0, 10)
                        newArray(v0, v0, "[B")
                        fillArrayData(v0, "byte_data")
                        returnObject(v0)
                    }
                    instructions {
                        label("byte_data")
                        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                        fillArrayDataPayload(1, 10, data)
                    }
                }

                method("fillArrayDataLong") {
                    returnType("[J")
                    static()
                    registers(2)
                    val v0 = 0

                    instructions {
                        const4(v0, 2)
                        newArray(v0, v0, "[J")
                        fillArrayData(v0, "long_data")
                        returnObject(v0)
                    }
                    instructions {
                        label("long_data")
                        val data = ByteArray(16)
                        data[0] = 1
                        data[8] = 2
                        fillArrayDataPayload(8, 2, data)
                    }
                }

            }
        }

    }

    private fun printDexInfo(dexFile: DexFile) {
        val header = dexFile.header
        val version = DexMagic.getVersion(header.magic)
        println("Версия: $version")
        println("Размер файла: ${header.fileSize} байт")
        println("Классов: ${dexFile.classDefs.size}")
        println("Методов: ${dexFile.methodIds.size}")
        println("Элементов кода: ${dexFile.codeItems.size}")
    }
}
