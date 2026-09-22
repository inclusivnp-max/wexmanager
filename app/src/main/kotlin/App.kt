package org.wexclient.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.ui.DualPaneScreen
import org.wexclient.app.ui.EditorWindowUi
import org.wexclient.app.ui.WexWindows
import org.wexclient.app.vfs.VfsManager

/**
 * Путь из командной строки: «wexdesktop ~/заметки.txt», «wexdesktop ~/Загрузки» или file://-адрес,
 * каким его передаёт xdg-open и пункт «Открыть с помощью». Берём первый существующий.
 */
internal fun startupPath(args: Array<String>): java.nio.file.Path? {
    for (arg in args) {
        if (arg.startsWith("-")) continue
        val path = runCatching {
            if (arg.startsWith("file://")) fileUriPath(arg)
            else java.nio.file.Paths.get(arg).toAbsolutePath().normalize()
        }.getOrNull() ?: continue
        if (java.nio.file.Files.exists(path)) return path
    }
    return null
}

/**
 * file://-адрес в путь. Большинство программ кодируют кириллицу как %D0%9F, но не все — тогда
 * разбираем сами, иначе путь просто не откроется.
 */
private fun fileUriPath(arg: String): java.nio.file.Path =
    runCatching { java.nio.file.Paths.get(java.net.URI(arg)) }.getOrElse {
        val raw = arg.removePrefix("file://").substringBefore('?')
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val hex = if (c == '%' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        java.nio.file.Paths.get(bytes.toString(Charsets.UTF_8))
    }

/**
 * Запуск; вызывается из короткой точки входа [wexdesktop].
 *
 * Программа живёт одним процессом: он держит окна менеджера и отдельные окна редактора. Запуск
 * с путём (двойной щелчок по файлу, «Открыть с помощью», xdg-open) отдаёт путь уже работающему
 * процессу — окно появляется сразу, без запуска второй копии. Когда все окна закрыты, процесс
 * остаётся в памяти и ждёт следующего открытия; полный выход — Ctrl+Q.
 */
fun launchWex(args: Array<String> = emptyArray()) {
    org.wexclient.app.util.Startup.mark("вход в launchWex")
    val openOnStart = startupPath(args)
    // Фоновый запуск (автозапуск при входе в систему): процесс поднимается без окна и ждёт.
    // Первое открытие файла или папки после этого — мгновенное, без ожидания запуска.
    val background = args.any { it == "--background" || it == "-b" }
    if (background) {
        // Уже работает — второй процесс не нужен, и окно открывать не просили
        if (org.wexclient.app.util.SingleInstance.alive()) return
    } else if (org.wexclient.app.util.SingleInstance.handOver(openOnStart)) {
        // Уже работает — пусть откроет он
        return
    }
    // Сбои пишутся в ~/.cache/wexdesktop/errors.log: окно запускается без консоли, и иначе их не увидеть
    org.wexclient.app.util.ErrorLog.install()
    // Значения по умолчанию; заданные снаружи (JAVA_OPTS) не перебиваем — так можно попробовать
    // другой способ отрисовки, не пересобирая программу
    if (System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "OPENGL")
    if (System.getProperty("compose.swing.render.on.graphics") == null) {
        System.setProperty("compose.swing.render.on.graphics", "true")
    }
    // Русская раскладка: Ctrl+S/Ctrl+F не должны заодно вставлять «ы»/«а» в поле ввода
    org.wexclient.app.util.installCtrlLetterFilter()
    org.wexclient.app.util.SwingLeaks.install()
    // Wayland (niri, sway): после возврата в окно AWT не узнаёт о фокусе, и клавиши не работают
    org.wexclient.app.util.WindowFocusRepair.install()
    // Значок окна: распаковка картинки идёт в своём потоке, пока собирается окно
    org.wexclient.app.util.WindowIcon.prepare()
    // Настройки, закладки и история читаются с диска при первой же композиции и задерживают
    // появление окна. Читаем их заранее, пока поток окна занят запуском Compose и AWT:
    // к моменту композиции всё уже в памяти. Порядок тот же, что у окна, — как и раньше.
    Thread({
        runCatching { org.wexclient.app.theme.ThemePreferences.colorScheme }
        runCatching { org.wexclient.app.ui.ViewPreferences.tabsAtBottom }
        runCatching { org.wexclient.app.vfs.BookmarksManager.userBookmarks.size }
        runCatching { org.wexclient.app.history.NavigationHistoryManager.items.size }
    }, "wex-preload").apply {
        isDaemon = true
        start()
    }
    // Первое открытие архива, текста или APK иначе ждёт загрузки классов — прогреваем заранее
    org.wexclient.app.util.WarmUp.start()
    // Рабочие файлы, брошенные прошлыми запусками (сбой, kill; прежние версии — в /tmp, то есть в памяти)
    Thread({
        org.wexclient.app.util.ProcessScratch.removeLegacyTemp()
        runCatching { org.wexclient.app.util.ProcessScratch.work.dir }
        runCatching { org.wexclient.app.vfs.archive.ArchiveCache.dir }
        runCatching { org.wexclient.app.vfs.UndoBackups.prepare() }
    }, "wex-temp-cleanup").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    org.wexclient.app.util.Startup.mark("подготовка окружения")

    application {
        val vfsManager = remember { VfsManager() }
        org.wexclient.app.util.Startup.mark("первая композиция application")
        // Полный выход: по Ctrl+Q и пункту меню, с обычным вопросом о несохранённом
        var quitting by remember { mutableStateOf(false) }
        var quitSignal by remember { mutableStateOf(0) }
        val finish = {
            org.wexclient.app.history.NavigationHistoryManager.flushCurrentStateBeforeClose()
            // Размер шрифта консоли пишется с задержкой — дописываем, иначе последний зум пропадёт
            org.wexclient.app.terminal.TerminalPreferences.flush()
            // Фоновые ssh-соединения закрываем сами, чтобы не висели после выхода
            org.wexclient.app.vfs.ssh.SshFileSystemProvider.instance.opened().forEach { runCatching { it.close() } }
            // Начатое сохранение (в архив — секунды) доводим до конца, иначе файл мог остаться недописанным
            org.wexclient.app.ui.BackgroundSave.awaitIdle(30_000)
            vfsManager.closeAll()
            exitApplication()
        }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!background) WexWindows.open(openOnStart)
            org.wexclient.app.util.SingleInstance.listen()
        }
        // Просьбы от других запусков WEX
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { org.wexclient.app.util.SingleInstance.requested }
                .collect { request ->
                    if (request == null) return@collect
                    org.wexclient.app.util.SingleInstance.done(request)
                    WexWindows.open(request.path)
                }
        }
        // Окон не осталось: отпускаем память и ждём следующего открытия — так оно мгновенное
        val hadWindows = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        androidx.compose.runtime.LaunchedEffect(WexWindows.isEmpty) {
            if (!WexWindows.isEmpty) {
                hadWindows.set(true)
                return@LaunchedEffect
            }
            // Фоновый запуск: окон ещё не было — убирать нечего
            if (!hadWindows.get()) return@LaunchedEffect
            org.wexclient.app.history.NavigationHistoryManager.flushCurrentStateBeforeClose()
            org.wexclient.app.terminal.TerminalPreferences.flush()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { vfsManager.closeAll() }
                org.wexclient.app.vfs.ssh.SshFileSystemProvider.instance.opened().forEach { runCatching { it.close() } }
                System.gc()
            }
        }

        for (managerWindow in WexWindows.managers.toList()) {
            key(managerWindow.id) {
                ManagerWindow(
                    window = managerWindow,
                    vfsManager = vfsManager,
                    quitSignal = quitSignal,
                    onQuitRequest = {
                        quitting = true
                        quitSignal++
                    },
                    onFinished = { if (quitting) finish() else WexWindows.close(managerWindow) }
                )
            }
        }
        for (editorWindow in WexWindows.editors.toList()) {
            key(editorWindow.id) {
                EditorWindowUi(
                    window = editorWindow,
                    vfsManager = vfsManager,
                    onReveal = { file -> WexWindows.reveal(file) },
                    onFallback = { file -> WexWindows.openManager(file) },
                    onClose = { WexWindows.close(editorWindow) }
                )
            }
        }
    }
}

