package com.tim.c2d

import com.tim.dasm.DexAsm
import com.tim.dasm.dsl.InstructionsDsl
import com.tim.dasm.dsl.MethodDsl
import com.tim.dex.data.DexMagic
import com.tim.dex.data.Opcode
import com.tim.dex.model.DexFileBuilder
import com.tim.dex.model.DexHeader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Верификатор тестов сам ловит типичные ошибки перевода — иначе его «зелёный» результат ничего не значит. */
class DalvikVerifierTest {

    private fun errors(setup: MethodDsl.() -> Unit, name: String = "m", code: InstructionsDsl.(DexAsm) -> Unit): List<String> {
        val b = DexFileBuilder()
        b.header = DexHeader(magic = DexMagic.DEX_035.copyOf())
        val asm = DexAsm.load(b.build())
        asm.useDsl {
            createClass("Lv/T;") {
                field("i", "I", 0)
                field("o", "Ljava/lang/Object;", 0)
                method(name) {
                    setup()
                    instructions { code(asm) }
                }
            }
        }
        return DalvikVerifier.verify(asm.build())
    }

    private fun assertRejected(
        what: String,
        setup: MethodDsl.() -> Unit = { static(); registers(4) },
        name: String = "m",
        code: InstructionsDsl.(DexAsm) -> Unit
    ) {
        val e = errors(setup, name, code)
        assertTrue(e.isNotEmpty(), "верификатор пропустил: $what")
    }

    @Test
    fun acceptsCorrectCode() {
        val e = errors({ static(); registers(4); returnType("I") }) {
            const4(0, 1)
            constWide16(1, 5)
            longToInt(3, 1)
            addInt(0, 0, 3)
            returnValue(0)
        }
        assertEquals(emptyList<String>(), e)
    }

    @Test
    fun rejectsObjectMovedAsPrimitive() = assertRejected("move строки") {
        constString(0, "s"); move(1, 0); returnVoid()
    }

    @Test
    fun rejectsPrimitiveMovedAsObject() = assertRejected("move-object числа 5") {
        const4(0, 5); moveObject(1, 0); returnVoid()
    }

    @Test
    fun rejectsBrokenPair() = assertRejected("половина пары long") {
        constWide16(0, 1); const4(1, 0); addLong(2, 0, 0); returnVoid()
    }

    @Test
    fun rejectsLongUsedAsInt() = assertRejected("long как int") {
        constWide16(0, 1); intToLong(2, 0); addInt(0, 2, 2); returnVoid()
    }

    @Test
    fun rejectsWrongArgument() = assertRejected("int в параметр String") { asm ->
        const16(0, 300)
        invoke(Opcode.INVOKE_STATIC, asm.methods.add("Ljava/lang/String;", "valueOf", listOf("Ljava/lang/Object;"), "Ljava/lang/String;"), 0)
        returnVoid()
    }

    @Test
    fun rejectsWrongFieldVariant() = assertRejected("iget-object на поле int", { registers(4) }) { asm ->
        emit(com.tim.dasm.model.Ins22c(Opcode.IGET_OBJECT, 0, 3, asm.fields.add("Lv/T;", "i", "I")))
        returnVoid()
    }

    @Test
    fun rejectsWrongArrayVariant() = assertRejected("aget-byte на boolean[]") {
        const4(0, 2); newArray(1, 0, "[Z"); const4(0, 0); agetByte(2, 1, 0); returnVoid()
    }

    @Test
    fun rejectsMisplacedMoveResult() = assertRejected("move-result без invoke") {
        const4(0, 0); moveResult(1); returnVoid()
    }

    @Test
    fun rejectsUninitializedRead() = assertRejected("чтение неинициализированного регистра") {
        addInt(0, 1, 2); returnVoid()
    }

    @Test
    fun rejectsConflictAfterMerge() = assertRejected("int/объект после слияния") {
        const4(0, 1)
        ifEqz(0, "L1")
        constString(1, "s")
        goto("L2")
        label("L1")
        const16(1, 700)
        label("L2")
        addInt(2, 1, 1)
        returnVoid()
    }

    @Test
    fun rejectsConstructorWithoutSuperCall() = assertRejected("конструктор без super()", {
        constructor(); registers(1)
    }, "<init>") {
        returnVoid()
    }

    @Test
    fun rejectsUseOfUninitializedObject() = assertRejected("вызов метода до <init>") { asm ->
        newInstance(0, "Ljava/lang/Object;")
        invoke(Opcode.INVOKE_VIRTUAL, asm.methods.add("Ljava/lang/Object;", "hashCode", emptyList(), "I"), 0)
        returnVoid()
    }
}
