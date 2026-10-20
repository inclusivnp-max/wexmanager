package org.wexclient.app.util

import java.io.File

actual object FileChooser {
    actual fun openFile(title: String, start: File?, extensions: Set<String>?, onPicked: (File?) -> Unit) {
        FileChooserRequests.show(FileChooserRequest(FileChooserRequest.Mode.OPEN_FILE, title, start, extensions, null, onPicked))
    }

    actual fun openDirectory(title: String, start: File?, onPicked: (File?) -> Unit) {
        FileChooserRequests.show(FileChooserRequest(FileChooserRequest.Mode.OPEN_DIRECTORY, title, start, null, null, onPicked))
    }

    actual fun saveFile(title: String, initial: File, onPicked: (File?) -> Unit) {
        FileChooserRequests.show(FileChooserRequest(FileChooserRequest.Mode.SAVE, title, initial.parentFile, null, initial.name, onPicked))
    }
}
