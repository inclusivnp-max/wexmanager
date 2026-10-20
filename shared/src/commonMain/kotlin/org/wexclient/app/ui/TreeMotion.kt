package org.wexclient.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Общие куски деревьев редакторов (arsc+, DEX+): стрелка, папка и движение строк
 * при раскрытии и сворачивании. Длительности — из [Motion], выключатель анимаций тоже его.
 */

/** Стрелка узла: поворачивается на 90°, а не меняет картинку скачком. */
@Composable
fun TreeExpandArrow(expanded: Boolean, tint: Color, size: Dp = 16.dp) {
    val angle by animateFloatAsState(
        if (expanded) 90f else 0f,
        tween(Motion.ENTER, easing = FastOutSlowInEasing),
        label = "tree-arrow"
    )
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size).graphicsLayer { rotationZ = angle }
    )
}

/** Папка узла: закрытая и открытая сменяют друг друга наплывом. */
@Composable
fun TreeFolderIcon(expanded: Boolean, tint: Color, size: Dp) {
    Crossfade(expanded, animationSpec = tween(Motion.ENTER), label = "tree-folder") { open ->
        Icon(
            imageVector = if (open) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * Строки плоского дерева в LazyColumn: раскрытые дети проявляются, свёрнутые тают,
 * а строки ниже съезжают на новое место, а не прыгают. Без анимаций — обычная строка.
 */
fun LazyItemScope.treeRowMotion(): Modifier =
    if (!ViewPreferences.animations) Modifier else Modifier.animateItem(
        fadeInSpec = tween(Motion.ENTER, easing = FastOutSlowInEasing),
        placementSpec = tween(Motion.ENTER, easing = FastOutSlowInEasing),
        fadeOutSpec = tween(Motion.EXIT)
    )
