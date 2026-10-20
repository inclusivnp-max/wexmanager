@file:JvmName("wexdesktop")

/** Имя класса задаёт WM_CLASS в X11 и должно совпадать с wexdesktop.desktop. */
fun main(args: Array<String>) {
    org.wexclient.app.launchWex(args)
}
