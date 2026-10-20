package com.tim.d2c

import com.tim.casm.hierarchy.ClassPathHierarchy
import com.tim.casm.hierarchy.CompositeHierarchy
import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.testutil.JvmVerifier
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.test.assertTrue

/**
 * Реальные APK (opt-in): все classes*.dex → [DexToClass.convertAll] → верификатор HotSpot с android.jar
 * в classpath. Каталог с *.apk — переменная окружения `D2C_APKS`, android.jar — `ANDROID_JAR`
 * (по умолчанию ~/Android/Sdk/platforms/android-35/android.jar). Ошибок верификации быть не должно.
 */
class D2cApkTest {

    private val androidJar: File =
        System.getenv("ANDROID_JAR")?.let { File(it) }
            ?: File(System.getProperty("user.home"), "Android/Sdk/platforms/android-35/android.jar")

    @TestFactory
    fun apks(): List<DynamicTest> {
        val dir = (System.getenv("D2C_APKS") ?: System.getProperty("d2c.apks"))?.let { File(it) } ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".apk") }.orEmpty().sortedBy { it.name }
            .map { apk -> DynamicTest.dynamicTest(apk.name) { check(apk) } }
    }

    private fun check(apk: File) {
        assumeTrue(androidJar.isFile, "нет android.jar: $androidJar")
        val dexes = readDexes(apk)
        val warnings = Collections.synchronizedList(ArrayList<String>())
        val hierarchy = CompositeHierarchy(listOf(ClassPathHierarchy(listOf(androidJar), includeJdk = false), ClassPathHierarchy.jdk()))
        val t0 = System.nanoTime()
        val classes = DexToClass.convertAllToBytes(dexes, D2cOptions(hierarchy = hierarchy, strict = false, warnings = { warnings.add(it) }))
        val ms = (System.nanoTime() - t0) / 1_000_000
        val jar = File(D2cTestSupport.tempDir("apk"), apk.nameWithoutExtension + ".jar")
        D2cTestSupport.jar(classes, jar)
        val report = JvmVerifier.verify(listOf(jar, androidJar), classes.keys)
        println("${apk.name}: ${dexes.size} DEX, ${classes.size} классов за $ms мс; $report; предупреждений ${warnings.size}")
        for (w in warnings.take(20)) println("   предупреждение: $w")
        assertTrue(report.failed.isEmpty(), report.toString())
    }

    /** classes.dex, classes2.dex, … из корня APK в порядке номеров. */
    private fun readDexes(apk: File): List<DexFile> = ZipFile(apk).use { zip ->
        val re = Regex("classes(\\d*)\\.dex")
        zip.entries().toList()
            .mapNotNull { e -> re.matchEntire(e.name)?.let { m -> (m.groupValues[1].toIntOrNull() ?: 1) to e } }
            .sortedBy { it.first }
            .map { (_, e) -> DexReader().read(zip.getInputStream(e).use { it.readBytes() }) }
    }
}
