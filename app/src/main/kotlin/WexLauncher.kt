@file:JvmName("wexdesktop")

/**
 * Короткая точка входа. X11 делает WM_CLASS окна из имени главного класса: у «AppKt» получалось
 * «org-wexclient-app-AppKt», панель задач не находила по нему ярлык и показывала стандартный
 * значок Java. С этим именем WM_CLASS — «wexdesktop», ровно как ярлык wexdesktop.desktop.
 */
fun main(args: Array<String>) {
    org.wexclient.app.launchWex(args)
}
