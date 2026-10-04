package io.github.yuubiz.folirel.backend.local.icon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import io.github.yuubiz.folirel.data.db.FeedIconEntity
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** A decoded icon, normalized to a small PNG. [width]/[height] are of the normalized image. */
class DecodedIcon(val png: ByteArray, val width: Int, val height: Int) {
    /** Width / height. */
    val aspect: Float get() = width.toFloat() / height

    /** A wide logo or a tall strip rather than an icon: usable only as a last resort. */
    val isBanner: Boolean get() = aspect > 2f || aspect < 0.5f
}

/** Turns downloaded bytes into a [DecodedIcon], or null for anything that is not a safely decodable image. */
fun interface IconDecoder {
    fun decode(bytes: ByteArray): DecodedIcon?
}

/**
 * Android's own decoder: PNG, JPEG, GIF (first frame), WebP, BMP and ICO. SVG and anything unknown decode to null
 * and the lookup moves on. Huge images are sub-sampled while decoding (never fully expanded) and the result is
 * scaled down to at most [FeedIconEntity.MAX_SIDE_PX] on the long side and stored as PNG.
 */
class AndroidIconDecoder : IconDecoder {
    override fun decode(bytes: ByteArray): DecodedIcon? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || w > MAX_SOURCE_SIDE || h > MAX_SOURCE_SIDE) return null
            var sample = 1
            while (max(w, h) / (sample * 2) >= FeedIconEntity.MAX_SIDE_PX * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scaled = scaleToFit(decoded)
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            DecodedIcon(out.toByteArray(), scaled.width, scaled.height)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= FeedIconEntity.MAX_SIDE_PX) return bitmap
        val ratio = FeedIconEntity.MAX_SIDE_PX.toFloat() / longSide
        return bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), filter = true)
    }

    private companion object {
        /** Anything larger is not an icon (and could exhaust memory while decoding). */
        const val MAX_SOURCE_SIDE = 4096
    }
}
