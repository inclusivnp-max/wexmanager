package org.wexclient.app.util

import java.io.File
import javax.swing.JFileChooser

actual object FileChooser {
    private fun activeWindow(): java.awt.Window? =
        runCatching { java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow }.getOrNull()

    private fun startDir(start: File?): File =
        start?.let { if (it.isDirectory) it else it.parentFile }?.takeIf { it.isDirectory } ?: AppDirs.userHome.toFile()

    // Окно выбора модальное: вызывающий получает ответ сразу, как и раньше (тот же поток)
    actual fun openFile(title: String, start: File?, extensions: Set<String>?, onPicked: (File?) -> Unit) {
        val picked = runCatching {
            val chooser = JFileChooser(startDir(start))
            chooser.dialogTitle = title
            chooser.isFileHidingEnabled = false
            if (extensions != null) {
                chooser.fileFilter = object : javax.swing.filechooser.FileFilter() {
                    override fun accept(f: File): Boolean =
                        f.isDirectory || f.name.substringAfterLast('.', "").lowercase() in extensions
                    override fun getDescription(): String = extensions.joinToString(", ") { "*.$it" }
                }
            }
            if (chooser.showOpenDialog(activeWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }.getOrNull()
        onPicked(picked)
    }

    actual fun openDirectory(title: String, start: File?, onPicked: (File?) -> Unit) {
        val picked = runCatching {
            val chooser = JFileChooser(startDir(start))
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = title
            chooser.isFileHidingEnabled = false
            if (chooser.showOpenDialog(activeWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }.getOrNull()
        onPicked(picked)
    }

    actual fun saveFile(title: String, initial: File, onPicked: (File?) -> Unit) {
        val picked = runCatching {
            val dir = startDir(initial.parentFile)
            val chooser = JFileChooser(dir)
            chooser.dialogTitle = title
            chooser.isFileHidingEnabled = false
            chooser.selectedFile = File(dir, initial.name)
            if (chooser.showSaveDialog(activeWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }.getOrNull()
        onPicked(picked)
    }
}
