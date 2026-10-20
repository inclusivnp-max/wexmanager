package org.wexclient.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Layers
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Css
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.lerp
import org.wexclient.app.theme.NoctaliaOnSurface
import org.wexclient.app.theme.NoctaliaOnSurfaceVariant
import org.wexclient.app.theme.NoctaliaOutline
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.VfsItemType

@Composable
fun FileIconBadge(
    type: VfsItemType,
    modifier: Modifier = Modifier,
    tintOverride: Color? = null
) {
    val (icon, defaultColor) = getIconAndColor(type)
    val color = tintOverride ?: defaultColor

    Box(
        modifier = modifier.size(22.dp),
        contentAlignment = Alignment.Center
    ) {
        if (type == VfsItemType.ARCHIVE_APKS) {
            // Набор split-APK: робот Android и «стопка» частей в углу
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp).align(Alignment.TopStart)
            )
            BundleCornerBadge(modifier = Modifier.align(Alignment.BottomEnd))
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Значок «несколько частей» в углу иконки набора split-APK. */
@Composable
fun BundleCornerBadge(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, BundleTint.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Layers,
            contentDescription = null,
            tint = BundleTint,
            modifier = Modifier.size(size * 0.7f)
        )
    }
}

/** Цвет наборов split-APK: зелёный Android, чуть бирюзовее, чтобы отличать от обычного APK. */
val BundleTint = Color(0xFF5FD4B0)

