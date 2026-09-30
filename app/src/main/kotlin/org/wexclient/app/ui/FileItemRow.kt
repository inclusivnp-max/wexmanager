package org.wexclient.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import org.wexclient.app.util.Keymap
import org.wexclient.app.vfs.MediaThumbnailLoader
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.wexclient.app.vfs.GitMark
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.vfs.ApkIconLoader
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

enum class ExtractAction {
    PROMPT,
    TO_SUBFOLDER,
    TO_OTHER_PANE,
    HERE
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemRow(
    entry: VfsEntry,
    isSelected: Boolean,
    isFocused: Boolean = false,
    isRecentlyReturned: Boolean = false,
    subtitle: String? = null,
    /** Файл открывается — рядом с именем полоска прогресса. */
    opening: OpeningFile? = null,
    /** Вырезан (Ctrl+X) — полупрозрачный, пока не вставят. */
    isCut: Boolean = false,
    /** Пункты меню от экрана (копировать, вырезать, ссылка…) — строятся при открытии меню. */
    actions: () -> List<RowAction> = { emptyList() },
    /** Соседняя панель справа от этой: по этому стрелки в меню смотрят в нужную сторону. */
    otherPaneOnRight: Boolean = true,
    /** Состояние в git: буква у файла, точка у папки, игнорируемые приглушены. */
    gitMark: GitMark? = null,
    /** Перетаскивание: строку можно потянуть, на папку — бросить. */
    dragModifier: Modifier = Modifier,
    /** Над папкой держат перетаскиваемые файлы. */
    isDropTarget: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Посчитанный размер папки; null — показываем «<DIR>». */
    folderSize: Long? = null,
    /** Правый щелчок: панель переносит сюда фокус, чтобы меню и выделение совпадали. */
    onRightClick: () -> Unit = {},
    onCopyOther: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onMoveOther: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onCompress: (() -> Unit)? = null,
    onExtract: ((entry: VfsEntry, action: ExtractAction) -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
    onAnalyzeApk: (() -> Unit)? = null,
    onInspectDex: (() -> Unit)? = null,
    onSignApk: (() -> Unit)? = null,
    onInstallApk: (() -> Unit)? = null,
    /** Набор split-APK → один APK. */
    onConvertBundle: (() -> Unit)? = null,
    onOpenHex: (() -> Unit)? = null,
    onChecksum: (() -> Unit)? = null,
    onDiffWithOther: (() -> Unit)? = null,
    /** Режим выделения: у строк показывается отметка, клик выделяет. */
    selectionMode: Boolean = false,
    /** Скрипт (.sh, .bat…) — кнопка «Запуск» рядом с именем. */
    onRun: (() -> Unit)? = null,
    onNewFolder: (() -> Unit)? = null,
    onNewFile: (() -> Unit)? = null,
    /** «Просмотр» / «Правка»: содержимое файла, даже если щелчок делает другое (ключи — импорт). */
    onViewContent: (() -> Unit)? = null,
    /** Файл ключа подписи — импорт в список ключей. */
    onImportKey: (() -> Unit)? = null,
    /** Результат поиска — открыть папку, где он лежит, и выделить его. */
    onRevealInFolder: (() -> Unit)? = null
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }

    val returnGlowAlpha by animateFloatAsState(
        targetValue = if (isRecentlyReturned) 0.35f else 0.0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    )

    val targetBackground = when {
        isDropTarget -> NoctaliaSecondary.copy(alpha = 0.28f)
        isSelected -> NoctaliaPrimary.copy(alpha = 0.22f)
        isFocused -> NoctaliaPrimary.copy(alpha = 0.12f)
        returnGlowAlpha > 0.005f -> NoctaliaPrimary.copy(alpha = returnGlowAlpha)
        else -> Color.Transparent
    }

    // Цвет подсветки переходит плавно: при ходьбе стрелками рамка больше не скачет
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = targetBackground,
        animationSpec = tween(Motion.TINT, easing = FastOutSlowInEasing)
    )