/** Окно менеджера: две панели, вкладки, консоль. Их может быть несколько в одном процессе. */
@Composable
private fun ApplicationScope.ManagerWindow(
    window: WexWindows.ManagerWindow,
    vfsManager: VfsManager,
    quitSignal: Int,
    onQuitRequest: () -> Unit,
    onFinished: () -> Unit
) {
    var closeRequests by remember { mutableStateOf(0) }
    Window(
        onCloseRequest = { closeRequests++ },
        title = "WEX Desktop — MT Manager",
        // Значок ставится ниже, из подготовленного заранее набора: распаковка картинки в потоке
        // окна задерживала первый кадр на добрую десятую секунды
        state = rememberWindowState(width = 1200.dp, height = 800.dp)
    ) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            org.wexclient.app.util.Startup.mark("первый кадр окна")
        }
        androidx.compose.runtime.LaunchedEffect(this.window) {
            // Значок окна задаём сами: параметр icon у Compose до X11 не доходит,
            // и панель задач показывает стандартный кофейный логотип Java
            org.wexclient.app.util.WindowIcon.applyTo(this@Window.window)
            this@Window.window.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    this@Window.window.revalidate()
                    this@Window.window.repaint()
                }
            })
        }

        // Файл или папку просят показать именно в этом окне — поднимаем его
        androidx.compose.runtime.LaunchedEffect(WexWindows.raise) {
            if (WexWindows.raise != window.id) return@LaunchedEffect
            runCatching {
                this@Window.window.toFront()
                this@Window.window.requestFocus()
            }
            WexWindows.raised(window.id)
        }

        NoctaliaTheme {
            // Фон рисует сама тема; сверху — окно, а поверх всего эффекты
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
                    DualPaneScreen(
                        vfsManager = vfsManager,
                        closeRequests = closeRequests + quitSignal,
                        onExit = onFinished,
                        openOnStart = window.startPath,
                        revealOnStart = window.revealName,
                        windowId = window.id,
                        persistTabs = window.primary,
                        onQuitRequest = onQuitRequest
                    )
                }
                // Снег и прочее — поверх панелей, но сквозь них проходят щелчки
                org.wexclient.app.ui.AppEffects()
            }
        }
    }
}
