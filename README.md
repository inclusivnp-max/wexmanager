# WEX Desktop

[![Build](https://github.com/inclusivnp-max/wexmanager/actions/workflows/build.yml/badge.svg)](https://github.com/inclusivnp-max/wexmanager/actions/workflows/build.yml)

Двухпанельный файловый менеджер для Linux и Windows. Внутри есть редактор кода,
терминал и инструменты для APK: можно открыть пакет, посмотреть DEX и ресурсы,
внести изменения, подписать и установить результат через ADB.

Проект написан на Kotlin и Compose Multiplatform. Код редактора, файловой системы
и APK-инструментов общий с Android-приложением из модуля `androidApp`.

## Что умеет

- **Файлы:** вкладки и закладки, копирование и перенос между панелями, корзина,
  отмена операций, пакетное переименование, контрольные суммы. Есть поиск по имени
  и содержимому, поиск дубликатов, сравнение папок и анализ занятого места.
- **Редактор:** подсветка синтаксиса, автодополнение, сворачивание блоков,
  несколько кареток, поиск и замена с регулярными выражениями. Для двоичных файлов
  есть hex-редактор, для текста — сравнение и предпросмотр Markdown и HTML.
- **Архивы:** открываются как папки. Поддерживаются ZIP, TAR и его сжатые варианты;
  для 7z и RAR нужны внешние архиваторы. Есть просмотр содержимого AppImage
  и наборов split APK.
- **Просмотр:** изображения, аудио и видео, PDF, DOCX, XLSX, базы SQLite.
  Отдельные инструменты открывают ELF, Java-классы, DEX, ARSC и бинарный XML Android.
- **APK:** манифест, разрешения и подписи, правка ресурсов и smali, сравнение
  версий по файлам, коду и ресурсам. Java-код можно смотреть через jadx, CFR,
  Vineflower и Procyon; jadx также экспортирует проект.
- **Подключения:** файлы на сервере через SSH/SFTP и на телефоне через ADB.
  В окне приложений телефона доступны выгрузка APK, запуск, остановка,
  очистка данных и удаление. Встроенный терминал работает в папке панели.

Набор функций Android-версии отличается от настольной: у неё свои просмотрщики,
способ запуска команд и доступ к хранилищу.

## Готовые сборки

[GitHub Actions](https://github.com/inclusivnp-max/wexmanager/actions/workflows/build.yml)
собирает проект для Linux x64, Windows x64 и Android. Откройте успешный запуск
и раздел **Artifacts**:

- `WEXdesktop-Linux` — архив `tar.gz` со встроенной Java. Распакуйте его и запустите `WEXdesktop/start.sh`.
- `WEXdesktop-Windows` — установщик `msi` со встроенной Java.
- `WEX-Android-debug` — отладочный APK для Android 8.0 и новее (API 26).

APK подписан отладочным ключом, а не постоянным ключом выпуска. Сборки из разных
запусков Actions могут требовать удаления предыдущего APK перед установкой.

## Сборка и запуск

Нужны **Git**, **JDK 21**, **Android SDK 37.0** и **Build Tools 37.0.0**.
Gradle отдельно устанавливать не нужно — в репозитории есть wrapper.
Android SDK требуется и для настольной сборки: Android-модули подключены
в общем [settings.gradle.kts](settings.gradle.kts).

```bash
git clone https://github.com/inclusivnp-max/wexmanager.git
cd wexmanager
```

SDK можно установить через Android Studio или командные инструменты Android:

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

Путь к SDK задаётся через `ANDROID_HOME` или `sdk.dir` в локальном файле
`local.properties`. Например, в Linux:

```properties
sdk.dir=/home/yourname/Android/Sdk
```

В Windows можно использовать прямые слеши: `sdk.dir=C:/Android/Sdk`.
Этот файл, сборки и личные ключи в Git не попадают. Для первого запуска сборки
нужен интернет: Gradle скачивает зависимости из объявленных репозиториев.

Исходники clazzpatcher уже находятся в [third_party/clazzpatcher/](third_party/clazzpatcher/).
Gradle собирает их вместе с WEX; скачивать библиотеку отдельно или настраивать
путь к ней не нужно. В каталоге сохранены её тесты, тестовые файлы и документация.

Для разработки с отдельной копией библиотеки путь можно переопределить:

```bash
./gradlew -Pclazzpatcher.dir=/path/to/clazzpatcher :app:run
```

Основные команды из корня проекта:

```bash
./gradlew :app:run                    # запустить настольную версию
./gradlew :app:classes                # скомпилировать её без запуска
./gradlew :shared:desktopTest         # выполнить JVM-тесты
./gradlew :androidApp:assembleDebug   # собрать отладочный APK
```

В PowerShell вместо `./gradlew` используется `.\gradlew.bat`.

В Linux для тестов интерфейса нужен графический сеанс или Xvfb:

```bash
xvfb-run -a ./gradlew :shared:desktopTest
```

Проверки скриптов запуска выполняются после сборки `:app:installDist`:

```bash
python3 tools/test_packaging.py
```

В Windows эта команда запускается через `python`.

### Дистрибутив

```bash
./gradlew :app:installDist   # приложение и скрипты запуска, без Java
./gradlew :app:packageApp    # пакет со встроенной Java
```

`installDist` создаёт каталог `app/build/install/app/`, скрипты лежат в `bin/`.
`packageApp` на Linux собирает `app/build/packages/WEXdesktop-linux-x64.tar.gz`,
на Windows — MSI в `app/build/packages/`. Пакет собирается на целевой ОС.
Linux-версия из архива запускается через `WEXdesktop/start.sh`.
Для MSI нужен WiX Toolset 3 (`candle.exe` и `light.exe` в `PATH`).

В собранном приложении `--help` показывает параметры запуска, а `--version` —
версию. Эти команды работают без графического сеанса. `--background` запускает
WEX без открытия окна; путь к файлу или папке можно передать отдельным аргументом.

## Внешние инструменты

WEX ищет эти программы в `PATH`. Они нужны для соответствующих функций,
а не для всех операций с файлами.

| Программа | Функция |
| --- | --- |
| `fd`, `rg` (ripgrep) | Ускоренный поиск по именам и содержимому |
| `git` | Отметки состояния файлов |
| `ssh`, `sftp` | Работа с сервером |
| `adb` | Работа с Android-устройством |
| `7z`, `unrar` | Архивы 7z и RAR |
| `ffmpeg`, `ffprobe`, `ffplay` | Аудио, видео, метаданные и миниатюры |
| Poppler: `pdfinfo`, `pdftoppm`, `pdftotext`, `pdfunite` | Просмотр и обработка PDF |
| Chromium или Google Chrome | Предпросмотр HTML |
| `objdump`, `llvm-objdump` | Дизассемблирование ELF; для ARM/AArch64 нужен `llvm-objdump` |

## Клавиши

Основные сочетания настольной версии:

| Клавиши | Действие |
| --- | --- |
| `Tab` | Другая панель |
| `F2` | Переименовать |
| `F3` / `F4` | Просмотр / редактирование |
| `F5` / `F6` | Копировать / перенести в другую панель |
| `F7` | Создать папку |
| `Delete` / `Shift+Delete` | В корзину / удалить навсегда |
| `Ctrl+Z` | Отменить файловую операцию |
| `Ctrl+F` / `Ctrl+Shift+F` | Поиск по имени / содержимому |
| `Ctrl+D` | Сравнить с файлом в соседней панели |
| `Ctrl+Shift+H` | Hex-редактор |
| `Ctrl+T` | Терминал панели |
| `Ctrl+Shift+K` / `Ctrl+Shift+M` | SSH / телефон через ADB |

Полный список открывается по `F1`. Сочетания меняются в настройках, в разделе «Бинды».

## Каталоги проекта

- [`app/`](app/) — запуск настольной версии и сборка дистрибутивов.
- [`shared/src/commonMain/`](shared/src/commonMain/) — файловая система, редакторы,
  APK-инструменты и общий интерфейс.
- [`shared/src/desktopMain/`](shared/src/desktopMain/) и
  [`shared/src/androidMain/`](shared/src/androidMain/) — платформенные реализации.
- [`shared/src/desktopTest/`](shared/src/desktopTest/) — JVM-тесты и тестовые файлы.
- [`androidApp/`](androidApp/) — Android-приложение, разрешения и навигация.
- [`buildSrc/`](buildSrc/) — общая логика Gradle.
- [`third_party/clazzpatcher/`](third_party/clazzpatcher/) — исходники библиотеки DEX/smali.
- [`gradle/libs.versions.toml`](gradle/libs.versions.toml) — версии зависимостей.

Настройки настольной версии лежат в `~/.config/wexdesktop/`, кэш —
в `~/.cache/wexdesktop/`. Ключи подписи хранятся в подкаталоге `keys/`,
сочетания клавиш — в `keymap.properties`.

HTML-предпросмотр и внешние изображения Markdown могут обращаться к сети.
Загрузка изображений Markdown меняется в настройках редактора.
