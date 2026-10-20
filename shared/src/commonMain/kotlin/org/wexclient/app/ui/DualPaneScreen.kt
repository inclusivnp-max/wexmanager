package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import org.wexclient.app.history.NavHistoryItem
import org.wexclient.app.history.NavigationHistoryManager
import org.wexclient.app.audio.AudioPlayerManager
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.wexclient.app.axml.AxmlDecoder
import org.wexclient.app.arsc.ArscDocument
import org.wexclient.app.dex.DexEditorEngine
import org.wexclient.app.dex.DexSource
import org.wexclient.app.elf.ElfDocument
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.util.Keymap
import org.wexclient.app.util.layoutKey
import org.wexclient.app.util.Plural
import org.wexclient.app.util.withUserEnv
import androidx.compose.ui.input.key.utf16CodePoint
import org.wexclient.app.vfs.ClipContent
import org.wexclient.app.vfs.ConflictPolicy
import org.wexclient.app.vfs.FileClipboard
import org.wexclient.app.vfs.FileOp
import org.wexclient.app.vfs.FileTransfers
import org.wexclient.app.vfs.FileUndo
import org.wexclient.app.vfs.FileUndoStack
import org.wexclient.app.vfs.Trash
import org.wexclient.app.vfs.TrashItem
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.FileCopy
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AnimatedOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: androidx.compose.animation.EnterTransition = fadeIn(),
    exit: androidx.compose.animation.ExitTransition = fadeOut(),
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content
    )
}

private data class InstallTarget(
    val entry: VfsEntry,
    val packageName: String?,
    val versionLabel: String?,
    val signatureValid: Boolean?
)

