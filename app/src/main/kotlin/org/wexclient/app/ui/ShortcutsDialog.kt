package org.wexclient.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.util.Keymap

private class ShortcutGroup(val title: String, val items: List<Pair<String, String>>)

/**
 * Клавиши, которые не выносятся в список биндов: их роль задаёт сам список файлов
 * (стрелки, набор имени) или окно просмотра.
 */
private val extraGroups = listOf(
    ShortcutGroup(
        "Перемещение по списку",
        listOf(
            "↑ ↓" to "Курсор по списку",
            "Home End · PgUp PgDn" to "Первый и последний файл, страница",
            "Enter" to "Открыть папку, архив или файл",
            "Ctrl+1…9" to "Вкладка панели по номеру",
            "Буквы" to "Перейти к файлу по первым буквам имени"
        )
    ),
    ShortcutGroup(
        "Выделение",
        listOf(
            "Пробел" to "Выделить и перейти ниже",
            "Ctrl+щелчок · Shift+щелчок" to "Добавить к выделению, выделить диапазон",
            "Esc" to "Снять выделение"
        )
    ),
    ShortcutGroup(
        "Окна просмотра",
        listOf(
            "Esc" to "Закрыть просмотр, сравнение, окно APK; прервать открытие tar",
            "← → · Home End" to "Картинки: предыдущая и следующая, первая и последняя",
            "+ − · 0" to "Картинки: масштаб, вписать в окно",
            "Alt+↑ · Alt+↓ · F7" to "Сравнение: предыдущее и следующее различие"
        )
    )
)

/** Сочетания берём из списка биндов: перенастроенные показываются такими, какими их сделали. */
private fun shortcutGroupsNow(): List<ShortcutGroup> {
    val fromKeymap = Keymap.groups.map { group ->
        ShortcutGroup(
            group,
            Keymap.binds.filter { it.group == group }.map { Keymap.label(it.id) to it.title }
        )
    }
    return fromKeymap + extraGroups
}

/** F1: все сочетания клавиш файлового менеджера. */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(focus) }
    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.dialogGlass(RoundedCornerShape(16.dp))
                .fillMaxWidth(0.94f)
                .widthIn(max = 1240.dp)
                .heightIn(max = 760.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Escape || e.key == Key.F1 || e.key == Key.Enter)) {
                        onDismiss()
                        true
                    } else false
                }
                .focusRequester(focus)
                .focusable()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Горячие клавиши", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Три колонки примерно одной высоты: раскладываем группы по числу строк
                    val groups = remember(Keymap.revision) { shortcutGroupsNow() }
                    val columns = remember(groups) {
                        val buckets = List(3) { mutableListOf<ShortcutGroup>() }
                        val sizes = IntArray(3)
                        for (g in groups) {
                            val i = sizes.indices.minByOrNull { sizes[it] } ?: 0
                            buckets[i].add(g)
                            sizes[i] += g.items.size + 2
                        }
                        buckets
                    }
                    for (column in columns) {
                        Column(modifier = Modifier.weight(1f)) {
                            for (group in column) {
                                Text(
                                    group.title.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NoctaliaPrimary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                )
                                for ((keys, what) in group.items) ShortcutRow(keys, what)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(keys: String, what: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            keys,
            fontSize = 11.5.sp,
            fontFamily = AppFonts.JetBrainsMono,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .width(212.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(what, fontSize = 12.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
