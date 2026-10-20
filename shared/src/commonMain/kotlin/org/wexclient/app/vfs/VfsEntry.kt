package org.wexclient.app.vfs

import java.nio.file.Path

enum class VfsItemType {
    FOLDER,
    PARENT_DIR,
    ARCHIVE_APK,
    /** Набор split-APK: .apks (SAI, bundletool), .xapk (APKPure, APKCombo), .apkm (APKMirror) */
    ARCHIVE_APKS,
    ARCHIVE_ZIP,
    ARCHIVE_JAR,
    /** Андроидные zip-контейнеры: библиотека .aar, бандл .aab */
    ARCHIVE_AAR,
    /** Приложение Linux в одном файле (.AppImage) */
    APPIMAGE,
    DEX,
    /** Скомпилированный класс JVM (.class) */
    JAVA_CLASS,
    ARSC,
    AXML_MANIFEST,
    XML,
    SMALI,
    CODE_KOTLIN,
    CODE_JAVA,
    CODE_PYTHON,
    CODE_LUA,
    CODE_JAVASCRIPT,
    CODE_TYPESCRIPT,
    CODE_C_CPP,
    CODE_RUST,
    CODE_GO,
    CODE_CSS,
    CODE_HTML,
    /** Pine Script (TradingView) */
    CODE_PINE,
    CODE_SQL,
    CODE_RUBY,
    CODE_PHP,
    CODE_SWIFT,
    CODE_CSHARP,
    CODE_DART,
    /** Прочие языки: Perl, Scala, Haskell, Zig, ассемблер и другие */
    CODE_OTHER,
    /** Скрипты сборки: .gradle, Makefile, CMakeLists.txt, Dockerfile */
    BUILD_SCRIPT,
    /** Правила сжатия и обфускации: proguard-rules.pro */
    PROGUARD,
    SHELL_SCRIPT,
    /** .bat / .cmd / .ps1 */
    BATCH_SCRIPT,
    NATIVE_SO,
    CERTIFICATE,
    /** Ключи подписи: .jks, .keystore, .p12, .pk8, закрытые .pem */
    KEYSTORE,
    IMAGE,
    VIDEO,
    AUDIO,
    AUDIO_VIDEO,
    JSON,
    YAML,
    MARKDOWN,
    PROPERTIES_CONF,
    TEXT_FILE,
    DATABASE,
    DOCUMENT_PDF,
    /** Документы: .doc, .odt, .rtf, .epub и подобные */
    DOCUMENT,
    /** Таблицы: .csv, .xlsx, .ods */
    SPREADSHEET,
    /** Презентации: .ppt, .pptx, .odp */
    PRESENTATION,
    FONT,
    /** Пакеты и образы: .deb, .rpm, .iso, .torrent */
    PACKAGE,
    /** Двоичное: .exe, .dll, .o, .wasm */
    BINARY,
    /** Заплатка: .patch, .diff */
    PATCH,
    /** Ярлык запуска: .desktop, .lnk, .url */
    DESKTOP_ENTRY,
    /** Именованный канал, сокет, устройство: содержимое не читается */
    SPECIAL,
    REGULAR_FILE
}

