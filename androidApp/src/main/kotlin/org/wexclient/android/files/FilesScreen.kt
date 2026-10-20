package org.wexclient.android.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.koin.compose.viewmodel.koinViewModel
import org.wexclient.app.ui.DualPaneScreen

/** Файловый менеджер — тот же экран, что на десктопе: две панели, вкладки, меню, редакторы. */
@Composable
fun FilesScreen(vm: FilesViewModel = koinViewModel()) {
    val activity = LocalActivity.current
    // «Назад» — на папку выше в активной панели (как в MT Manager); из корня — выйти
    BackHandler {
        val pane = vm.panes.activePane
        if (pane.canGoUp) pane.goUp() else activity?.finish()
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        DualPaneScreen(
            vfsManager = vm.vfsManager,
            onExit = { activity?.finish() },
            panes = vm.panes
        )
    }
}
