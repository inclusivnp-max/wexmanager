package org.wexclient.app.appimage

import kotlinx.coroutines.runBlocking
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import org.wexclient.app.vfs.archive.ArchiveFormats
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppImagesTest {
    private lateinit var dir: Path
    private val home: Path get() = Paths.get(System.getProperty("user.home"))

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_appimage")
        cleanInstalled()
    }

    @AfterTest
    fun tearDown() {
        cleanInstalled()
        dir.toFile().deleteRecursively()
    }

    private fun cleanInstalled() {
        for (d in listOf(home.resolve("AppImages"), home.resolve("AppImages/.icons"), home.resolve(".local/share/applications"))) {
            if (!Files.isDirectory(d)) continue
            Files.list(d).use { s -> s.toList() }.filter { it.fileName.toString().startsWith("wex_test_app") }.forEach { Files.deleteIfExists(it) }
        }
    }

    private val tools get() = ArchiveFormats.toolAvailable("mksquashfs") && ArchiveFormats.toolAvailable("unsquashfs")

    private fun png(): ByteArray {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 16) for (y in 0 until 16) img.setRGB(x, y, if ((x + y) % 2 == 0) 0xFF2962FF.toInt() else 0xFFFFFFFF.toInt())
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /** Настоящий по устройству AppImage: ELF (/usr/bin/true) с меткой AI 2 и squashfs за таблицей секций. */
    private fun buildAppImage(version: String, fileName: String = "Wex_Test-$version-x86_64.AppImage"): Path {
        val appDir = Files.createDirectories(dir.resolve("AppDir-$fileName"))
        Files.writeString(appDir.resolve("AppRun"), "#!/bin/sh\necho $version\n")
        Files.writeString(
            appDir.resolve("wex-test.desktop"),
            """
            [Desktop Entry]
            Type=Application
            Name=Wex Test App
            Comment=Проверка установки
            Exec=AppRun --no-sandbox %U
            Icon=wex-test
            Categories=Utility;Development;
            Terminal=false
            X-AppImage-Version=$version
            Actions=new;

            [Desktop Action new]
            Name=Новое окно
            Exec=AppRun --new-window %U
            """.trimIndent() + "\n"
        )
        Files.write(appDir.resolve("wex-test.png"), png())
        Files.createSymbolicLink(appDir.resolve(".DirIcon"), Paths.get("wex-test.png"))
        val img = dir.resolve("img-$fileName.squashfs")
        val p = ProcessBuilder("mksquashfs", appDir.toString(), img.toString(), "-root-owned", "-noappend", "-comp", "gzip", "-quiet", "-no-progress")
            .redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        assertEquals(0, p.waitFor(), log)
        val runtime = Files.readAllBytes(Paths.get("/usr/bin/true"))
        runtime[8] = 'A'.code.toByte()
        runtime[9] = 'I'.code.toByte()
        runtime[10] = 2
        val file = dir.resolve(fileName)
        Files.write(file, runtime + Files.readAllBytes(img))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        return file
    }

    @Test
    fun `desktop entry editing keeps the rest of the file`() {
        val e = DesktopEntry.parse(
            "# comment\n[Desktop Entry]\nName=Obsidian\nName[ru]=Обсидиан\nExec=obsidian %U\n\n[Desktop Action new]\nName=New\nExec=obsidian --new\n"
        )
        assertEquals("Обсидиан", e.localized("Name", Locale.forLanguageTag("ru-RU")))
        assertEquals("Obsidian", e.localized("Name", Locale.ENGLISH))
        e.set("TryExec", "/home/u/AppImages/obsidian.appimage")
        e.set("Comment", "Две\nстроки")
        e.remove("Name[ru]")
        e.set("Icon", "x", "Desktop Action new")
        assertEquals(
            "# comment\n[Desktop Entry]\nName=Obsidian\nExec=obsidian %U\nTryExec=/home/u/AppImages/obsidian.appimage\nComment=Две\\nстроки\n\n" +
                "[Desktop Action new]\nName=New\nExec=obsidian --new\nIcon=x\n",
            e.text()
        )
        assertEquals("Две\nстроки", e.get("Comment"))
        assertEquals(listOf("Desktop Entry", "Desktop Action new"), e.groups)

        assertEquals("%U", DesktopEntry.execArguments("obsidian %U"))
        assertEquals("--no-sandbox %U", DesktopEntry.execArguments("env DESKTOPINTEGRATION=1 /home/u/a.appimage --no-sandbox %U"))
        assertEquals("--x \"a b\"", DesktopEntry.execArguments("\"/opt/My App/run\" --x \"a b\""))
        assertEquals("", DesktopEntry.execArguments("AppRun"))
        assertEquals("/home/u/AppImages/a.appimage", DesktopEntry.quoteExecArg("/home/u/AppImages/a.appimage"))
        assertEquals("\"/home/u/My Apps/a\\\$b.appimage\"", DesktopEntry.quoteExecArg("/home/u/My Apps/a\$b.appimage"))
        assertEquals("/home/u/100%%/a.appimage", DesktopEntry.quoteExecArg("/home/u/100%/a.appimage"))
        assertEquals("\"/home/u/50% off/%f\"".replace("%", "%%"), DesktopEntry.quoteExecArg("/home/u/50% off/%f"))
    }

    @Test
    fun `names from files and for installation`() {
        assertEquals("Obsidian", AppImages.nameFromFile(Paths.get("Obsidian-1.13.7.AppImage")))
        assertEquals("1.13.7", AppImages.versionFromFile(Paths.get("Obsidian-1.13.7.AppImage")))
        assertEquals("freebuff", AppImages.nameFromFile(Paths.get("freebuff-x86_64.AppImage")))
        assertEquals("Some App", AppImages.nameFromFile(Paths.get("Some App_v2.3-aarch64.appimage")))
        assertNull(AppImages.versionFromFile(Paths.get("freebuff-x86_64.AppImage")))
        assertEquals("outline_manager", AppImages.baseName("Outline Manager"))
        assertEquals("тест_приложение", AppImages.baseName("Тест: Приложение!"))
        assertEquals(VfsItemType.APPIMAGE, VfsEntry.detectType("Obsidian-1.13.7.AppImage", false))
        assertEquals(VfsItemType.APPIMAGE, VfsEntry.detectType("tool.appimage", false))
    }

    @Test
    fun `inspect, install, update and remove like gear lever`() {
        if (!tools) return
        val v1 = buildAppImage("1.0.0")
        val info = AppImages.inspect(v1)
        assertEquals(2, info.type)
        assertEquals(Files.size(Paths.get("/usr/bin/true")), info.offset)
        assertEquals("x86-64", info.arch)
        assertEquals("gzip", info.compression)
        assertEquals("wex-test.desktop", info.desktopFile)
        assertEquals("Wex Test App", info.name)
        assertEquals("1.0.0", info.version)
        assertEquals("Проверка установки", info.comment)
        assertEquals(listOf("Utility", "Development"), info.categories)
        assertFalse(info.terminal)
        assertEquals(".png", info.iconExt)
        assertContentEquals(png(), info.icon)
        assertTrue(info.fileCount >= 3)
        val icon = assertNotNull(runBlocking { AppImages.loadIcon(v1, Files.getLastModifiedTime(v1).toMillis()) })
        assertEquals(16, icon.width)
        assertNull(AppImages.installedFor(info))

        // Установка копией
        val app = AppImages.install(info, move = false)
        val folder = home.resolve("AppImages")
        assertEquals(folder.resolve("wex_test_app.appimage"), app.appImage)
        assertTrue(Files.exists(v1), "копия — исходник остаётся")
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(app.appImage))
        assertEquals(folder.resolve(".icons/wex_test_app.png"), app.icon)
        assertContentEquals(png(), Files.readAllBytes(app.icon!!))
        val desktop = DesktopEntry.parse(Files.readString(app.desktopFile))
        assertEquals(home.resolve(".local/share/applications/wex_test_app.desktop"), app.desktopFile)
        assertEquals("env DESKTOPINTEGRATION=1 ${app.appImage} --no-sandbox %U", desktop.get("Exec"))
        assertEquals(app.appImage.toString(), desktop.get("TryExec"))
        assertEquals(app.icon.toString(), desktop.get("Icon"))
        assertEquals("1.0.0", desktop.get("X-AppImage-Version"))
        assertEquals("Wex Test App", desktop.get("X-AppImage-Name"))
        assertEquals("${app.appImage} --new-window %U", desktop.get("Exec", "Desktop Action new"))
        assertEquals("Utility;Development;", desktop.get("Categories"))

        val other = assertNotNull(AppImages.installedFor(info))
        assertFalse(other.sameFile)
        assertEquals("1.0.0", other.version)
        assertTrue(assertNotNull(AppImages.installedFor(AppImages.inspect(app.appImage))).sameFile)

        // Обновление: тот же ярлык и файл, исходник перенесён
        val v2 = buildAppImage("2.0.0")
        val info2 = AppImages.inspect(v2)
        val installed = assertNotNull(AppImages.installedFor(info2))
        val updated = AppImages.install(info2, move = true, replace = installed)
        assertEquals(app.appImage, updated.appImage)
        assertEquals(app.desktopFile, updated.desktopFile)
        assertFalse(Files.exists(v2), "перенос — исходника больше нет")
        assertEquals("2.0.0", DesktopEntry.parse(Files.readString(updated.desktopFile)).get("X-AppImage-Version"))
        assertEquals("2.0.0", AppImages.inspect(updated.appImage).version)

        // Вторая установка рядом — имя с версией
        val v2b = buildAppImage("2.0.0", "copy.AppImage")
        val second = AppImages.install(AppImages.inspect(v2b), move = false)
        assertEquals(folder.resolve("wex_test_app_2_0_0.appimage"), second.appImage)
        assertEquals(home.resolve(".local/share/applications/wex_test_app_2_0_0.desktop"), second.desktopFile)

        // Распаковка — в новую папку рядом
        val extracted = AppImages.extract(AppImages.inspect(v2b), dir)
        assertEquals("copy", extracted.fileName.toString())
        assertTrue(Files.isRegularFile(extracted.resolve("wex-test.desktop")))
        assertEquals("#!/bin/sh\necho 2.0.0\n", Files.readString(extracted.resolve("AppRun")))
        assertEquals("copy (2)", AppImages.extract(AppImages.inspect(v2b), dir).fileName.toString())

        // Удаление из системы
        for (a in listOf(updated, second)) {
            assertTrue(AppImages.uninstall(a))
            assertFalse(Files.exists(a.desktopFile) || Files.exists(a.appImage) || Files.exists(a.icon!!))
        }
        assertNull(AppImages.installedFor(info))
    }

    @Test
    fun `tool output is capped`() {
        // Чужой образ: «значок» на сотни мегабайт не должен оказаться в памяти целиком
        val started = System.nanoTime()
        val e = kotlin.test.assertFailsWith<java.io.IOException> {
            AppImages.runTool(listOf("head", "-c", "100000000", "/dev/zero"), timeoutSec = 30, maxOutput = 1 shl 20)
        }
        assertTrue(e.message!!.contains("больше"), e.message)
        assertTrue(System.nanoTime() - started < 10_000_000_000L)
        val (code, out) = AppImages.runTool(listOf("head", "-c", "1000", "/dev/zero"), maxOutput = 1 shl 20)
        assertEquals(0, code)
        assertEquals(1000, out.size)
    }
}
