package com.example.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

object AlbumColorExtractor {
    suspend fun extractAccentColor(context: Context, imageUrl: String): Color? =
        withContext(Dispatchers.IO) {
            if (imageUrl.isBlank()) return@withContext null
            try {
                val bitmap = loadSmallBitmap(context, imageUrl) ?: return@withContext null
                sampleVibrantColor(bitmap)
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun loadSmallBitmap(context: Context, imageUrl: String): Bitmap? {
        if (imageUrl.startsWith("file://")) {
            val path = Uri.parse(imageUrl).path
            if (!path.isNullOrBlank() && File(path).exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, opts)?.let { return it }
            }
        }
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(64)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return result.drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
    }

    private fun sampleVibrantColor(bitmap: Bitmap): Color? {
        var bestScore = -1f
        var bestColor: Int? = null
        var fallbackHueScore = -1f
        var fallbackHueColor: Int? = null

        val stepX = (bitmap.width / 14).coerceAtLeast(1)
        val stepY = (bitmap.height / 14).coerceAtLeast(1)
        val hsv = FloatArray(3)

        for (x in 0 until bitmap.width step stepX) {
            for (y in 0 until bitmap.height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha < 180) continue
                android.graphics.Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val brightness = hsv[2]
                if (brightness in 0.28f..0.96f && saturation > 0.24f) {
                    val score = saturation * 1.5f + brightness * 0.5f
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = pixel
                    }
                } else if (brightness > 0.12f && saturation > 0.08f) {
                    val score = saturation + brightness * 0.35f
                    if (score > fallbackHueScore) {
                        fallbackHueScore = score
                        fallbackHueColor = pixel
                    }
                }
            }
        }

        if (bestColor != null) return Color(bestColor)
        if (fallbackHueColor != null) {
            android.graphics.Color.colorToHSV(fallbackHueColor, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.62f)
            hsv[2] = hsv[2].coerceAtLeast(0.86f)
            return Color(android.graphics.Color.HSVToColor(hsv))
        }
        return null
    }

    fun deriveTrackAccentFallback(track: Track): Color {
        val seed = "${track.id}|${track.title}|${track.artist}|${track.genre}".hashCode()
        val paletteHues = floatArrayOf(168f, 265f, 338f, 198f, 34f, 292f, 145f, 18f)
        val hue = paletteHues[abs(seed) % paletteHues.size]
        val sat = 0.76f + (abs(seed shr 4) % 18) * 0.01f
        val brightness = 0.92f
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceAtMost(0.95f), brightness))
        return Color(argb)
    }
}
