package com.inscreen.mic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

@SuppressLint("AppCompatCustomView")
internal class ZoomableImageView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(1f, 5f)
            val factor = next / scale
            scale = next
            transform.postScale(factor, factor, detector.focusX, detector.focusY)
            imageMatrix = transform
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (scale > 1f) resetZoom() else {
                scale = 2.5f
                transform.postScale(scale, scale, event.x, event.y)
                imageMatrix = transform
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post(::resetZoom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && scale > 1f) {
                transform.postTranslate(event.x - lastX, event.y - lastY)
                imageMatrix = transform
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        parent?.requestDisallowInterceptTouchEvent(scale > 1f || scaler.isInProgress)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun resetZoom() {
        scale = 1f
        transform.reset()
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return
        val fit = minOf(width.toFloat() / drawable.intrinsicWidth, height.toFloat() / drawable.intrinsicHeight)
        val dx = (width - drawable.intrinsicWidth * fit) / 2f
        val dy = (height - drawable.intrinsicHeight * fit) / 2f
        transform.setScale(fit, fit)
        transform.postTranslate(dx, dy)
        imageMatrix = transform
    }
}
