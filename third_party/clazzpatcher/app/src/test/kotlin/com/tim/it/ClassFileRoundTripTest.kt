package com.tim.it

import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.testutil.Fixtures
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertSameBytes
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/** ClassReader -> ClassWriter возвращает исходный .class байт в байт. */
class ClassFileRoundTripTest {
    companion object {
        @JvmStatic
        fun classes() = Fixtures.classFiles.map { it.name }

        private fun file(name: String) = Fixtures.classFiles.single { it.name == name }
    }

    @TempDir
    lateinit var tmp: File

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("classes")
    fun `round-trip побайтно совпадает`(name: String) {
        val input = file(name).readBytes()
        val out = File(tmp, "out.class")
        ClassWriter(ClassReader().read(input)).write(out)
        assertSameBytes(input, out.readBytes(), "$name: ClassReader -> ClassWriter")
    }
}