private fun getIconAndColor(type: VfsItemType): Pair<ImageVector, Color> {
    return when (type) {
        VfsItemType.FOLDER -> Pair(Icons.Default.Folder, NoctaliaPrimary)
        VfsItemType.PARENT_DIR -> Pair(Icons.Default.Folder, NoctaliaSecondary)
        VfsItemType.ARCHIVE_APK -> Pair(Icons.Default.Android, Color(0xFF4ADE80)) // Android Green
        VfsItemType.ARCHIVE_APKS -> Pair(Icons.Default.Android, BundleTint) // Набор split-APK
        VfsItemType.APPIMAGE -> Pair(Icons.Default.RocketLaunch, Color(0xFF60A5FA)) // Приложение Linux
        VfsItemType.ARCHIVE_ZIP, VfsItemType.ARCHIVE_JAR -> Pair(Icons.Default.FolderZip, Color(0xFFA78BFA)) // Violet
        VfsItemType.ARCHIVE_AAR -> Pair(Icons.Default.Widgets, Color(0xFF9CCC65)) // Библиотека и бандл Android
        VfsItemType.DEX -> Pair(Icons.Default.DataObject, Color(0xFFC084FC)) // Purple
        VfsItemType.JAVA_CLASS -> Pair(Icons.Default.Coffee, ClassFileTint) // Java — чашка
        VfsItemType.ARSC -> Pair(Icons.Default.Storage, Color(0xFFFCD34D)) // Amber
        VfsItemType.AXML_MANIFEST -> Pair(Icons.Default.IntegrationInstructions, Color(0xFF34D399)) // Emerald
        VfsItemType.XML -> Pair(Icons.Default.Code, Color(0xFF67E8F9)) // Cyan
        VfsItemType.SMALI -> Pair(Icons.Default.IntegrationInstructions, Color(0xFFF472B6)) // Pink
        VfsItemType.CODE_LUA -> Pair(Icons.Default.Code, Color(0xFF00A2FF)) // Lua Royal Blue
        VfsItemType.CODE_KOTLIN -> Pair(Icons.Default.Code, Color(0xFFE879F9)) // Kotlin Magenta
        VfsItemType.CODE_JAVA -> Pair(Icons.Default.Code, Color(0xFFFB923C)) // Java Orange
        VfsItemType.CODE_PYTHON -> Pair(Icons.Default.Terminal, Color(0xFF38BDF8)) // Python Blue
        VfsItemType.CODE_JAVASCRIPT -> Pair(Icons.Default.Javascript, Color(0xFFFDE047)) // JS Yellow
        VfsItemType.CODE_TYPESCRIPT -> Pair(Icons.Default.Code, Color(0xFF60A5FA)) // TS Blue
        VfsItemType.CODE_C_CPP -> Pair(Icons.Default.Code, Color(0xFF38BDF8)) // C/C++ Cyan
        VfsItemType.CODE_RUST -> Pair(Icons.Default.Code, Color(0xFFFB923C)) // Rust Orange
        VfsItemType.CODE_GO -> Pair(Icons.Default.Pets, Color(0xFF00ADD8)) // Go — суслик, фирменная бирюза
        VfsItemType.CODE_CSS -> Pair(Icons.Default.Css, Color(0xFF60A5FA)) // CSS Blue
        VfsItemType.CODE_HTML -> Pair(Icons.Default.Html, Color(0xFFFB7A45)) // HTML Orange
        VfsItemType.CODE_PINE -> Pair(Icons.Default.Park, Color(0xFF26C281)) // Pine Script — ёлка
        VfsItemType.CODE_SQL -> Pair(Icons.Default.Schema, Color(0xFFEAB308)) // Запросы SQL
        VfsItemType.CODE_RUBY -> Pair(Icons.Default.Diamond, Color(0xFFEF4444)) // Ruby — самоцвет
        VfsItemType.CODE_PHP -> Pair(Icons.Default.Code, Color(0xFF9CA3FF)) // PHP Lilac
        VfsItemType.CODE_SWIFT -> Pair(Icons.Default.Code, Color(0xFFF05138)) // Swift Orange
        VfsItemType.CODE_CSHARP -> Pair(Icons.Default.Code, Color(0xFFB794F6)) // C# Purple
        VfsItemType.CODE_DART -> Pair(Icons.Default.Code, Color(0xFF29B6F6)) // Dart Blue
        VfsItemType.CODE_OTHER -> Pair(Icons.Default.Code, Color(0xFF94A3B8)) // Прочие языки
        VfsItemType.BUILD_SCRIPT -> Pair(Icons.Default.Build, Color(0xFF6BC5B1)) // Gradle, Make, CMake, Docker
        VfsItemType.PROGUARD -> Pair(Icons.Default.Compress, Color(0xFFF59E0B)) // Сжатие и обфускация
        VfsItemType.SHELL_SCRIPT -> Pair(Icons.Default.Terminal, Color(0xFF86EFAC)) // Shell Green
        VfsItemType.BATCH_SCRIPT -> Pair(Icons.Default.Terminal, Color(0xFF7DD3FC)) // Windows Sky
        VfsItemType.NATIVE_SO -> Pair(Icons.Default.Memory, Color(0xFFF87171)) // Red
        VfsItemType.CERTIFICATE -> Pair(Icons.Default.VerifiedUser, Color(0xFFFCD34D)) // Yellow
        VfsItemType.KEYSTORE -> Pair(Icons.Default.Key, Color(0xFFFBBF24)) // Gold
        VfsItemType.IMAGE -> Pair(Icons.Default.Image, Color(0xFF38BDF8)) // Cyan
        VfsItemType.VIDEO -> Pair(Icons.Default.Movie, Color(0xFFF43F5E)) // Rose / Crimson
        VfsItemType.AUDIO -> Pair(Icons.Default.Audiotrack, Color(0xFFE879F9)) // Fuchsia
        VfsItemType.AUDIO_VIDEO -> Pair(Icons.Default.Audiotrack, Color(0xFFE879F9)) // Fuchsia
        VfsItemType.JSON, VfsItemType.YAML -> Pair(Icons.Default.DataObject, Color(0xFFA3E635)) // Lime
        VfsItemType.MARKDOWN -> Pair(Icons.AutoMirrored.Filled.Article, Color(0xFFFF8B7D)) // Markdown Coral
        VfsItemType.PROPERTIES_CONF -> Pair(Icons.Default.SettingsApplications, Color(0xFF5EC8E5)) // Config Azure
        VfsItemType.DOCUMENT_PDF -> Pair(Icons.Default.PictureAsPdf, Color(0xFFEF4444)) // PDF
        VfsItemType.DOCUMENT -> Pair(Icons.Default.MenuBook, Color(0xFF60A5FA)) // Документы и книги
        VfsItemType.SPREADSHEET -> Pair(Icons.Default.TableChart, Color(0xFF22C55E)) // Таблицы
        VfsItemType.PRESENTATION -> Pair(Icons.Default.Slideshow, Color(0xFFF97316)) // Презентации
        VfsItemType.FONT -> Pair(Icons.Default.TextFields, Color(0xFFD8B4FE)) // Шрифты
        VfsItemType.PACKAGE -> Pair(Icons.Default.Inventory2, Color(0xFFFBBF24)) // Пакеты и образы
        VfsItemType.BINARY -> Pair(Icons.Default.DeveloperBoard, Color(0xFF9CA3AF)) // Двоичное: свой значок, не как у .so
        VfsItemType.PATCH -> Pair(Icons.Default.Difference, Color(0xFF34D399)) // Заплатки
        VfsItemType.DESKTOP_ENTRY -> Pair(Icons.AutoMirrored.Filled.Launch, Color(0xFF7DD3FC)) // Ярлык запуска
        // Нейтральные типы — из текущей темы, иначе в холодных темах они «тёплые»
        VfsItemType.TEXT_FILE -> Pair(Icons.AutoMirrored.Filled.TextSnippet, NoctaliaOnSurface)
        VfsItemType.DATABASE -> Pair(Icons.Default.Storage, Color(0xFF5EEAD4)) // Teal
        VfsItemType.SPECIAL -> Pair(Icons.Default.Cable, Color(0xFF94A3B8)) // Канал / сокет / устройство
        VfsItemType.REGULAR_FILE -> Pair(Icons.Default.InsertDriveFile, lerp(NoctaliaOnSurfaceVariant, NoctaliaOutline, 0.45f))
    }
}
