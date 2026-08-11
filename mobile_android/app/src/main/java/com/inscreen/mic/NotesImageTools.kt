package com.inscreen.mic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal object NotesImageTools {
    fun decode(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val wantedWidth = max(1, targetWidth)
        val wantedHeight = max(1, targetHeight)
        while (bounds.outWidth / (sample * 2) >= wantedWidth && bounds.outHeight / (sample * 2) >= wantedHeight) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    fun writeMarkerJpeg(source: File, destination: File, maxBytes: Int = MARKER_TARGET_BYTES): Boolean {
        var working = decode(source, MARKER_MAX_DIMENSION, MARKER_MAX_DIMENSION) ?: return false
        try {
            val largest = max(working.width, working.height)
            if (largest > MARKER_MAX_DIMENSION) {
                val ratio = MARKER_MAX_DIMENSION.toFloat() / largest
                val scaled = Bitmap.createScaledBitmap(
                    working,
                    max(1, (working.width * ratio).roundToInt()),
                    max(1, (working.height * ratio).roundToInt()),
                    true,
                )
                if (scaled !== working) working.recycle()
                working = scaled
            }
            while (working.width >= 800 && working.height >= 800) {
                for (quality in 90 downTo 60 step 5) {
                    val output = ByteArrayOutputStream()
                    if (!working.compress(Bitmap.CompressFormat.JPEG, quality, output)) return false
                    val bytes = output.toByteArray()
                    if (bytes.size <= maxBytes) {
                        destination.outputStream().buffered().use { it.write(bytes) }
                        return true
                    }
                }
                val smaller = Bitmap.createScaledBitmap(
                    working,
                    max(1, (working.width * 0.85f).roundToInt()),
                    max(1, (working.height * 0.85f).roundToInt()),
                    true,
                )
                working.recycle()
                working = smaller
            }
            return false
        } finally {
            working.recycle()
        }
    }

    const val MARKER_MAX_DIMENSION = 2400
    const val MARKER_TARGET_BYTES = 3_750 * 1024
}
