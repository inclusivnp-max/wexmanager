package org.wexclient.app.vfs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Android: миниатюра EXIF, BitmapFactory с прореживанием, кадр видео — MediaMetadataRetriever. */
internal actual object ThumbnailCodec {
    actual fun image(path: Path, vfsManager: VfsManager?, maxBytes: Long): ImageBitmap? {
        val size = THUMBNAIL_SIZE
        val local = path.fileSystem === FileSystems.getDefault()
        if ((runCatching { Files.size(path) }.getOrDefault(0L)) > maxBytes) return null
        if (local) {
            val file = path.toFile()
            // Готовая миниатюра из EXIF — единицы миллисекунд вместо декодирования снимка целиком
            runCatching { ExifInterface(file.absolutePath).thumbnailBitmap }.getOrNull()
                ?.takeIf { it.width >= size && it.height >= size / 2 }
                ?.let { return crop(it, size) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val decoded = BitmapFactory.decodeFile(file.absolutePath, sampled(bounds, size)) ?: return null
            return crop(decoded, size)
        }
        val bytes = vfsManager?.readBytes(path) ?: Files.readAllBytes(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled(bounds, size)) ?: return null
        return crop(decoded, size)
    }

    actual fun video(path: Path): ImageBitmap? {
        val size = THUMBNAIL_SIZE
        if (path.fileSystem !== FileSystems.getDefault() || !Files.exists(path)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path.toFile().absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            crop(frame, size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Прореживание степенью двойки: 24-мегапиксельный снимок не занимает 96 МБ ради значка 64×64. */
    private fun sampled(bounds: BitmapFactory.Options, size: Int) = BitmapFactory.Options().apply {
        var s = 1
        val side = minOf(bounds.outWidth, bounds.outHeight)
        while (side / (s * 2) >= size * 2) s *= 2
        inSampleSize = s
    }

    /** Обрезка по центру с сохранением пропорций. */
    private fun crop(src: Bitmap, size: Int): ImageBitmap {
        val scale = maxOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        val dst = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(dst).drawBitmap(
            src, null,
            Rect((size - nw) / 2, (size - nh) / 2, (size - nw) / 2 + nw, (size - nh) / 2 + nh),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        if (src !== dst) src.recycle()
        return dst.asImageBitmap()
    }
}