    val border = when {
        isDropTarget -> BorderStroke(2.dp, NoctaliaSecondary)
        isSelected -> BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.7f))
        isFocused -> BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.4f))
        returnGlowAlpha > 0.005f -> BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = returnGlowAlpha * 2f))
        else -> null
    }

    val isHidden = entry.name.startsWith(".")
    val isHiddenFile = isHidden && !entry.isDirectory
    val isHiddenFolder = isHidden && entry.isDirectory

    // Текст обычных папок — обычным цветом, акцентом красятся только иконки.
    // Скрытые папки — сероватым (текст + иконка), скрытые файлы — приглушены.
    val fileNameColor = when {
        isSelected -> NoctaliaPrimary
        isHiddenFolder -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        isHiddenFile -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f) // Dimmed gray for dotfiles
        else -> MaterialTheme.colorScheme.onSurface
    }

    val folderTintOverride =
        if (isHiddenFolder && !isSelected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else null
    val rowContentAlpha = when {
        isCut -> 0.45f
        gitMark == GitMark.IGNORED && !isSelected -> 0.55f
        isHiddenFile && !isSelected -> 0.75f
        else -> 1.0f
    }
    val gitColor = gitMark?.takeIf { it != GitMark.IGNORED }?.let(::gitMarkColor)

    // Dynamic APK launcher icon loading
    var apkIcon by remember(entry.path, entry.lastModified) { mutableStateOf(ApkIconLoader.getCachedIcon(entry.path, entry.lastModified)) }

    val showThumbs = ViewPreferences.showThumbnails
    val isBundle = entry.itemType == VfsItemType.ARCHIVE_APKS
    if (showThumbs && (entry.itemType == VfsItemType.ARCHIVE_APK || isBundle) && apkIcon == null) {
        LaunchedEffect(entry.path, entry.lastModified) {
            apkIcon = ApkIconLoader.loadIcon(entry.path, entry.lastModified)
        }
    }

    // Иконка приложения из AppImage
    var appImageIcon by remember(entry.path, entry.lastModified) {
        mutableStateOf(
            if (entry.itemType == VfsItemType.APPIMAGE) org.wexclient.app.appimage.AppImages.cachedIcon(entry.path, entry.lastModified) else null
        )
    }
    if (showThumbs && entry.itemType == VfsItemType.APPIMAGE && appImageIcon == null) {
        LaunchedEffect(entry.path, entry.lastModified) {
            appImageIcon = org.wexclient.app.appimage.AppImages.loadIcon(entry.path, entry.lastModified)
        }
    }

    // Dynamic Image / Video thumbnail loading
    // Ключ — время изменения из списка: файл перезаписали — миниатюра новая
    val wantsThumb = showThumbs && (entry.isImage || entry.isVideo)
    var mediaThumbnail by remember(entry.path, entry.lastModified) {
        mutableStateOf(if (wantsThumb) MediaThumbnailLoader.getCached(entry.path, entry.lastModified) else null)
    }

    if (wantsThumb && mediaThumbnail == null) {
        LaunchedEffect(entry.path, entry.lastModified) {
            try {
                mediaThumbnail = MediaThumbnailLoader.loadThumbnail(entry.path, entry.isImage, entry.isVideo, entry.lastModified)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // без миниатюры — значок типа
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            .pointerInput(entry.path) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val pos = event.changes.first().position
                            menuOffset = DpOffset(pos.x.toDp(), pos.y.toDp())
                            onRightClick()
                            showContextMenu = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        // Фон и рамка строки — без Surface: он ещё и обрезал содержимое по скруглённому контуру (свой слой
        // со сглаженной маской на каждую строку, и так каждый кадр живого фона). Содержимое от углов
        // отстоит на отступ — обрезать нечего
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .then(if (border != null) Modifier.border(border, RowShape) else Modifier)
                .background(bgColor, RowShape),
            propagateMinConstraints = true
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Слой с альфой — только у приглушённых строк (вырезанные, скрытые, игнорируемые git);
                    // обычной строке он не нужен. ModulateAlpha не годится: на skiko текст и значки рисуются
                    // мимо Compose Paint и остались бы непрозрачными
                    .then(if (rowContentAlpha < 1f) Modifier.alpha(rowContentAlpha) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Real APK Icon, Media Thumbnail or FileIconBadge
                when {
                    showThumbs && (entry.itemType == VfsItemType.ARCHIVE_APK || isBundle) && apkIcon != null -> {
                        Box(modifier = Modifier.size(24.dp)) {
                            Image(
                                bitmap = apkIcon!!,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(5.dp))
                            )
                            // У набора — значок «несколько частей» в углу иконки приложения
                            if (isBundle) {
                                BundleCornerBadge(
                                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp),
                                    size = 12.dp
                                )
                            }
                        }
                    }
                    showThumbs && entry.itemType == VfsItemType.APPIMAGE && appImageIcon != null -> {
                        Image(
                            bitmap = appImageIcon!!,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(5.dp))
                        )
                    }
                    showThumbs && mediaThumbnail != null -> {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = mediaThumbnail!!,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                contentScale = ContentScale.Crop
                            )
                            if (entry.isVideo) {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), shape = CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(9.dp)
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        FileIconBadge(
                            type = entry.itemType,
                            tintOverride = folderTintOverride
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // File Name & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (entry.isDirectory || isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            // Изменённые файлы — оттенком статуса, как в редакторах кода
                            color = if (gitColor != null && !isSelected && !entry.isDirectory) lerp(fileNameColor, gitColor, 0.6f) else fileNameColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (gitColor != null && gitMark != null) GitMarkBadge(gitMark, gitColor, folder = entry.isDirectory)
                        if (onRun != null) {
                            Spacer(modifier = Modifier.width(5.dp))
                            RunButton(entry.name, onRun, inConsole = entry.itemType != VfsItemType.APPIMAGE)
                        }
                        if (opening != null) OpeningProgress(opening)
                    }
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // File Size
                if (ViewPreferences.showSizeColumn) Text(
                    text = folderSize?.let { humanSize(it) } ?: entry.formattedSize,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = if (entry.isDirectory) NoctaliaSecondary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp),
                    maxLines = 1
                )

                // Date
                if (ViewPreferences.showDateColumn) Text(
                    text = entry.formattedDate,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(120.dp),
                    maxLines = 1
                )

                if (selectionMode) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Выделено" else "Не выделено",
                        tint = if (isSelected) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Custom Noctalia "Grail" Context Menu at Cursor
        CursorMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = menuOffset,
            // 320: «Переместить в соседнюю панель» вместе с «F6» не обрезается
            modifier = Modifier
                .width(320.dp)
        ) {
            // 1. Open / Enter (у ключей подписи — импорт)
            if (onImportKey != null) {
                GrailMenuItem(
                    icon = Icons.Default.Key,
                    label = "Импортировать ключ…",
                    shortcut = "Enter",
                    iconTint = NoctaliaWarn
                ) {
                    showContextMenu = false
                    onImportKey()
                }
            } else {
                GrailMenuItem(
                    icon = if (entry.isDirectory || entry.isArchive) Icons.Default.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew,
                    label = if (entry.isDirectory || entry.isArchive) "Открыть / Войти" else "Открыть",
                    shortcut = "Enter",
                    iconTint = NoctaliaPrimary
                ) {
                    showContextMenu = false
                    onClick()
                }
            }

            if (onRevealInFolder != null) {
                GrailMenuItem(
                    icon = Icons.Default.FolderOpen,
                    label = "Перейти к расположению",
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    onRevealInFolder()
                }
            }

            if (onRun != null) {
                GrailMenuItem(
                    icon = Icons.Rounded.PlayArrow,
                    label = "Запустить в консоли",
                    iconTint = RunGreen
                ) {
                    showContextMenu = false
                    onRun()
                }
            }

            // View / Edit (if file and not dir)
            if (!entry.isDirectory && !entry.isArchive) {
                GrailMenuItem(
                    icon = Icons.Default.Visibility,
                    label = "Просмотр",
                    shortcut = Keymap.label("pane.view"),
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    (onViewContent ?: onClick)()
                }
                GrailMenuItem(
                    icon = Icons.Default.Edit,
                    label = "Правка",
                    shortcut = Keymap.label("pane.edit"),
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    (onViewContent ?: onClick)()
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))

            // 2. Copy / Move / Rename / Delete
            GrailMenuItem(
                icon = Icons.Default.ContentCopy,
                label = "Копировать в соседнюю панель",
                shortcut = Keymap.label("pane.copyTo"),
                iconTint = NoctaliaPrimary,
                pointsRight = otherPaneOnRight
            ) {
                showContextMenu = false
                onCopyOther()
            }

            if (onMoveOther != null) {
                GrailMenuItem(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    label = "Переместить в соседнюю панель",
                    shortcut = Keymap.label("pane.moveTo"),
                    iconTint = NoctaliaPrimary,
                    pointsRight = otherPaneOnRight
                ) {
                    showContextMenu = false
                    onMoveOther()
                }
            }

            if (onRename != null) {
                GrailMenuItem(
                    icon = Icons.Default.DriveFileRenameOutline,
                    label = "Переименовать",
                    shortcut = Keymap.label("pane.rename"),
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    onRename()
                }
            }

            GrailMenuItem(
                icon = Icons.Default.Delete,
                label = "В корзину",
                shortcut = Keymap.label("pane.trash"),
                isDestructive = true
            ) {
                showContextMenu = false
                onDelete()
            }

            for (action in actions()) {
                GrailMenuItem(
                    icon = action.icon,
                    label = action.label,
                    shortcut = action.shortcut,
                    iconTint = action.tint ?: NoctaliaPrimary,
                    isDestructive = action.destructive,
                    pointsRight = !action.directional || otherPaneOnRight
                ) {
                    showContextMenu = false
                    action.onClick()
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))

            // 3. Compress to Archive
            if (onCompress != null) {
                GrailMenuItem(
                    icon = Icons.Default.Archive,
                    label = "Сжать в архив…",
                    shortcut = Keymap.label("pane.compress"),
                    iconTint = NoctaliaTertiary
                ) {
                    showContextMenu = false
                    onCompress()
                }
            }

            // 3.5 Extract Archive (ПКМ по архивам)
            if (entry.isExtractableArchive && onExtract != null) {
                GrailMenuItem(
                    icon = Icons.Default.FolderZip,
                    label = "Извлечь архив...",
                    shortcut = Keymap.label("pane.extract"),
                    iconTint = NoctaliaTertiary
                ) {
                    showContextMenu = false
                    onExtract(entry, ExtractAction.PROMPT)
                }

                GrailMenuItem(
                    icon = Icons.Default.FolderOpen,
                    label = "Извлечь в «${entry.archiveBaseName}/»",
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    onExtract(entry, ExtractAction.TO_SUBFOLDER)
                }

                if (onMoveOther != null) {
                    GrailMenuItem(
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        label = "Извлечь в соседнюю панель",
                        iconTint = NoctaliaPrimary,
                        pointsRight = otherPaneOnRight
                    ) {
                        showContextMenu = false
                        onExtract(entry, ExtractAction.TO_OTHER_PANE)
                    }
                }

                GrailMenuItem(
                    icon = Icons.Default.Archive,
                    label = "Извлечь в текущую папку",
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    showContextMenu = false
                    onExtract(entry, ExtractAction.HERE)
                }
            }

            // 4. Android Tools (if APK or DEX)
            if (isBundle) {
                // Набор split-APK: поставить целиком, подписать все части, собрать в один APK
                if (onInstallApk != null) {
                    GrailMenuItem(
                        icon = Icons.Default.InstallMobile,
                        label = "Установить через ADB",
                        iconTint = NoctaliaSecondary
                    ) {
                        showContextMenu = false
                        onInstallApk()
                    }
                }
                if (onSignApk != null) {
                    GrailMenuItem(
                        icon = Icons.Default.Security,
                        label = "Подписать все части",
                        iconTint = Color(0xFFFFD54F)
                    ) {
                        showContextMenu = false
                        onSignApk()
                    }
                }
                if (onConvertBundle != null) {
                    GrailMenuItem(
                        icon = Icons.Default.Android,
                        label = "Конвертировать в APK",
                        iconTint = BundleTint
                    ) {
                        showContextMenu = false
                        onConvertBundle()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))
            } else if (entry.itemType == VfsItemType.ARCHIVE_APK) {
                if (onAnalyzeApk != null) {
                    GrailMenuItem(
                        icon = Icons.Default.Android,
                        label = "MT Анализ APK",
                        iconTint = Color(0xFFA5D6A7)
                    ) {
                        showContextMenu = false
                        onAnalyzeApk()
                    }
                }
                if (onInstallApk != null) {
                    GrailMenuItem(
                        icon = Icons.Default.InstallMobile,
                        label = "Установить через ADB",
                        iconTint = NoctaliaSecondary
                    ) {
                        showContextMenu = false
                        onInstallApk()
                    }
                }
                if (onSignApk != null) {
                    GrailMenuItem(
                        icon = Icons.Default.Security,
                        label = "Подписать APK",
                        iconTint = Color(0xFFFFD54F)
                    ) {
                        showContextMenu = false
                        onSignApk()
                    }
                }
                if (onInspectDex != null) {
                    GrailMenuItem(
                        icon = Icons.Default.Code,
                        label = "DEX Редактор+ (classes.dex)",
                        iconTint = NoctaliaPrimary
                    ) {
                        showContextMenu = false
                        onInspectDex()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))
            } else if (entry.itemType == VfsItemType.DEX && onInspectDex != null) {
                GrailMenuItem(
                    icon = Icons.Default.Code,
                    label = "DEX Редактор+",
                    iconTint = NoctaliaPrimary
                ) {
                    showContextMenu = false
                    onInspectDex()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))
            }

            // Advanced Tools: Hex Editor, Checksums, Diff — канал или устройство так не прочитать
            val readableFile = !entry.isDirectory && entry.itemType != org.wexclient.app.vfs.VfsItemType.SPECIAL
            if (readableFile && onOpenHex != null) {
                GrailMenuItem(
                    icon = Icons.Default.Memory,
                    label = "Hex-редактор (просмотр байт)",
                    iconTint = NoctaliaTertiary
                ) {
                    showContextMenu = false
                    onOpenHex()
                }
            }

            if (readableFile && onChecksum != null) {
                GrailMenuItem(
                    icon = Icons.Default.Fingerprint,
                    label = "Хэши и контрольные суммы",
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    onChecksum()
                }
            }

            if (readableFile && onDiffWithOther != null) {
                GrailMenuItem(
                    icon = Icons.AutoMirrored.Filled.CompareArrows,
                    label = "Сравнить с файлом соседней панели",
                    iconTint = NoctaliaPrimary
                ) {
                    showContextMenu = false
                    onDiffWithOther()
                }
            }

            if (readableFile && (onOpenHex != null || onChecksum != null || onDiffWithOther != null)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))
            }

            // 5. Utilities: Copy Path, Copy Name, Terminal
            GrailMenuItem(
                icon = Icons.Default.Link,
                label = "Копировать полный путь",
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                showContextMenu = false
                val sel = StringSelection(entry.path.toAbsolutePath().toString())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }

            GrailMenuItem(
                icon = Icons.Default.ContentCopy,
                label = "Копировать имя файла",
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                showContextMenu = false
                val sel = StringSelection(entry.name)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }

            if (onOpenTerminal != null) {
                GrailMenuItem(
                    icon = Icons.Default.Terminal,
                    label = "Открыть в терминале",
                    iconTint = NoctaliaSecondary
                ) {
                    showContextMenu = false
                    onOpenTerminal()
                }
            }

            GrailMenuItem(
                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                label = if (isSelected) "Снять выделение" else "Выделить",
                shortcut = "Space",
                iconTint = NoctaliaPrimary
            ) {
                showContextMenu = false
                onLongClick()
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))

            if (onNewFolder != null || onNewFile != null) {
                onNewFolder?.let { cb ->
                    GrailMenuItem(
                        icon = Icons.Default.CreateNewFolder,
                        label = "Создать папку…",
                        shortcut = Keymap.label("pane.newFolder"),
                        iconTint = NoctaliaPrimary
                    ) {
                        showContextMenu = false
                        cb()
                    }
                }
                onNewFile?.let { cb ->
                    GrailMenuItem(
                        icon = Icons.AutoMirrored.Filled.NoteAdd,
                        label = "Создать файл…",
                        shortcut = Keymap.label("pane.newFile"),
                        iconTint = NoctaliaPrimary
                    ) {
                        showContextMenu = false
                        cb()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 1.dp))
            }

            // 6. Properties
            GrailMenuItem(
                icon = Icons.Default.Info,
                label = "Свойства объекта",
                shortcut = Keymap.label("pane.properties"),
                iconTint = NoctaliaSecondary
            ) {
                showContextMenu = false
                onProperties()
            }
        }
    }
}

