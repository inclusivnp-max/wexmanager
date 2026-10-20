package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Пакетное переименование без потерь: сначала проверка (недопустимые имена, одинаковые новые
 * имена, занятые имена), затем в два шага через временные имена — так проходят и обмены
 * «a → b, b → a», и перенумерация «01 → 02, 02 → 03». При ошибке сделанное откатывается.
 */
object BatchRenamer {
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "пустое имя"
        '/' in name || name.contains(Char(0)) -> "«/» в имени"
        name == "." || name == ".." -> "недопустимое имя"
        name.toByteArray().size > 255 -> "слишком длинное имя"
        else -> null
    }

    /** Что мешает переименованию, или null. [items] — (путь, новое имя). */
    fun problem(items: List<Pair<Path, String>>): String? {
        val changing = items.filter { (p, n) -> p.fileName?.toString()?.trimEnd('/') != n }
        for ((p, n) in changing) nameProblem(n)?.let { return "«${p.fileName}»: $it" }
        val finalPaths = HashMap<Path, Path>()
        for ((p, n) in items) {
            val target = (p.parent ?: return "«$p»: нет папки").resolve(n).normalize()
            finalPaths.put(target, p)?.let { other -> return "«${other.fileName}» и «${p.fileName}» получат одно имя «$n»" }
        }
        val sources = items.mapTo(HashSet()) { it.first.normalize() }
        for ((p, n) in changing) {
            val target = p.parent!!.resolve(n).normalize()
            // Занято тем, что само не переименовывается (свои же — освободятся)
            if (target !in sources && Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                !target.toString().equals(p.normalize().toString(), ignoreCase = true)
            ) {
                return "«$n» уже есть в папке"
            }
        }
        return null
    }

    /** Переименовать; возвращает (было → стало) для отмены. */
    fun apply(vfs: VfsManager, items: List<Pair<Path, String>>): List<Pair<Path, Path>> {
        problem(items)?.let { throw IllegalArgumentException(it) }
        val changing = items.map { (p, n) -> vfs.live(p) to n }.filter { (p, n) -> p.fileName?.toString()?.trimEnd('/') != n }
        if (changing.isEmpty()) return emptyList()
        val stamp = System.nanoTime()
        val temps = ArrayList<Triple<Path, Path, String>>() // исходный, временный, новое имя
        val done = ArrayList<Pair<Path, Path>>()
        try {
            vfs.batch {
                changing.forEachIndexed { i, (p, n) ->
                    val temp = vfs.renameEntry(p, ".wex-rename-$stamp-$i")
                    temps.add(Triple(p, temp, n))
                }
                for ((orig, temp, n) in temps) {
                    val finalPath = vfs.renameEntry(vfs.live(temp), n)
                    done.add(orig to finalPath)
                }
            }
        } catch (e: Exception) {
            // Откат: готовые — обратно, временные — на исходные имена
            runCatching {
                vfs.batch {
                    for ((orig, final) in done.asReversed()) {
                        runCatching { vfs.renameEntry(vfs.live(final), ".wex-back-$stamp-${orig.fileName}") }
                    }
                    for ((orig, temp, _) in temps) {
                        val live = vfs.live(temp)
                        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) runCatching { vfs.renameEntry(live, orig.fileName.toString()) }
                    }
                    for ((orig, _) in done) {
                        val back = vfs.live(orig).resolveSibling(".wex-back-$stamp-${orig.fileName}")
                        if (Files.exists(back, LinkOption.NOFOLLOW_LINKS)) runCatching { vfs.renameEntry(back, orig.fileName.toString()) }
                    }
                }
            }
            throw e
        }
        return done
    }
}
