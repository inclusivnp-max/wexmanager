package org.wexclient.app.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Просьба выбрать файл, которую показывает окно выбора WEX (там, где нет системного — на Android). */
class FileChooserRequest(
    val mode: Mode,
    val title: String,
    val start: File?,
    val extensions: Set<String>?,
    val suggestedName: String?,
    val onPicked: (File?) -> Unit
) {
    enum class Mode { OPEN_FILE, OPEN_DIRECTORY, SAVE }
}

/** Текущая просьба: окно выбора в корне экрана показывает её и отвечает через [answer]. */
object FileChooserRequests {
    var current: FileChooserRequest? by mutableStateOf(null)
        private set

    fun show(request: FileChooserRequest) {
        // Новая просьба вытесняет старую: та получает «отменили»
        current?.onPicked?.invoke(null)
        current = request
    }

    fun answer(file: File?) {
        val r = current ?: return
        current = null
        r.onPicked(file)
    }
}
