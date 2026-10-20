package org.wexclient.app.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import org.wexclient.app.util.AppDirs
import java.io.File

actual fun resourceFont(path: String, weight: FontWeight): Font {
    // Font(File) читает шрифт с диска — из ресурсов он один раз копируется в кэш
    val target = AppDirs.cache.resolve("fonts").resolve(path.substringAfterLast('/')).toFile()
    if (!target.isFile || target.length() == 0L) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        AppFonts::class.java.classLoader!!.getResourceAsStream(path)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(target)
    }
    return Font(target, weight)
}
