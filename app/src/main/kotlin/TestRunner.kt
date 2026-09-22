package org.wexclient.app

import kotlinx.coroutines.runBlocking
import org.wexclient.app.dex.DexEditorEngine
import org.wexclient.app.dex.DexSource
import java.io.File
import java.util.zip.ZipFile

fun main() = runBlocking {
    val apkFile = File("/home/zapahtravki/AndroidStudioProjects/WEX/app/release/app-release.apk")
    if (!apkFile.exists()) {
        println("APK does not exist: ${apkFile.absolutePath}")
        return@runBlocking
    }

    println("Reading DEX files from ${apkFile.name}...")
    val dexSources = mutableListOf<DexSource>()
    ZipFile(apkFile).use { zip ->
        for (entry in zip.entries().asSequence()) {
            if (entry.name.endsWith(".dex")) {
                println("Found DEX: ${entry.name} (${entry.size} bytes)")
                val bytes = zip.getInputStream(entry).readBytes()
                dexSources.add(DexSource(entry.name, bytes))
            }
        }
    }

    val engine = DexEditorEngine(dexSources)
    println("Engine classes count: ${engine.classes.size}")
    val sampleClasses = engine.classes.take(10)
    for (c in sampleClasses) {
        println("Sample dex class: fullName='${c.fullName}', simpleName='${c.simpleName}', pkg='${c.packageName}', src='${c.dexSourceName}'")
    }

    // Now test decompileToJava for first 3 classes
    val files = dexSources.map { src ->
        src.tempFile ?: run {
            val f = File.createTempFile("dex_", "_${src.name.replace('/', '_')}")
            f.deleteOnExit()
            f.writeBytes(src.bytes)
            f
        }
    }
    for (f in files) {
        println("Temp file: ${f.absolutePath}, exists: ${f.exists()}, size: ${f.length()}")
    }
    val args = jadx.api.JadxArgs()
    args.setInputFiles(files)
    val testJadx = jadx.api.JadxDecompiler(args)
    testJadx.load()
    println("testJadx classes count: ${testJadx.classes.size}")
    println("testJadx root packages: ${testJadx.packages.size}")
    println("testJadx getErrorsCount: ${testJadx.errorsCount}")
    val rootField = testJadx.javaClass.getDeclaredField("root")
    rootField.isAccessible = true
    val root = rootField.get(testJadx)
    println("testJadx root: $root")
    if (root != null) {
        val rootClass = root.javaClass
        println("root class: ${rootClass.name}")
        for (m in rootClass.methods.filter { it.name.contains("class", ignoreCase = true) || it.name.contains("error", ignoreCase = true) || it.name.contains("load", ignoreCase = true) }) {
            println("root method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()}) -> ${m.returnType.simpleName}")
        }
    }
    for (m in testJadx.javaClass.methods.filter { it.name.contains("search", ignoreCase = true) || it.name.contains("class", ignoreCase = true) }) {
        println("Jadx method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()}) -> ${m.returnType.simpleName}")
    }
    for (jc in testJadx.classes.take(15)) {
        println("Jadx class: fullName='${jc.fullName}', name='${jc.name}'")
    }

    // Try finding by fullName or other properties
    println("Searching in jadx.classes for targets...")
    val nonObfuscated = engine.classes.filter { it.packageName.isNotEmpty() }.take(5)
    for (t in nonObfuscated) {
        println("Target dex class: '${t.fullName}'")
        val match = testJadx.classes.firstOrNull { it.fullName == t.fullName }
        println("  -> match by fullName: $match")
    }
}

