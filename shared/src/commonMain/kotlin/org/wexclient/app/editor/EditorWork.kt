package org.wexclient.app.editor

import kotlinx.coroutines.Dispatchers

/** Общие лимиты на процесс: десяток окон не запускает десяток разборов и внешних проверщиков. */
internal object EditorWork {
    val loading = Dispatchers.IO.limitedParallelism(2)
    val analysis = Dispatchers.Default.limitedParallelism(2)
    val externalChecks = Dispatchers.IO.limitedParallelism(1)
}