data class VfsEntry(
    val name: String,
    val path: Path,
    val isDirectory: Boolean,
    val isArchive: Boolean,
    val size: Long,
    val lastModified: Long,
    val itemType: VfsItemType,
    val extension: String
) {
    private val isSpecial: Boolean get() = itemType == VfsItemType.SPECIAL

    // Считается один раз: строки списка спрашивают это при каждой перерисовке. Не `by lazy`: он — два
    // объекта (Lazy и лямбда) на запись, а записей в кэше папок — до ста тысяч. Гонка безвредна:
    // в худшем случае одна и та же строка посчитается дважды
    private var lowerExtCache: String? = null
    private val lowerExt: String get() = lowerExtCache ?: extension.lowercase().also { lowerExtCache = it }

    val isImage: Boolean
        get() = !isDirectory && !isSpecial && (itemType == VfsItemType.IMAGE || lowerExt in IMAGE_EXT)

    val isVideo: Boolean
        get() = !isDirectory && !isSpecial && (itemType == VfsItemType.VIDEO || lowerExt in VIDEO_EXT)

    val isAudio: Boolean
        get() = !isDirectory && !isSpecial && (itemType == VfsItemType.AUDIO || lowerExt in AUDIO_EXT)

    val isMedia: Boolean
        get() = isImage || isVideo || isAudio

    val isExtractableArchive: Boolean
        get() = !isDirectory && !isSpecial && (
            isArchive ||
            itemType in ARCHIVE_TYPES ||
            lowerExt in ARCHIVE_EXT ||
            org.wexclient.app.vfs.archive.ArchiveFormats.isTarFamily(name)
        )

    val archiveBaseName: String
        get() {
            val kind = org.wexclient.app.vfs.archive.ArchiveFormats.kindOf(name)
            return when {
                kind == null -> name.substringBeforeLast('.')
                kind.tar -> name.dropLast(kind.suffix.length)
                // dump.sql.gz → dump.sql
                else -> org.wexclient.app.vfs.archive.ArchiveFormats.innerName(name)
            }
        }

    private var formattedSizeCache: String? = null
    val formattedSize: String
        get() = formattedSizeCache ?: when {
            isDirectory -> "<DIR>"
            size < 1024 -> "$size B"
            else -> {
                val exp = (Math.log(size.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 6)
                val pre = "KMGTPE"[exp - 1]
                String.format("%.1f %sB", size / Math.pow(1024.0, exp.toDouble()), pre)
            }
        }.also { formattedSizeCache = it }

    private var formattedDateCache: String? = null
    val formattedDate: String
        get() = formattedDateCache
            ?: (if (lastModified <= 0) "-" else DATE_FORMAT.format(java.time.Instant.ofEpochMilli(lastModified)))
                .also { formattedDateCache = it }

    companion object {
        /** Запись о файле по пути: тип, размер и дата читаются с диска (битая ссылка — без них). */
        fun of(path: java.nio.file.Path): VfsEntry {
            val name = path.fileName?.toString().orEmpty()
            val attrs = runCatching {
                java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
            }.getOrNull()
            val dir = attrs?.isDirectory == true
            val special = attrs?.isOther == true
            return VfsEntry(
                name = name,
                path = path,
                isDirectory = dir,
                isArchive = !dir && !special &&
                    org.wexclient.app.vfs.archive.ArchiveFormats.isBrowsable(name),
                size = attrs?.size() ?: 0L,
                lastModified = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
                itemType = if (special) VfsItemType.SPECIAL else detectType(name, dir),
                extension = name.substringAfterLast('.', "")
            )
        }

        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "svg", "ico", "bmp", "gif", "tif", "tiff", "avif", "heic")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "3gp", "ts")
        private val AUDIO_EXT = setOf("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus", "wma", "mid", "midi")
        private val ARCHIVE_EXT = setOf("zip", "apk", "jar", "xapk", "apks", "aar", "aab", "apkm", "7z", "rar")
        private val ARCHIVE_TYPES = setOf(
            VfsItemType.ARCHIVE_APK, VfsItemType.ARCHIVE_APKS, VfsItemType.ARCHIVE_JAR, VfsItemType.ARCHIVE_ZIP, VfsItemType.ARCHIVE_AAR
        )
        private val DATE_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())

        fun detectType(name: String, isDirectory: Boolean): VfsItemType {
            if (isDirectory) return VfsItemType.FOLDER
            val lower = name.lowercase()
            return when {
                lower.endsWith(".apk") -> VfsItemType.ARCHIVE_APK
                lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") -> VfsItemType.ARCHIVE_APKS
                lower.endsWith(".aar") || lower.endsWith(".aab") -> VfsItemType.ARCHIVE_AAR
                lower.endsWith(".jar") -> VfsItemType.ARCHIVE_JAR
                lower.endsWith(".appimage") -> VfsItemType.APPIMAGE
                lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") ||
                org.wexclient.app.vfs.archive.ArchiveFormats.isTarFamily(lower) -> VfsItemType.ARCHIVE_ZIP
                // Ключи и сертификаты: .x509.pem — сертификат, хотя в имени бывает «key» (testkey.x509.pem)
                lower.endsWith(".x509.pem") || lower.endsWith(".crt") || lower.endsWith(".der") ||
                lower.endsWith(".idsig") || lower.endsWith(".sig") || lower.endsWith(".asc") -> VfsItemType.CERTIFICATE
                lower.endsWith(".jks") || lower.endsWith(".keystore") || lower.endsWith(".bks") ||
                lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".pk8") ||
                lower.endsWith(".pepk") || lower.endsWith(".key") ||
                (lower.endsWith(".pem") && (lower.contains("key") || lower.contains("private"))) -> VfsItemType.KEYSTORE
                lower.endsWith(".pem") -> VfsItemType.CERTIFICATE
                lower.endsWith(".dex") -> VfsItemType.DEX
                lower.endsWith(".class") -> VfsItemType.JAVA_CLASS
                lower == "resources.arsc" || lower.endsWith(".arsc") -> VfsItemType.ARSC
                lower == "androidmanifest.xml" -> VfsItemType.AXML_MANIFEST
                // Сборка и обфускация — до общих правил: CMakeLists.txt иначе стал бы просто текстом
                lower.endsWith(".gradle") || lower.endsWith(".gradle.kts") || lower == "gradlew" || lower.endsWith(".mk") ||
                lower == "makefile" || lower == "gnumakefile" || lower == "cmakelists.txt" ||
                lower.endsWith(".cmake") || lower == "dockerfile" || lower.endsWith(".dockerfile") ||
                lower.endsWith(".bzl") || lower == "build" || lower == "workspace" ||
                lower == "meson.build" || lower.endsWith(".nix") || lower.endsWith(".cabal") -> VfsItemType.BUILD_SCRIPT
                lower.endsWith(".pro") || lower.endsWith("proguard-rules.txt") ||
                lower.startsWith("proguard") && lower.endsWith(".cfg") -> VfsItemType.PROGUARD
                lower.endsWith(".xml") || lower.endsWith(".iml") || lower.endsWith(".xsd") || lower.endsWith(".xsl") ||
                lower.endsWith(".xslt") || lower.endsWith(".plist") || lower.endsWith(".csproj") ||
                lower.endsWith(".vcxproj") || lower.endsWith(".vbproj") || lower.endsWith(".nuspec") ||
                lower == "pom.xml" -> VfsItemType.XML
                lower.endsWith(".smali") -> VfsItemType.SMALI
                lower.endsWith(".kt") || lower.endsWith(".kts") -> VfsItemType.CODE_KOTLIN
                lower.endsWith(".java") || lower.endsWith(".aidl") -> VfsItemType.CODE_JAVA
                lower.endsWith(".py") || lower.endsWith(".pyw") -> VfsItemType.CODE_PYTHON
                lower.endsWith(".lua") -> VfsItemType.CODE_LUA
                lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") ||
                lower.endsWith(".jsx") -> VfsItemType.CODE_JAVASCRIPT
                lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".mts") ||
                lower.endsWith(".cts") -> VfsItemType.CODE_TYPESCRIPT
                lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h") || lower.endsWith(".hpp") ||
                lower.endsWith(".cc") || lower.endsWith(".cxx") || lower.endsWith(".c++") ||
                lower.endsWith(".hh") || lower.endsWith(".hxx") || lower.endsWith(".h++") ||
                lower.endsWith(".ipp") || lower.endsWith(".inl") || lower.endsWith(".ino") ||
                lower.endsWith(".cu") || lower.endsWith(".mm") -> VfsItemType.CODE_C_CPP
                lower.endsWith(".rs") -> VfsItemType.CODE_RUST
                lower.endsWith(".go") || lower == "go.mod" || lower == "go.sum" -> VfsItemType.CODE_GO
                lower.endsWith(".sql") || lower.endsWith(".ddl") || lower.endsWith(".dml") ||
                lower.endsWith(".psql") || lower.endsWith(".hql") -> VfsItemType.CODE_SQL
                lower.endsWith(".rb") || lower.endsWith(".gemspec") || lower == "gemfile" ||
                lower == "rakefile" -> VfsItemType.CODE_RUBY
                lower.endsWith(".php") || lower.endsWith(".phtml") -> VfsItemType.CODE_PHP
                lower.endsWith(".swift") -> VfsItemType.CODE_SWIFT
                lower.endsWith(".cs") || lower.endsWith(".csx") -> VfsItemType.CODE_CSHARP
                lower.endsWith(".dart") -> VfsItemType.CODE_DART
                lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".sass") || lower.endsWith(".less") -> VfsItemType.CODE_CSS
                lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml") ||
                lower.endsWith(".vue") || lower.endsWith(".svelte") || lower.endsWith(".astro") ||
                lower.endsWith(".hbs") || lower.endsWith(".ejs") || lower.endsWith(".erb") ||
                lower.endsWith(".twig") || lower.endsWith(".j2") -> VfsItemType.CODE_HTML
                lower.endsWith(".pine") || lower.endsWith(".pinescript") -> VfsItemType.CODE_PINE
                lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") ||
                lower.endsWith(".fish") || lower.endsWith(".command") -> VfsItemType.SHELL_SCRIPT
                lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1") -> VfsItemType.BATCH_SCRIPT
                lower.endsWith(".so") || lower.contains(".so.") -> VfsItemType.NATIVE_SO
                lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec") || lower.endsWith(".sf") ||
                lower == "manifest.mf" || lower.endsWith(".cer") -> VfsItemType.CERTIFICATE
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".ico") || lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".tiff") || lower.endsWith(".tif") || lower.endsWith(".avif") -> VfsItemType.IMAGE
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".flv") || lower.endsWith(".wmv") || lower.endsWith(".m4v") || lower.endsWith(".3gp") || lower.endsWith(".ts") -> VfsItemType.VIDEO
                lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".opus") || lower.endsWith(".wma") || lower.endsWith(".mid") -> VfsItemType.AUDIO
                lower.endsWith(".json") || lower.endsWith(".json5") || lower.endsWith(".jsonc") ||
                lower.endsWith(".ndjson") || lower.endsWith(".geojson") || lower.endsWith(".har") ||
                lower.endsWith(".webmanifest") -> VfsItemType.JSON
                lower.endsWith(".yaml") || lower.endsWith(".yml") -> VfsItemType.YAML
                lower.endsWith(".md") || lower.endsWith(".markdown") -> VfsItemType.MARKDOWN
                lower.endsWith(".properties") || lower.endsWith(".conf") || lower.endsWith(".cfg") ||
                lower.endsWith(".toml") || lower.endsWith(".ini") || lower.endsWith(".env") ||
                lower.startsWith(".env") || lower.contains(".env.") ||
                lower.endsWith(".lock") || lower.endsWith(".service") || lower == ".editorconfig" ||
                lower == ".gitignore" || lower == ".gitattributes" || lower == ".dockerignore" ||
                lower == ".npmrc" || lower == ".yarnrc" || lower.startsWith(".babelrc") ||
                lower.startsWith(".prettierrc") || lower.startsWith(".eslintrc") -> VfsItemType.PROPERTIES_CONF
                lower.endsWith(".pdf") -> VfsItemType.DOCUMENT_PDF
                lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".odt") ||
                lower.endsWith(".rtf") || lower.endsWith(".epub") || lower.endsWith(".fb2") ||
                lower.endsWith(".djvu") || lower.endsWith(".mobi") || lower.endsWith(".azw3") ||
                lower.endsWith(".pages") -> VfsItemType.DOCUMENT
                lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".xls") ||
                lower.endsWith(".xlsx") || lower.endsWith(".ods") || lower.endsWith(".numbers") -> VfsItemType.SPREADSHEET
                lower.endsWith(".ppt") || lower.endsWith(".pptx") || lower.endsWith(".odp") -> VfsItemType.PRESENTATION
                lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc") ||
                lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".eot") -> VfsItemType.FONT
                lower.endsWith(".deb") || lower.endsWith(".rpm") || lower.endsWith(".msi") ||
                lower.endsWith(".dmg") || lower.endsWith(".pkg") || lower.endsWith(".snap") ||
                lower.endsWith(".flatpak") || lower.endsWith(".appx") || lower.endsWith(".iso") ||
                lower.endsWith(".img") || lower.endsWith(".obb") || lower.endsWith(".torrent") ||
                lower.endsWith(".cab") -> VfsItemType.PACKAGE
                lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".dylib") ||
                lower.endsWith(".o") || lower.endsWith(".a") || lower.endsWith(".lib") ||
                lower.endsWith(".bin") || lower.endsWith(".wasm") || lower.endsWith(".pyc") ||
                lower.endsWith(".pyo") || lower.endsWith(".ko") || lower.endsWith(".elf") ||
                lower.endsWith(".sys") || lower.endsWith(".efi") || lower.endsWith(".out") ||
                lower.endsWith(".rom") || lower.endsWith(".pyd") || lower.endsWith(".node") ||
                lower.endsWith(".jnilib") || lower.endsWith(".dat") -> VfsItemType.BINARY
                lower.endsWith(".patch") || lower.endsWith(".diff") -> VfsItemType.PATCH
                lower.endsWith(".desktop") || lower.endsWith(".lnk") || lower.endsWith(".url") ||
                lower.endsWith(".webloc") -> VfsItemType.DESKTOP_ENTRY
                lower.endsWith(".pl") || lower.endsWith(".pm") || lower.endsWith(".scala") ||
                lower.endsWith(".groovy") || lower.endsWith(".gvy") || lower.endsWith(".hs") ||
                lower.endsWith(".lhs") || lower.endsWith(".ml") || lower.endsWith(".mli") ||
                lower.endsWith(".clj") || lower.endsWith(".cljs") || lower.endsWith(".edn") ||
                lower.endsWith(".ex") || lower.endsWith(".exs") || lower.endsWith(".erl") ||
                lower.endsWith(".hrl") || lower.endsWith(".jl") || lower.endsWith(".zig") ||
                lower.endsWith(".nim") || lower.endsWith(".cr") || lower.endsWith(".vb") ||
                lower.endsWith(".vbs") || lower.endsWith(".awk") || lower.endsWith(".tcl") ||
                lower.endsWith(".asm") || lower.endsWith(".proto") || lower.endsWith(".graphql") ||
                lower.endsWith(".gql") || lower.endsWith(".tf") || lower.endsWith(".tfvars") ||
                lower.endsWith(".hcl") || lower.endsWith(".sol") || lower.endsWith(".f90") ||
                lower.endsWith(".pas") -> VfsItemType.CODE_OTHER
                lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".text") ||
                lower.endsWith(".rst") || lower.endsWith(".adoc") || lower.endsWith(".asciidoc") ||
                lower.endsWith(".org") || lower.endsWith(".tex") || lower.endsWith(".nfo") ||
                lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") ||
                lower == "readme" || lower == "license" || lower == "changelog" ||
                lower == "authors" || lower == "copying" -> VfsItemType.TEXT_FILE
                lower.endsWith(".db") || lower.endsWith(".db3") || lower.endsWith(".sqlite") ||
                lower.endsWith(".sqlite3") || lower.endsWith(".sqlitedb") || lower.endsWith(".s3db") ||
                lower.endsWith(".sl3") -> VfsItemType.DATABASE
                else -> VfsItemType.REGULAR_FILE
            }
        }

        // Обе зовутся из сравнения при сортировке (2·n·log n раз) — без lowercase() и подстрок на каждый вызов
        fun getApkItemPriority(name: String, isDirectory: Boolean, isArchive: Boolean): Int {
            if (isDirectory || isArchive) return 0
            return when {
                name.equals("androidmanifest.xml", ignoreCase = true) -> 1
                name.endsWith(".dex", ignoreCase = true) -> 2
                name.equals("resources.arsc", ignoreCase = true) -> 3
                else -> 4
            }
        }

        /** classes.dex — 1, classesN.dex — N, прочие .dex (и нечисловые) — 999, не .dex — Int.MAX_VALUE. */
        fun getDexNaturalIndex(name: String): Int {
            if (!name.endsWith(".dex", ignoreCase = true)) return Int.MAX_VALUE
            if (name.equals("classes.dex", ignoreCase = true)) return 1
            val from = if (name.startsWith("classes", ignoreCase = true)) 7 else 0
            val to = name.length - 4
            if (to <= from || to - from > 9) return 999
            var n = 0
            for (i in from until to) {
                val c = name[i]
                if (c !in '0'..'9') return 999
                n = n * 10 + (c - '0')
            }
            return n
        }
    }

    val apkItemPriority: Int get() = getApkItemPriority(name, isDirectory, isArchive)
    val dexNaturalIndex: Int get() = getDexNaturalIndex(name)
}
