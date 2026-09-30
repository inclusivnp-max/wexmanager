package org.wexclient.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.wexclient.app.editor.EditorPreferences
import org.wexclient.app.signing.SigningKeys
import org.wexclient.app.signing.SigningPreferences
import org.wexclient.app.terminal.ShellLocator
import org.wexclient.app.terminal.TerminalPreferences
import org.wexclient.app.theme.AppContrast
import org.wexclient.app.theme.AppTheme
import org.wexclient.app.theme.BackgroundEffect
import org.wexclient.app.theme.BackgroundPreferences
import org.wexclient.app.theme.EffectDensity
import org.wexclient.app.theme.SHADER_EFFECTS
import org.wexclient.app.theme.SHADER_STYLES
import org.wexclient.app.theme.TRAIL_COLORS
import org.wexclient.app.theme.ShaderPrefs
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Restore
import kotlinx.coroutines.delay
import org.wexclient.app.util.BindDef
import org.wexclient.app.util.KeyCombo
import org.wexclient.app.util.Keymap
import org.wexclient.app.util.awtEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.widthIn
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.ThemePreferences

/**
 * Разделы окна настроек. Раньше всё жило в одной узкой колонке с длинной прокруткой:
 * половина настроек программы вообще не была сюда выведена, а найти нужную было нечем.
 */
private enum class SettingsSection(val title: String, val icon: ImageVector, val hint: String) {
    APPEARANCE("Оформление", Icons.Default.Palette, "Тема, контраст, движение"),
    BACKGROUND("Фон", Icons.Default.Wallpaper, "Живой фон, картинка, затемнение, эффекты"),
    LIST("Список файлов", Icons.Default.Folder, "Колонки, сортировка, вкладки"),
    EDITOR("Редактор", Icons.Default.Code, "Шрифт, подсказки, проверки"),
    TERMINAL("Консоль", Icons.Default.Terminal, "Оболочка, шрифт, высота"),
    BINDS("Бинды", Icons.Default.Keyboard, "Сочетания клавиш: своё для каждого действия"),
    SIGNING("Подпись APK", Icons.Default.Key, "Ключ, схемы, автоподпись"),
    COMMANDS("Свои команды", Icons.Default.PlayArrow, "Пункты меню файла со своими командами"),
    ABOUT("О программе", Icons.Default.Info, "Где что лежит")
}

/**
 * Отбор по строке поиска. Каждая настройка сама сверяет своё название и пояснение,
 * поэтому новый пункт попадает в поиск без отдельной регистрации.
 */
internal class SettingsFilter(raw: String) {
    val query: String = raw.trim()
    val active: Boolean get() = query.length >= 2
    /** Сколько настроек показано — считается за проход отрисовки, нужен только для «ничего не найдено». */
    var shown: Int = 0
        private set

