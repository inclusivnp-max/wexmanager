package org.wexclient.app.util

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual object TextClipboard {
    actual fun copy(text: String) {
        val sel = StringSelection(text)
        // Буфер бывает занят другим процессом (X11) — копирование не должно ронять окно
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel) }
    }

    actual fun text(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
