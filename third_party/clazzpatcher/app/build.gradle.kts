import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "com.tim"
version = "1.0" // синхронно с Main.VERSION

repositories {
    mavenCentral()
    google() // эталонные smali/baksmali (только для тестов)
}

// Эталонные smali/baksmali 3.0.9: оракул в тестах и сборка фикстур (src/test/fixtures-src/build-fixtures.sh).
val smaliRef: Configuration by configurations.creating

configurations.testImplementation { extendsFrom(smaliRef) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    smaliRef("com.android.tools.smali:smali:3.0.9")
    smaliRef("com.android.tools.smali:smali-baksmali:3.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

application {
    mainClass.set("com.tim.Main")
    applicationName = "clazzpatcher"
    applicationDefaultJvmArgs = listOf("-Xss16m", "-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=256m")
}

// ./gradlew :app:run --args="..." — относительные пути считаются от каталога, из которого запущен Gradle
tasks.named<JavaExec>("run") {
    workingDir = gradle.startParameter.currentDir
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = (findProperty("testMaxHeap") as String?) ?: "2g"
    jvmArgs("-Xss16m")
    // Опциональный прогон по каталогу с DEX (см. CorpusTest):
    // ./gradlew :app:test -Pcorpus=/путь [-Pcorpus.pipelines=raw,smali] --tests 'com.tim.it.CorpusTest'
    val corpus = findProperty("corpus") as String?
    corpus?.let { systemProperty("clazzpatcher.corpus", it) }
    (findProperty("corpus.pipelines") as String?)?.let { systemProperty("clazzpatcher.corpus.pipelines", it) }
    // Корпус .class для csmali (jar/каталоги через разделитель путей): -PclassCorpus=/путь/a.jar:/путь/b.jar
    val classCorpus = findProperty("classCorpus") as String?
    classCorpus?.let { systemProperty("clazzpatcher.classCorpus", it) }
    // Запуск сгенерированного DEX в ART через adb: -Padb=<serial> (см. com.tim.it.ArtRunTest)
    (findProperty("adb") as String?)?.let { systemProperty("clazzpatcher.adb", it) }
    // Фаззинг (com.tim.fuzz): -Pfuzz=<итераций на seed> [-Pfuzz.seed=N]; без параметра — короткий прогон
    (findProperty("fuzz") as String?)?.let { systemProperty("clazzpatcher.fuzz", it) }
    (findProperty("fuzz.seed") as String?)?.let { systemProperty("clazzpatcher.fuzz.seed", it) }
    // Дифференциальный фаззинг на ART (com.tim.fuzz.DiffFuzzTest): -Pdifffuzz=<программ> и -Padb=<serial>
    (findProperty("difffuzz") as String?)?.let { systemProperty("clazzpatcher.difffuzz", it) }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = corpus != null || classCorpus != null
    }
}

// Библиотечный jar (для WEX — includeBuild): по имени проекта, а не подпроекта «app»
tasks.jar { archiveBaseName.set("clazzpatcher") }

// Самодостаточный jar: java -jar app/build/libs/clazzpatcher.jar ...
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Собирает исполняемый jar со всеми зависимостями"
    archiveBaseName.set("clazzpatcher")
    archiveVersion.set("")
    archiveClassifier.set("")
    manifest { attributes["Main-Class"] = "com.tim.Main" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.build { dependsOn("fatJar") }

tasks.register("smaliClasspath") {
    description = "Печатает classpath эталонных smali/baksmali (для build-fixtures.sh)"
    val cp: FileCollection = smaliRef
    inputs.files(cp)
    doLast { println(cp.asPath) }
}