@Composable
fun DualPaneScreen(
    vfsManager: VfsManager,
    /** Растёт при каждой попытке закрыть окно. */
    closeRequests: Int = 0,
    onExit: () -> Unit = {},
    /** Путь из командной строки: папку показываем в левой панели, файл открываем на просмотр и правку. */
    openOnStart: java.nio.file.Path? = null,
    /** Имя файла в [openOnStart], который нужно подсветить («Расположение» из окна редактора). */
    revealOnStart: String? = null,
    /** Номер окна: по нему приходят пути, которые просят показать именно здесь. */
    windowId: Int = 0,
    /** Вкладки запоминает только первое окно — второе не перетирает его расстановку. */
    persistTabs: Boolean = true,
    /** Полный выход из WEX (Ctrl+Q): закрыть все окна и освободить память. */
    onQuitRequest: (() -> Unit)? = null,
    /** Вкладки панелей, которыми владеет ViewModel экрана (Android); null — свои, на время окна. */
    panes: DualPaneTabs? = null
) {
    val homeDir = remember { vfsManager.getHomeDirectory() }
    // Вкладки: у каждой своё состояние, панель показывает активную
    val leftTabs = panes?.left ?: remember { PaneTabs(vfsManager, if (persistTabs) "left" else "", homeDir) }
    val rightTabs = panes?.right ?: remember { PaneTabs(vfsManager, if (persistTabs) "right" else "", homeDir) }
    val leftPaneState = leftTabs.active
    val rightPaneState = rightTabs.active
    // Вкладку открыли впервые за сеанс — читаем её папку. При запуске так читается только видимая
    androidx.compose.runtime.LaunchedEffect(leftPaneState) { leftPaneState.ensureListed() }
    androidx.compose.runtime.LaunchedEffect(rightPaneState) { rightPaneState.ensureListed() }

    val leftActiveState: androidx.compose.runtime.MutableState<Boolean> = panes?.leftActive ?: remember { mutableStateOf(true) }
    var isLeftActive by leftActiveState
    var splitRatio by remember { mutableStateOf(0.5f) }
    // Язычок на границе панелей: положение берётся при размещении, без перекомпоновки экрана
    val splitTabAlignment = remember {
        Alignment { size, space, dir -> BiasAlignment((splitRatio - 0.5f) * 2f, 1.0f).align(size, space, dir) }
    }

    val activeState = if (isLeftActive) leftPaneState else rightPaneState
    val inactiveState = if (isLeftActive) rightPaneState else leftPaneState

    // Dialogs state
    var viewingEntry by remember { mutableStateOf<VfsEntry?>(null) }
    // Скомпилированный класс JVM — декомпиляция и байт-код
    var classFileEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var databaseEntry by remember { mutableStateOf<VfsEntry?>(null) }
    // Приложение AppImage: сведения, запуск, установка
    var appImageEntry by remember { mutableStateOf<VfsEntry?>(null) }
    // Канал, сокет, устройство: вместо чтения (которое повисло бы) — пояснение
    var specialFile by remember { mutableStateOf<Pair<VfsEntry, String>?>(null) }
    var viewerContent by remember { mutableStateOf("") }
    var viewerDecoded by remember { mutableStateOf<org.wexclient.app.editor.DecodedText?>(null) }
    var viewerBytes by remember { mutableStateOf<ByteArray?>(null) }
    var viewingManifestXml by remember { mutableStateOf<String?>(null) }
    var viewingManifestFormat by remember { mutableStateOf<org.wexclient.app.editor.TextFileFormat?>(null) }
    var viewingManifestPath by remember { mutableStateOf<java.nio.file.Path?>(null) }

    // APK Detail Dialog state (MT Manager style)
    var apkDetailEntry by remember { mutableStateOf<VfsEntry?>(null) }

    // Dex Editor Plus state
    var dexEngine by remember { mutableStateOf<DexEditorEngine?>(null) }
    var activeDexName by remember { mutableStateOf("") }
    var dexSelectionCandidate by remember { mutableStateOf<Pair<String, List<VfsEntry>>?>(null) }

    // ELF editor state (.so native libraries)
    var elfDoc by remember { mutableStateOf<ElfDocument?>(null) }
    var elfEntry by remember { mutableStateOf<VfsEntry?>(null) }

    // ARSC editor state (resources.arsc)
    var arscDoc by remember { mutableStateOf<ArscDocument?>(null) }
    var arscEntry by remember { mutableStateOf<VfsEntry?>(null) }

    // Properties dialog state
    var propertiesEntry by remember { mutableStateOf<VfsEntry?>(null) }

    // Rename dialog state
    var renameEntry by remember { mutableStateOf<VfsEntry?>(null) }

    // Sign APK dialog state
    var signingApkEntry by remember { mutableStateOf<VfsEntry?>(null) }
    // Набор split-APK: окно выбора действия и конвертация в один APK
    var bundleEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var bundleConvert by remember { mutableStateOf<VfsEntry?>(null) }

    val axmlDecoder = remember { AxmlDecoder() }

    // Что удалять и из какой панели (Delete и контекстное меню — через одно подтверждение)
    var pendingDelete by remember { mutableStateOf<Pair<FilePaneState, List<VfsEntry>>?>(null) }
    // Почему спрашиваем про удаление навсегда (архив, корзина недоступна)
    var pendingDeleteNote by remember { mutableStateOf<String?>(null) }
    // Удаление навсегда после сбоя корзины: вопрос неожиданный — привычный Enter не должен удалять
    var pendingDeleteCautious by remember { mutableStateOf(false) }
    // Пока файлы уходят в корзину, повторный Del (второе нажатие, автоповтор) не запускает второй перенос
    var trashing by remember { mutableStateOf(false) }
    // Итоги подписи, запущенной из окна APK и окна установки, — показываются в этих окнах
    var apkNotice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var adbNotice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val undoLock = remember { kotlinx.coroutines.sync.Mutex() }
    // Ctrl+Z: последние действия с файлами
    val undoStack = remember { FileUndoStack() }
    // Окно закрыли — отменять в нём больше нечего, а копии заменённого в архивах лежали бы в ~/.cache до выхода
    androidx.compose.runtime.DisposableEffect(undoStack) {
        onDispose { undoStack.clear() }
    }
    var showTrash by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showContentSearch by remember { mutableStateOf(false) }
    val contentSearch = remember { ContentSearchState() }
    // Найденная строка, на которой открыть просмотр
    var viewerTarget by remember { mutableStateOf<org.wexclient.app.editor.TextTarget?>(null) }
    // Имена заняты в папке назначения — ждём решения
    var pendingTransfer by remember { mutableStateOf<PendingTransfer?>(null) }
    // Распакованное во временную папку ждёт решения о совпадениях
    var pendingExtract by remember { mutableStateOf<Pair<org.wexclient.app.vfs.SafeExtract.Prepared, String>?>(null) }
    // Текст из буфера обмена → файл: спрашиваем имя
    var pasteTextRequest by remember { mutableStateOf<Pair<java.nio.file.Path, String>?>(null) }
    // Что копировать/переносить по F5/F6: выделенное или, без выделения, файл под курсором
    var copyConfirm by remember { mutableStateOf<List<VfsEntry>?>(null) }
    var moveConfirm by remember { mutableStateOf<List<VfsEntry>?>(null) }
    var compressingEntries by remember { mutableStateOf<List<VfsEntry>?>(null) }
    var extractDialogTarget by remember { mutableStateOf<Triple<VfsEntry, java.nio.file.Path, java.nio.file.Path?>?>(null) }
    // Импорт ключа подписи по щелчку на .jks/.keystore/.p12/.pk8
    var keyImport by remember { mutableStateOf<KeyImportRequest?>(null) }

    // Создание папки/файла: панель запоминаем в момент вызова (фокус следует за мышью)
    var createRequest by remember { mutableStateOf<Pair<NewEntryKind, FilePaneState>?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var unknownFileEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // Откуда открыта история: язычок внизу между панелями или кнопка в шапке — оттуда и выезжает карточка
    var historyFromTab by remember { mutableStateOf(false) }
    var viewingImageEntry by remember { mutableStateOf<Pair<VfsEntry, List<VfsEntry>>?>(null) }
    var viewingVideoEntry by remember { mutableStateOf<Pair<VfsEntry, List<VfsEntry>>?>(null) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var hexEditorEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var checksumEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var batchRenameEntries by remember { mutableStateOf<List<VfsEntry>?>(null) }
    var diffCandidates by remember { mutableStateOf<Pair<VfsEntry, VfsEntry>?>(null) }
    var apkCompare by remember { mutableStateOf<Pair<VfsEntry, VfsEntry>?>(null) }
    // Консоли панелей объявлены рано: к ним обращаются и меню файлов, и свои команды
    // Консоль принадлежит вкладке, а не панели: переключение вкладок прячет её вместе с работой,
    // а в новой вкладке открывается своя
    val leftTerminal = leftPaneState.terminal
    val rightTerminal = rightPaneState.terminal
    var pdfEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var folderCompare by remember { mutableStateOf(false) }
    // PDF и число страниц в нём: pdfinfo спрашивается в фоне до показа окна
    var pdfRange by remember { mutableStateOf<Pair<VfsEntry, Int>?>(null) }
    var sshConnect by remember { mutableStateOf(false) }
    var adbConnect by remember { mutableStateOf(false) }
    // Окно приложений телефона: серийный номер и имя телефона
    var adbApps by remember { mutableStateOf<Pair<String, String>?>(null) }
    var diskUsageRoot by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var duplicateRoots by remember { mutableStateOf<List<java.nio.file.Path>?>(null) }
    // APK → проект (jadx): что выгружаем, идущая работа и видно ли её окно
    var jadxInput by remember { mutableStateOf<VfsEntry?>(null) }
    var jadxJob by remember { mutableStateOf<JadxJob?>(null) }
    var jadxShown by remember { mutableStateOf(false) }
    var sheetEntry by remember { mutableStateOf<VfsEntry?>(null) }
    var docEntry by remember { mutableStateOf<VfsEntry?>(null) }

    // Автоматическое сохранение истории переходов и последнего положения
    androidx.compose.runtime.LaunchedEffect(leftPaneState.currentPath) {
        val focused = leftPaneState.selectedEntries.firstOrNull()?.name
        NavigationHistoryManager.record(leftPaneState.currentPath, fileName = focused, paneSide = "Левая")
    }
    androidx.compose.runtime.LaunchedEffect(rightPaneState.currentPath) {
        val focused = rightPaneState.selectedEntries.firstOrNull()?.name
        NavigationHistoryManager.record(rightPaneState.currentPath, fileName = focused, paneSide = "Правая")
    }
    // Непрерывное обновление текущего состояния для гарантированного восстановления перед закрытием.
    // Через snapshotFlow, а не ключами эффекта: ключи читались в теле экрана, и каждая стрелка
    // перекомпоновывала весь DualPaneScreen
    androidx.compose.runtime.LaunchedEffect(leftPaneState, rightPaneState) {
        androidx.compose.runtime.snapshotFlow {
            PanePlaces(
                leftPath = leftPaneState.currentPath,
                leftFile = leftPaneState.selectedEntries.firstOrNull()?.name
                    ?: leftPaneState.entries.getOrNull(leftPaneState.focusedIndex)?.name,
                rightPath = rightPaneState.currentPath,
                rightFile = rightPaneState.selectedEntries.firstOrNull()?.name
                    ?: rightPaneState.entries.getOrNull(rightPaneState.focusedIndex)?.name
            )
        }.collect { p ->
            NavigationHistoryManager.updateCurrentPaneState(
                leftPath = p.leftPath,
                leftFile = p.leftFile,
                rightPath = p.rightPath,
                rightFile = p.rightFile
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            NavigationHistoryManager.flushCurrentStateBeforeClose()
        }
    }

    // Фоновые файловые операции (копирование/перемещение/сжатие/распаковка/удаление),
    // чтобы тяжёлые операции не замораживали UI-поток
    val ioScope = rememberCoroutineScope()
    var bgBusyMessage by remember { mutableStateOf<String?>(null) }
    val toast = rememberToastState()

    // --- Изменённые APK: как в MT Manager, при выходе из архива предлагаем подписать ---
    val modifiedApks = remember { mutableStateListOf<String>() }
    var signPrompt by remember { mutableStateOf<List<String>?>(null) }
    var signingApks by remember { mutableStateOf(false) }
    var apkDetailRefresh by remember { mutableStateOf(0) }
    var installTarget by remember { mutableStateOf<InstallTarget?>(null) }
    var exitPrompt by remember { mutableStateOf(false) }

    DisposableEffect(vfsManager) {
        val listener: (java.nio.file.Path) -> Unit = { file ->
            val key = file.toAbsolutePath().normalize().toString()
            if (key.lowercase().endsWith(".apk")) {
                ioScope.launch(Dispatchers.Main) { if (key !in modifiedApks) modifiedApks.add(key) }
            }
        }
        vfsManager.addArchiveModifiedListener(listener)
        onDispose { vfsManager.removeArchiveModifiedListener(listener) }
    }

    // Воспроизведение не началось (нет ffmpeg, занято звуковое устройство) — сказать, а не молчать
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { AudioPlayerManager.playbackError }
            .drop(1) // старая ошибка — не новость
            .collect { error -> if (error != null) toast.error(error.second) }
    }

    // Иконки для меню и выбора — разобрать заранее, после старта
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        withContext(Dispatchers.Default) { org.wexclient.app.icons.MaterialSymbols.all }
        // Там же — давние копии файлов с серверов и телефонов (раз за процесс)
        withContext(Dispatchers.IO) { org.wexclient.app.vfs.remote.RemoteCopies.pruneOnce() }
    }

    fun apkEntryFor(path: String): VfsEntry {
        val f = java.io.File(path)
        return VfsEntry(
            name = f.name,
            path = f.toPath(),
            isDirectory = false,
            isArchive = true,
            size = f.length(),
            lastModified = f.lastModified(),
            itemType = VfsItemType.ARCHIVE_APK,
            extension = f.extension
        )
    }

    /** Подпись APK поверх исходных файлов ключом быстрой подписи (или выбранным в меню). */
    fun signApksInPlace(
        paths: List<String>,
        key: org.wexclient.app.signing.SigningKey = org.wexclient.app.signing.SigningPreferences.defaultKey,
        /** Куда сообщить итог, если подпись запущена из окна-инструмента: главное окно лежит за ним. */
        report: ((ok: Boolean, text: String) -> Unit)? = null,
        onDone: (Boolean) -> Unit = {}
    ) {
        if (paths.isEmpty() || signingApks) return
        signingApks = true
        bgBusyMessage = if (paths.size == 1) "Подпись ${java.io.File(paths[0]).name}..." else "Подпись APK: ${paths.size}..."
        ioScope.launch {
            val errors = ArrayList<String>()
            val signed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (p in paths) {
                    try {
                        org.wexclient.app.signing.ApkSignerEngine.signInPlace(java.io.File(p), key)
                        signed.add(p)
                    } catch (e: Exception) {
                        errors.add("${java.io.File(p).name}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            modifiedApks.removeAll(signed.toSet())
            signingApks = false
            bgBusyMessage = null
            activeState.refresh()
            inactiveState.refresh()
            apkDetailRefresh++
            val how = "ключ «${key.title}», ${org.wexclient.app.signing.SigningPreferences.schemes.label}"
            val text = if (errors.isEmpty()) {
                if (signed.size == 1) "APK подписан: ${java.io.File(signed[0]).name} ($how)" else "Подписано APK: ${signed.size} ($how)"
            } else {
                "Не удалось подписать:\n" + errors.joinToString("\n")
            }
            when {
                report != null -> report(errors.isEmpty(), text)
                errors.isEmpty() -> toast.success(text)
                else -> infoMessage = text
            }
            onDone(errors.isEmpty())
        }
    }

    /** Окно установки: сразу, а пакет/подпись дочитываются в фоне. */
    fun openInstall(entry: VfsEntry, details: org.wexclient.app.apk.ApkDetails? = null) {
        val bundle = org.wexclient.app.apk.ApkAnalyzer.isBundle(entry.name)
        fun targetOf(d: org.wexclient.app.apk.ApkDetails?, pkgOverride: String? = null) = InstallTarget(
            entry = entry,
            packageName = pkgOverride ?: d?.packageName?.takeIf { it != "Не определен" },
            versionLabel = d?.takeIf { !bundle }?.let { "v${it.versionName} (${it.versionCode})" },
            signatureValid = if (bundle) null else d?.isSignatureValid
        )
        installTarget = targetOf(details)
        if (details == null || bundle) {
            ioScope.launch {
                val (d, pkg) = withContext(Dispatchers.IO) {
                    if (bundle) null to org.wexclient.app.apk.ApkAnalyzer.bundlePackageName(entry.path)
                    else runCatching { org.wexclient.app.apk.ApkAnalyzer.analyzePath(entry.path) }.getOrNull() to null
                }
                if (installTarget?.entry == entry) installTarget = targetOf(d ?: details, pkg)
            }
        }
    }

    // Редакторы, которые могут писать в APK, ещё открыты — с подписью подождём
    val editorsOpen = dexEngine != null || elfDoc != null || arscDoc != null || viewingEntry != null ||
        viewingManifestXml != null || hexEditorEntry != null
    androidx.compose.runtime.LaunchedEffect(
        leftPaneState.currentPath, rightPaneState.currentPath, modifiedApks.size, editorsOpen, signPrompt, signingApks
    ) {
        if (modifiedApks.isEmpty() || editorsOpen || signPrompt != null || signingApks || exitPrompt) return@LaunchedEffect
        val inside = listOf(leftPaneState.currentPath, rightPaneState.currentPath)
            .mapNotNull { vfsManager.topArchiveFile(it)?.toAbsolutePath()?.normalize()?.toString() }
            .toSet()
        val leftApks = modifiedApks.filter { it !in inside && java.io.File(it).isFile }
        modifiedApks.removeAll { !java.io.File(it).isFile }
        if (leftApks.isEmpty()) return@LaunchedEffect
        if (org.wexclient.app.signing.SigningPreferences.autoSignModified) signApksInPlace(leftApks) else signPrompt = leftApks
    }

    // Закрытие окна: несохранённые редакторы и неподписанные APK
    // «Выйти после завершения»: ждём конца операции с файлами
    var exitWhenIdle by remember { mutableStateOf(false) }
    // Окно выхода открыто из-за идущей операции
    var exitPromptForOperation by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(exitWhenIdle, bgBusyMessage) {
        if (exitWhenIdle && bgBusyMessage == null) onExit()
    }
    androidx.compose.runtime.LaunchedEffect(closeRequests) {
        if (closeRequests == 0) return@LaunchedEffect
        val quiet = modifiedApks.none { java.io.File(it).isFile } && UnsavedRegistry.entries.isEmpty() && bgBusyMessage == null
        if (quiet) onExit() else {
            exitPromptForOperation = bgBusyMessage != null && modifiedApks.none { java.io.File(it).isFile } && UnsavedRegistry.entries.isEmpty()
            exitPrompt = true
        }
    }

    // Новые элементы в папке dir — показать в панели, где она открыта (прокрутка и подсветка)
    fun revealArrived(dir: java.nio.file.Path, names: List<String>) {
        val target = runCatching { vfsManager.live(dir) }.getOrNull() ?: return
        for (pane in listOf(leftPaneState, rightPaneState)) {
            if (pane.searchQuery.isNotBlank()) continue
            if (runCatching { vfsManager.live(pane.currentPath) }.getOrNull() == target) pane.revealArrived(names)
        }
    }

    fun runFileOp(
        label: String,
        revealIn: java.nio.file.Path? = null,
        revealNames: List<String> = emptyList(),
        block: () -> String?
    ) {
        bgBusyMessage = label
        ioScope.launch {
            val doneMsg = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    e.printStackTrace()
                    "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            bgBusyMessage = null
            activeState.refresh()
            inactiveState.refresh()
            // Даже после ошибки: то, что успело появиться, показываем
            if (revealIn != null && revealNames.isNotEmpty()) revealArrived(revealIn, revealNames)
            if (doneMsg != null) infoMessage = doneMsg
        }
    }

    // Имя в папке назначения: у каталогов в zip путь оканчивается на «/»
    fun arrivedName(path: java.nio.file.Path): String = path.fileName?.toString()?.trimEnd('/') ?: path.toString()

    // Операция над элементом из контекстного меню: весь выделенный набор, если элемент в нём
    fun targetsFor(pane: FilePaneState, entry: VfsEntry): List<VfsEntry> =
        if (pane.isSelected(entry)) pane.selectedEntries.toList() else listOf(entry)

    // Выделенное, а если ничего не выделено — файл под курсором
    fun activeTargets(): List<VfsEntry> = activeState.selectedEntries.toList().ifEmpty {
        listOfNotNull(activeState.entries.getOrNull(activeState.focusedIndex))
    }

    fun refreshPanes(force: Boolean = false) {
        leftPaneState.refresh(force)
        rightPaneState.refresh(force)
    }

    /** «DEX Редактор+» для APK: выбор classes*.dex. Архив открывается в фоне — большой APK не морозит окно. */
    fun openApkDex(apk: VfsEntry) {
        bgBusyMessage = "Открытие ${apk.name}…"
        ioScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val archiveRoot = vfsManager.openArchive(apk.path)
                    vfsManager.listEntries(archiveRoot).filter { it.itemType == VfsItemType.DEX }
                }
            }
            bgBusyMessage = null
            r.onSuccess { dexFiles ->
                if (dexFiles.isNotEmpty()) dexSelectionCandidate = apk.name to dexFiles
                else infoMessage = "Файлы DEX не найдены в архиве APK!"
            }.onFailure { e -> infoMessage = "Ошибка открытия DEX: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    /** Ctrl+Z: отменить последнее действие с файлами. */
    fun undoLast() {
        // Отмены — строго по одной и по порядку: зажатый Ctrl+Z иначе запускал их одновременно
        // Снимаем шаг здесь же, в потоке окна: два Ctrl+Z подряд отменяют два разных шага
        val op = undoStack.pop()
        if (op == null) {
            toast.info("Отменять нечего")
            return
        }
        // Что вернётся на место — показать в панелях
        val back: List<java.nio.file.Path> = when (op) {
            is FileOp.Trashed -> op.items.map { it.originalPath }
            is FileOp.Moved -> op.moves.map { it.first }
            is FileOp.Renamed -> listOf(op.from)
            is FileOp.BatchRenamed -> op.renames.map { it.first }
            is FileOp.Copied -> op.replaced.map { it.originalPath }
            is FileOp.Created -> emptyList()
        }
        ioScope.launch {
            val result = undoLock.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { FileUndo.revert(op, vfsManager) }.onFailure {
                        // Несделанное — обратно в стек, чтобы можно было повторить
                        FileUndo.remaining(op, vfsManager)?.let { rest -> undoStack.push(rest) }
                    }
                }
            }
            refreshPanes()
            result.onSuccess { msg ->
                toast.success(msg)
                back.groupBy { it.parent }.forEach { (dir, paths) ->
                    if (dir != null) revealArrived(dir, paths.map { arrivedName(it) })
                }
            }.onFailure { e -> toast.error("Не удалось отменить ${op.title}: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun undoAction() = ToastAction("Отменить") { undoLast() }

    /** Копирование или перенос в папку: конфликты имён — вопросом, результат — подсветкой и «Отменить». */
    fun transferTo(
        sources: List<java.nio.file.Path>,
        targetDir: java.nio.file.Path,
        move: Boolean,
        policy: ConflictPolicy? = null,
        clearCutAfter: Boolean = false,
        /** После окончания — например, чтобы сравнение папок пересчиталось само. */
        onDone: (() -> Unit)? = null
    ) {
        if (sources.isEmpty()) {
            onDone?.invoke()
            return
        }
        if (policy == null) {
            // Проверка имён — в фоне: на сервере это запросы по сети, окно не должно ждать
            ioScope.launch {
                val conflicts = withContext(Dispatchers.IO) {
                    runCatching { FileTransfers.conflicts(sources, targetDir) }.getOrDefault(emptyList())
                }
                if (conflicts.isNotEmpty()) {
                    pendingTransfer = PendingTransfer(sources, targetDir, move, conflicts.map { it.fileName.toString() }, clearCutAfter)
                } else {
                    transferTo(sources, targetDir, move, ConflictPolicy.KEEP_BOTH, clearCutAfter, onDone)
                }
            }
            return
        }
        bgBusyMessage = (if (move) "Перемещение: " else "Копирование: ") + Plural.items(sources.size) + "…"
        ioScope.launch {
            val r = withContext(Dispatchers.IO) {
                FileTransfers.transfer(vfsManager, sources, targetDir, move, policy ?: ConflictPolicy.KEEP_BOTH)
            }
            bgBusyMessage = null
            refreshPanes()
            // Даже если скопировать не удалось, заменённое уже в корзине — Ctrl+Z вернёт его
            if (r.done.isNotEmpty() || r.replaced.isNotEmpty() || r.archiveReplaced.isNotEmpty()) {
                undoStack.push(
                    if (move) FileOp.Moved(r.done, r.replaced, r.archiveReplaced)
                    else FileOp.Copied(r.done.map { it.second }, r.replaced, r.archiveReplaced)
                )
            }
            if (r.done.isNotEmpty()) {
                revealArrived(targetDir, r.done.map { arrivedName(it.second) })
                // Уже перенесённое убираем из «вырезанного» и при частичной ошибке — повторная вставка не тронет его
                if (move && clearCutAfter) {
                    val moved = r.done.mapTo(HashSet()) { it.first }
                    val remaining = sources.map { vfsManager.live(it) }.filter { it !in moved }
                    if (r.error == null || remaining.isEmpty()) {
                        withContext(Dispatchers.IO) { FileClipboard.clearCut(moved) }
                    } else {
                        FileClipboard.cut(remaining)
                    }
                }
                val verb = if (move) "Перемещено" else "Скопировано"
                val extra = if (r.skipped > 0) ", пропущено ${r.skipped}" else ""
                if (r.error == null) toast.show("$verb: ${r.done.size}$extra", ToastKind.SUCCESS, undoAction())
            } else if (r.skipped > 0 && r.error == null) {
                toast.info("Пропущено: ${r.skipped}")
            }
            r.error?.let { e ->
                infoMessage = "Ошибка после ${r.done.size} из ${sources.size}: ${e.message ?: e.javaClass.simpleName}" +
                    if (r.done.isNotEmpty() || r.replaced.isNotEmpty() || r.archiveReplaced.isNotEmpty()) "\nСделанное можно отменить: Ctrl+Z" else ""
            }
            onDone?.invoke()
        }
    }

    fun copyToPane(entries: List<VfsEntry>, destDir: java.nio.file.Path) =
        transferTo(entries.map { it.path }, destDir, move = false)

    fun moveToPane(entries: List<VfsEntry>, destDir: java.nio.file.Path) =
        transferTo(entries.map { it.path }, destDir, move = true)

    /** Delete: сразу в корзину с «Отменить»; в архиве корзины нет — спросим про удаление навсегда. */
    fun moveToTrash(pane: FilePaneState, entries: List<VfsEntry>) {
        if (entries.isEmpty() || trashing) return
        pendingDeleteCautious = false
        if (entries.any { !Trash.supports(it.path) }) {
            val remote = entries.firstNotNullOfOrNull { it.path.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem }
            pendingDeleteNote = if (remote != null) {
                "${remote.whereText} корзины нет — удаление сразу и насовсем."
            } else {
                "Внутри архива корзины нет — удаление сразу и насовсем."
            }
            pendingDelete = pane to entries
            return
        }
        bgBusyMessage = "В корзину: ${Plural.items(entries.size)}…"
        trashing = true
        ioScope.launch {
            val done = ArrayList<TrashItem>()
            val failure = try {
                withContext(Dispatchers.IO) {
                    var error: Throwable? = null
                    for (e in entries) {
                        try {
                            done.add(Trash.trash(e.path))
                        } catch (ex: Exception) {
                            error = ex
                            break
                        }
                    }
                    error
                }
            } finally {
                bgBusyMessage = null
                trashing = false
            }
            refreshPanes()
            if (done.isNotEmpty()) {
                undoStack.push(FileOp.Trashed(done))
                val what = if (done.size == 1) "«${done[0].originalPath.fileName}»" else Plural.items(done.size)
                toast.show("$what в корзине", ToastKind.SUCCESS, undoAction())
            }
            if (failure != null) {
                pendingDeleteNote = "В корзину не получилось: ${failure.message ?: failure.javaClass.simpleName}"
                pendingDeleteCautious = true
                pendingDelete = pane to entries.drop(done.size)
            }
        }
    }

    /**
     * В корзину из окон-инструментов (анализ места, дубликаты): с «Отменить» по Ctrl+Z.
     * [onDone] — в потоке окна: что ушло и текст ошибки, если что-то не ушло.
     */
    fun trashPaths(paths: List<java.nio.file.Path>, onDone: (List<java.nio.file.Path>, String?) -> Unit) {
        if (paths.isEmpty()) return onDone(emptyList(), null)
        bgBusyMessage = "В корзину: ${Plural.items(paths.size)}…"
        ioScope.launch {
            val done = ArrayList<TrashItem>()
            val failure = withContext(Dispatchers.IO) {
                var error: Throwable? = null
                for (p in paths) {
                    try {
                        done.add(Trash.trash(p))
                    } catch (ex: Exception) {
                        error = ex
                        break
                    }
                }
                error
            }
            bgBusyMessage = null
            refreshPanes()
            if (done.isNotEmpty()) undoStack.push(FileOp.Trashed(done))
            onDone(done.map { it.originalPath }, failure?.let { it.message ?: it.javaClass.simpleName })
        }
    }

    /** APK или DEX → проект на Java (jadx) в фоне; окно можно свернуть, по окончании — уведомление. */
    fun startJadx(input: java.nio.file.Path, options: org.wexclient.app.apk.JadxExportOptions, outDir: java.nio.file.Path) {
        val job = JadxJob(input, outDir)
        jadxJob = job
        ioScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    org.wexclient.app.apk.JadxExport.export(input, outDir, options, cancelled = { job.cancel.get() }) { job.progress = it }
                }
            }
            r.onSuccess { job.result = it }
            r.onFailure { e ->
                if (job.cancel.get()) {
                    // Недоделанное не оставляем: папку создали мы сами, ссылки внутри не раскрываются
                    withContext(Dispatchers.IO) {
                        runCatching {
                            java.nio.file.Files.walk(outDir).use { s ->
                                s.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
                            }
                        }
                    }
                    job.stopped = true
                } else {
                    job.error = e.message ?: e.javaClass.simpleName
                }
            }
            refreshPanes()
            if (!jadxShown) {
                job.result?.let {
                    toast.show("Проект готов: «${outDir.fileName}»", ToastKind.SUCCESS, ToastAction("Открыть") { activeState.navigateTo(outDir) })
                }
                job.error?.let { toast.error("Не декомпилировалось: $it") }
                jadxJob = null
                jadxInput = null
            }
        }
    }

    fun copyToClipboard(entries: List<VfsEntry>, cut: Boolean) {
        if (entries.isEmpty()) return
        val paths = entries.map { it.path }
        if (cut) FileClipboard.cut(paths) else FileClipboard.copy(paths)
        val what = if (entries.size == 1) "«${entries[0].name}»" else Plural.items(entries.size)
        toast.info((if (cut) "Вырезано: " else "Скопировано: ") + what + " — Ctrl+V вставит")
    }

    fun clipboardStamp(): String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))

    /** Ctrl+V: файлы (из WEX, Dolphin, Nautilus), картинка или текст — в папку. */
    fun pasteInto(pane: FilePaneState, dir: java.nio.file.Path = pane.currentPath) {
        ioScope.launch {
            when (val content = withContext(Dispatchers.IO) { runCatching { FileClipboard.read() }.getOrNull() }) {
                null -> toast.info("В буфере обмена нет файлов")
                is ClipContent.FileList -> transferTo(content.paths, dir, move = content.cut, clearCutAfter = content.cut)
                is ClipContent.Picture -> {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            val target = FileTransfers.freeName(dir, "Изображение ${clipboardStamp()}.png")
                            vfsManager.writeBytes(target, content.png)
                            target
                        }
                    }
                    refreshPanes()
                    saved.onSuccess { target ->
                        undoStack.push(FileOp.Created(target))
                        revealArrived(dir, listOf(arrivedName(target)))
                        toast.show("Картинка сохранена: ${target.fileName}", ToastKind.SUCCESS, undoAction())
                    }.onFailure { e -> toast.error("Не удалось сохранить картинку: ${e.message ?: e.javaClass.simpleName}") }
                }
                is ClipContent.PlainText -> pasteTextRequest = dir to content.text
            }
        }
    }

    /** Ctrl+D: копии рядом, «имя (копия)». */
    fun duplicate(pane: FilePaneState, entries: List<VfsEntry>) =
        transferTo(entries.map { it.path }, pane.currentPath, move = false)

    /** Символические ссылки на [paths] — в папке [dir]. */
    fun linkPaths(paths: List<java.nio.file.Path>, dir: java.nio.file.Path) {
        if (!Trash.supports(dir) || paths.any { !Trash.supports(it) }) {
            toast.error("Ссылки создаются только на обычном диске, не в архиве")
            return
        }
        ioScope.launch {
            val made = ArrayList<java.nio.file.Path>()
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    for (p in paths) {
                        val target = FileTransfers.freeName(dir, arrivedName(p))
                        Files.createSymbolicLink(target, p.toAbsolutePath().normalize())
                        made.add(target)
                    }
                }.exceptionOrNull()
            }
            refreshPanes()
            if (made.isNotEmpty()) {
                undoStack.push(FileOp.Created(made.toList()))
                revealArrived(dir, made.map { arrivedName(it) })
                toast.show("Ссылок создано: ${made.size}", ToastKind.SUCCESS, undoAction())
            }
            failure?.let { toast.error("Не удалось создать ссылку: ${it.message}") }
        }
    }

    /** Ссылки на выбранное — в папке соседней панели. */
    fun linkInOtherPane(pane: FilePaneState, entries: List<VfsEntry>) {
        val other = if (pane === leftPaneState) rightPaneState else leftPaneState
        linkPaths(entries.map { it.path }, other.currentPath)
    }

    /** Файлы бросили в папку — действие выбрали в меню. */
    fun dropFiles(paths: List<java.nio.file.Path>, dir: java.nio.file.Path, action: DropAction) {
        when (action) {
            DropAction.COPY -> transferTo(paths, dir, move = false)
            DropAction.MOVE -> transferTo(paths, dir, move = true)
            DropAction.LINK -> linkPaths(paths, dir)
        }
    }

    /** Операция над PDF в фоне: по окончании — сообщение и обновлённая панель. */
    fun pdfTask(pane: FilePaneState, busy: String, work: () -> java.nio.file.Path) {
        bgBusyMessage = busy
        ioScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(work) }
            bgBusyMessage = null
            pane.refresh()
            result.onSuccess { toast.info("Готово: " + (it.fileName?.toString() ?: it.toString())) }
            result.onFailure { toast.error(it.message ?: "Не получилось") }
        }
    }

    /** Запуск своей команды: в консоли панели или в фоне с сообщением по окончании. */
    fun runUserCommand(pane: FilePaneState, command: org.wexclient.app.commands.UserCommand, paths: List<java.nio.file.Path>) {
        val other = if (pane === leftPaneState) rightPaneState else leftPaneState
        val otherDir = other.currentPath.takeIf { it.fileSystem == java.nio.file.FileSystems.getDefault() }
        val line = org.wexclient.app.commands.UserCommands.expand(command.template, paths, otherDir)
        val dir = pane.currentPath
        if (command.inTerminal) {
            (if (pane === leftPaneState) leftTerminal else rightTerminal).runCommand(dir, line)
            return
        }
        bgBusyMessage = command.title + "…"
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    org.wexclient.app.util.ProcessRunner.run(
                        listOf("sh", "-c", line),
                        timeoutMs = 10 * 60_000,
                        workDir = dir.toFile()
                    )
                }
            }
            bgBusyMessage = null
            val run = result.getOrNull()
            when {
                run == null -> toast.error("Не запустилось: " + (result.exceptionOrNull()?.message ?: "ошибка"))
                run.ok -> {
                    toast.info("Готово: " + command.title)
                    pane.refresh()
                }
                else -> toast.error(
                    command.title + ": код " + run.exitCode +
                        run.output.lines().lastOrNull { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                )
            }
        }
    }

    /**
     * Отключиться от сервера или телефона: панели, открытые там, возвращаются в домашнюю папку.
     * Закрытие ждёт ssh до нескольких секунд — не в потоке окна.
     */
    fun disconnect(fs: org.wexclient.app.vfs.remote.RemoteFileSystem) {
        val home = java.nio.file.Path.of(org.wexclient.app.util.AppDirs.userHome.toString())
        for (pane in listOf(leftPaneState, rightPaneState)) {
            if (pane.currentPath.fileSystem === fs) pane.navigateTo(home)
        }
        ioScope.launch(Dispatchers.IO) { runCatching { fs.close() } }
        toast.info("Отключено от ${fs.label}")
    }

    /** Куда сохранять APK с телефона: папка соседней панели, если она на диске, иначе этой, иначе «Загрузки». */
    fun apkSaveDir(): java.nio.file.Path {
        listOf(inactiveState, activeState).map { it.currentPath }
            .firstOrNull { it.fileSystem === java.nio.file.FileSystems.getDefault() }
            ?.let { return it }
        val downloads = org.wexclient.app.vfs.BookmarksManager.xdgDir("DOWNLOAD")?.let { java.nio.file.Path.of(it) }
        return downloads?.takeIf { java.nio.file.Files.isDirectory(it) } ?: java.nio.file.Path.of(org.wexclient.app.util.AppDirs.userHome.toString())
    }

    /** Папка на телефоне — в активной панели; телефон открывается, если ещё не открыт. */
    fun openOnPhone(serial: String, name: String, dir: String) {
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fs = org.wexclient.app.vfs.adb.AdbFileSystemProvider.instance.open(serial, name)
                    fs.getPath(dir).takeIf { java.nio.file.Files.isDirectory(it) }
                }
            }
            result.onSuccess { path ->
                if (path != null) activeState.navigateTo(path) else toast.info("На телефоне нет папки $dir")
            }
            result.onFailure { toast.error(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Пункты меню файла, общие для обеих панелей. */
    fun fileActions(pane: FilePaneState, entry: VfsEntry): List<RowAction> {
        val targets = targetsFor(pane, entry)
        return buildList {
            add(RowAction(Icons.Default.DeleteForever, "Удалить навсегда", "Shift+Del", destructive = true) {
                pendingDeleteNote = null
                pendingDeleteCautious = false
                pendingDelete = pane to targets
            })
            add(RowAction(Icons.Default.ContentCopy, "Копировать", "Ctrl+C", NoctaliaSecondary) { copyToClipboard(targets, cut = false) })
            add(RowAction(Icons.Default.ContentCut, "Вырезать", "Ctrl+X", NoctaliaSecondary) { copyToClipboard(targets, cut = true) })
            if (entry.isDirectory && !entry.isArchive) {
                add(RowAction(Icons.Default.ContentPaste, "Вставить в эту папку", tint = NoctaliaSecondary) { pasteInto(pane, entry.path) })
                if (entry.path.fileSystem == java.nio.file.FileSystems.getDefault()) {
                    add(RowAction(Icons.Default.PieChart, "Анализ места в папке", tint = NoctaliaPrimary) { diskUsageRoot = entry.path })
                    add(RowAction(Icons.Default.FileCopy, "Найти дубликаты в папке", tint = NoctaliaPrimary) { duplicateRoots = listOf(entry.path) })
                }
            }
            add(RowAction(Icons.Default.FileCopy, "Дублировать", "Ctrl+Shift+D", NoctaliaSecondary) { duplicate(pane, targets) })
            // APK или DEX → проект на Java, как «Сохранить как проект Gradle» в jadx-gui
            if (targets.size == 1 && entry.path.fileSystem == java.nio.file.FileSystems.getDefault() &&
                org.wexclient.app.apk.JadxExport.supports(entry.name)
            ) {
                add(RowAction(Icons.Default.Code, "Декомпилировать в проект (jadx)", tint = NoctaliaPrimary) {
                    if (jadxJob?.running == true) {
                        toast.info("Уже идёт выгрузка «${jadxJob?.input?.fileName}» — дождитесь её")
                    } else {
                        jadxJob = null
                        jadxInput = entry
                    }
                    jadxShown = true
                })
            }
            // Два выделенных APK — можно посмотреть, чем новая сборка отличается от старой
            if (targets.size == 2 && targets.all { it.itemType == VfsItemType.ARCHIVE_APK }) {
                add(
                    RowAction(
                        Icons.AutoMirrored.Filled.CompareArrows,
                        "Сравнить эти два APK",
                        tint = NoctaliaPrimary
                    ) { apkCompare = targets[0] to targets[1] }
                )
            }
            if (Trash.supports(entry.path)) {
                add(RowAction(Icons.Default.AddLink, "Создать ссылку в соседней панели", tint = NoctaliaSecondary) { linkInOtherPane(pane, targets) })
            }
            // PDF: разделить, вытащить страницы и картинки, склеить несколько в один
            val pdfLocal = targets.all {
                it.itemType == VfsItemType.DOCUMENT_PDF && it.path.fileSystem == java.nio.file.FileSystems.getDefault()
            }
            if (pdfLocal && org.wexclient.app.doc.PdfTools.available) {
                if (targets.size >= 2) {
                    add(RowAction(Icons.Default.PictureAsPdf, "Объединить в один PDF", tint = NoctaliaPrimary) {
                        pdfTask(pane, "Объединение PDF…") { org.wexclient.app.doc.PdfTools.merge(targets.map { it.path }) }
                    })
                } else {
                    add(RowAction(Icons.Default.PictureAsPdf, "Разделить на страницы", tint = NoctaliaPrimary) {
                        pdfTask(pane, "Разделение на страницы…") { org.wexclient.app.doc.PdfTools.split(entry.path) }
                    })
                    add(RowAction(Icons.Default.PictureAsPdf, "Извлечь страницы…", tint = NoctaliaPrimary) {
                        ioScope.launch {
                            val pages = withContext(Dispatchers.IO) {
                                runCatching { org.wexclient.app.doc.PdfTools.info(entry.path)?.pages }.getOrNull() ?: 0
                            }
                            pdfRange = entry to pages
                        }
                    })
                    add(RowAction(Icons.Default.Image, "Вытащить картинки", tint = NoctaliaPrimary) {
                        pdfTask(pane, "Извлечение картинок…") { org.wexclient.app.doc.PdfTools.extractImages(entry.path) }
                    })
                }
            }
            // Свои команды: только те, что подходят к выделенному
            val paths = targets.map { it.path }
            val local = paths.all { it.fileSystem == java.nio.file.FileSystems.getDefault() }
            if (local) {
                for (command in org.wexclient.app.commands.UserCommands.commands) {
                    if (!command.accepts(paths) { p -> java.nio.file.Files.isDirectory(p) }) continue
                    add(RowAction(Icons.Default.PlayArrow, command.title, tint = NoctaliaPrimary) {
                        runUserCommand(pane, command, paths)
                    })
                }
            }
        }
    }

    fun paneActions(pane: FilePaneState): List<RowAction> = buildList {
        add(RowAction(Icons.Default.ContentPaste, "Вставить", "Ctrl+V") { pasteInto(pane) })
        val bothLocal = listOf(leftPaneState, rightPaneState).all {
            it.currentPath.fileSystem == java.nio.file.FileSystems.getDefault()
        }
        if (bothLocal) {
            add(RowAction(Icons.Default.SyncAlt, "Сравнить папки панелей", "Ctrl+Shift+S", NoctaliaPrimary) { folderCompare = true })
        }
        if (pane.currentPath.fileSystem == java.nio.file.FileSystems.getDefault()) {
            add(RowAction(Icons.Default.PieChart, "Анализ места…", "Ctrl+Shift+U", NoctaliaPrimary) { diskUsageRoot = pane.currentPath })
            add(RowAction(Icons.Default.FileCopy, "Найти дубликаты…", tint = NoctaliaPrimary) { duplicateRoots = listOf(pane.currentPath) })
            val other = if (pane === leftPaneState) rightPaneState else leftPaneState
            if (bothLocal && other.currentPath != pane.currentPath) {
                add(RowAction(Icons.Default.FileCopy, "Найти дубликаты в обеих панелях", tint = NoctaliaPrimary) {
                    duplicateRoots = listOf(leftPaneState.currentPath, rightPaneState.currentPath)
                })
            }
        }
        // Сервер по SSH и телефон по adb: подключиться, а если панель уже там — отключиться
        val remote = pane.currentPath.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem
        val phone = remote as? org.wexclient.app.vfs.adb.AdbFileSystem
        if (phone != null) {
            add(RowAction(Icons.Default.Apps, "Приложения на телефоне…", tint = NoctaliaPrimary) { adbApps = phone.serial to phone.deviceName })
        }
        if (remote != null) {
            add(RowAction(if (phone != null) Icons.Default.PhoneAndroid else Icons.Default.Dns, "Отключиться от ${remote.label}", tint = NoctaliaPrimary) { disconnect(remote) })
        } else {
            add(RowAction(Icons.Default.Dns, "Подключиться к серверу…", "Ctrl+Shift+K", NoctaliaPrimary) { sshConnect = true })
            add(RowAction(Icons.Default.PhoneAndroid, "Телефон по adb…", "Ctrl+Shift+M", NoctaliaPrimary) { adbConnect = true })
        }
        undoStack.peek()?.let { op ->
            add(RowAction(Icons.AutoMirrored.Filled.Undo, "Отменить ${op.title}", "Ctrl+Z", NoctaliaSecondary) { undoLast() })
        }
        // Закрытие окна оставляет WEX в памяти (следующее открытие мгновенное); это — полный выход
        onQuitRequest?.let { quit ->
            add(RowAction(Icons.Default.PowerSettingsNew, "Выйти из WEX", "Ctrl+Q", destructive = true) { quit() })
        }
    }

    /** Распаковать в новую папку рядом: имя занято — «имя (2)», ничего не перезаписывается. */
    fun extractToNewFolder(entry: VfsEntry, parent: java.nio.file.Path) {
        val target = org.wexclient.app.vfs.SafeExtract.freeFolder(parent, entry.archiveBaseName)
        val name = arrivedName(target)
        bgBusyMessage = "Распаковка в «$name»…"
        ioScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { vfsManager.extractArchive(entry.path, target) } }
            bgBusyMessage = null
            refreshPanes()
            result.onSuccess {
                undoStack.push(FileOp.Created(target))
                revealArrived(parent, listOf(name))
                toast.show("Распаковано в «$name»", ToastKind.SUCCESS, undoAction())
            }.onFailure { e ->
                infoMessage = "Не удалось распаковать «${entry.name}»: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun finishExtract(prepared: org.wexclient.app.vfs.SafeExtract.Prepared, policy: ConflictPolicy, archiveName: String) {
        bgBusyMessage = "Распаковка «$archiveName»…"
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { org.wexclient.app.vfs.SafeExtract.finish(vfsManager, prepared, policy) }
            }
            bgBusyMessage = null
            refreshPanes()
            result.onSuccess { r ->
                val anything = r.created.isNotEmpty() || r.replaced.isNotEmpty() || r.archiveReplaced.isNotEmpty()
                if (anything) {
                    undoStack.push(FileOp.Copied(r.created, r.replaced, r.archiveReplaced))
                }
                revealArrived(prepared.dest, prepared.topNames)
                val failed = r.error
                if (failed != null) {
                    infoMessage = "Распаковка «$archiveName» прервалась: ${failed.message ?: failed.javaClass.simpleName}" +
                        if (anything) "\nСделанное можно отменить: Ctrl+Z" else ""
                } else {
                    val extra = if (r.skipped > 0) ", пропущено: ${r.skipped}" else ""
                    toast.show("Распаковано: «$archiveName»$extra", ToastKind.SUCCESS, undoAction())
                }
            }.onFailure { e ->
                infoMessage = "Распаковка «$archiveName» прервалась: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Распаковать в существующую папку: совпадения — вопросом, заменённое — в корзину. */
    fun extractInto(entry: VfsEntry, dir: java.nio.file.Path) {
        bgBusyMessage = "Распаковка «${entry.name}»…"
        ioScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { org.wexclient.app.vfs.SafeExtract.prepare(vfsManager, entry.path, dir) }
            }
            bgBusyMessage = null
            prepared.onFailure { e ->
                refreshPanes()
                infoMessage = "Не удалось распаковать «${entry.name}»: ${e.message ?: e.javaClass.simpleName}"
            }.onSuccess { p ->
                if (p.conflicts.isEmpty()) finishExtract(p, ConflictPolicy.KEEP_BOTH, entry.name)
                else pendingExtract = p to entry.name
            }
        }
    }

    fun handleArchiveExtract(
        entry: VfsEntry,
        action: ExtractAction,
        currentPane: FilePaneState,
        otherPane: FilePaneState
    ) {
        val currentDir = currentPane.currentPath
        when (action) {
            ExtractAction.PROMPT -> {
                extractDialogTarget = Triple(entry, currentDir, otherPane.currentPath)
            }
            ExtractAction.TO_SUBFOLDER -> extractToNewFolder(entry, currentDir)
            ExtractAction.TO_OTHER_PANE -> extractToNewFolder(entry, otherPane.currentPath)
            ExtractAction.HERE -> extractInto(entry, currentDir)
        }
    }

    fun openTerminalAt(path: java.nio.file.Path) {
        if (path.fileSystem !== java.nio.file.FileSystems.getDefault()) {
            toast.info("Внутри архива терминал не открыть — сначала распакуйте")
            return
        }
        val dir = if (Files.isDirectory(path)) path else (path.parent ?: path)
        val dirStr = dir.toAbsolutePath().toString()
        val termCommands = listOf(
            listOf("ghostty", "--working-directory=$dirStr"),
            listOf("kitty", "--directory", dirStr),
            listOf("alacritty", "--working-directory", dirStr),
            listOf("xterm", "-e", "bash")
        )
        for (cmd in termCommands) {
            try {
                ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectOutput(org.wexclient.app.util.DiscardOutput)
                    .redirectError(org.wexclient.app.util.DiscardOutput)
                    .withUserEnv().start()
                return
            } catch (e: Exception) {
                // continue to next emulator
            }
        }
        toast.error("Не найден эмулятор терминала (ghostty, kitty, alacritty, xterm)")
    }

    fun isBinaryContent(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val probeSize = minOf(bytes.size, 8192)
        // NUL-байт почти всегда означает бинарь
        for (i in 0 until probeSize) {
            if (bytes[i] == 0.toByte()) return true
        }
        var nonText = 0
        for (i in 0 until probeSize) {
            val v = bytes[i].toInt() and 0xFF
            if (v < 0x09 || (v in 0x0E..0x1F) || v == 0x7F) nonText++
        }
        return nonText * 100 / probeSize > 10
    }

    /** Окно импорта ключа; файл из архива копируется во временную папку (вместе с парным сертификатом). */
    fun importKeyFrom(path: java.nio.file.Path) {
        val live = vfsManager.live(path)
        if (live.fileSystem === java.nio.file.FileSystems.getDefault()) {
            keyImport = KeyImportRequest(live.toFile(), null, null)
            return
        }
        try {
            val tmp = org.wexclient.app.util.ProcessScratch.work.newDir("key-")
            val name = live.fileName?.toString() ?: "key"
            Files.copy(live, tmp.resolve(name))
            val base = name.substringBeforeLast('.')
            for (suffix in listOf(".x509.pem", ".pem", ".crt", ".cer")) {
                val sibling = live.parent?.resolve(base + suffix) ?: continue
                if (sibling.fileName.toString() != name && Files.isRegularFile(sibling)) {
                    Files.copy(sibling, tmp.resolve(base + suffix), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val label = vfsManager.archiveLabel(live) ?: "архив"
            keyImport = KeyImportRequest(tmp.resolve(name).toFile(), "$label: $live", tmp)
        } catch (e: Exception) {
            infoMessage = "Не удалось прочитать ключ из архива: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Открытие в фоне: чтение и разбор не морозят окно, у строки файла — полоска прогресса
    var opening by remember { mutableStateOf<OpeningFile?>(null) }
    var openingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun show(action: () -> Unit): () -> Unit = action

    /** [prepare] читает и разбирает файл в фоне и возвращает, что показать (выполняется в потоке окна). */
    fun startOpening(entry: VfsEntry, prepare: (OpeningFile) -> () -> Unit) {
        // Повторный щелчок, пока файл открывается, — не начинаем заново
        if (opening?.path == entry.path && openingJob?.isActive == true) return
        openingJob?.cancel()
        val token = OpeningFile(entry.path)
        opening = token
        openingJob = ioScope.launch {
            val result: () -> Unit = try {
                withContext(Dispatchers.IO) { prepare(token) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                show { unknownFileEntry = entry }
            } finally {
                if (opening === token) opening = null
            }
            result()
        }
    }

    /**
     * Копия файла с сервера или телефона для просмотра: ~/.cache/wexdesktop/remote/<откуда>/<путь>
     * (давние убирает [org.wexclient.app.vfs.remote.RemoteCopies]). Имя сохраняется — по нему выбирается
     * просмотрщик. Правки в копии обратно не уходят.
     */
    fun downloadForViewing(entry: VfsEntry, token: OpeningFile): VfsEntry {
        val remote = entry.path.fileSystem as org.wexclient.app.vfs.remote.RemoteFileSystem
        val target = org.wexclient.app.vfs.remote.RemoteCopies.target(remote, entry.path)
        java.nio.file.Files.createDirectories(target.parent)
        val total = entry.size.coerceAtLeast(1)
        var done = 0L
        java.nio.file.Files.newInputStream(entry.path).use { input ->
            java.nio.file.Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    token.report(done, total)
                }
            }
        }
        token.progress = null
        return vfsManager.listEntries(target.parent).firstOrNull { it.name == entry.name }
            ?: entry.copy(path = target)
    }

    /** Байты файла с прогрессом чтения; после чтения — разбор, его длительность неизвестна. */
    fun readWithProgress(entry: VfsEntry, token: OpeningFile): ByteArray =
        vfsManager.readBytes(entry.path) { done, total -> token.report(done, total) }.also { token.progress = null }

    // Кодировка (UTF-8/CP1251/KOI8-R, BOM) и переводы строк определяются по байтам
    fun prepareText(entry: VfsEntry, token: OpeningFile): () -> Unit {
        val bytes = readWithProgress(entry, token)
        val decoded = org.wexclient.app.editor.TextCodec.decode(bytes)
        // Больше, чем выдерживает даже быстрый редактор, — сказать, а не уронить окно
        org.wexclient.app.editor.EditorLimits.fastProblem(decoded.text)?.let { why ->
            return show { infoMessage = "«${entry.name}» не открыть в редакторе: $why.\nЕго можно открыть в Hex-редакторе или во внешней программе." }
        }
        return show {
            viewerBytes = bytes
            viewerDecoded = decoded
            viewerContent = decoded.text
            viewingEntry = entry
        }
    }

    fun siblingsOf(entry: VfsEntry, accept: (VfsEntry) -> Boolean): List<VfsEntry> {
        val parentDir = entry.path.parent ?: return listOf(entry)
        return try {
            vfsManager.listEntries(parentDir).filter(accept)
        } catch (_: Exception) {
            listOf(entry)
        }
    }

    /** Канал, сокет, устройство не читаются как файл (чтение канала ждало бы вечно) — объясняем вместо чтения. */
    fun readable(entry: VfsEntry): Boolean {
        val kind = runCatching { vfsManager.specialFileKind(entry.path) }.getOrNull() ?: return true
        specialFile = entry to kind
        return false
    }

    fun prepareOpen(entry: VfsEntry, token: OpeningFile): () -> Unit {
        // Файл на сервере или телефоне. Текст и код правятся прямо там: читаются и пишутся через ssh или adb.
        // Всё остальное — PDF, видео, архивы, документы — открываем из скачанной копии:
        // внешние программы получили бы чужой путь и открыли бы локальный файл с тем же именем
        if (entry.path.fileSystem is org.wexclient.app.vfs.remote.RemoteFileSystem) {
            val editable = entry.itemType == VfsItemType.REGULAR_FILE || TypeFilter.CODE.accepts(entry)
            if (!editable) {
                val local = downloadForViewing(entry, token)
                return prepareOpen(local, token)
            }
        }
        vfsManager.specialFileKind(entry.path)?.let { kind -> return show { specialFile = entry to kind } }
        val lowerName = entry.name.lowercase()
        // Целиком читаем только то, что парсим; медиа и неизвестное — по заголовку,
        // иначе многогигабайтное видео читалось в память ещё до выбора просмотрщика
        val head by lazy { vfsManager.readHead(entry.path, 8192) }
        val fileSize by lazy { try { vfsManager.size(entry.path) } catch (_: Exception) { entry.size } }

        return when {
            entry.itemType == VfsItemType.DEX -> {
                val siblingDexes = siblingsOf(entry) { it.itemType == VfsItemType.DEX }
                if (siblingDexes.size > 1 || vfsManager.isInsideArchive(entry.path)) {
                    val contextName = if (vfsManager.isInsideArchive(entry.path)) {
                        "Архив приложения"
                    } else {
                        entry.path.parent?.fileName?.toString() ?: "Папка"
                    }
                    show { dexSelectionCandidate = contextName to siblingDexes }
                } else {
                    if (fileSize > MAX_BINARY_EDIT_BYTES) return show { unknownFileEntry = entry }
                    val engine = DexEditorEngine(readWithProgress(entry, token), path = entry.path)
                    show {
                        activeDexName = entry.name
                        dexEngine = engine
                    }
                }
            }
            // PDF показывает poppler: страницы картинками плюс текстовый слой
            entry.itemType == VfsItemType.DOCUMENT_PDF && org.wexclient.app.doc.PdfTools.available -> {
                show { pdfEntry = entry }
            }
            org.wexclient.app.doc.XlsxDocument.supports(entry.name) -> {
                show { sheetEntry = entry }
            }
            org.wexclient.app.doc.DocxDocument.supports(entry.name) -> {
                show { docEntry = entry }
            }
            entry.itemType == VfsItemType.JAVA_CLASS && org.wexclient.app.dex.ClassFiles.isClassFile(head) -> {
                show { classFileEntry = entry }
            }
            entry.itemType == VfsItemType.APPIMAGE -> show { appImageEntry = entry }
            org.wexclient.app.db.SqliteDb.looksLikeSqlite(head) -> show { databaseEntry = entry }
            AxmlDecoder.isBinaryXml(head) && fileSize <= MAX_TEXT_VIEW_BYTES -> {
                // Бинарный AXML: сохранить текст поверх нельзя — APK перестанет ставиться
                val xml = axmlDecoder.decode(readWithProgress(entry, token))
                show {
                    viewingManifestXml = xml
                    viewingManifestPath = null
                    viewingManifestFormat = null
                }
            }
            lowerName == "androidmanifest.xml" && fileSize <= MAX_TEXT_VIEW_BYTES -> {
                // Текстовый манифест: кодировка, BOM и переводы строк сохраняются как были
                val decoded = org.wexclient.app.editor.TextCodec.decode(readWithProgress(entry, token))
                show {
                    viewingManifestXml = decoded.text
                    viewingManifestPath = entry.path
                    viewingManifestFormat = decoded.format
                }
            }
            // Редакторы ELF/ARSC держат файл в памяти в нескольких копиях — огромное открываем как неизвестное
            (entry.itemType == VfsItemType.NATIVE_SO || lowerName.endsWith(".so") ||
                entry.itemType == VfsItemType.ARSC || lowerName.endsWith(".arsc")) && fileSize > MAX_BINARY_EDIT_BYTES -> {
                show { unknownFileEntry = entry }
            }
            entry.itemType == VfsItemType.NATIVE_SO || lowerName.endsWith(".so") -> {
                try {
                    val doc = ElfDocument(readWithProgress(entry, token))
                    show {
                        elfEntry = entry
                        elfDoc = doc
                    }
                } catch (e: Exception) {
                    show {
                        elfEntry = null
                        elfDoc = null
                        unknownFileEntry = entry
                    }
                }
            }
            entry.itemType == VfsItemType.ARSC || lowerName.endsWith(".arsc") -> {
                try {
                    val doc = ArscDocument(readWithProgress(entry, token))
                    show {
                        arscEntry = entry
                        arscDoc = doc
                    }
                } catch (e: Exception) {
                    show {
                        arscEntry = null
                        arscDoc = null
                        unknownFileEntry = entry
                    }
                }
            }
            entry.isImage -> {
                val siblingImages = siblingsOf(entry) { it.isImage }
                show { viewingImageEntry = entry to siblingImages }
            }
            entry.isVideo -> {
                val siblingVideos = siblingsOf(entry) { it.isVideo }
                show { viewingVideoEntry = entry to siblingVideos }
            }
            entry.isAudio -> {
                val siblingAudio = siblingsOf(entry) { it.isAudio }
                show {
                    AudioPlayerManager.play(entry, siblingAudio)
                    showAudioDialog = true
                }
            }
            // Бинарь или слишком большой файл — предлагаем выбор вместо мусора в редакторе.
            // Предел раньше был два мегабайта: больше редактор не открывал за разумное время.
            // Теперь текст крупнее показывает быстрый редактор — он рисует только видимые строки.
            fileSize > MAX_TEXT_VIEW_BYTES || isBinaryContent(head) -> {
                show { unknownFileEntry = entry }
            }
            else -> prepareText(entry, token)
        }
    }

    /** Элемент списка для пути вне текущей панели (ссылки из просмотра Markdown). */
    fun entryFor(path: java.nio.file.Path): VfsEntry {
        val name = path.fileName?.toString().orEmpty()
        val attrs = runCatching {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        }.getOrNull()
        val dir = attrs?.isDirectory == true
        val special = attrs?.isOther == true
        val type = if (special) VfsItemType.SPECIAL else VfsEntry.detectType(name, dir)
        return VfsEntry(
            name = name,
            path = path,
            isDirectory = dir,
            // Архив открывается как папка — признак нужен и здесь (файл из Telegram, xdg-open)
            isArchive = !dir && !special && org.wexclient.app.vfs.archive.ArchiveFormats.isBrowsable(name),
            size = attrs?.size() ?: 0L,
            lastModified = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            itemType = type,
            extension = name.substringAfterLast('.', "")
        )
    }

    fun openEntryContent(entry: VfsEntry) {
        if (entry.isDirectory) return
        val parentDir = entry.path.parent ?: entry.path
        NavigationHistoryManager.record(
            path = parentDir,
            fileName = entry.name,
            paneSide = if (isLeftActive) "Левая" else "Правая"
        )
        startOpening(entry) { token -> prepareOpen(entry, token) }
    }

    /**
     * Щелчок/Enter по файлу: ключ подписи — окно импорта, остальное — просмотр.
     * F3/F4 и «Просмотр»/«Правка» всегда открывают содержимое ([openEntryContent]).
     */
    fun openEntry(entry: VfsEntry) {
        if (!entry.isDirectory && entry.itemType == VfsItemType.KEYSTORE) importKeyFrom(entry.path) else openEntryContent(entry)
    }

    val rootFocusRequester = remember { FocusRequester() }
    // Каждый обмен панелей доворачивает значок на пол-оборота
    var swapSpin by remember { mutableStateOf(false) }
    // Вкладку с работающей в консоли программой закрываем только с подтверждением
    var pendingTabClose by remember { mutableStateOf<Pair<PaneTabs, Int>?>(null) }
    androidx.compose.runtime.DisposableEffect(leftTabs, rightTabs) {
        val ask: (PaneTabs) -> (Int) -> Boolean = { tabs ->
            { at ->
                if (tabs.tabs.getOrNull(at)?.terminalBusy == true) {
                    pendingTabClose = tabs to at
                    false
                } else true
            }
        }
        leftTabs.onCloseRequest = ask(leftTabs)
        rightTabs.onCloseRequest = ask(rightTabs)
        onDispose {
            leftTabs.onCloseRequest = null
            rightTabs.onCloseRequest = null
        }
    }
    // Клавиатура — файловому менеджеру (после поиска, строки пути, консоли)
    val focusFileManager: () -> Unit = remember { { runCatching { rootFocusRequester.requestFocus() } } }
    // Окна-слои (ModalDialog) после закрытия возвращают клавиатуру файловому менеджеру —
    // со следующего кадра и только если главное окно активно (не у диалога в другом окне)
    val mainWindowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        FocusReturn.target = {
            ioScope.launch {
                androidx.compose.runtime.withFrameNanos { }
                // Не забираем клавиатуру у диалога, поля поиска или консоли, которые открыл пункт меню
                val typing = leftPaneState.textInputFocused || rightPaneState.textInputFocused ||
                    org.wexclient.app.terminal.TerminalFocus.owned
                if (mainWindowInfo.isWindowFocused && FocusReturn.openLayers == 0 && !typing) focusFileManager()
            }
        }
        onDispose { FocusReturn.target = null }
    }
    /** Манифест APK — разбор в фоне, с полоской у строки файла. */
    fun analyzeApk(apk: VfsEntry) {
        startOpening(apk) { _ ->
            try {
                val root = vfsManager.openArchive(apk.path)
                val m = root.resolve("AndroidManifest.xml")
                if (!Files.exists(m)) {
                    show { infoMessage = "В «${apk.name}» нет AndroidManifest.xml" }
                } else {
                    val xml = axmlDecoder.decode(vfsManager.readBytesLimited(m, 16 shl 20))
                    show {
                        viewingManifestXml = xml
                        viewingManifestPath = null
                        viewingManifestFormat = null
                    }
                }
            } catch (e: Exception) {
                show { infoMessage = "Ошибка анализа «${apk.name}»: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    /** Результат поиска по содержимому: текст на найденной строке. */
    fun openAtLine(m: org.wexclient.app.vfs.ContentMatch) {
        val entry = entryFor(m.path)
        if (entry.size > MAX_TEXT_VIEW_BYTES) {
            toast.error("«${entry.name}» слишком большой для редактора")
            return
        }
        startOpening(entry) { token ->
            val open = prepareText(entry, token)
            show {
                viewerTarget = org.wexclient.app.editor.TextTarget(m.line, m.column, m.length)
                open()
            }
        }
    }

    /** Открыть в панели папку файла и встать на него. */
    fun revealFile(pane: FilePaneState, path: java.nio.file.Path) {
        val dir = path.parent ?: return
        pane.searchQuery = ""
        pane.navigateTo(dir)
        if (pane.currentPath != vfsManager.live(dir)) return
        val name = arrivedName(path)
        pane.whenLoaded {
            val idx = pane.entries.indexOfFirst { it.name == name }
            if (idx >= 0) {
                pane.focusedIndex = idx
                pane.revealArrived(listOf(name))
            }
        }
        focusFileManager()
    }

    /** Текст набирают в поле панели или в консоли — буквы и щелчки его не трогают. */
    fun textInputActive(): Boolean =
        leftPaneState.textInputFocused || rightPaneState.textInputFocused || org.wexclient.app.terminal.TerminalFocus.owned

    /** Буква без модификаторов — переход к файлу по имени. */
    fun typeAheadKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val cp = e.utf16CodePoint
        if (cp <= ' '.code || cp == 0xFFFF || cp == 0x7F) return false
        val ch = cp.toChar()
        if (!ch.isLetterOrDigit() && ch !in "._-+=()[]{}@#\$%&!~,;'^") return false
        return activeState.typeAhead(ch)
    }

    // Встроенные консоли панелей
    androidx.compose.runtime.DisposableEffect(leftTerminal, rightTerminal) {
        val refocus = { try { rootFocusRequester.requestFocus() } catch (_: Exception) {} }
        leftTerminal.onRequestOuterFocus = { refocus() }
        rightTerminal.onRequestOuterFocus = { refocus() }
        onDispose { }
    }

    // Закрытие окна закрывает вкладки целиком, а не только консоли: иначе каждая оставалась в памяти —
    // слушатель в VfsManager и слежение за папкой (inotify) держали её вместе со списком файлов и историей,
    // и закрытая вкладка продолжала перечитывать свою папку при каждом изменении
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            leftTabs.tabs.forEach { it.dispose() }
            rightTabs.tabs.forEach { it.dispose() }
        }
    }

    // Кнопка ▶ у AppImage: оконное — отдельным процессом, консольное — во встроенной консоли
    fun launchAppImage(entry: VfsEntry) {
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val info = org.wexclient.app.appimage.AppImages.inspect(entry.path)
                    if (info.foreignArch) {
                        error("«${entry.name}» собран для ${info.arch}, а этот компьютер — ${org.wexclient.app.appimage.AppImages.systemArch()}")
                    }
                    if (info.terminal) org.wexclient.app.appimage.AppImages.makeExecutable(entry.path)
                    else org.wexclient.app.appimage.AppImages.launch(entry.path)
                    info
                }
            }
            result.onSuccess { info ->
                val dir = entry.path.parent
                if (info.terminal && dir != null) {
                    try {
                        (if (isLeftActive) leftTerminal else rightTerminal)
                            .runCommand(dir, org.wexclient.app.terminal.ScriptRunner.word("./" + entry.name))
                    } catch (e: Exception) {
                        toast.error(e.message ?: "Не удалось запустить «${entry.name}»")
                        return@onSuccess
                    }
                }
                toast.success("Запущено: ${info.name}")
            }.onFailure { toast.error(it.message ?: "Не удалось запустить «${entry.name}»") }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            rootFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    /**
     * Файл, открытый снаружи (двойной щелчок в Telegram, «Открыть с помощью», xdg-open), ведёт себя
     * как щелчок по строке в панели: APK — окном APK, набор — выбором действия, архив — входом
     * внутрь, остальное — просмотром и правкой.
     */
    fun openFromOutside(target: java.nio.file.Path) {
        val entry = entryFor(target)
        target.parent?.let { leftPaneState.navigateTo(it) }
        when {
            entry.itemType == VfsItemType.ARCHIVE_APKS -> bundleEntry = entry
            entry.itemType == VfsItemType.ARCHIVE_APK -> apkDetailEntry = entry
            entry.isDirectory || entry.isArchive -> leftPaneState.enterEntry(entry)
            else -> openEntryContent(entry)
        }
    }

    // Файл или папка из командной строки: так работают «Открыть с помощью», xdg-open и ярлык
    androidx.compose.runtime.LaunchedEffect(openOnStart) {
        val target = openOnStart ?: return@LaunchedEffect
        if (Files.isDirectory(target)) {
            leftPaneState.navigateTo(target)
            revealOnStart?.let { leftPaneState.revealArrived(listOf(it)) }
        } else {
            openFromOutside(target)
        }
    }

    // То же, но от другого запуска WEX (путь передали этому окну): папку открываем новой вкладкой
    androidx.compose.runtime.LaunchedEffect(windowId) {
        androidx.compose.runtime.snapshotFlow { WexWindows.pendingFor(windowId) }
            .collect { pending ->
                if (pending == null) return@collect
                WexWindows.takePending(windowId)
                val target = pending.path
                when {
                    // «Расположение»: показать файл в папке, не открывая его заново
                    pending.revealName != null -> {
                        if (leftPaneState.currentPath != target) leftPaneState.navigateTo(target)
                        leftPaneState.revealArrived(listOf(pending.revealName))
                    }
                    Files.isDirectory(target) -> leftTabs.add(target)
                    else -> openFromOutside(target)
                }
            }
    }

    // Вернулись в окно из другого окна или рабочего стола: Compose не отдаёт клавиатуру обратно,
    // и до щелчка мышью не работают ни F-клавиши, ни Tab, ни консоль. Забираем её сами —
    // но не у открытого окна-слоя, поля поиска, строки пути и консоли
    var screenHasFocus by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(mainWindowInfo.isWindowFocused, screenHasFocus, FocusReturn.openLayers) {
        if (!mainWindowInfo.isWindowFocused || screenHasFocus || FocusReturn.openLayers > 0) return@LaunchedEffect
        // Пара кадров на то, чтобы фокус успел уйти к полю или консоли, если его забирают они
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        val typing = leftPaneState.textInputFocused || rightPaneState.textInputFocused ||
            org.wexclient.app.terminal.TerminalFocus.owned
        if (!screenHasFocus && FocusReturn.openLayers == 0 && !typing) focusFileManager()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .focusRequester(rootFocusRequester)
            // Кнопки, строки и сами панели забирают фокус при нажатии мыши (так устроен Compose Desktop):
            // Enter и пробел доставались им, а не списку. После щелчка клавиатура — снова у списка.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release && !textInputActive()) {
                            focusFileManager()
                        }
                    }
                }
            }
            .focusable()
            // Фон окна рисует тема (NoctaliaTheme) — под этим экраном и под каждым отдельным окном
            .onPreviewKeyEvent { keyEvent ->
                val tabs = { if (isLeftActive) leftTabs else rightTabs }
                // Консоль панели — единственное сочетание, которое работает и внутри самой консоли
                val terminalToggle = Keymap.matches("app.terminal", keyEvent) || Keymap.matches("app.terminal2", keyEvent)
                if (terminalToggle) {
                    val term = if (isLeftActive) leftTerminal else rightTerminal
                    if (term.visible && org.wexclient.app.terminal.TerminalFocus.active) term.hide()
                    else term.show(activeState.currentPath)
                    return@onPreviewKeyEvent true
                }
                // Пока фокус в консоли (или в её поиске по выводу), клавиши не перехватываем
                if (org.wexclient.app.terminal.TerminalFocus.owned) return@onPreviewKeyEvent false
                // Всё остальное — из списка биндов: настройки → «Бинды»
                when (Keymap.action(keyEvent, Keymap.GROUP_MAIN)) {
                    "app.shortcuts" -> { showShortcuts = true; return@onPreviewKeyEvent true }
                    "app.search.name" -> { activeState.requestSearchFocus(); return@onPreviewKeyEvent true }
                    "app.search.content" -> { showContentSearch = true; return@onPreviewKeyEvent true }
                    "app.history" -> {
                        if (!showHistory) historyFromTab = false
                        showHistory = !showHistory
                        return@onPreviewKeyEvent true
                    }
                    "app.ssh" -> { sshConnect = true; return@onPreviewKeyEvent true }
                    "app.adb" -> { adbConnect = true; return@onPreviewKeyEvent true }
                    "app.diskusage" -> {
                        val dir = activeState.currentPath
                        if (dir.fileSystem == java.nio.file.FileSystems.getDefault()) diskUsageRoot = dir
                        else toast.info("Анализ места — для папок на этом компьютере")
                        return@onPreviewKeyEvent true
                    }
                    "app.foldercompare" -> { folderCompare = true; return@onPreviewKeyEvent true }
                    "app.refresh" -> { refreshPanes(force = true); return@onPreviewKeyEvent true }
                    "app.pathedit" -> { activeState.requestPathEdit(); return@onPreviewKeyEvent true }
                    "app.quit" -> { onQuitRequest?.invoke(); return@onPreviewKeyEvent true }
                }
                when (Keymap.action(keyEvent, Keymap.GROUP_PANE)) {
                    "tabs.new" -> { tabs().add(); true }
                    "tabs.close" -> { val t = tabs(); t.close(t.index); true }
                    "tabs.next" -> { tabs().cycle(1); true }
                    "tabs.prev" -> { tabs().cycle(-1); true }
                    else -> {
                        // Ctrl+1…9 — вкладка по номеру: цифры в список биндов не выносим
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isCtrlPressed && !keyEvent.isAltPressed &&
                            keyEvent.key in DIGIT_KEYS
                        ) {
                            tabs().select(DIGIT_KEYS.indexOf(keyEvent.key))
                            true
                        } else false
                    }
                }
            }
            .onKeyEvent { keyEvent ->
                val plain = !keyEvent.isCtrlPressed && !keyEvent.isAltPressed && !keyEvent.isMetaPressed
                // Пока набирают имя, пробел и Backspace — часть набора
                if (keyEvent.type == KeyEventType.KeyDown && plain && !textInputActive() && activeState.isTypingAhead()) {
                    when (keyEvent.key) {
                        Key.Spacebar -> return@onKeyEvent activeState.typeAhead(' ')
                        Key.Backspace -> {
                            activeState.typeAheadBackspace()
                            return@onKeyEvent true
                        }
                        Key.Escape -> {
                            activeState.clearTypeAhead()
                            return@onKeyEvent true
                        }
                        else -> Unit
                    }
                }
                // Действия над файлами — из списка биндов (настройки → «Бинды»)
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val pick = { activeState.selectedEntries.firstOrNull() ?: activeState.entries.getOrNull(activeState.focusedIndex) }
                    val bound = when (Keymap.action(keyEvent, Keymap.GROUP_PANE)) {
                        "pane.other" -> { isLeftActive = !isLeftActive; true }
                        "pane.up", "pane.up2" -> if (activeState.canGoUp) { activeState.goUp(); true } else false
                        "pane.back" -> if (activeState.canGoBack) { activeState.goBack(); true } else false
                        "pane.forward" -> if (activeState.canGoForward) { activeState.goForward(); true } else false
                        "pane.properties" -> {
                            val entry = activeState.entries.getOrNull(activeState.focusedIndex)
                            if (entry != null) { propertiesEntry = entry; true } else false
                        }
                        "pane.selectAll" -> { activeState.selectAll(); true }
                        "pane.rename" -> {
                            if (activeState.selectedEntries.size > 1) {
                                batchRenameEntries = activeState.selectedEntries.toList()
                                true
                            } else {
                                val selected = pick()
                                if (selected != null) { renameEntry = selected; true } else false
                            }
                        }
                        "pane.view", "pane.edit" -> {
                            val selected = pick()
                            if (selected != null) { openEntryContent(selected); true } else false
                        }
                        "pane.newFile", "pane.newFile2" -> { createRequest = NewEntryKind.FILE to activeState; true }
                        "pane.newFolder", "pane.newFolder2" -> { createRequest = NewEntryKind.FOLDER to activeState; true }
                        "pane.copyTo" -> {
                            val targets = activeTargets()
                            if (targets.isEmpty()) false else { copyConfirm = targets; true }
                        }
                        "pane.moveTo" -> {
                            val targets = activeTargets()
                            if (targets.isEmpty()) false else { moveConfirm = targets; true }
                        }
                        "pane.compress" -> {
                            val targets = activeTargets()
                            if (targets.isEmpty()) false else { compressingEntries = targets; true }
                        }
                        "pane.extract" -> {
                            val entry = pick()
                            if (entry != null && entry.isExtractableArchive) {
                                handleArchiveExtract(entry, ExtractAction.PROMPT, activeState, inactiveState)
                                true
                            } else false
                        }
                        "pane.trash" -> {
                            val targets = activeTargets()
                            if (targets.isEmpty()) false else { moveToTrash(activeState, targets); true }
                        }
                        "pane.delete" -> {
                            val targets = activeTargets()
                            if (targets.isEmpty()) false else {
                                pendingDeleteNote = null
                                pendingDeleteCautious = false
                                pendingDelete = activeState to targets
                                true
                            }
                        }
                        "pane.undo" -> { undoLast(); true }
                        "pane.clipCopy" -> {
                            val targets = activeTargets()
                            if (targets.isNotEmpty()) copyToClipboard(targets, cut = false)
                            targets.isNotEmpty()
                        }
                        "pane.clipCut" -> {
                            val targets = activeTargets()
                            if (targets.isNotEmpty()) copyToClipboard(targets, cut = true)
                            targets.isNotEmpty()
                        }
                        "pane.clipPaste" -> { pasteInto(activeState); true }
                        "pane.duplicate" -> {
                            val targets = activeTargets()
                            if (targets.isNotEmpty()) duplicate(activeState, targets)
                            targets.isNotEmpty()
                        }
                        "pane.diff" -> {
                            val currentSel = pick()
                            if (currentSel != null && !currentSel.isDirectory) {
                                val otherSel = inactiveState.selectedEntries.firstOrNull()
                                    ?: inactiveState.entries.firstOrNull { it.name == currentSel.name }
                                if (otherSel != null) diffCandidates = currentSel to otherSel
                                else infoMessage = "Для сравнения выберите файл в противоположной панели"
                                true
                            } else false
                        }
                        "pane.hex" -> {
                            val selected = pick()
                            if (selected != null && !selected.isDirectory) { hexEditorEntry = selected; true } else false
                        }
                        else -> false
                    }
                    if (bound) return@onKeyEvent true
                }
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val handled = when (keyEvent.layoutKey()) {
                        Key.DirectionDown -> {
                            if (activeState.entries.isNotEmpty()) {
                                activeState.focusedIndex = (activeState.focusedIndex + 1).coerceAtMost(activeState.entries.size - 1)
                                true
                            } else false
                        }
                        // Home/End — первый и последний файл, PgUp/PgDn — на экран
                        Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown -> {
                            val last = activeState.entries.size - 1
                            if (last >= 0 && !keyEvent.isAltPressed) {
                                val page = activeState.pageRows.coerceAtLeast(1)
                                activeState.focusedIndex = when (keyEvent.layoutKey()) {
                                    Key.MoveHome -> 0
                                    Key.MoveEnd -> last
                                    Key.PageUp -> activeState.focusedIndex - page
                                    else -> activeState.focusedIndex + page
                                }.coerceIn(0, last)
                                true
                            } else false
                        }
                        Key.DirectionUp -> {
                            if (activeState.entries.isNotEmpty() && !keyEvent.isAltPressed) {
                                activeState.focusedIndex = (activeState.focusedIndex - 1).coerceAtLeast(0)
                                true
                            } else false
                        }
                        Key.Spacebar -> {
                            val entry = activeState.entries.getOrNull(activeState.focusedIndex)
                            if (entry != null) {
                                activeState.toggleSelection(entry)
                                activeState.focusedIndex = (activeState.focusedIndex + 1).coerceAtMost(activeState.entries.size - 1)
                                true
                            } else false
                        }
                        Key.Enter -> {
                            val entry = activeState.entries.getOrNull(activeState.focusedIndex)
                            if (entry != null && !keyEvent.isAltPressed) {
                                activeState.clearTypeAhead()
                                when {
                                    entry.itemType == VfsItemType.ARCHIVE_APKS -> {
                                        bundleEntry = entry
                                    }
                                    entry.itemType == VfsItemType.ARCHIVE_APK -> {
                                        apkDetailEntry = entry
                                    }
                                    entry.isDirectory || entry.isArchive -> {
                                        activeState.enterEntry(entry)
                                    }
                                    else -> {
                                        openEntry(entry)
                                    }
                                }
                                true
                            } else false
                        }
                        Key.Escape -> {
                            if (leftPaneState.cancelArchiveOpening() or rightPaneState.cancelArchiveOpening()) {
                                true
                            } else if (showAudioDialog) {
                                showAudioDialog = false
                                true
                            } else if (showHistory) {
                                showHistory = false
                                true
                            } else {
                                activeState.clearSelection()
                                true
                            }
                        }
                        else -> false
                    }
                    handled || (plain && !textInputActive() && typeAheadKey(keyEvent))
                } else false
            }
    ) {
        // App Header Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Левой части — что останется после кнопок: на узком окне сжимается подсказка, а не
                // «Настройки» и «Поменять местами»
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = "MT Logo",
                        tint = NoctaliaPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "WEX Desktop",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = NoctaliaTertiary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "MT Manager Edition",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoctaliaTertiary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = "F1: все клавиши  ·  Tab: панель  ·  Space: выделение  ·  Del: в корзину  ·  Ctrl+Z: отменить  ·  ПКМ: меню",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .noFocus()
                            .clickable { showShortcuts = true }
                            .pointerHoverIcon(PointerIcon.Hand)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // История переходов (та же, что у язычка внизу между панелями)
                    IconButton(
                        onClick = {
                            if (!showHistory) historyFromTab = false
                            showHistory = !showHistory
                        },
                        modifier = Modifier.background(
                            animateColorAsState(
                                if (showHistory) NoctaliaPrimary.copy(alpha = 0.16f) else Color.Transparent,
                                tween(Motion.TINT),
                                label = "history-bg"
                            ).value,
                            CircleShape
                        )
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "История переходов (Ctrl+H)",
                            tint = animateColorAsState(
                                if (showHistory) NoctaliaPrimary else NoctaliaSecondary,
                                tween(Motion.TINT),
                                label = "history-tint"
                            ).value
                        )
                    }
                    IconButton(
                        onClick = { showSettings = true }
                    ) {
                        // Шестерёнка поворачивается, пока открыты настройки
                        val gear by animateFloatAsState(
                            if (showSettings) 60f else 0f,
                            tween(Motion.ENTER),
                            label = "settings-gear"
                        )
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = if (showSettings) NoctaliaPrimary else NoctaliaSecondary,
                            modifier = Modifier.graphicsLayer { rotationZ = gear }
                        )
                    }
                    IconButton(
                        onClick = {
                            swapSpin = !swapSpin
                            val temp = leftPaneState.currentPath
                            leftPaneState.navigateTo(rightPaneState.currentPath)
                            rightPaneState.navigateTo(temp)
                        }
                    ) {
                        // Стрелки разворачиваются на пол-оборота — как сами панели
                        val turn by animateFloatAsState(
                            if (swapSpin) 180f else 0f,
                            tween(Motion.ENTER),
                            label = "swap-turn"
                        )
                        Icon(
                            Icons.Default.SyncAlt,
                            contentDescription = "Поменять панели местами",
                            tint = NoctaliaSecondary,
                            modifier = Modifier.graphicsLayer { rotationZ = turn }
                        )
                    }
                }
            }
        }

        // Dual Pane Workspace with Adjustable Splitter (+ busy overlay)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        // Доля панелей читается при раскладке, а не в композиции: перетаскивание разделителя
        // раньше перекомпоновывало весь экран на каждое движение мыши
        SplitPanes(
            ratio = { splitRatio },
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 0.dp)
        ) {
            // Left Pane (Niri style: focus follows mouse)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(bottom = 8.dp)
                    .activateOnMouseMove { if (!isLeftActive) isLeftActive = true }
            ) {
                FilePaneView(
                    otherPaneOnRight = true,
                    tabs = leftTabs,
                    state = leftPaneState,
                    rowActions = { entry -> fileActions(leftPaneState, entry) },
                    onDropFiles = { paths, dir, action -> dropFiles(paths, dir, action) },
                    onDragFinished = { refreshPanes() },
                    paneActions = { paneActions(leftPaneState) },
                    onOpenTrash = { showTrash = true },
                    onDisconnect = { disconnect(it) },
                    isActive = isLeftActive,
                    opening = opening,
                    onLaunchAppImage = { launchAppImage(it) },
                    onActivate = { isLeftActive = true },
                    onOpenFile = { entry -> openEntry(entry) },
                    onViewContent = { entry -> openEntryContent(entry) },
                    onImportKey = { entry -> importKeyFrom(entry.path) },
                    onCopyOther = { entry ->
                        copyToPane(targetsFor(leftPaneState, entry), rightPaneState.currentPath)
                    },
                    onMoveOther = { entry ->
                        moveToPane(targetsFor(leftPaneState, entry), rightPaneState.currentPath)
                    },
                    onRename = { entry -> renameEntry = entry },
                    onDelete = { entry ->
                        moveToTrash(leftPaneState, targetsFor(leftPaneState, entry))
                    },
                    onProperties = { entry -> propertiesEntry = entry },
                    onCompress = { entry ->
                        compressingEntries = if (leftPaneState.isSelected(entry)) {
                            leftPaneState.selectedEntries.toList()
                        } else {
                            listOf(entry)
                        }
                    },
                    onExtract = { entry, action ->
                        handleArchiveExtract(entry, action, currentPane = leftPaneState, otherPane = rightPaneState)
                    },
                    onOpenTerminal = { path -> openTerminalAt(path) },
                    onNewFolder = { createRequest = NewEntryKind.FOLDER to leftPaneState },
                    onNewFile = { createRequest = NewEntryKind.FILE to leftPaneState },
                    onAnalyzeApk = { apk -> analyzeApk(apk) },
                    onInspectDex = { dex -> if (dex.itemType == VfsItemType.ARCHIVE_APK) openApkDex(dex) else openEntryContent(dex) },
                    onOpenApkDetail = { apk -> apkDetailEntry = apk },
                    onSignApk = { apk -> signingApkEntry = apk },
                    onOpenHex = { if (readable(it)) hexEditorEntry = it },
                    onChecksum = { if (readable(it)) checksumEntry = it },
                    onDiffWithOther = { file ->
                        val rightSel = rightPaneState.selectedEntries.firstOrNull()
                            ?: rightPaneState.entries.firstOrNull { it.name == file.name }
                        if (rightSel != null) {
                            if (readable(file) && readable(rightSel)) diffCandidates = file to rightSel
                        } else {
                            infoMessage = "Выберите файл в правой панели для сравнения"
                        }
                    },
                    onBatchRename = { batchRenameEntries = it },
                    onOpenInOtherPane = { rightPaneState.navigateTo(it) },
                    onInstallApk = { openInstall(it) },
                    onOpenBundle = { bundleEntry = it },
                    onConvertBundle = { bundleConvert = it },
                    onMessage = { infoMessage = it },
                    terminal = leftTerminal,
                    onReturnFocus = focusFileManager,
                    footerInsetEnd = 24.dp
                )
            }

            // Draggable Center Splitter (slim 6.dp divider so panels touch seamlessly)
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(ResizeHorizontalPointerIcon)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newRatio = (splitRatio + dragAmount.x / 1200f).coerceIn(0.2f, 0.8f)
                            splitRatio = newRatio
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.width(2.dp).fillMaxHeight(0.35f)
                ) {}
            }

            // Right Pane (Niri style: focus follows mouse)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(bottom = 8.dp)
                    .activateOnMouseMove { if (isLeftActive) isLeftActive = false }
            ) {
                FilePaneView(
                    otherPaneOnRight = false,
                    tabs = rightTabs,
                    state = rightPaneState,
                    rowActions = { entry -> fileActions(rightPaneState, entry) },
                    onDropFiles = { paths, dir, action -> dropFiles(paths, dir, action) },
                    onDragFinished = { refreshPanes() },
                    paneActions = { paneActions(rightPaneState) },
                    onOpenTrash = { showTrash = true },
                    onDisconnect = { disconnect(it) },
                    isActive = !isLeftActive,
                    opening = opening,
                    onLaunchAppImage = { launchAppImage(it) },
                    onActivate = { isLeftActive = false },
                    onOpenFile = { entry -> openEntry(entry) },
                    onViewContent = { entry -> openEntryContent(entry) },
                    onImportKey = { entry -> importKeyFrom(entry.path) },
                    onCopyOther = { entry ->
                        copyToPane(targetsFor(rightPaneState, entry), leftPaneState.currentPath)
                    },
                    onMoveOther = { entry ->
                        moveToPane(targetsFor(rightPaneState, entry), leftPaneState.currentPath)
                    },
                    onRename = { entry -> renameEntry = entry },
                    onDelete = { entry ->
                        moveToTrash(rightPaneState, targetsFor(rightPaneState, entry))
                    },
                    onProperties = { entry -> propertiesEntry = entry },
                    onCompress = { entry ->
                        compressingEntries = if (rightPaneState.isSelected(entry)) {
                            rightPaneState.selectedEntries.toList()
                        } else {
                            listOf(entry)
                        }
                    },
                    onExtract = { entry, action ->
                        handleArchiveExtract(entry, action, currentPane = rightPaneState, otherPane = leftPaneState)
                    },
                    onOpenTerminal = { path -> openTerminalAt(path) },
                    onNewFolder = { createRequest = NewEntryKind.FOLDER to rightPaneState },
                    onNewFile = { createRequest = NewEntryKind.FILE to rightPaneState },
                    onAnalyzeApk = { apk -> analyzeApk(apk) },
                    onInspectDex = { dex -> if (dex.itemType == VfsItemType.ARCHIVE_APK) openApkDex(dex) else openEntryContent(dex) },
                    onOpenApkDetail = { apk -> apkDetailEntry = apk },
                    onSignApk = { apk -> signingApkEntry = apk },
                    onOpenHex = { if (readable(it)) hexEditorEntry = it },
                    onChecksum = { if (readable(it)) checksumEntry = it },
                    onDiffWithOther = { file ->
                        val leftSel = leftPaneState.selectedEntries.firstOrNull()
                            ?: leftPaneState.entries.firstOrNull { it.name == file.name }
                        if (leftSel != null) {
                            if (readable(file) && readable(leftSel)) diffCandidates = file to leftSel
                        } else {
                            infoMessage = "Выберите файл в левой панели для сравнения"
                        }
                    },
                    onBatchRename = { batchRenameEntries = it },
                    onOpenInOtherPane = { leftPaneState.navigateTo(it) },
                    onInstallApk = { openInstall(it) },
                    onOpenBundle = { bundleEntry = it },
                    onConvertBundle = { bundleConvert = it },
                    onMessage = { infoMessage = it },
                    terminal = rightTerminal,
                    onReturnFocus = focusFileManager,
                    footerInsetStart = 24.dp
                )
            }
        }

        // Язычок на границе панелей: открыть в соседней панели ту же папку, что и в активной
        Surface(
            onClick = {
                inactiveState.navigateTo(activeState.currentPath)
                toast.info("Вторая панель: " + (activeState.currentPath.fileName?.toString() ?: activeState.currentPath.toString()))
            },
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .width(56.dp)
                .height(20.dp)
                .align(splitTabAlignment)
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                // Стрелка показывает, куда уедет папка: из левой панели — вправо, из правой — влево
                val direction by animateFloatAsState(
                    if (isLeftActive) 1f else -1f,
                    tween(Motion.ENTER),
                    label = "handoff-direction"
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowRightAlt,
                    contentDescription = if (isLeftActive) "Открыть эту же папку в правой панели"
                    else "Открыть эту же папку в левой панели",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).graphicsLayer { scaleX = direction }
                )
            }
        }

        // Docked Mini Audio Player Bar (when audio is playing or paused in background) with expressive motion
        val playingTrack = AudioPlayerManager.currentTrack
        AnimatedOverlay(
            visible = playingTrack != null && !showAudioDialog,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 28.dp),
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 380f)
            ) { it } + fadeIn(tween(220)) + scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 380f)
            ),
            exit = slideOutVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            ) { it } + fadeOut(tween(160)) + scaleOut(
                targetScale = 0.82f,
                animationSpec = tween(160)
            )
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .clickable { showAudioDialog = true }
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAudioPlaying = AudioPlayerManager.isPlaying && !AudioPlayerManager.isPaused
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.widthIn(max = 160.dp)) {
                        Text(
                            text = playingTrack?.name ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${AudioPlayerManager.formatTime(AudioPlayerManager.positionSeconds * 1000)} / ${AudioPlayerManager.formatTime(AudioPlayerManager.totalDurationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { AudioPlayerManager.playPause() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isAudioPlaying) "Пауза" else "Воспроизведение",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { AudioPlayerManager.next() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Следующий трек",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { AudioPlayerManager.closePlayer() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть плеер",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

            // Busy pill for background file operations
            bgBusyMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WexSpinner(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NoctaliaPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Свёрнутая выгрузка jadx: ход работы; щелчок снова открывает окно
            jadxJob?.takeIf { it.running && !jadxShown }?.let { job ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // Над строкой состояния панели: «свободно …» остаётся видно
                        .padding(end = 18.dp, bottom = 40.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { jadxShown = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val f = job.fraction
                        if (f != null) {
                            CircularProgressIndicator(progress = { f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                        } else {
                            WexSpinner(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "jadx: ${job.input.fileName}" + (f?.let { " · ${(it * 100).toInt()} %" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            ToastHost(toast)

            // History popup overlay with fluid spring animations
            AnimatedOverlay(
                visible = showHistory,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180))
            ) {
                // Scrim to dismiss on click outside
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = Motion.SCRIM))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showHistory = false
                        }
                )
            }

            // Карточка истории: от язычка — снизу по центру, выезжает вверх; от кнопки в шапке — справа сверху, вниз
            val fromTab = historyFromTab
            val cardOrigin = if (fromTab) {
                androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            } else {
                androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            }
            val slideSign = if (fromTab) 1 else -1
            AnimatedOverlay(
                visible = showHistory,
                modifier = if (fromTab) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                } else {
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)
                },
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.76f, stiffness = 400f)
                ) { slideSign * it / 3 } + fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.90f,
                    transformOrigin = cardOrigin,
                    animationSpec = spring(dampingRatio = 0.76f, stiffness = 400f)
                ),
                exit = slideOutVertically(
                    animationSpec = tween(170, easing = FastOutSlowInEasing)
                ) { slideSign * it / 4 } + fadeOut(tween(150)) + scaleOut(
                    targetScale = 0.92f,
                    transformOrigin = cardOrigin,
                    animationSpec = tween(150)
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, NoctaliaSecondary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .dialogGlass(RoundedCornerShape(16.dp))
                        .widthIn(min = 460.dp, max = 640.dp)
                        .fillMaxWidth(0.55f)
                        .heightIn(max = 440.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    tint = NoctaliaPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "История переходов",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (NavigationHistoryManager.items.isNotEmpty()) {
                                    TextButton(
                                        onClick = { NavigationHistoryManager.clear() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Очистить", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(
                                    onClick = { showHistory = false },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Закрыть",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Content List
                        if (NavigationHistoryManager.items.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "История пуста",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(NavigationHistoryManager.items) { item ->
                                    HistoryRowItem(
                                        item = item,
                                        onNavigate = {
                                            val path = try {
                                                java.nio.file.Paths.get(item.pathStr)
                                            } catch (_: Exception) {
                                                null
                                            }
                                            if (path != null) {
                                                val targetState = if (item.isBeforeClose) {
                                                    if (item.paneSide == "Правая") rightPaneState else leftPaneState
                                                } else {
                                                    activeState
                                                }
                                                isLeftActive = (targetState == leftPaneState)

                                                if (targetState.currentPath != path) {
                                                    targetState.navigateTo(path)
                                                }
                                                if (item.fileName != null) {
                                                    targetState.whenLoaded {
                                                        val idx = targetState.entries.indexOfFirst { it.name == item.fileName }
                                                        if (idx >= 0) {
                                                            targetState.focusedIndex = idx
                                                            targetState.selectedEntries.clear()
                                                            targetState.selectedEntries.add(targetState.entries[idx])
                                                        }
                                                        targetState.recentlyReturnedChildName = item.fileName
                                                    }
                                                }
                                                showHistory = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Audio Player In-Window Overlay Modal with expressive spring appearance and disappearance
            AnimatedOverlay(
                visible = showAudioDialog && AudioPlayerManager.currentTrack != null,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = Motion.SCRIM))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showAudioDialog = false
                        }
                )
            }

            AnimatedOverlay(
                visible = showAudioDialog && AudioPlayerManager.currentTrack != null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                ) { it / 5 } + fadeIn(tween(220)) + scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                ),
                exit = slideOutVertically(
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                ) { it / 5 } + fadeOut(tween(160)) + scaleOut(
                    targetScale = 0.90f,
                    animationSpec = tween(160)
                )
            ) {
                AudioPlayerContent(
                    onDismiss = { showAudioDialog = false }
                )
            }
        }
    }

    // Modal: APK Detail Dialog (MT Manager style)
    apkDetailEntry?.let { apk ->
        val onDisk = apk.path.fileSystem === java.nio.file.FileSystems.getDefault()
        ApkDetailDialog(
            apkEntry = apk,
            refreshKey = apkDetailRefresh,
            signing = signingApks,
            onSignInPlace = if (onDisk) {
                { signApksInPlace(listOf(apk.path.toAbsolutePath().normalize().toString()), report = { ok, text -> apkNotice = ok to text }) }
            } else null,
            onSignWithKey = if (onDisk) {
                { key -> signApksInPlace(listOf(apk.path.toAbsolutePath().normalize().toString()), key, report = { ok, text -> apkNotice = ok to text }) }
            } else null,
            notice = apkNotice,
            onNoticeShown = { apkNotice = null },
            onInstall = { d -> openInstall(apk, d) },
            onEnterArchive = {
                apkDetailEntry = null
                activeState.enterEntry(apk)
            },
            onSignApk = {
                apkDetailEntry = null
                signingApkEntry = apk
            },
            onOpenDexEditor = {
                apkDetailEntry = null
                openApkDex(apk)
            },
            onViewManifest = {
                apkDetailEntry = null
                // Архив открывается и манифест раскодируется в фоне: большой APK не морозит окно
                bgBusyMessage = "Чтение манифеста…"
                ioScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val archiveRoot = vfsManager.openArchive(apk.path)
                            val mfPath = archiveRoot.resolve("AndroidManifest.xml")
                            if (!Files.exists(mfPath)) null
                            else {
                                val bytes = vfsManager.readBytesLimited(mfPath, 16 shl 20)
                                Triple(axmlDecoder.decode(bytes), AxmlDecoder.isBinaryXml(bytes), mfPath)
                            }
                        }
                    }
                    bgBusyMessage = null
                    r.onSuccess { found ->
                        if (found == null) {
                            infoMessage = "Файл AndroidManifest.xml не найден в архиве APK!"
                        } else {
                            viewingManifestXml = found.first
                            viewingManifestPath = if (found.second) null else found.third
                        }
                    }.onFailure { e -> infoMessage = "Ошибка чтения манифеста: ${e.message ?: e.javaClass.simpleName}" }
                }
            },
            onDismiss = { apkDetailEntry = null }
        )
    }

    // Установка через ADB
    installTarget?.let { t ->
        val onDisk = t.entry.path.fileSystem === java.nio.file.FileSystems.getDefault()
        AdbInstallDialog(
            apkPath = t.entry.path,
            apkName = t.entry.name,
            packageName = t.packageName,
            versionLabel = t.versionLabel,
            signatureValid = t.signatureValid,
            onRequestSign = if (onDisk && t.signatureValid == false) {
                {
                    signApksInPlace(
                        listOf(t.entry.path.toAbsolutePath().normalize().toString()),
                        report = { ok, text -> adbNotice = ok to text }
                    ) { ok ->
                        if (ok && installTarget?.entry == t.entry) installTarget = t.copy(signatureValid = true)
                    }
                }
            } else null,
            notice = adbNotice,
            onNoticeShown = { adbNotice = null },
            onDismiss = { installTarget = null }
        )
    }

    // APK изменён — подписать?
    signPrompt?.let { apks ->
        ModifiedApkDialog(
            apks = apks,
            onSign = {
                signPrompt = null
                signApksInPlace(apks)
            },
            onSignAs = if (apks.size == 1) {
                {
                    signPrompt = null
                    modifiedApks.removeAll(apks.toSet())
                    signingApkEntry = apkEntryFor(apks[0])
                }
            } else null,
            onSkip = {
                signPrompt = null
                modifiedApks.removeAll(apks.toSet())
            },
            // Esc — не отказ от подписи: при выходе программа ещё напомнит
            onLater = { signPrompt = null }
        )
    }

    // Выход с несохранённым
    if (exitPrompt) {
        val unsigned = modifiedApks.filter { java.io.File(it).isFile }
        ExitConfirmDialog(
            unsavedEditors = UnsavedRegistry.entries.values.toList(),
            unsignedApks = unsigned,
            onSignAndExit = if (unsigned.isNotEmpty()) {
                {
                    exitPrompt = false
                    signApksInPlace(unsigned) { ok -> if (ok) onExit() }
                }
            } else null,
            onExit = {
                exitPrompt = false
                onExit()
            },
            onCancel = { exitPrompt = false },
            runningOperation = bgBusyMessage,
            onExitWhenDone = if (exitPromptForOperation && UnsavedRegistry.entries.isEmpty() && unsigned.isEmpty()) {
                {
                    exitPrompt = false
                    exitWhenIdle = true
                    toast.info("Выход после завершения операции")
                }
            } else null
        )
    }

    // Modal: Dex Selection Dialog (multi-dex selector)
    dexSelectionCandidate?.let { (archiveName, dexList) ->
        DexSelectionDialog(
            archiveOrFolderName = archiveName,
            dexEntries = dexList,
            onConfirm = { selectedDexEntries ->
                dexSelectionCandidate = null
                val first = selectedDexEntries.firstOrNull() ?: return@DexSelectionDialog
                // Чтение и разбор всех DEX (десятки МБ) — в фоне, с полоской у файла
                startOpening(first) { token ->
                    try {
                        val total = selectedDexEntries.sumOf { it.size }.coerceAtLeast(1)
                        var before = 0L
                        val sources = selectedDexEntries.map { dexEntry ->
                            val bytes = vfsManager.readBytes(dexEntry.path) { done, _ -> token.report(before + done, total) }
                            before += bytes.size
                            DexSource(name = dexEntry.name, bytes = bytes, path = dexEntry.path)
                        }
                        token.progress = null
                        val engine = DexEditorEngine(sources)
                        show {
                            activeDexName = if (sources.size == 1) sources.first().name else "$archiveName (${sources.size} DEX)"
                            dexEngine = engine
                        }
                    } catch (e: Exception) {
                        show { infoMessage = "Ошибка загрузки DEX: ${e.message ?: e.javaClass.simpleName}" }
                    }
                }
            },
            onDismiss = { dexSelectionCandidate = null }
        )
    }

    // Modal: Dex Editor Plus (Smali & Java)
    dexEngine?.let { engine ->
        DexEditorPlusDialog(
            dexName = activeDexName,
            engine = engine,
            onDismiss = {
                dexEngine = null
                activeDexName = ""
            },
            onSaveDex = { modified ->
                // Вызывается из фонового потока; внутри APK все DEX пишутся одной перезаписью архива
                vfsManager.batch {
                    modified.forEach { src ->
                        val target = src.path ?: throw IllegalStateException("Неизвестен путь для ${src.name}")
                        vfsManager.writeBytes(target, src.bytes)
                    }
                }
                ioScope.launch(Dispatchers.Main) {
                    activeState.refresh()
                    inactiveState.refresh()
                }
            }
        )
    }

    // Modal: ELF Editor (.so native libraries)
    elfDoc?.let { doc ->
        ElfEditorDialog(
            doc = doc,
            fileName = elfEntry?.name ?: "lib.so",
            onDismiss = {
                elfDoc = null
                elfEntry = null
            },
            onSave = { updated ->
                // Вызывается в фоне: запись в APK переписывает архив
                val target = elfEntry
                    ?: throw IllegalStateException("Нет целевого файла")
                vfsManager.writeBytes(target.path, updated)
                ioScope.launch(Dispatchers.Main) { refreshPanes() }
            }
        )
    }

    // Modal: ARSC Editor (resources.arsc)
    arscDoc?.let { doc ->
        ArscEditorDialog(
            doc = doc,
            fileName = arscEntry?.name ?: "resources.arsc",
            onDismiss = {
                arscDoc = null
                arscEntry = null
            },
            onSave = { updated ->
                // Вызывается в фоне: запись в APK переписывает архив
                val target = arscEntry
                    ?: throw IllegalStateException("Нет целевого файла")
                vfsManager.writeBytes(target.path, updated)
                ioScope.launch(Dispatchers.Main) { refreshPanes() }
            }
        )
    }

    // Modal: Sign APK Dialog
    bundleEntry?.let { bundle ->
        BundleActionDialog(
            entry = bundle,
            onChoose = { action ->
                bundleEntry = null
                when (action) {
                    BundleAction.INSTALL -> openInstall(bundle)
                    BundleAction.OPEN -> activeState.enterEntry(bundle)
                    BundleAction.SIGN -> signingApkEntry = bundle
                    BundleAction.CONVERT -> bundleConvert = bundle
                }
            },
            onDismiss = { bundleEntry = null }
        )
    }

    bundleConvert?.let { bundle ->
        BundleConvertDialog(
            entry = bundle,
            onReveal = { path -> revealFile(activeState, path) },
            onFinished = { refreshPanes() },
            onDismiss = { bundleConvert = null }
        )
    }

    signingApkEntry?.let { apk ->
        SignApkDialog(
            apkEntry = apk,
            targetDirectory = (
                if (vfsManager.isInsideArchive(inactiveState.currentPath)) apk.path.parent
                else inactiveState.currentPath
            ).toString(),
            onDismiss = { signingApkEntry = null },
            onSignCompleted = { result ->
                signingApkEntry = null
                val signedPath = result.output.toPath().toAbsolutePath().normalize().toString()
                modifiedApks.remove(signedPath)
                toast.success(result.message)
                apkDetailRefresh++
                inactiveState.refresh()
                activeState.refresh()
            }
        )
    }

    // Modal: Manifest Decompiler & Overview Dialog
    viewingManifestXml?.let { xml ->
        // Путь есть только у текстового XML: бинарный AXML (манифест из APK) показываем
        // только для чтения — текст поверх него сломал бы установку APK
        val savePath = viewingManifestPath
        ManifestViewerDialog(
            xmlContent = xml,
            onDismiss = {
                viewingManifestXml = null
                viewingManifestPath = null
            },
            onSave = savePath?.let { path ->
                { updatedXml: String ->
                    // Вызывается в фоне
                    val format = viewingManifestFormat ?: org.wexclient.app.editor.TextFileFormat()
                    vfsManager.writeBytes(path, org.wexclient.app.editor.TextCodec.encode(updatedXml, format))
                    ioScope.launch(Dispatchers.Main) { refreshPanes() }
                }
            }
        )
    }

    // Modal: File Viewer / Editor
    viewingEntry?.let { entry ->
        FileViewerDialog(
            entry = entry,
            content = viewerContent,
            decoded = viewerDecoded,
            originalBytes = viewerBytes,
            initialTarget = viewerTarget,
            onDismiss = {
                viewingEntry = null
                viewerBytes = null
                viewerDecoded = null
                viewerTarget = null
                // Текст закрытого файла (до ~10 МБ) в памяти не держим
                viewerContent = ""
            },
            onOpenFile = { target ->
                val linked = entryFor(target)
                if (linked.isDirectory) activeState.navigateTo(target) else openEntryContent(linked)
            },
            onReveal = {
                val dir = entry.path.parent
                if (dir != null) {
                    activeState.navigateTo(dir)
                    activeState.revealArrived(listOf(entry.name))
                }
            },
            onSave = { bytes ->
                // Вызывается в фоне. Исключение пробрасываем: редактор покажет ошибку и не снимет «изменено»
                vfsManager.writeBytes(entry.path, bytes)
                ioScope.launch(Dispatchers.Main) {
                    viewerBytes = bytes
                    refreshPanes()
                }
            }
        )
    }

    databaseEntry?.let { entry ->
        DatabaseViewerDialog(entry = entry, vfsManager = vfsManager, onDismiss = { databaseEntry = null })
    }

    classFileEntry?.let { entry ->
        ClassFileViewerDialog(entry = entry, vfsManager = vfsManager, onDismiss = { classFileEntry = null })
    }

    appImageEntry?.let { entry ->
        AppImageDialog(
            entry = entry,
            onDismiss = { appImageEntry = null },
            onRunInTerminal = { dir, command -> (if (isLeftActive) leftTerminal else rightTerminal).runCommand(dir, command) },
            onMessage = { toast.success(it) },
            onChanged = { changed ->
                activeState.refresh()
                inactiveState.refresh()
                val parent = changed?.parent
                if (changed != null && parent != null) revealArrived(parent, listOf(changed.fileName.toString()))
            }
        )
    }

    specialFile?.let { (entry, kind) ->
        val dir = entry.path.parent ?: activeState.currentPath
        val hint = if (kind.startsWith("именованный")) {
            "\n\nЧтение канала ждёт, пока в него кто-нибудь напишет, поэтому WEX его не открывает. " +
                "Отправить строку можно из консоли: echo 'команда' > '${entry.name}'"
        } else ""
        ConfirmDialog(
            title = entry.name,
            message = "Это $kind — содержимое не читается как файл.$hint",
            confirmText = "Консоль в этой папке",
            cancelText = "Закрыть",
            onConfirm = {
                specialFile = null
                (if (isLeftActive) leftTerminal else rightTerminal).show(dir)
            },
            onDismiss = { specialFile = null }
        )
    }

    // Во вкладке работает программа: закрытие вкладки её остановит
    pendingTabClose?.let { (tabs, at) ->
        val name = tabs.tabs.getOrNull(at)?.let { tabs.title(it) } ?: "вкладка"
        ConfirmDialog(
            title = "Закрыть «$name»?",
            message = "В консоли этой вкладки работает программа. Вкладка закроется вместе с консолью, и программа остановится.",
            confirmText = "Закрыть",
            cancelText = "Оставить",
            isDestructive = true,
            focusCancel = true,
            onConfirm = {
                tabs.closeNow(at)
                pendingTabClose = null
            },
            onDismiss = { pendingTabClose = null }
        )
    }

    // Modal: Image Viewer Dialog
    viewingImageEntry?.let { (imageEntry, siblingImages) ->
        ImageViewerDialog(
            entry = imageEntry,
            siblingImages = siblingImages,
            vfsManager = vfsManager,
            onDismiss = { viewingImageEntry = null }
        )
    }

    // Modal: Video Player Dialog
    viewingVideoEntry?.let { (videoEntry, siblingVideos) ->
        VideoPlayerDialog(
            entry = videoEntry,
            siblingVideos = siblingVideos,
            onDismiss = { viewingVideoEntry = null }
        )
    }

    // Modal: Extract Archive Dialog
    extractDialogTarget?.let { (archiveEntry, curPath, otherPath) ->
        ExtractArchiveDialog(
            entry = archiveEntry,
            currentPath = curPath,
            otherPath = otherPath,
            onExtract = { targetDir ->
                extractDialogTarget = null
                extractInto(archiveEntry, targetDir)
            },
            onDismiss = { extractDialogTarget = null }
        )
    }

    // Modal: File Properties Dialog
    propertiesEntry?.let { entry ->
        FilePropertiesDialog(
            entry = entry,
            vfsManager = vfsManager,
            onDismiss = { propertiesEntry = null },
            onChecksum = {
                propertiesEntry = null
                checksumEntry = entry
            },
            onChanged = { refreshPanes() }
        )
    }

    // Modal: Rename Dialog
    renameEntry?.let { entry ->
        InputDialog(
            title = "Переименовать",
            initialValue = entry.name,
            label = "Новое имя",
            confirmText = "Сохранить",
            // Та же проверка, что при создании; смена одного регистра — не «уже существует»
            validate = { name ->
                newEntryNameProblem(name) { n ->
                    n != entry.name && !n.equals(entry.name, ignoreCase = true) &&
                        runCatching { Files.exists(entry.path.resolveSibling(n), java.nio.file.LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
                }
            },
            onConfirm = { newName ->
                renameEntry = null
                if (newName == entry.name) return@InputDialog
                // В архиве переименование переписывает весь архив — не в потоке окна
                bgBusyMessage = "Переименование…"
                ioScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { vfsManager.renameEntry(entry.path, newName) } }
                    bgBusyMessage = null
                    refreshPanes()
                    result.onSuccess { renamed ->
                        undoStack.push(FileOp.Renamed(entry.path, renamed))
                        renamed.parent?.let { revealArrived(it, listOf(arrivedName(renamed))) }
                    }.onFailure { e -> infoMessage = "Ошибка переименования: ${e.message ?: e.javaClass.simpleName}" }
                }
            },
            onDismiss = { renameEntry = null }
        )
    }

    // Modal: Hex Editor & Inspector
    hexEditorEntry?.let { entry ->
        HexEditorDialog(
            entry = entry,
            vfsManager = vfsManager,
            onDismiss = { hexEditorEntry = null },
            onSaved = {
                activeState.refresh()
                inactiveState.refresh()
            }
        )
    }

    // Modal: File Checksums & Hashes (MD5, SHA1, SHA256, SHA512, CRC32)
    checksumEntry?.let { entry ->
        FileChecksumDialog(
            entry = entry,
            vfsManager = vfsManager,
            onDismiss = { checksumEntry = null }
        )
    }

    // Modal: Batch Renaming
    batchRenameEntries?.let { entries ->
        BatchRenameDialog(
            entries = entries,
            vfsManager = vfsManager,
            onDismiss = { batchRenameEntries = null },
            onApplied = { renamed ->
                if (renamed.isNotEmpty()) {
                    undoStack.push(FileOp.BatchRenamed(renamed))
                    toast.show("Переименовано: ${renamed.size}", ToastKind.SUCCESS, undoAction())
                }
                refreshPanes()
            }
        )
    }

    // Modal: File Diff / Comparison
    diffCandidates?.let { (fileA, fileB) ->
        FileDiffDialog(
            fileA = fileA,
            fileB = fileB,
            vfsManager = vfsManager,
            onDismiss = { diffCandidates = null }
        )
    }

    apkCompare?.let { (first, second) ->
        ApkCompareDialog(first = first, second = second, onDismiss = { apkCompare = null })
    }

    pdfEntry?.let { entry ->
        PdfViewerDialog(entry = entry, onDismiss = { pdfEntry = null })
    }

    if (sshConnect) {
        SshConnectDialog(
            onConnected = { home ->
                sshConnect = false
                activeState.navigateTo(home)
            },
            onDismiss = { sshConnect = false }
        )
    }

    if (adbConnect) {
        AdbDevicesDialog(
            onOpenFiles = { home ->
                adbConnect = false
                activeState.navigateTo(home)
            },
            onOpenApps = { device ->
                adbConnect = false
                adbApps = device.serial to device.displayName
            },
            onDismiss = { adbConnect = false }
        )
    }

    adbApps?.let { (serial, name) ->
        AdbAppsDialog(
            serial = serial,
            deviceName = name,
            saveDir = apkSaveDir(),
            onSaved = { saved ->
                activeState.refresh()
                inactiveState.refresh()
                saved.parent?.let { revealArrived(it, listOf(saved.fileName.toString())) }
            },
            onOpenDataFolder = { dir -> openOnPhone(serial, name, dir) },
            onDismiss = { adbApps = null }
        )
    }

    pdfRange?.let { (entry, pages) ->
        InputDialog(
            title = "Извлечь страницы",
            initialValue = "1-" + pages.coerceAtLeast(1),
            label = "Страницы, например 3-7 (всего $pages)",
            confirmText = "Извлечь",
            validate = { text ->
                val parts = text.split('-', '–').map { it.trim().toIntOrNull() }
                val from = parts.getOrNull(0)
                val to = parts.getOrNull(1) ?: from
                when {
                    from == null || to == null -> "Нужны номера страниц: 3-7 или просто 5"
                    from < 1 || to > pages || from > to -> "Страницы от 1 до $pages, первая не больше последней"
                    else -> null
                }
            },
            onConfirm = { text ->
                val parts = text.split('-', '–').map { it.trim().toInt() }
                val from = parts[0]
                val to = parts.getOrElse(1) { from }
                pdfRange = null
                pdfTask(activeState, "Извлечение страниц $from–$to…") {
                    org.wexclient.app.doc.PdfTools.extract(entry.path, from, to)
                }
            },
            onDismiss = { pdfRange = null }
        )
    }

    if (folderCompare) {
        FolderCompareDialog(
            left = leftPaneState.currentPath,
            right = rightPaneState.currentPath,
            onCopy = { sources, target, replace, done ->
                runCatching { java.nio.file.Files.createDirectories(target) }
                transferTo(
                    sources, target, move = false,
                    policy = if (replace) ConflictPolicy.REPLACE else ConflictPolicy.SKIP,
                    onDone = done
                )
            },
            onDismiss = { folderCompare = false }
        )
    }

    jadxInput?.let { input ->
        if (jadxShown) {
            JadxExportDialog(
                input = input.path,
                inputSize = input.size,
                job = jadxJob,
                onStart = { options, outDir -> startJadx(input.path, options, outDir) },
                onOpenFolder = { dir -> activeState.navigateTo(dir) },
                onHide = { jadxShown = false },
                onDismiss = {
                    jadxShown = false
                    if (jadxJob?.running != true) {
                        jadxJob = null
                        jadxInput = null
                    }
                }
            )
        }
    }

    duplicateRoots?.let { roots ->
        DuplicatesDialog(
            roots = roots,
            onTrash = { paths, done -> trashPaths(paths, done) },
            onReveal = { path -> revealFile(activeState, path) },
            onDismiss = { duplicateRoots = null }
        )
    }

    diskUsageRoot?.let { root ->
        DiskUsageDialog(
            root = root,
            onTrash = { paths, done -> trashPaths(paths, done) },
            onReveal = { path -> revealFile(activeState, path) },
            onDismiss = { diskUsageRoot = null }
        )
    }

    sheetEntry?.let { entry ->
        SpreadsheetViewerDialog(entry = entry, onDismiss = { sheetEntry = null })
    }

    docEntry?.let { entry ->
        DocumentViewerDialog(entry = entry, onDismiss = { docEntry = null })
    }

    fun describe(entries: List<VfsEntry>) = if (entries.size == 1) "«${entries[0].name}»" else Plural.items(entries.size)

    // Modal: Copy Confirmation
    copyConfirm?.let { targets ->
        val dest = inactiveState.currentPath.toString()
        ConfirmDialog(
            title = "Копирование файлов",
            message = "Скопировать ${describe(targets)} в папку:\n$dest ?",
            confirmText = "Копировать",
            onConfirm = {
                copyConfirm = null
                copyToPane(targets, inactiveState.currentPath)
            },
            onDismiss = { copyConfirm = null }
        )
    }

    // Modal: Move Confirmation
    moveConfirm?.let { targets ->
        val dest = inactiveState.currentPath.toString()
        ConfirmDialog(
            title = "Перемещение файлов",
            message = "Переместить ${describe(targets)} в папку:\n$dest ?",
            confirmText = "Переместить",
            onConfirm = {
                moveConfirm = null
                moveToPane(targets, inactiveState.currentPath)
            },
            onDismiss = { moveConfirm = null }
        )
    }

    // Modal: Compression to Archives (.zip, .tar.gz, .7z, .apk)
    compressingEntries?.let { toCompress ->
        CompressDialog(
            sources = toCompress,
            currentDirectory = activeState.currentPath,
            oppositeDirectory = inactiveState.currentPath,
            onDismiss = { compressingEntries = null },
            onCompress = { targets, destFile, format ->
                compressingEntries = null
                bgBusyMessage = "Создание архива ${destFile.fileName}…"
                ioScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            // Сначала новый архив во временный файл; прежний — в корзину только после успеха
                            val replaced = ArrayList<TrashItem>()
                            val archived = ArrayList<org.wexclient.app.vfs.ArchiveBackup>()
                            vfsManager.compressToNewFile(targets, destFile, format.name.lowercase()) { existing ->
                                // В архиве корзины нет — прежний файл копией на диск, чтобы Ctrl+Z его вернул
                                if (Trash.supports(existing)) replaced.add(Trash.trash(existing))
                                else archived.add(org.wexclient.app.vfs.UndoBackups.save(vfsManager, existing))
                            }
                            replaced to archived
                        }
                    }
                    bgBusyMessage = null
                    refreshPanes()
                    result.onSuccess { (replaced, archived) ->
                        undoStack.push(FileOp.Copied(listOf(destFile), replaced, archived))
                        destFile.parent?.let { revealArrived(it, listOf(arrivedName(destFile))) }
                        toast.show("Архив создан: ${destFile.fileName}", ToastKind.SUCCESS, undoAction())
                    }.onFailure { e -> infoMessage = "Архив не создан: ${e.message ?: e.javaClass.simpleName}" }
                }
            }
        )
    }

    // Modal: Delete Confirmation
    pendingDelete?.let { (pane, toDelete) ->
        val what = toDelete.singleOrNull()?.let { e ->
            if (e.isDirectory) "папку «${e.name}» со всем содержимым" else "«${e.name}»"
        } ?: Plural.items(toDelete.size)
        ConfirmDialog(
            title = "Удаление навсегда",
            message = (pendingDeleteNote?.let { "$it\n\n" } ?: "") + "Удалить $what безвозвратно? Корзина не используется.",
            confirmText = "Удалить",
            isDestructive = true,
            focusCancel = pendingDeleteCautious,
            onConfirm = {
                pendingDelete = null
                pendingDeleteNote = null
                runFileOp("Удаление: ${Plural.items(toDelete.size)}…") {
                    vfsManager.batch {
                        toDelete.forEach { entry ->
                            vfsManager.deleteEntry(entry.path)
                        }
                    }
                    null
                }
                pane.selectedEntries.removeAll(toDelete.toHashSet())
            },
            onDismiss = {
                pendingDelete = null
                pendingDeleteNote = null
            }
        )
    }

    pendingExtract?.let { (prepared, archiveName) ->
        ConflictDialog(
            conflicts = prepared.conflicts,
            total = prepared.fileCount,
            move = false,
            noun = "распаковываемых файлов",
            targetOnDisk = prepared.dest.fileSystem === java.nio.file.FileSystems.getDefault(),
            onDecision = { policy ->
                pendingExtract = null
                finishExtract(prepared, policy, archiveName)
            },
            onCancel = {
                pendingExtract = null
                ioScope.launch {
                    withContext(Dispatchers.IO) { org.wexclient.app.vfs.SafeExtract.discard(prepared) }
                    refreshPanes()
                }
            }
        )
    }

    pendingTransfer?.let { t ->
        ConflictDialog(
            conflicts = t.conflicts,
            total = t.sources.size,
            move = t.move,
            targetOnDisk = t.targetDir.fileSystem === java.nio.file.FileSystems.getDefault(),
            onDecision = { policy ->
                pendingTransfer = null
                transferTo(t.sources, t.targetDir, t.move, policy, t.clearCutAfter)
            },
            onCancel = { pendingTransfer = null }
        )
    }

    pasteTextRequest?.let { (dir, text) ->
        InputDialog(
            title = "Вставить текст как файл",
            initialValue = "Текст ${clipboardStamp()}.txt",
            label = "Имя файла",
            confirmText = "Создать",
            onConfirm = { name ->
                pasteTextRequest = null
                ioScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            val target = FileTransfers.freeName(dir, name.trim())
                            vfsManager.writeBytes(target, text.toByteArray(Charsets.UTF_8))
                            target
                        }
                    }
                    refreshPanes()
                    saved.onSuccess { target ->
                        undoStack.push(FileOp.Created(target))
                        revealArrived(dir, listOf(arrivedName(target)))
                        toast.show("Текст сохранён: ${target.fileName}", ToastKind.SUCCESS, undoAction())
                    }.onFailure { e -> toast.error("Не удалось сохранить: ${e.message ?: e.javaClass.simpleName}") }
                }
            },
            onDismiss = { pasteTextRequest = null }
        )
    }

    if (showShortcuts) {
        ShortcutsDialog(onDismiss = { showShortcuts = false })
    }

    if (showContentSearch) {
        ContentSearchDialog(
            state = contentSearch,
            dir = activeState.currentPath,
            onOpen = { m -> openAtLine(m) },
            onReveal = { m ->
                showContentSearch = false
                revealFile(activeState, m.path)
            },
            onDismiss = { showContentSearch = false }
        )
    }

    if (showTrash) {
        TrashDialog(
            onDismiss = { showTrash = false },
            onRestored = { restored ->
                refreshPanes()
                restored.groupBy { it.parent }.forEach { (dir, paths) ->
                    if (dir != null) revealArrived(dir, paths.map { it.fileName.toString() })
                }
            }
        )
    }

    // Modal: создание папки или файла
    createRequest?.let { (kind, pane) ->
        val dirPath = pane.currentPath
        CreateEntryDialog(
            initialKind = kind,
            directory = dirPath,
            exists = { name -> runCatching { vfsManager.exists(dirPath.resolve(name)) }.getOrDefault(false) },
            onCreate = { k, name, openAfter ->
                createRequest = null
                // В архиве создание переписывает весь архив — не в потоке окна
                ioScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (k == NewEntryKind.FOLDER) vfsManager.createDirectory(dirPath, name)
                            else vfsManager.createFile(dirPath, name)
                        }
                    }
                    result.onSuccess { created ->
                        undoStack.push(FileOp.Created(created))
                        refreshPanes()
                        pane.whenLoaded {
                            val idx = pane.entries.indexOfFirst { it.name == name }
                            if (idx >= 0) {
                                pane.focusedIndex = idx
                                pane.recentlyReturnedChildName = name
                                if (k == NewEntryKind.FILE && openAfter) openEntryContent(pane.entries[idx])
                            }
                        }
                    }.onFailure { e ->
                        val what = if (k == NewEntryKind.FOLDER) "папку" else "файл"
                        infoMessage = "Не удалось создать $what «$name»: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            },
            onDismiss = { createRequest = null }
        )
    }

    // Modal: импорт ключа подписи
    keyImport?.let { req ->
        fun finish() {
            keyImport = null
            req.tempDir?.let { dir -> org.wexclient.app.util.FileTree.delete(dir) }
        }
        androidx.compose.runtime.key(req) {
            ImportKeyDialog(
                initialFile = req.file,
                displayPath = req.displayPath,
                onImported = { key ->
                    finish()
                    toast.success("Ключ «${key.title}» импортирован — он в меню «Подписать»")
                },
                onDismiss = { finish() }
            )
        }
    }

    // Modal: Info Message Dialog
    infoMessage?.let { msg ->
        ConfirmDialog(
            title = "Информация",
            message = msg,
            confirmText = "Понятно",
            cancelText = "",
            onConfirm = { infoMessage = null },
            onDismiss = { infoMessage = null }
        )
    }

    // Modal: Settings Dialog (theme & contrast)
    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }

    // Modal: Unknown/Binary File Dialog
    unknownFileEntry?.let { uf ->
        ModalDialog(onDismissRequest = { unknownFileEntry = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                tonalElevation = 6.dp,
                modifier = Modifier.width(340.dp).dialogGlass(RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Неизвестный формат",
                        style = MaterialTheme.typography.titleMedium,
                        color = NoctaliaPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        uf.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Extract Archive (if extractable)
                    if (uf.isExtractableArchive) {
                        OutlinedButton(
                            onClick = {
                                val entry = uf
                                unknownFileEntry = null
                                handleArchiveExtract(entry, ExtractAction.PROMPT, activeState, inactiveState)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, NoctaliaTertiary.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoctaliaTertiary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Извлечь архив...", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Open as Archive
                    OutlinedButton(
                        onClick = {
                            val entry = uf
                            unknownFileEntry = null
                            try {
                                val archiveRoot = vfsManager.openArchive(entry.path)
                                activeState.navigateTo(archiveRoot)
                            } catch (e: Exception) {
                                infoMessage = "Не удалось открыть как архив: ${e.message ?: e.javaClass.simpleName}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoctaliaPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть как архив", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Open as Text
                    OutlinedButton(
                        onClick = {
                            val entry = uf
                            unknownFileEntry = null
                            startOpening(entry) { token ->
                                try {
                                    val size = vfsManager.size(entry.path)
                                    if (size > MAX_TEXT_VIEW_BYTES) {
                                        show { infoMessage = "Файл слишком большой для текстового просмотра (${size / 1024 / 1024} МБ)" }
                                    } else {
                                        prepareText(entry, token)
                                    }
                                } catch (e: Exception) {
                                    show { infoMessage = "Ошибка чтения: ${e.message ?: e.javaClass.simpleName}" }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NoctaliaSecondary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoctaliaSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть как текст", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Open in Hex Editor
                    OutlinedButton(
                        onClick = {
                            val entry = uf
                            unknownFileEntry = null
                            hexEditorEntry = entry
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoctaliaPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть в Hex-редакторе", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Open with System App
                    OutlinedButton(
                        onClick = {
                            val entry = uf
                            unknownFileEntry = null
                            try {
                                ProcessBuilder(org.wexclient.app.util.systemOpenCommand(entry.path.toAbsolutePath().toString())).withUserEnv().start()
                            } catch (e: Exception) {
                                infoMessage = "Не удалось открыть: ${e.message ?: e.javaClass.simpleName}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NoctaliaTertiary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoctaliaTertiary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть с помощью...", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { unknownFileEntry = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private const val MAX_TEXT_VIEW_BYTES = 10L * 1024 * 1024
private const val MAX_BINARY_EDIT_BYTES = 256L * 1024 * 1024

/** Файл ключа для окна импорта; [tempDir] — временная копия из архива, удаляется после окна. */
private data class KeyImportRequest(val file: java.io.File, val displayPath: String?, val tempDir: java.nio.file.Path?)

@Composable
private fun HistoryRowItem(
    item: NavHistoryItem,
    onNavigate: () -> Unit
) {
    Surface(
        onClick = onNavigate,
        shape = RoundedCornerShape(8.dp),
        color = if (item.isBeforeClose) {
            NoctaliaPrimary.copy(alpha = 0.09f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = if (item.isBeforeClose) {
            BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.4f))
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (item.fileName != null) {
                    NoctaliaPrimary.copy(alpha = 0.15f)
                } else {
                    NoctaliaSecondary.copy(alpha = 0.15f)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (item.fileName != null) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (item.fileName != null) NoctaliaPrimary else NoctaliaSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Main Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (item.isBeforeClose) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NoctaliaPrimary.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "Перед закрытием",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = NoctaliaPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = item.paneSide,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = item.pathStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Relative Time & Action indicator
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = NavigationHistoryManager.formatRelativeTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Перейти",
                    tint = NoctaliaPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Копирование/перенос, ждущий решения о конфликтах имён. */
private class PendingTransfer(
    val sources: List<java.nio.file.Path>,
    val targetDir: java.nio.file.Path,
    val move: Boolean,
    val conflicts: List<String>,
    val clearCutAfter: Boolean
)

/**
 * Панель становится активной, когда над ней действительно двигают мышь. Compose присылает
 * «вход» и «движение» и без движения — например, когда закрывается окно поверх неподвижного
 * курсора: активной становилась панель под мышью, и Delete после Tab удалял не там.
 */
private fun Modifier.activateOnMouseMove(activate: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var last: androidx.compose.ui.geometry.Offset? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pos = event.changes.firstOrNull()?.position
            when (event.type) {
                PointerEventType.Move -> {
                    val prev = last
                    last = pos
                    if (pos != null && prev != null && (pos - prev).getDistanceSquared() >= 1f) activate()
                }
                // Вход без движения ничего не значит: следом при настоящем движении придёт Move
                PointerEventType.Enter -> last = pos
                PointerEventType.Exit -> last = null
                else -> Unit
            }
        }
    }
}

/** Ctrl+1…Ctrl+9 — переход к вкладке по номеру. */
private val DIGIT_KEYS = listOf(
    Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine
)

/** Где стоят панели — для восстановления после закрытия. */
private data class PanePlaces(
    val leftPath: java.nio.file.Path,
    val leftFile: String?,
    val rightPath: java.nio.file.Path,
    val rightFile: String?
)

/**
 * Две панели и разделитель между ними: ширины делятся по [ratio] при раскладке (как веса Row),
 * поэтому смена доли — только новая раскладка, без перекомпоновки. Порядок детей: левая, разделитель, правая.
 */
@Composable
private fun SplitPanes(ratio: () -> Float, modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val divider = measurables[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = w, minHeight = h, maxHeight = h))
        val free = (w - divider.width).coerceAtLeast(0)
        val leftW = (free * ratio().coerceIn(0f, 1f)).toInt()
        val left = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(leftW, h))
        val right = measurables[2].measure(androidx.compose.ui.unit.Constraints.fixed(free - leftW, h))
        layout(w, h) {
            left.place(0, 0)
            divider.place(leftW, 0)
            right.place(leftW + divider.width, 0)
        }
    }
}
