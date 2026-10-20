package org.wexclient.android.files

import androidx.lifecycle.ViewModel
import org.wexclient.app.ui.DualPaneTabs
import org.wexclient.app.ui.PaneTabs
import org.wexclient.app.vfs.VfsManager

/**
 * Состояние файлового менеджера — вкладки обеих панелей и активная панель. Живёт во ViewModel:
 * экран можно пересоздать (смена темы, уход на другой экран), а папки, вкладки и выделение останутся.
 */
class FilesViewModel(val vfsManager: VfsManager) : ViewModel() {
    private val home = vfsManager.getHomeDirectory()

    val panes = DualPaneTabs(
        left = PaneTabs(vfsManager, "left", home),
        right = PaneTabs(vfsManager, "right", home)
    )

    override fun onCleared() {
        panes.left.tabs.forEach { it.dispose() }
        panes.right.tabs.forEach { it.dispose() }
    }
}
