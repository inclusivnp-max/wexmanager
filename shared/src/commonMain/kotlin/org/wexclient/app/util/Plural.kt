package org.wexclient.app.util

/** Русские числительные: 1 элемент, 2 элемента, 5 элементов. */
object Plural {
    fun ru(n: Long, one: String, few: String, many: String): String {
        val abs = if (n < 0) -n else n
        val mod10 = abs % 10
        val mod100 = abs % 100
        return when {
            mod10 == 1L && mod100 != 11L -> one
            mod10 in 2L..4L && mod100 !in 12L..14L -> few
            else -> many
        }
    }

    /** «3 элемента» */
    fun items(n: Int): String = "$n " + ru(n.toLong(), "элемент", "элемента", "элементов")

    /** «21 файл» */
    fun files(n: Int): String = "$n " + ru(n.toLong(), "файл", "файла", "файлов")
}
