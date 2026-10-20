package org.wexclient.app.util

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Где WEX держит своё и где у пользователя «дом».
 *
 * Десктоп: всё от `user.home` — `~/.config/wexdesktop`, `~/.cache/wexdesktop`, дом — `~`. Значения
 * читаются при каждом обращении: тесты подменяют `user.home`, и настройки уходят в их папку.
 * Android: настройки и кэш — в закрытых папках приложения, дом — общее хранилище; задаётся
 * в [useAndroidDirs] при старте, до первого обращения к настройкам.
 */
object AppDirs {
    private class Roots(val userHome: Path, val configRoot: Path, val cacheRoot: Path, val trash: Path?)

    @Volatile
    private var fixed: Roots? = null

    /** Дом пользователя: с него открываются панели, от него считаются «~» и закладка «Домашняя». */
    val userHome: Path get() = fixed?.userHome ?: Paths.get(System.getProperty("user.home"))

    /** Общая папка настроек программ (XDG_CONFIG_HOME): здесь же лежат чужие файлы вроде noctalia/colors.json. */
    val configRoot: Path get() = fixed?.configRoot ?: userHome.resolve(".config")

    /** Общая папка кэша программ (XDG_CACHE_HOME). */
    val cacheRoot: Path get() = fixed?.cacheRoot ?: userHome.resolve(".cache")

    /** Настройки WEX: тема, закладки, вкладки, ключи подписи. */
    val config: Path get() = configRoot.resolve("wexdesktop")

    /** Кэш WEX: миниатюры, распакованные архивы, временные файлы декомпиляторов. */
    val cache: Path get() = cacheRoot.resolve("wexdesktop")

    /** Своя корзина платформы (Android); null — по стандарту freedesktop ($XDG_DATA_HOME/Trash). */
    val trashOverride: Path? get() = fixed?.trash

    fun useAndroidDirs(userHome: Path, configRoot: Path, cacheRoot: Path, trash: Path) {
        fixed = Roots(userHome, configRoot, cacheRoot, trash)
    }
}
