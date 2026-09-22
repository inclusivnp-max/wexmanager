package org.wexclient.utils

fun main() {
    val path = java.nio.file.Paths.get("/home/zapahtravki/AndroidStudioProjects/WEX/app/release/app-release.apk")
    println("File exists: " + java.nio.file.Files.exists(path))
}