/** Кнопка запуска у скриптов, как в Android Studio: только зелёный треугольник. */
/** Чтение (с процентами) или разбор (бегущая полоска) открываемого файла. */
@Composable
private fun OpeningProgress(opening: OpeningFile) {
    // Быстрое открытие — без мелькания
    var shown by remember(opening) { mutableStateOf(false) }
    LaunchedEffect(opening) {
        kotlinx.coroutines.delay(150)
        shown = true
    }
    if (!shown) return
    val progress = opening.progress
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
        val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        val barModifier = Modifier.width(64.dp).height(4.dp)
        if (progress != null) {
            val animated by animateFloatAsState(progress, animationSpec = tween(120))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { animated },
                modifier = barModifier,
                color = NoctaliaPrimary,
                trackColor = track,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "${(progress * 100).toInt()}%",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            WexLinearSpinner(
                modifier = barModifier,
                color = NoctaliaPrimary,
                trackColor = track,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

/** Пункт контекстного меню, который добавляет экран. */
class RowAction(
    val icon: ImageVector,
    val label: String,
    val shortcut: String? = null,
    val tint: Color? = null,
    val destructive: Boolean = false,
    /** Значок со стрелкой в сторону соседней панели — её разворачивают вместе с панелью. */
    val directional: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunButton(fileName: String, onRun: () -> Unit, inConsole: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp
            ) {
                Text(
                    if (inConsole) "Запустить «$fileName» в консоли" else "Запустить «$fileName»",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        delayMillis = 500
    ) {
        val color = if (hovered) RunGreenHover else RunGreen
        // Область нажатия 18dp, сам треугольник — 15.3dp (на 15% меньше прежнего);
        // сдвиг вниз на 10% высоты — вровень со строчными буквами имени
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(y = 1.8.dp)
                .size(18.dp)
                .semantics { contentDescription = if (inConsole) "Запустить в консоли" else "Запустить" }
                .hoverable(interaction)
                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onRun)
        ) {
        // Контур — один на размер (drawWithCache): новый Path на каждую отрисовку — нативный объект Skia,
        // который ждал сборщика мусора, по штуке на каждую строку со скриптом при каждой перерисовке списка
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(15.3.dp).drawWithCache {
            // Треугольник с мягкими углами: заливка + обводка того же цвета со скруглёнными стыками
            val w = size.width
            val h = size.height
            val round = w * 0.11f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.30f, h * 0.20f)
                lineTo(w * 0.80f, h * 0.50f)
                lineTo(w * 0.30f, h * 0.80f)
                close()
            }
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = round * 2,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
            onDrawBehind {
                drawPath(path, color)
                drawPath(path, color, style = stroke)
            }
        })
        }
    }
}