    fun hit(vararg words: String): Boolean {
        if (!active) {
            shown++
            return true
        }
        val ok = words.any { it.contains(query, ignoreCase = true) }
        if (ok) shown++
        return ok
    }
}

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit
) {
    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var section by remember { mutableStateOf(SettingsSection.APPEARANCE) }
        var search by remember { mutableStateOf("") }
        val filter = SettingsFilter(search)

        // На маленьком экране окно ужимается, а не вылезает за край
        val window = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current
        val maxW = with(density) { window.width.toDp() } - 60.dp
        val maxH = with(density) { window.height.toDp() } - 60.dp

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier
                .width(minOf(900.dp, maxW))
                .height(minOf(620.dp, maxH))
                .dialogGlass(RoundedCornerShape(18.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ---- боковой список разделов ----
                Column(
                    modifier = Modifier
                        .width(238.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                        .padding(start = 14.dp, end = 10.dp, top = 16.dp, bottom = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = NoctaliaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Настройки",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    SearchField(value = search, onChange = { search = it })
                    Spacer(modifier = Modifier.height(10.dp))

                    SettingsSection.entries.forEach { item ->
                        SectionRow(
                            item = item,
                            selected = !filter.active && item == section,
                            dimmed = filter.active,
                            onClick = {
                                search = ""
                                section = item
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Всё применяется сразу",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )

                // ---- содержимое раздела ----
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (filter.active) "Поиск: ${filter.query}" else section.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (filter.active) "по всем разделам" else section.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        DialogClose(onDismiss)
                    }

                    AnimatedContent(
                        targetState = if (filter.active) null else section,
                        transitionSpec = {
                            (fadeIn(tween(Motion.ENTER)) + slideInVertically(tween(Motion.ENTER)) { it / 16 })
                                .togetherWith(fadeOut(tween(Motion.EXIT)))
                        },
                        label = "settings-section"
                    ) { shownSection ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, end = 16.dp, bottom = 18.dp)
                        ) {
                            if (shownSection == null) {
                                // Поиск идёт сразу по всем разделам — так настройку находят, не зная, где она лежит
                                SettingsSection.entries.forEach { s ->
                                    SectionBody(s, filter)
                                }
                                if (filter.shown == 0) {
                                    Text(
                                        text = "Ничего не нашлось. Попробуйте другое слово — например «шрифт», «вкладки» или «фон».",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 20.dp)
                                    )
                                }
                            } else {
                                SectionBody(shownSection, filter)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Имя текущего раздела: в выдаче поиска строки идут вперемешку, и рядом с каждой видно, откуда она. */
private val LocalSectionTitle = androidx.compose.runtime.compositionLocalOf { "" }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SectionBody(section: SettingsSection, f: SettingsFilter) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSectionTitle provides section.title) {
        SectionContent(section, f)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SectionContent(section: SettingsSection, f: SettingsFilter) {
    when (section) {
        SettingsSection.APPEARANCE -> AppearanceSettings(f)
        SettingsSection.BACKGROUND -> BackgroundSettings(f)
        SettingsSection.LIST -> ListSettings(f)
        SettingsSection.EDITOR -> EditorSettings(f)
        SettingsSection.TERMINAL -> TerminalSettings(f)
        SettingsSection.BINDS -> BindSettings(f)
        SettingsSection.SIGNING -> SigningSettings(f)
        SettingsSection.COMMANDS -> CommandsSettings(f)
        SettingsSection.ABOUT -> AboutSettings(f)
    }
}

// ---------------------------------------------------------------- разделы

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettings(f: SettingsFilter) {
    GroupTitle("Тема", f)
    val themeMatch = AppTheme.entries.filter { f.hit(it.title, it.subtitle, "тема цвет оформление") }
    if (themeMatch.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            themeMatch.forEach { theme -> ThemeCard(theme) }
        }
    }

    if (f.hit("Контраст", "чёткость текста")) {
        GroupTitle("Контраст", f)
        ChipsRow(
            options = AppContrast.entries.map { it.title },
            selectedIndex = AppContrast.entries.indexOf(ThemePreferences.appContrast),
            onSelect = { ThemePreferences.setContrast(AppContrast.entries[it]) }
        )
        Hint(f, "Повышенный — больше разницы между текстом и фоном")
        Spacer(modifier = Modifier.height(10.dp))
    }

    GroupTitle("Движение", f)
    SettingsSwitch(
        f,
        "Плавные переходы",
        "Появление окон, меню и вкладок. Выключить — всё открывается мгновенно",
        ViewPreferences.animations
    ) { ViewPreferences.updateAnimations(it) }

    if (f.hit("Сбросить оформление", "тема по умолчанию")) {
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = {
            ThemePreferences.setTheme(AppTheme.NOCTALIA)
            ThemePreferences.setContrast(AppContrast.STANDARD)
        }) {
            Text("Вернуть тему Noctalia", fontSize = 12.sp, color = NoctaliaPrimary)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSettings(f: SettingsFilter) {
    LiveBackgroundSettings(f)

    GroupTitle("Картинка", f)
    if (BackgroundPreferences.systemWallpaperAvailable) {
        SettingsSwitch(
            f,
            "Обои рабочего стола",
            "Брать текущие обои Noctalia и менять их вместе с системой",
            BackgroundPreferences.useSystemWallpaper
        ) { BackgroundPreferences.updateUseSystemWallpaper(it) }
    }

    if (f.hit("Своя картинка", "фон изображение обои")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Своя картинка", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = BackgroundPreferences.imagePath.ifBlank { "не выбрана" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { pickImage()?.let { BackgroundPreferences.updateImagePath(it) } }) {
                Text("Выбрать…", fontSize = 12.sp, color = NoctaliaPrimary)
            }
            if (BackgroundPreferences.imagePath.isNotBlank()) {
                TextButton(onClick = { BackgroundPreferences.updateImagePath("") }) {
                    Text("Убрать", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    GroupTitle("Как выглядит", f)
    SettingsSlider(f, "Затемнение", BackgroundPreferences.dim, 0..95, "%", "темнее — лучше читается текст") {
        BackgroundPreferences.updateDim(it)
    }
    SettingsSlider(f, "Размытие", BackgroundPreferences.blur, 0..60, " точек", "размытая картинка не отвлекает от списка") {
        BackgroundPreferences.updateBlur(it)
    }
    SettingsSlider(f, "Плотность панелей", BackgroundPreferences.panelOpacity, 40..100, "%", "меньше — сильнее видно картинку сквозь панели") {
        BackgroundPreferences.updatePanelOpacity(it)
    }

    if (f.hit("Эффекты", "снег пузыри звёзды дождь листья светлячки")) {
        GroupTitle("Эффекты", f)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BackgroundEffect.entries.forEach { effect ->
                ChoiceChip(effect.title, BackgroundPreferences.effect == effect) {
                    BackgroundPreferences.updateEffect(effect)
                }
            }
        }
        if (BackgroundPreferences.effectEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Сколько", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            ChipsRow(
                options = EffectDensity.entries.map { it.title },
                selectedIndex = EffectDensity.entries.indexOf(BackgroundPreferences.density),
                onSelect = { BackgroundPreferences.updateDensity(EffectDensity.entries[it]) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (f.hit("Сбросить фон", "по умолчанию")) {
        TextButton(onClick = {
            BackgroundPreferences.updateImagePath("")
            BackgroundPreferences.updateDim(45)
            BackgroundPreferences.updateBlur(12)
            BackgroundPreferences.updatePanelOpacity(88)
            BackgroundPreferences.updateEffect(BackgroundEffect.NONE)
            BackgroundPreferences.updateShader { ShaderPrefs() }
        }) {
            Text("Вернуть обычный фон", fontSize = 12.sp, color = NoctaliaPrimary)
        }
    }
}

/** Живой фон (как в WexRE): переливающийся градиент с элементами поверх, рисуется вместо картинки. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LiveBackgroundSettings(f: SettingsFilter) {
    val sh = BackgroundPreferences.shader
    fun set(change: (ShaderPrefs) -> ShaderPrefs) = BackgroundPreferences.updateShader(change)

    GroupTitle("Живой фон", f)
    SettingsSwitch(
        f,
        "Анимированный фон",
        "Живой градиент с эффектами и откликом на мышь — вместо картинки",
        sh.enabled
    ) { v -> set { it.copy(enabled = v) } }
    if (!sh.enabled) return

    if (f.hit("Предпросмотр", "живой фон анимированный")) {
        ShaderPreview(sh)
        Text(
            "Сквозь панели фон виден настолько, насколько позволяет «Плотность панелей» ниже",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
    if (f.hit("Стиль", "тема лайм океан неон закат бирюза палитра")) {
        Text("Стиль", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SHADER_STYLES.forEachIndexed { i, title ->
                ChoiceChip(title, sh.style == i) { set { it.copy(style = i) } }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (f.hit("Эффект живого фона", "боке частицы пузыри фигуры")) {
        Text("Эффект", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SHADER_EFFECTS.forEachIndexed { i, title ->
                ChoiceChip(title, sh.effect == i) { set { it.copy(effect = i) } }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    SettingsSlider(f, "Насыщенность", sh.alpha, 0..80, "%", "насколько заметны цветные пятна") { v -> set { it.copy(alpha = v) } }
    SettingsSlider(f, "Яркость", sh.intensity, 0..100, "%") { v -> set { it.copy(intensity = v) } }
    SettingsSlider(f, "Сдвиг оттенка", sh.hue, -180..180, "°") { v -> set { it.copy(hue = v) } }
    SettingsSlider(f, "Скорость", sh.speed, 10..100, "") { v -> set { it.copy(speed = v) } }
    SettingsSlider(f, "Размытие живого фона", sh.blur, 0..40, " точек", "мягче переходы между пятнами") { v -> set { it.copy(blur = v) } }
    if (sh.effect != 0) {
        SettingsSlider(f, "Плотность эффекта", sh.effectDensity, 0..100, "%") { v -> set { it.copy(effectDensity = v) } }
        SettingsSlider(f, "Размер элементов", sh.effectSize, 0..100, "%") { v -> set { it.copy(effectSize = v) } }
        SettingsSlider(f, "Свечение", sh.effectGlow, 0..100, "%") { v -> set { it.copy(effectGlow = v) } }
    }
    SettingsSwitch(f, "Переливание цвета", "Оттенок плавно проходит по кругу", sh.colorAnimation) { v ->
        set { it.copy(colorAnimation = v) }
    }
    SettingsSwitch(f, "Реакция на мышь", "Свечение со следом под курсором, при нажатии — ярче", sh.touch) { v ->
        set { it.copy(touch = v) }
    }
    if (sh.touch) {
        SettingsSwitch(
            f,
            "След за курсором",
            "Свечение со следом идёт за курсором при обычном движении; выключено — только пока зажата кнопка",
            sh.hoverTrail
        ) { v -> set { it.copy(hoverTrail = v) } }
        SettingsSlider(f, "Сила отклика", sh.touchIntensity, 0..100, "%", "насколько сильно курсор расталкивает пятна и фигуры") { v ->
            set { it.copy(touchIntensity = v) }
        }
        TrailSettings(f, sh, ::set)
    }
}

/** След за курсором: цвет, длина, толщина, яркость и свечение под самим курсором. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TrailSettings(f: SettingsFilter, sh: ShaderPrefs, set: ((ShaderPrefs) -> ShaderPrefs) -> Unit) {
    GroupTitle("След", f)
    if (f.hit("Цвет следа", "тема белый голубой фиолетовый розовый оранжевый зелёный радуга")) {
        Text("Цвет следа", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TRAIL_COLORS.forEachIndexed { i, title ->
                ChoiceChip(title, sh.trailColor == i) { set { it.copy(trailColor = i) } }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    SettingsSlider(f, "Длина следа", sh.trailLength, 150..2000, " мс", "сколько живёт хвост за курсором") { v ->
        set { it.copy(trailLength = v) }
    }
    SettingsSlider(f, "Толщина следа", sh.trailWidth, 20..250, "%") { v -> set { it.copy(trailWidth = v) } }
    SettingsSlider(f, "Яркость следа", sh.trailBrightness, 5..100, "%") { v -> set { it.copy(trailBrightness = v) } }
    SettingsSlider(f, "Размер свечения", sh.glowSize, 20..250, "%", "пятно света под самим курсором") { v ->
        set { it.copy(glowSize = v) }
    }
    if (f.hit("Вернуть след по умолчанию", "след сбросить")) {
        TextButton(onClick = {
            val d = ShaderPrefs()
            set {
                it.copy(
                    trailColor = d.trailColor,
                    trailLength = d.trailLength,
                    trailWidth = d.trailWidth,
                    trailBrightness = d.trailBrightness,
                    glowSize = d.glowSize
                )
            }
        }) {
            Text("Вернуть след по умолчанию", fontSize = 12.sp, color = NoctaliaPrimary)
        }
    }
}

/** Живой фон в маленьком окошке: настройки видно сразу, даже пока окно программы не в фокусе. */
@Composable
private fun ShaderPreview(prefs: ShaderPrefs) {
    val renderer = remember { ShaderBackgroundRenderer() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 1f))
            .observeBackgroundTouches(renderer, prefs.touch)
    ) {
        ShaderBackground(prefs, renderer, animate = true)
        if (prefs.touch) ShaderTouchOverlay(renderer)
        Text(
            "WEX",
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun ListSettings(f: SettingsFilter) {
    GroupTitle("Что показывать", f)
    SettingsSwitch(f, "Скрытые файлы", "Файлы и папки, имя которых начинается с точки", ViewPreferences.showHidden) {
        ViewPreferences.updateShowHidden(it)
    }
    SettingsSwitch(f, "Миниатюры", "Картинки, видео и APK показывают своё содержимое вместо значка", ViewPreferences.showThumbnails) {
        ViewPreferences.updateShowThumbnails(it)
    }
    SettingsSwitch(f, "Колонка размера", "Размер файла справа от имени", ViewPreferences.showSizeColumn) {
        ViewPreferences.updateShowSizeColumn(it)
    }
    SettingsSwitch(f, "Колонка даты", "Когда файл менялся в последний раз", ViewPreferences.showDateColumn) {
        ViewPreferences.updateShowDateColumn(it)
    }
    SettingsSwitch(f, "Статус git", "Буквы у файлов, точка у папок с изменениями, ветка под списком", ViewPreferences.showGitStatus) {
        ViewPreferences.updateShowGitStatus(it)
    }

    GroupTitle("Порядок", f)
    SettingsSwitch(f, "Папки в начале списка", "Сначала папки, потом файлы", ViewPreferences.foldersFirst) {
        ViewPreferences.updateFoldersFirst(it)
    }
    SettingsSwitch(f, "Человеческая сортировка чисел", "file2 идёт раньше file10, а не наоборот", ViewPreferences.naturalSort) {
        ViewPreferences.updateNaturalSort(it)
    }

    GroupTitle("Вкладки панели", f)
    SettingsSwitch(
        f,
        "Вкладки снизу",
        "Полоса вкладок под списком файлов. Выключить — будет под панелью инструментов",
        ViewPreferences.tabsAtBottom
    ) { ViewPreferences.updateTabsAtBottom(it) }
    Hint(f, "Новая вкладка — Ctrl+Shift+T, закрыть — Ctrl+W, переключение — Ctrl+Tab и Ctrl+1…9")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorSettings(f: SettingsFilter) {
    GroupTitle("Текст", f)
    SettingsSlider(
        f,
        "Размер шрифта",
        EditorPreferences.fontSizeSp.toInt(),
        8..36,
        " пт",
        "в самом редакторе — Ctrl+колесо, Ctrl+= и Ctrl+−"
    ) { EditorPreferences.updateFontSize(it.toFloat()) }

    if (f.hit("Ширина отступа", "табуляция отступ tab")) {
        Text("Ширина отступа", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(4.dp))
        val sizes = listOf(2, 4, 8)
        ChipsRow(
            options = sizes.map { "$it пробела" },
            selectedIndex = sizes.indexOf(EditorPreferences.tabSize),
            onSelect = { EditorPreferences.updateTabSize(sizes[it]) }
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    SettingsSwitch(f, "Переносить длинные строки", "Строка не уезжает за край окна (Alt+Z)", EditorPreferences.softWrap) {
        EditorPreferences.updateSoftWrap(it)
    }
    SettingsSwitch(f, "Лигатуры", "Слитное начертание -> и != в моноширинном шрифте", EditorPreferences.ligatures) {
        EditorPreferences.updateLigatures(it)
    }
    SettingsSwitch(f, "Невидимые символы", "Точки вместо пробелов и стрелки вместо табуляций", EditorPreferences.showWhitespace) {
        EditorPreferences.updateShowWhitespace(it)
    }

    GroupTitle("Поля и подсветка", f)
    SettingsSwitch(f, "Номера строк", "Колонка с номерами слева", EditorPreferences.showLineNumbers) {
        EditorPreferences.updateShowLineNumbers(it)
    }
    SettingsSwitch(f, "Миникарта", "Уменьшенная копия файла справа", EditorPreferences.showMinimap) {
        EditorPreferences.updateShowMinimap(it)
    }
    SettingsSwitch(f, "Структура файла", "Список функций и классов сбоку (Alt+7)", EditorPreferences.showOutline) {
        EditorPreferences.updateShowOutline(it)
    }
    SettingsSwitch(f, "Закреплённые заголовки", "Начало текущего блока не уезжает вверх при прокрутке", EditorPreferences.stickyHeaders) {
        EditorPreferences.updateStickyHeaders(it)
    }
    SettingsSwitch(f, "Направляющие отступов", "Вертикальные линии по уровням вложенности", EditorPreferences.showIndentGuides) {
        EditorPreferences.updateShowIndentGuides(it)
    }
    SettingsSwitch(f, "Подсветка текущей строки", "Строка под курсором чуть светлее", EditorPreferences.highlightCurrentLine) {
        EditorPreferences.updateHighlightCurrentLine(it)
    }
    SettingsSwitch(f, "Подсветка такого же слова", "Все вхождения слова под курсором", EditorPreferences.highlightOccurrences) {
        EditorPreferences.updateHighlightOccurrences(it)
    }

    GroupTitle("Помощь при наборе", f)
    SettingsSwitch(f, "Подсказки при вводе", "Список дополнений всплывает сам (иначе — Ctrl+Пробел)", EditorPreferences.autoPopup) {
        EditorPreferences.updateAutoPopup(it)
    }
    SettingsSwitch(f, "Закрывать скобки и кавычки", "Вторая половина пары подставляется сама", EditorPreferences.autoClose) {
        EditorPreferences.updateAutoClose(it)
    }
    SettingsSwitch(f, "Проверка ошибок", "Подчёркивание проблем прямо во время набора", EditorPreferences.diagnostics) {
        EditorPreferences.updateDiagnostics(it)
    }
    SettingsSwitch(
        f,
        "Внешние проверщики",
        "bash -n, python, node, luac, gofmt, qmllint, niri validate — если установлены",
        EditorPreferences.externalChecks
    ) { EditorPreferences.updateExternalChecks(it) }

    GroupTitle("Просмотр Markdown", f)
    SettingsSwitch(
        f,
        "Загружать картинки из интернета",
        "Выключено — вместо картинки плашка: чужой сервер не узнает, что документ открыли",
        ViewPreferences.remoteImages
    ) { ViewPreferences.updateRemoteImages(it) }
    Hint(f, "Адреса в локальной сети всегда загружаются только по щелчку")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TerminalSettings(f: SettingsFilter) {
    GroupTitle("Оболочка", f)
    if (f.hit("Оболочка", "shell bash fish zsh консоль")) {
        val shells = remember { ShellLocator.available() }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            shells.forEach { path ->
                ChoiceChip(path.substringAfterLast('/'), TerminalPreferences.shell == path) {
                    TerminalPreferences.updateShell(path)
                }
            }
        }
        Hint(f, "Выбор действует для новых вкладок консоли")
        Spacer(modifier = Modifier.height(10.dp))
    }

    GroupTitle("Вид", f)
    SettingsSlider(
        f,
        "Размер шрифта консоли",
        TerminalPreferences.fontSize.toInt(),
        8..32,
        " пт",
        "в самой консоли — Ctrl+колесо"
    ) { TerminalPreferences.zoom(it - TerminalPreferences.fontSize) }

    SettingsSlider(
        f,
        "Высота консоли",
        (TerminalPreferences.heightFraction * 100).toInt(),
        15..85,
        "% панели",
        "можно тянуть мышью за границу консоли"
    ) {
        TerminalPreferences.updateHeightFraction(it / 100f)
        TerminalPreferences.commitHeight()
    }

    GroupTitle("Поведение", f)
    SettingsSwitch(
        f,
        "Следовать за папкой",
        "Консоль переходит туда же, куда переходит панель",
        TerminalPreferences.followPane
    ) { TerminalPreferences.updateFollowPane(it) }
    Hint(f, "Открыть и скрыть консоль — Ctrl+` или Ctrl+T")
}

/**
 * Список всех сочетаний клавиш. Щелчок по сочетанию включает захват: следующее нажатие и
 * становится новым сочетанием. Всё сохраняется в ~/.config/wexdesktop/keymap.properties.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BindSettings(f: SettingsFilter) {
    var capturing by remember { mutableStateOf<String?>(null) }
    // Пересобираем подписи после каждой правки
    val revision = Keymap.revision

    if (!f.active) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                "Щёлкните по сочетанию и нажмите новое. Esc — отмена, Backspace — отключить действие.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            ChoiceChip("Сбросить всё", selected = false) {
                capturing = null
                Keymap.resetAll()
            }
        }
    }

    for (group in Keymap.groups) {
        val items = Keymap.binds.filter { it.group == group && f.hit(it.title, group, it.default, Keymap.label(it.id)) }
        if (items.isEmpty()) continue
        GroupTitle(group, f)
        for (def in items) {
            key(def.id, revision) {
                BindRow(
                    def = def,
                    capturing = capturing == def.id,
                    onCapture = { capturing = if (capturing == def.id) null else def.id },
                    onDone = { capturing = null }
                )
            }
        }
    }
}

@Composable
private fun BindRow(def: BindDef, capturing: Boolean, onCapture: () -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    val combo = Keymap.combo(def.id)
    val conflicts = Keymap.conflicts(def.id, combo)
    LaunchedEffect(capturing) {
        if (capturing) {
            repeat(12) {
                if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(16)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(def.title, style = MaterialTheme.typography.bodyMedium)
            if (conflicts.isNotEmpty()) {
                Text(
                    "Занято: " + conflicts.joinToString(", ") { it.title },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (!Keymap.isDefault(def.id)) {
            IconButton(onClick = { onDone(); Keymap.reset(def.id) }, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Default.Restore,
                    contentDescription = "Вернуть ${def.default}",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        val border = if (capturing) NoctaliaPrimary else Color.Transparent
        Box(
            modifier = Modifier
                .widthIn(min = 132.dp)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .background(
                    if (capturing) NoctaliaPrimary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(6.dp)
                )
                .clickable { onCapture() }
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (!capturing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent capturing
                    val awt = e.awtEvent()
                    when {
                        e.key == Key.Escape -> onDone()
                        awt?.keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE ||
                            awt?.keyCode == java.awt.event.KeyEvent.VK_DELETE -> {
                            Keymap.assign(def.id, null)
                            onDone()
                        }
                        else -> {
                            val pressed = KeyCombo.of(e)
                            if (pressed != null) {
                                Keymap.assign(def.id, pressed)
                                onDone()
                            }
                        }
                    }
                    true
                }
                .focusable()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                if (capturing) "Нажмите сочетание…" else (combo?.label() ?: "отключено"),
                fontSize = 11.5.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = when {
                    capturing -> NoctaliaPrimary
                    combo == null -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SigningSettings(f: SettingsFilter) {
    GroupTitle("Когда подписывать", f)
    SettingsSwitch(
        f,
        "Подписывать изменённые APK сами",
        "При выходе из APK после правок — без вопроса",
        SigningPreferences.autoSignModified
    ) { SigningPreferences.updateAutoSignModified(it) }

    if (f.hit("Ключ", "подпись keystore ключ по умолчанию")) {
        GroupTitle("Ключ по умолчанию", f)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SigningKeys.keys.forEach { key ->
                ChoiceChip(key.title, SigningPreferences.defaultKeyId == key.id) {
                    SigningPreferences.updateDefaultKey(key.id)
                }
            }
        }
        Hint(f, "Новые ключи добавляются в окне подписи APK")
        Spacer(modifier = Modifier.height(10.dp))
    }

    if (f.hit("Схемы подписи", "v1 v2 v3 v4")) {
        GroupTitle("Схемы", f)
        val s = SigningPreferences.schemes
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("V1", s.v1) { SigningPreferences.updateSchemes(s.copy(v1 = !s.v1)) }
            ChoiceChip("V2", s.v2) { SigningPreferences.updateSchemes(s.copy(v2 = !s.v2)) }
            ChoiceChip("V3", s.v3) { SigningPreferences.updateSchemes(s.copy(v3 = !s.v3)) }
            ChoiceChip("V4", s.v4) { SigningPreferences.updateSchemes(s.copy(v4 = !s.v4)) }
        }
        Hint(f, "V4 кладёт рядом файл .idsig и требует V2 или V3")
        Spacer(modifier = Modifier.height(10.dp))
    }

    SettingsSwitch(
        f,
        "Заменять исходный файл",
        "Иначе подписанный APK сохраняется рядом отдельной копией",
        SigningPreferences.replaceOriginal
    ) {
        SigningPreferences.updateDefaults(SigningPreferences.defaultKeyId, SigningPreferences.schemes, it)
    }
}

@Composable
private fun CommandsSettings(f: SettingsFilter) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(false) }

    if (f.hit("Свои команды", "команда меню шаблон запуск")) {
        Text(
            text = "Появляются в меню файла по правой кнопке. В шаблоне: %f — файл, %F — все выделенные, " +
                "%n — имя, %b — имя без расширения, %d — папка файла, %D — папка соседней панели.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
    }

    val commands = org.wexclient.app.commands.UserCommands.commands
    commands.forEachIndexed { index, command ->
        if (!f.hit(command.title, command.template)) return@forEachIndexed
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(command.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        SectionBadge(f)
                    }
                    Text(
                        text = command.template,
                        fontSize = 11.sp,
                        fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(
                            command.filter.ifBlank { "для любых файлов" }.let { if (it == "/") "только папки" else it },
                            if (command.inTerminal) "в консоли" else "в фоне"
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(onClick = { editing = index }) {
                    Text("Изменить", fontSize = 11.sp, color = NoctaliaPrimary)
                }
                TextButton(onClick = { org.wexclient.app.commands.UserCommands.remove(index) }) {
                    Text("Убрать", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    if (!f.active) {
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { creating = true }) {
            Text("+ Новая команда", fontSize = 12.sp, color = NoctaliaPrimary)
        }
    }

    val editIndex = editing
    if (editIndex != null && editIndex in commands.indices) {
        CommandEditor(
            initial = commands[editIndex],
            onDismiss = { editing = null },
            onSave = {
                org.wexclient.app.commands.UserCommands.update(editIndex, it)
                editing = null
            }
        )
    }
    if (creating) {
        CommandEditor(
            initial = org.wexclient.app.commands.UserCommand("", ""),
            onDismiss = { creating = false },
            onSave = {
                org.wexclient.app.commands.UserCommands.add(it)
                creating = false
            }
        )
    }
}

/** Окно правки своей команды. */
@Composable
private fun CommandEditor(
    initial: org.wexclient.app.commands.UserCommand,
    onDismiss: () -> Unit,
    onSave: (org.wexclient.app.commands.UserCommand) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var template by remember { mutableStateOf(initial.template) }
    var filter by remember { mutableStateOf(initial.filter) }
    var inTerminal by remember { mutableStateOf(initial.inTerminal) }

    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            modifier = Modifier.width(560.dp).dialogGlass(RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (initial.title.isEmpty()) "Новая команда" else "Команда",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DialogClose(onDismiss)
                }
                Spacer(modifier = Modifier.height(12.dp))
                LabeledField("Название в меню", title, "Установить на телефон") { title = it }
                LabeledField("Команда", template, "adb install -r %f", mono = true) { template = it }
                LabeledField("Для каких файлов", filter, "apk xapk — или пусто для всех, / — для папок") { filter = it }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Показывать вывод в консоли", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Выключено — команда идёт в фоне, по окончании придёт сообщение",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = inTerminal,
                        onCheckedChange = { inTerminal = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = NoctaliaPrimary),
                        modifier = Modifier.noFocus()
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.height(36.dp)) {
                        Text("Отмена", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    val ready = title.isNotBlank() && template.isNotBlank()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NoctaliaPrimary.copy(alpha = if (ready) 0.18f else 0.06f),
                        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = if (ready) 1f else 0.3f)),
                        modifier = Modifier.height(36.dp).clickable(enabled = ready) {
                            onSave(org.wexclient.app.commands.UserCommand(title.trim(), template.trim(), filter.trim(), inTerminal))
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp)) {
                            Text("Сохранить", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NoctaliaPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, placeholder: String, mono: Boolean = false, onChange: (String) -> Unit) {
    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(4.dp))
    GrailCompactField(
        value = value,
        onChange = onChange,
        placeholder = placeholder,
        mono = mono,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun AboutSettings(f: SettingsFilter) {
    val home = System.getProperty("user.home")
    GroupTitle("WEX Desktop", f)
    if (f.hit("Версия", "о программе")) {
        Text(
            text = "Двухпанельный файловый менеджер для Linux. Всё окно рисуется одним движком, " +
                "поэтому просмотр, редактор и консоль работают в одном месте.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    GroupTitle("Где что лежит", f)
    if (f.hit("Файлы настроек", "конфиг папка настроек ключи журнал ошибок сбои")) {
        InfoLine("Настройки", "$home/.config/wexdesktop")
        InfoLine("Ключи подписи", "$home/.config/wexdesktop/keys")
        InfoLine("Корзина", "$home/.local/share/Trash")
        // Журнал сбоев: окно запускается без консоли, и ошибки видны только здесь
        val errors by androidx.compose.runtime.produceState(0) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.wexclient.app.util.ErrorLog.count() }
        }
        InfoLine(
            "Журнал ошибок",
            org.wexclient.app.util.ErrorLog.file.toString() + if (errors > 0) " · записей: $errors" else " · пока пуст"
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    GroupTitle("Подсказки", f)
    if (f.hit("Горячие клавиши", "сочетания F1")) {
        InfoLine("F1", "полный список горячих клавиш")
        InfoLine("Ctrl+H", "история переходов")
        InfoLine("Ctrl+Shift+F", "поиск текста внутри файлов")
        InfoLine("Ctrl+`", "консоль активной панели")
    }
}

// ---------------------------------------------------------------- мелкие части

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = "Найти настройку",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(NoctaliaPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(18.dp).noFocus()) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Очистить",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionRow(item: SettingsSection, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (selected) NoctaliaPrimary.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(Motion.TINT),
        label = "section-background"
    )
    val tint = when {
        selected -> NoctaliaPrimary
        dimmed -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = item.title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/** Заголовок группы. При поиске мешает — там строки идут сплошным списком. */
@Composable
private fun GroupTitle(text: String, f: SettingsFilter) {
    if (f.active) return
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(6.dp))
}

/** Метка раздела рядом с названием — только в выдаче поиска. */
@Composable
private fun SectionBadge(f: SettingsFilter) {
    if (!f.active) return
    Spacer(modifier = Modifier.width(6.dp))
    Text(
        text = LocalSectionTitle.current,
        style = MaterialTheme.typography.labelSmall,
        color = NoctaliaPrimary.copy(alpha = 0.7f),
        maxLines = 1
    )
}

/** Пояснение под группой. В выдаче поиска не нужно: там строки идут вперемешку из разных разделов. */
@Composable
private fun Hint(f: SettingsFilter, text: String) {
    if (f.active) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Строка-переключатель: вся строка кликабельна, справа выключатель. */
@Composable
private fun SettingsSwitch(
    f: SettingsFilter,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    if (!f.hit(title, subtitle)) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                SectionBadge(f)
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = NoctaliaPrimary),
            modifier = Modifier.noFocus()
        )
    }
}

/** Ползунок с числом справа. */
@Composable
private fun SettingsSlider(
    f: SettingsFilter,
    title: String,
    value: Int,
    range: IntRange,
    suffix: String,
    hint: String = "",
    onChange: (Int) -> Unit
) {
    if (!f.hit(title, hint)) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            SectionBadge(f)
            Spacer(modifier = Modifier.weight(1f))
            Text("$value$suffix", style = MaterialTheme.typography.labelSmall, color = NoctaliaPrimary)
        }
        if (hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = NoctaliaPrimary,
                activeTrackColor = NoctaliaPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.height(26.dp).noFocus()
        )
    }
}

/** Ряд взаимоисключающих кнопок. */
@Composable
private fun ChipsRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) NoctaliaPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(
                    1.dp,
                    if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.weight(1f).clickable { onSelect(index) }
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/** Карточка темы: имя, пояснение и три цвета палитры. */
@Composable
private fun ThemeCard(theme: AppTheme) {
    val selected = ThemePreferences.appTheme == theme
    val palette = ThemePreferences.paletteOf(theme)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.width(272.dp).clickable { ThemePreferences.setTheme(theme) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(palette.primary, palette.secondary, palette.tertiary).forEach { c ->
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(c))
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = theme.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = theme.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = NoctaliaPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Кнопка-чип с выбором. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** Выбор картинки системным окном; null — отменили. */
private fun pickImage(): String? {
    val chooser = javax.swing.JFileChooser(System.getProperty("user.home"))
    chooser.dialogTitle = "Фоновая картинка"
    chooser.fileFilter = object : javax.swing.filechooser.FileFilter() {
        override fun accept(f: java.io.File): Boolean = f.isDirectory ||
            f.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "avif", "gif")

        override fun getDescription(): String = "Картинки"
    }
    val result = chooser.showOpenDialog(null)
    return if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}
