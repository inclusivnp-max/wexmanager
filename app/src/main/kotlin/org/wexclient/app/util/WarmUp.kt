package org.wexclient.app.util

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Прогрев тяжёлых путей сразу после появления окна. Первое в сессии открытие архива, APK или
 * текста стоит сотни миллисекунд — это загрузка и компиляция классов, а не сама работа
 * (второе открытие того же — единицы миллисекунд). Делаем это заранее, в фоне и на низком
 * приоритете, пока пользователь смотрит на список файлов.
 */
object WarmUp {

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            runCatching { Thread.sleep(1500) }
            runCatching { archives() }
            runCatching { text() }
            runCatching { images() }
            runCatching { apkTools() }
        }, "wex-warmup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** zipfs из JDK: первый открытый архив иначе ждёт загрузки всего провайдера. */
    private fun archives() {
        val zip = Files.createTempFile("wex-warm", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                out.putNextEntry(ZipEntry("warm.txt"))
                out.write("прогрев".toByteArray())
                out.closeEntry()
            }
            java.nio.file.FileSystems.newFileSystem(zip, mapOf("create" to "false", "encoding" to "UTF-8")).use { fs ->
                val inside = fs.getPath("/warm.txt")
                Files.readAllBytes(inside)
                Files.newDirectoryStream(fs.getPath("/")).use { it.count() }
            }
            java.util.zip.ZipFile(zip.toFile()).use { it.entries().asSequence().count() }
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    /** Разбор кодировки, определение языка и подсветка: это путь просмотра и правки файла. */
    private fun text() {
        val sample = """
            package warm
            /** Прогрев подсветки. */
            fun main() {
                val list = listOf(1, 2, 3)
                println("итог: " + list.sum())
            }
        """.trimIndent()
        // Столько же строк, сколько на экране большого файла: этого хватает, чтобы разогнать лексер
        val body = buildString {
            repeat(60) { append(sample).append('\n') }
        }
        val decoded = org.wexclient.app.editor.TextCodec.decode(body.toByteArray())
        org.wexclient.app.editor.EditorLimits.problem(decoded.text)
        val language = org.wexclient.app.editor.lang.Languages.detect("warm.kt", decoded.text)
        val tokens = mutableListOf<org.wexclient.app.editor.lang.Token>()
        repeat(3) {
            var state = 0
            for (line in decoded.text.lineSequence()) {
                tokens.clear()
                state = runCatching { language.lexer.tokenize(line, state, tokens) }.getOrDefault(0)
            }
        }
        // Разметка и веб-файлы — свои лексеры
        for (name in listOf("warm.md", "warm.json", "warm.xml", "warm.sh", "warm.py")) {
            val lang = org.wexclient.app.editor.lang.Languages.detect(name, decoded.text)
            tokens.clear()
            runCatching { lang.lexer.tokenize(sample.lineSequence().first(), 0, tokens) }
        }
    }

    /** Декодирование картинок: миниатюры и просмотр. */
    private fun images() {
        val png = ByteArrayOutputStream()
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        javax.imageio.ImageIO.write(image, "png", png)
        val bytes = png.toByteArray()
        javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        org.wexclient.app.util.ImageScale.decodeScaled(bytes, maxPx = 64)
    }

    /** Классы разбора APK: подпись, манифест, таблица ресурсов. Только загрузка, без работы. */
    private fun apkTools() {
        val names = listOf(
            "com.android.apksig.ApkVerifier",
            "com.android.apksig.apk.ApkUtils",
            "org.wexclient.app.axml.AxmlDecoder",
            "org.wexclient.app.arsc.ArscDocument",
            "org.wexclient.app.apk.ApkAnalyzer"
        )
        val loader = WarmUp::class.java.classLoader
        for (name in names) runCatching { Class.forName(name, true, loader) }
    }
}
