package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.R
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Software-bitmap [BitmapLoader] backed by Coil so lock-screen and MediaStyle notification
 * artwork benefits from Coil's memory/disk cache and never produces hardware bitmaps.
 */
@OptIn(UnstableApi::class)
internal class CoilBitmapLoader(context: Context) : BitmapLoader {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    ?: fallbackLogoBitmap()
                    ?: throw IllegalArgumentException("Could not decode audio artwork bytes")
                future.set(decoded)
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val request = ImageRequest.Builder(appContext)
                    .data(uri)
                    .size(512, 512)
                    .allowHardware(false)
                    .build()
                val result = appContext.imageLoader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable
                val bitmap = when {
                    drawable is BitmapDrawable && drawable.bitmap != null -> {
                        val raw = drawable.bitmap
                        if (raw.config == Bitmap.Config.HARDWARE) {
                            raw.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            raw
                        }
                    }
                    drawable != null -> {
                        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
                        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
                        Bitmap.createBitmap(w.coerceAtMost(512), h.coerceAtMost(512), Bitmap.Config.ARGB_8888).also { bmp ->
                            val canvas = Canvas(bmp)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                        }
                    }
                    else -> fallbackLogoBitmap()
                }
                if (bitmap != null) {
                    future.set(bitmap)
                } else {
                    future.setException(IllegalStateException("Unable to load artwork bitmap for $uri"))
                }
            } catch (t: Throwable) {
                val fallback = fallbackLogoBitmap()
                if (fallback != null) {
                    future.set(fallback)
                } else {
                    future.setException(t)
                }
            }
        }
        return future
    }

    private fun fallbackLogoBitmap(): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(appContext, R.drawable.ic_pulsewave_logo) ?: return null
            if (drawable is BitmapDrawable && drawable.bitmap != null && drawable.bitmap.config != Bitmap.Config.HARDWARE) {
                return drawable.bitmap
            }
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
            Bitmap.createBitmap(w.coerceAtMost(512), h.coerceAtMost(512), Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        scope.cancel()
    }
}
