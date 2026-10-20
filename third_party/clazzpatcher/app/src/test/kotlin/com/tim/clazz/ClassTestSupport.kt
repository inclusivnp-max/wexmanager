package com.tim.clazz

import com.tim.clazz.model.ClassFile
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/** Корень репозитория (каталог с settings.gradle.kts) — тесты запускаются из app/. */
internal fun repoRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    fail("Не найден корень репозитория от ${File("").absolutePath}")
}

/** Загрузчик классов из байт (для проверки, что JVM принимает записанные классы). */
internal class BytesClassLoader(private val classes: Map<String, ByteArray>) :
    ClassLoader(BytesClassLoader::class.java.classLoader) {

    override fun findClass(name: String): Class<*> {
        val bytes = classes[name] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    /** Определить класс из байт напрямую, даже если класс с таким именем есть у родителя. */
    fun define(name: String): Class<*> {
        val bytes = classes.getValue(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}

/** Байты скомпилированного класса из classpath тестов. */
internal fun classBytes(cls: Class<*>): ByteArray =
    cls.getResourceAsStream(cls.simpleName + ".class")!!.use { it.readBytes() }

/** Чтение → запись; проверяет побайтовое совпадение и равенство моделей при повторном чтении. */
internal fun assertRoundTrip(bytes: ByteArray, name: String): ClassFile {
    val model = ClassReader().read(bytes, name)
    val written = ClassWriter(model).toByteArray()
    val diff = TestClass.firstDifference(bytes, written)
    if (diff >= 0) fail("$name: первый различающийся байт $diff (${bytes.size} → ${written.size} байт)")
    assertEquals(model, ClassReader().read(written, name), "$name: модель после повторного чтения")
    return model
}
