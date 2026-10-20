package org.wexclient.app.util

import java.io.File

/**
 * Выбор файла или папки по просьбе пользователя. Десктоп — системное окно (JFileChooser), Android —
 * окно выбора WEX поверх экрана (настоящие пути, а не content://). Ответ приходит в [onPicked]
 * в потоке интерфейса; null — отменили.
 */
expect object FileChooser {
    /** Существующий файл; [extensions] — допустимые расширения в нижнем регистре (null — любые). */
    fun openFile(title: String, start: File?, extensions: Set<String>? = null, onPicked: (File?) -> Unit)

    fun openDirectory(title: String, start: File?, onPicked: (File?) -> Unit)

    /** Куда сохранить: папка и имя по умолчанию берутся из [initial]. */
    fun saveFile(title: String, initial: File, onPicked: (File?) -> Unit)
}
