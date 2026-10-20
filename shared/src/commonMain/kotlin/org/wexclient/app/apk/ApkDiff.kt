package org.wexclient.app.apk

import org.wexclient.app.util.readUpTo

import org.wexclient.app.axml.ManifestInfo
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile

/** Что стало с записью при переходе от старого APK к новому. */
enum class DiffKind {
    ADDED,
    REMOVED,
    CHANGED,
    SAME
}

/** Одна запись внутри APK: размер и контрольная сумма берутся из оглавления zip — файл не распаковывается. */
data class ApkEntry(
    val name: String,
    val size: Long,
    val compressed: Long,
    val crc: Long,
    val directory: Boolean
)

/** Строка сравнения содержимого двух APK. */
data class ApkFileChange(
    val name: String,
    val kind: DiffKind,
    val oldSize: Long,
    val newSize: Long
) {
    /** Насколько файл прибавил (или убавил) в весе. */
    val delta: Long get() = newSize - oldSize
}

/** Изменившееся поле манифеста: было — стало. */
data class ManifestChange(val label: String, val oldValue: String, val newValue: String)

/** Чем отличаются манифесты двух APK. */
data class ManifestDiff(
    val fields: List<ManifestChange>,
    val addedPermissions: List<String>,
    val removedPermissions: List<String>,
    val addedActivities: List<String>,
    val removedActivities: List<String>
) {
    val empty: Boolean
        get() = fields.isEmpty() && addedPermissions.isEmpty() && removedPermissions.isEmpty() &&
            addedActivities.isEmpty() && removedActivities.isEmpty()
}

/** Сводка по сравнению: сколько чего изменилось и как изменился вес. */
data class ApkDiffSummary(
    val added: Int,
    val removed: Int,
    val changed: Int,
    val same: Int,
    val oldTotal: Long,
    val newTotal: Long,
    val oldFileSize: Long,
    val newFileSize: Long
) {
    val hasChanges: Boolean get() = added > 0 || removed > 0 || changed > 0
}

/** Сравнение записей APK по размеру и CRC из оглавления ZIP. */
object ApkDiff {

    /** Все записи архива по имени. Ошибка чтения отличается от пустого архива. */
    @Throws(IOException::class)
    fun entries(path: Path): Map<String, ApkEntry> {
        return try {
            ZipFile(path.toFile()).use { zip ->
                val result = LinkedHashMap<String, ApkEntry>()
                val list = zip.entries()
                while (list.hasMoreElements()) {
                    val e = list.nextElement()
                    result[e.name] = ApkEntry(
                        name = e.name,
                        size = if (e.size >= 0) e.size else 0L,
                        compressed = if (e.compressedSize >= 0) e.compressedSize else 0L,
                        crc = e.crc,
                        directory = e.isDirectory
                    )
                }
                result
            }
        } catch (e: IOException) {
            throw IOException("Не удалось прочитать APK «${path.fileName}»: ${e.message}", e)
        }
    }

    /**
     * Сравнение содержимого. Папки пропускаем: в APK они ничего не весят и только засоряют список.
     * Порядок: сначала изменённые, потом добавленные и удалённые, затем совпавшие — по имени.
     */
    fun compare(old: Map<String, ApkEntry>, new: Map<String, ApkEntry>): List<ApkFileChange> {
        val names = (old.keys + new.keys).toSortedSet()
        val result = ArrayList<ApkFileChange>(names.size)
        for (name in names) {
            val o = old[name]
            val n = new[name]
            if (o?.directory == true || n?.directory == true) continue
            val kind = when {
                o == null -> DiffKind.ADDED
                n == null -> DiffKind.REMOVED
                o.crc != n.crc || o.size != n.size -> DiffKind.CHANGED
                else -> DiffKind.SAME
            }
            result += ApkFileChange(name, kind, o?.size ?: 0L, n?.size ?: 0L)
        }
        return result.sortedWith(
            compareBy({ order(it.kind) }, { it.name })
        )
    }

    private fun order(kind: DiffKind): Int = when (kind) {
        DiffKind.CHANGED -> 0
        DiffKind.ADDED -> 1
        DiffKind.REMOVED -> 2
        DiffKind.SAME -> 3
    }

    fun summarize(
        changes: List<ApkFileChange>,
        oldFileSize: Long,
        newFileSize: Long
    ): ApkDiffSummary = ApkDiffSummary(
        added = changes.count { it.kind == DiffKind.ADDED },
        removed = changes.count { it.kind == DiffKind.REMOVED },
        changed = changes.count { it.kind == DiffKind.CHANGED },
        same = changes.count { it.kind == DiffKind.SAME },
        oldTotal = changes.sumOf { it.oldSize },
        newTotal = changes.sumOf { it.newSize },
        oldFileSize = oldFileSize,
        newFileSize = newFileSize
    )

    /** Содержимое записи. null — записи нет, она не читается или больше [limit]. */
    fun read(path: Path, entry: String, limit: Int = 16 * 1024 * 1024): ByteArray? = runCatching {
        ZipFile(path.toFile()).use { zip ->
            val e = zip.getEntry(entry) ?: return null
            if (e.size > limit) return null
            // Размер в оглавлении может врать (zip-бомба): распаковываем не больше limit + 1 байта
            val cap = minOf(limit, Int.MAX_VALUE - 16)
            val bytes = zip.getInputStream(e).use { it.readUpTo(cap + 1) }
            if (bytes.size > cap) null else bytes
        }
    }.getOrNull()

    /**
     * Разница манифестов: сначала поля, потом списки разрешений и экранов.
     * Поля показываем только изменившиеся — совпавшие в сравнении не интересны.
     */
    fun compareManifests(old: ManifestInfo, new: ManifestInfo): ManifestDiff {
        val fields = buildList {
            fun field(label: String, a: String, b: String) {
                if (a != b) add(ManifestChange(label, a, b))
            }
            field("Пакет", old.packageName, new.packageName)
            field("Версия", old.versionName, new.versionName)
            field("Код версии", old.versionCode, new.versionCode)
            field("minSdk", old.minSdkVersion, new.minSdkVersion)
            field("targetSdk", old.targetSdkVersion, new.targetSdkVersion)
            field("Главный экран", old.launcherActivity.orEmpty(), new.launcherActivity.orEmpty())
        }
        return ManifestDiff(
            fields = fields,
            addedPermissions = (new.permissions - old.permissions.toSet()).sorted(),
            removedPermissions = (old.permissions - new.permissions.toSet()).sorted(),
            addedActivities = (new.activities - old.activities.toSet()).sorted(),
            removedActivities = (old.activities - new.activities.toSet()).sorted()
        )
    }

    /** Имена всех dex внутри APK по порядку: classes.dex, classes2.dex, … */
    fun dexNames(entries: Map<String, ApkEntry>): List<String> =
        entries.keys.filter { it.matches(DEX_NAME) }.sortedBy { name ->
            // classes.dex идёт первым, дальше по номеру
            name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1
        }

    private val DEX_NAME = Regex("""^classes\d*\.dex$""")
}
