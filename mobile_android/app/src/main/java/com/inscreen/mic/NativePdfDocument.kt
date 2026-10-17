package com.inscreen.mic

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

/** PDFium supplied by Android. All operations run on the viewer's single worker. */
internal class NativePdfDocument(file: File) : Closeable {
    private val renderer: PdfRenderer
    init {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { renderer = PdfRenderer(descriptor) }
        catch (error: Exception) { descriptor.close(); throw error }
    }
    val pageCount: Int get() = renderer.pageCount
    fun render(index: Int, requestedWidth: Int): Bitmap {
        require(index in 0 until pageCount)
        return renderer.openPage(index).use { page ->
            val ratio = page.height.toDouble() / page.width.coerceAtLeast(1)
            // Bound allocations even for unusually tall pages or large screens.
            val width = minOf(requestedWidth.coerceIn(1, 2400), sqrt(4_000_000.0 / ratio).toInt().coerceAtLeast(1))
            val height = (width * ratio).toInt().coerceIn(1, 4_000_000)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
    }
    override fun close() = renderer.close()
}
