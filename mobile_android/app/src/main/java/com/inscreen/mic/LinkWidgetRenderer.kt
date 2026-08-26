package com.inscreen.mic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

internal object LinkWidgetRenderer {
    fun oval(color: Int, widthDp: Int, heightDp: Int): Bitmap {
        val aspect = widthDp.coerceAtLeast(1).toFloat() / heightDp.coerceAtLeast(1)
        var bitmapWidth = if (aspect >= 1f) 360 else (240f * aspect).roundToInt()
        var bitmapHeight = if (aspect >= 1f) (360f / aspect).roundToInt() else 240
        bitmapWidth = bitmapWidth.coerceIn(48, 360)
        bitmapHeight = bitmapHeight.coerceIn(48, 240)

        return Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val inset = 1.5f
            canvas.drawOval(
                RectF(inset, inset, bitmapWidth - inset, bitmapHeight - inset),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
            )
        }
    }
}
