package com.voiceagent.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class EncodedSoMScreenshot(
    val base64: String,
    val mimeType: String,
    val width: Int,
    val height: Int
)

object SetOfMarksRenderer {
    fun annotateAndEncodeWebP(
        source: Bitmap,
        nodes: List<UiNodeRef>,
        targetWidth: Int = 720
    ): EncodedSoMScreenshot {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val scale = if (srcW > targetWidth) targetWidth.toFloat() / srcW.toFloat() else 1f
        val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(source, dstW, dstH, true)
        val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled !== source) scaled.recycle()

        val canvas = Canvas(mutable)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(210, 255, 82, 82)
        }
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 211, 47, 47)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        for (node in nodes) {
            if (!node.clickable && !node.editable && !node.scrollable && node.label.isBlank()) continue
            val l = (node.bounds.left * scale).coerceIn(0f, (dstW - 4).toFloat())
            val t = (node.bounds.top * scale).coerceIn(0f, (dstH - 4).toFloat())
            val r = (node.bounds.right * scale).coerceIn(l + 4f, dstW.toFloat())
            val b = (node.bounds.bottom * scale).coerceIn(t + 4f, dstH.toFloat())
            canvas.drawRect(l, t, r, b, boxPaint)

            val tag = node.refId.toString()
            val textW = textPaint.measureText(tag)
            val pillW = textW + 12f
            val pillH = 24f
            val px = l.coerceIn(0f, (dstW - pillW).coerceAtLeast(0f))
            val py = t.coerceIn(0f, (dstH - pillH).coerceAtLeast(0f))
            canvas.drawRoundRect(RectF(px, py, px + pillW, py + pillH), 6f, 6f, pillPaint)
            canvas.drawText(tag, px + 6f, py + 18f, textPaint)
        }

        val baos = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        mutable.compress(format, 78, baos)
        mutable.recycle()

        val bytes = baos.toByteArray()
        return EncodedSoMScreenshot(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mimeType = "image/webp",
            width = dstW,
            height = dstH
        )
    }
}