// Цвет кнопки Run в Android Studio
private val RunGreen = Color(0xFF59A869)
private val RunGreenHover = Color(0xFF73C27F)

@Composable
private fun GrailMenuItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = NoctaliaPrimary,
    shortcut: String? = null,
    isDestructive: Boolean = false,
    /** Значок со стрелкой: false — развернуть её влево (соседняя панель слева). */
    pointsRight: Boolean = true,
    onClick: () -> Unit
) {
    // Стрелка разворачивается плавно: при переходе между панелями это видно
    val flip by androidx.compose.animation.core.animateFloatAsState(
        if (pointsRight) 1f else -1f,
        tween(Motion.ENTER),
        label = "menu-icon-flip"
    )
    DropdownMenuItem(
        // M3 зашивает min-height 48.dp внутрь item'а — contentPadding его не убирает,
        // поэтому фиксируем высоту явно, иначе меню получается «высоким» как на скрине.
        modifier = Modifier.height(32.dp),
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isDestructive) Color(0xFFFFB4AB) else iconTint,
                modifier = Modifier.size(16.dp).graphicsLayer { scaleX = flip }
            )
        },
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                color = if (isDestructive) Color(0xFFFFB4AB) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingIcon = shortcut?.let { sc ->
            {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = sc,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    )
}

internal fun gitMarkColor(mark: GitMark): Color = when (mark) {
    GitMark.CONFLICT -> NoctaliaDanger
    GitMark.MODIFIED -> NoctaliaWarn
    GitMark.ADDED -> NoctaliaOk
    GitMark.RENAMED -> Color(0xFF60A5FA)
    GitMark.DELETED -> NoctaliaDanger
    GitMark.UNTRACKED -> Color(0xFF2DD4BF)
    GitMark.IGNORED -> Color(0xFF9CA3AF)
}

/** Буква статуса у файла; у папки — точка «внутри есть изменения». */
@Composable
private fun GitMarkBadge(mark: GitMark, color: Color, folder: Boolean) {
    Spacer(modifier = Modifier.width(6.dp))
    if (folder && mark != GitMark.UNTRACKED) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        return
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = mark.letter,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp),
            color = color
        )
    }
}

private val RowShape = RoundedCornerShape(6.dp)
