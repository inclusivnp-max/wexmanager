package org.wexclient.app.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbManagerTest {

    @Test
    fun `devices list is parsed with states and models`() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            192.168.1.5:5555       device product:husky model:Pixel_8_Pro device:husky transport_id:3
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
            R58M123ABC             unauthorized usb:1-1 transport_id:2
            0123456789ABCDEF       no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:1-2 transport_id:4
            adb-XYZ._adb-tls-connect._tcp offline transport_id:5

        """.trimIndent()
        val devices = AdbManager.parseDevices(out)
        assertEquals(5, devices.size)
        assertEquals("192.168.1.5:5555", devices[0].serial)
        assertEquals(AdbDeviceState.READY, devices[0].state)
        assertEquals("Pixel 8 Pro", devices[0].model)
        assertTrue(devices[0].isWireless)
        assertTrue(devices[1].isEmulator)
        assertEquals(AdbDeviceState.UNAUTHORIZED, devices[2].state)
        assertEquals(AdbDeviceState.NO_PERMISSIONS, devices[3].state)
        assertEquals(AdbDeviceState.OFFLINE, devices[4].state)
        assertTrue(devices[4].isWireless)
        assertTrue(AdbManager.parseDevices("List of devices attached\n\n").isEmpty())
    }

    @Test
    fun `display names look like on the box`() {
        val usb = AdbDevice("X", "device", model = "Pixel 9 Pro")
        assertEquals(
            "Google Pixel 9 Pro",
            AdbManager.displayName(usb, mapOf("ro.product.manufacturer" to "Google", "ro.product.model" to "Pixel 9 Pro"))
        )
        assertEquals(
            "Redmi Note 12 Pro",
            AdbManager.displayName(usb, mapOf(
                "ro.product.manufacturer" to "Xiaomi", "ro.product.brand" to "Redmi",
                "ro.product.model" to "22101316G", "ro.product.marketname" to "Redmi Note 12 Pro"
            ))
        )
        assertEquals(
            "Samsung SM-S918B",
            AdbManager.displayName(usb, mapOf("ro.product.manufacturer" to "samsung", "ro.product.model" to "SM-S918B"))
        )
        assertEquals(
            "OnePlus 12",
            AdbManager.displayName(usb, mapOf("ro.product.manufacturer" to "OnePlus", "ro.product.model" to "CPH2581", "ro.product.marketname" to "OnePlus 12"))
        )
        assertEquals(
            "Nothing Phone (2)",
            AdbManager.displayName(usb, mapOf("ro.product.manufacturer" to "Nothing", "ro.product.model" to "A065", "ro.product.marketname" to "Phone (2)"))
        )
        val emu = AdbDevice("emulator-5554", "device", model = "sdk gphone64 x86 64")
        assertTrue(AdbManager.displayName(emu, mapOf("ro.product.manufacturer" to "Google", "ro.product.model" to "sdk_gphone64_x86_64")).startsWith("Эмулятор"))
    }

    @Test
    fun `install output is explained`() {
        val ok = AdbManager.parseInstallOutput("Performing Streamed Install\nSuccess\n", 0)
        assertTrue(ok.success)
        assertNull(ok.suggestion)

        val incompatible = AdbManager.parseInstallOutput(
            "Performing Streamed Install\nadb: failed to install app.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.x signatures do not match newer version; ignoring!]\n", 1
        )
        assertFalse(incompatible.success)
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", incompatible.code)
        assertEquals(InstallSuggestion.UNINSTALL_AND_RETRY, incompatible.suggestion)

        val unsigned = AdbManager.parseInstallOutput(
            "adb: failed to install a.apk: Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed to collect certificates from /data/app/vmdl.tmp/base.apk]", 1
        )
        assertEquals(InstallSuggestion.SIGN, unsigned.suggestion)

        val arsc = AdbManager.parseInstallOutput(
            "adb: failed to install a.apk: Failure [-124: Failed parse during installPackageLI: Targeting R+ (version 30 and above) requires the resources.arsc of installed APKs to be stored uncompressed and aligned on a 4-byte boundary]", 1
        )
        assertEquals("INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED", arsc.code)
        assertEquals(InstallSuggestion.SIGN, arsc.suggestion)

        val downgrade = AdbManager.parseInstallOutput("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", 1)
        assertEquals(InstallSuggestion.ALLOW_DOWNGRADE, downgrade.suggestion)

        val unauthorized = AdbManager.parseInstallOutput("adb: device unauthorized.\nThis adb server's \$ADB_VENDOR_KEYS is not set", 1)
        assertEquals("DEVICE_UNAUTHORIZED", unauthorized.code)
        assertTrue(unauthorized.explanation!!.contains("отладку"))
    }

    @Test
    fun `package names from untrusted apks cannot reach the device shell`() {
        assertTrue(AdbManager.isValidPackageName("com.example.app"))
        assertTrue(AdbManager.isValidPackageName("org.wexclient.app_2"))
        assertTrue(AdbManager.isValidPackageName("android"))
        for (bad in listOf("", "com.x; rm -rf /sdcard", "com.x'\$(reboot)'", "com.x\nreboot", "1com.x", "com..x", "com.x.", ".com", "com x", "com.x|sh")) {
            assertFalse(AdbManager.isValidPackageName(bad), bad)
        }
        // Проверка — до вызова adb: даже без adb на машине отказ именно из-за имени
        val e = kotlin.test.assertFailsWith<java.io.IOException> { AdbManager.launch("serial", "com.x;reboot") }
        assertTrue(e.message!!.startsWith("Недопустимое имя пакета"), e.message)
        kotlin.test.assertFailsWith<java.io.IOException> { AdbManager.uninstall("serial", "x && reboot") }
        assertNull(AdbManager.installedVersion("serial", "x\$(id)"))
    }

    @Test
    fun `obb files from xapk stay inside the obb folder`() {
        assertEquals("/sdcard/Android/obb/com.game/main.1.com.game.obb", AdbManager.obbRemotePath("Android/obb/com.game/main.1.com.game.obb"))
        assertEquals("/sdcard/Android/obb/com.game/patch.obb", AdbManager.obbRemotePath("xapk\\Android\\obb\\com.game\\patch.obb"))
        assertNull(AdbManager.obbRemotePath("Android/obb/../../DCIM/photo.obb"))
        assertNull(AdbManager.obbRemotePath("Android/obb/com.game/../../../DCIM/x.obb"))
        assertNull(AdbManager.obbRemotePath("Android/obb/main.obb"))
        assertNull(AdbManager.obbRemotePath("Android/obb/com.game//x.obb"))
        assertNull(AdbManager.obbRemotePath("Android/obb/bad name/x.obb"))

        val dir = java.nio.file.Files.createTempDirectory("wex_xapk")
        try {
            val xapk = dir.resolve("game.xapk").toFile()
            java.util.zip.ZipOutputStream(xapk.outputStream()).use { zip ->
                for (name in listOf("base.apk", "config.arm64_v8a.apk", "Android/obb/com.game/main.obb", "Android/obb/../../evil.obb")) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
            val work = dir.resolve("work").toFile().also { it.mkdirs() }
            val (apks, obbs) = AdbManager.extractBundle(xapk, work)
            assertEquals(listOf("base.apk", "config.arm64_v8a.apk"), apks.map { it.name.substringAfter('_') })
            assertEquals(listOf("/sdcard/Android/obb/com.game/main.obb"), obbs.map { it.second })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `набор bundletool ставится частями без standalones`() {
        val dir = java.nio.file.Files.createTempDirectory("wex_apks")
        try {
            val apks = dir.resolve("app.apks").toFile()
            java.util.zip.ZipOutputStream(apks.outputStream()).use { zip ->
                for (name in listOf("toc.pb", "splits/base-master.apk", "splits/base-arm64_v8a.apk", "standalones/standalone-arm64_v8a.apk")) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
            val work = dir.resolve("work").toFile().also { it.mkdirs() }
            val (parts, _) = AdbManager.extractBundle(apks, work)
            assertEquals(setOf("base-master.apk", "base-arm64_v8a.apk"), parts.map { it.name.substringAfter('_') }.toSet())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
