package org.wexclient.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import org.wexclient.app.audio.AudioPlayerManager
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry

@Composable
fun AudioPlayerContent(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    var showPlaylist by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .widthIn(min = 460.dp, max = 640.dp)
            .fillMaxWidth(0.58f)
            .heightIn(max = if (showPlaylist) 720.dp else 560.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 10.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                    // Top Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Аудиоплеер WEX",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Студийное воспроизведение 44.1 kHz",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showPlaylist = !showPlaylist },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = if (showPlaylist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Список треков")
                            }
                            IconButton(
                                onClick = onDismiss,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Скрыть")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Center Animated Vinyl Record Disc
                    val isPlayingNow = AudioPlayerManager.isPlaying && !AudioPlayerManager.isPaused
                    // Бесконечная анимация — только пока играет: на паузе окно не перерисовывается 60 раз в секунду
                    val discRotation: State<Float> =
                        if (isPlayingNow) discSpin() else remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .shadow(16.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF2A2A2A),
                                        Color(0xFF141414),
                                        Color(0xFF080808)
                                    )
                                )
                            )
                            // Поворот — на этапе отрисовки, без перекомпоновки
                            .graphicsLayer { rotationZ = discRotation.value },
                        contentAlignment = Alignment.Center
                    ) {
                        // Concentric grooves
                        for (r in listOf(150.dp, 130.dp, 110.dp, 90.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                                modifier = Modifier.size(r)
                            ) {}
                        }

                        // Center label
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Spindle hole
                        Surface(
                            shape = CircleShape,
                            color = Color.Black,
                            modifier = Modifier.size(10.dp)
                        ) {}
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Track Title & Info
                    val currentTrack = AudioPlayerManager.currentTrack
                    Text(
                        text = currentTrack?.name ?: "Нет трека",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Text(
                        text = "${currentTrack?.formattedSize ?: ""} • ${currentTrack?.extension?.uppercase() ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Полоски и позиция меняются 5–60 раз в секунду: их чтение — в своих функциях,
                    // иначе каждый кадр пересобиралось бы всё окно
                    SpectrumBars(isPlayingNow)

                    Spacer(modifier = Modifier.height(8.dp))

                    PlaybackProgress()

                    Spacer(modifier = Modifier.height(8.dp))

                    // Playback Controls (Shuffle, Prev, Play/Pause, Next, Repeat)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle
                        IconButton(
                            onClick = { AudioPlayerManager.toggleShuffle() },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (AudioPlayerManager.isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Перемешать", modifier = Modifier.size(20.dp))
                        }

                        // Prev
                        FilledIconButton(
                            onClick = { AudioPlayerManager.previous() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий", modifier = Modifier.size(22.dp))
                        }

                        // Big Play / Pause FAB
                        FilledIconButton(
                            onClick = { AudioPlayerManager.playPause() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.size(56.dp).shadow(6.dp, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlayingNow) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlayingNow) "Пауза" else "Воспроизвести",
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        // Next
                        FilledIconButton(
                            onClick = { AudioPlayerManager.next() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Следующий", modifier = Modifier.size(22.dp))
                        }

                        // Repeat
                        IconButton(
                            onClick = { AudioPlayerManager.cycleRepeatMode() },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = when (AudioPlayerManager.repeatMode) {
                                    org.wexclient.app.audio.RepeatMode.OFF -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        ) {
                            Icon(
                                imageVector = when (AudioPlayerManager.repeatMode) {
                                    org.wexclient.app.audio.RepeatMode.ONE -> Icons.Default.RepeatOne
                                    else -> Icons.Default.Repeat
                                },
                                contentDescription = "Повтор",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Volume Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { AudioPlayerManager.toggleMute() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    AudioPlayerManager.isMuted || AudioPlayerManager.volume == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                                    AudioPlayerManager.volume < 0.4f -> Icons.AutoMirrored.Filled.VolumeDown
                                    else -> Icons.AutoMirrored.Filled.VolumeUp
                                },
                                contentDescription = "Громкость",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Slider(
                            value = if (AudioPlayerManager.isMuted) 0f else AudioPlayerManager.volume,
                            onValueChange = { AudioPlayerManager.setVolumeLevel(it) },
                            modifier = Modifier.weight(1f).height(20.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        )

                        Text(
                            text = "${((if (AudioPlayerManager.isMuted) 0f else AudioPlayerManager.volume) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    // Collapsible Playlist Drawer
                    AnimatedVisibility(
                        visible = showPlaylist,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .padding(top = 10.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Очередь (${AudioPlayerManager.playlist.size} треков)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(AudioPlayerManager.playlist) { idx, track ->
                                    val isCurrent = track.path == AudioPlayerManager.currentTrack?.path
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { AudioPlayerManager.play(track, AudioPlayerManager.playlist) }
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isCurrent) {
                                                Icon(
                                                    Icons.Default.GraphicEq,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            } else {
                                                Text(
                                                    text = "${idx + 1}.",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.width(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = track.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = track.formattedSize,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

@Composable
fun AudioPlayerDialog(
    onDismiss: () -> Unit
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        onKeyEvent = latestKeyHandler { closeOnEscape(it, onDismiss) },
        state = rememberDialogState(size = DpSize(620.dp, 560.dp)),
        title = "Аудиоплеер — ${AudioPlayerManager.currentTrack?.name ?: "WEX Music"}"
    ) {
        NoctaliaTheme {
            AudioPlayerContent(
                modifier = Modifier.fillMaxSize(),
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun discSpin(): State<Float> =
    rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

/** Спектр: высоты анимируются, но читаются только при отрисовке — без перекомпоновки на каждом кадре. */
@Composable
private fun SpectrumBars(isPlaying: Boolean) {
    val bands = AudioPlayerManager.visualizerBands
    val heights = List(bands.size) { index ->
        animateFloatAsState(
            targetValue = if (isPlaying) (bands[index] * 44f).coerceIn(4f, 44f) else 4f,
            animationSpec = tween(durationMillis = 60, easing = FastOutSlowInEasing)
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 24.dp)
    ) {
        if (heights.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val radius = 3.dp.toPx()
        val barWidth = ((size.width - gap * (heights.size - 1)) / heights.size).coerceAtLeast(1f)
        clipRect {
            heights.forEachIndexed { index, height ->
                val color = if (index % 2 == 0) primary else tertiary
                val top = size.height - height.value.dp.toPx().coerceAtMost(size.height)
                val left = index * (barWidth + gap)
                // Скруглены только верхние углы: нижние уходят за край и срезаются
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.4f)), startY = top, endY = size.height),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, size.height - top + radius),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }
    }
}

/** Ползунок и время: позиция обновляется 5 раз в секунду и перекомпонует только этот блок. */
@Composable
private fun PlaybackProgress() {
    val pos = AudioPlayerManager.currentPositionMs
    val dur = AudioPlayerManager.totalDurationMs
    val progressRatio = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    // Пока тянут — только ползунок; ffmpeg перезапускается один раз, при отпускании
    var dragRatio by remember { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Slider(
            value = dragRatio ?: progressRatio,
            onValueChange = { ratio -> dragRatio = ratio },
            onValueChangeFinished = {
                dragRatio?.let { AudioPlayerManager.seekTo((it * dur).toLong()) }
                dragRatio = null
            },
            modifier = Modifier.fillMaxWidth().height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = AudioPlayerManager.formatTime(pos),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = AudioPlayerManager.formatTime(dur),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
