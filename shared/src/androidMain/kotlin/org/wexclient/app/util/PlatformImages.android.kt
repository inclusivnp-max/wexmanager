package org.wexclient.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream

actual object PlatformImages {
    actual fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    actual fun size(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    actual fun encodePng(image: ImageBitmap): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    actual fun svgPainter(bytes: ByteArray): Painter = SvgPainter(SVG.getFromString(String(bytes, Charsets.UTF_8)))

    actual fun fromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap =
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

    actual fun release(image: ImageBitmap) {
        runCatching { image.asAndroidBitmap().recycle() }
    }
}

actual fun ImageBitmap.frozen(): ImageBitmap = this

actual object ImageScale {
    actual fun decodeScaled(bytes: ByteArray, maxPx: Int, maxSourcePx: Int, maxSourcePixels: Long): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > maxSourcePx || h > maxSourcePx || w.toLong() * h > maxSourcePixels) return null
        // Сначала прореживание степенью двойки (декодер не держит полный размер), потом точное уменьшение
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, maxPx.toFloat() / maxOf(decoded.width, decoded.height))
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        out.asImageBitmap()
    }.getOrNull()
}

/** SVG через AndroidSVG: документ рисуется в размер области. */
private class SvgPainter(private val svg: SVG) : Painter() {
    override val intrinsicSize: Size = run {
        val w = svg.documentWidth
        val h = svg.documentHeight
        if (w > 0 && h > 0) Size(w, h) else Size.Unspecified
    }

    override fun DrawScope.onDraw() {
        svg.setDocumentWidth(size.width)
        svg.setDocumentHeight(size.height)
        drawIntoCanvas { svg.renderToCanvas(it.nativeCanvas) }
    }
}
