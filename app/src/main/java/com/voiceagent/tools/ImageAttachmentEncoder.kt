package com.voiceagent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

data class EncodedImageAttachment(
    val base64: String,
    val mimeType: String,
    val width: Int,
    val height: Int
)

object ImageAttachmentEncoder {
    private const val MAX_DIMENSION_PX = 1024
    private const val JPEG_QUALITY = 82

    suspend fun encodeUri(context: Context, uri: Uri): EncodedImageAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) return@runCatching null

            var sample = 1
            while (max(srcW / sample, srcH / sample) > MAX_DIMENSION_PX * 2) {
                sample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return@runCatching null

            val scaled = scaleDownIfNeeded(decoded, MAX_DIMENSION_PX)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val bytes = baos.toByteArray()
            val outW = scaled.width
            val outH = scaled.height
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()

            EncodedImageAttachment(
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                mimeType = "image/jpeg",
                width = outW,
                height = outH
            )
        }.getOrNull()
    }

    internal fun computeScaledDimensions(srcW: Int, srcH: Int, maxEdge: Int): Pair<Int, Int> {
        val largest = max(srcW, srcH)
        if (largest <= maxEdge || largest <= 0) return srcW to srcH
        val ratio = maxEdge.toFloat() / largest.toFloat()
        return ((srcW * ratio).toInt().coerceAtLeast(1)) to ((srcH * ratio).toInt().coerceAtLeast(1))
    }

    private fun scaleDownIfNeeded(bmp: Bitmap, maxEdge: Int): Bitmap {
        val (targetW, targetH) = computeScaledDimensions(bmp.width, bmp.height, maxEdge)
        if (targetW == bmp.width && targetH == bmp.height) return bmp
        val m = Matrix().apply {
            postScale(targetW.toFloat() / bmp.width, targetH.toFloat() / bmp.height)
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
