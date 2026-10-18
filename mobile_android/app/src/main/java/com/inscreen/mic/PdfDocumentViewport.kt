package com.inscreen.mic

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One zoom level and horizontal offset for the whole, vertically recycled document. */
internal class PdfDocumentViewport(context: Context, val pages: RecyclerView) : HorizontalScrollView(context) {
    var zoom = 1f
        private set
    var documentWidth = 0
        private set
    var onDocumentWidthChanged: (() -> Unit)? = null
    private var pinching = false
    private var doubleTapped = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            doubleTapped = true
            zoomAt(if (zoom > 1.05f) 1f else 2.5f, event.x, event.y)
            return true
        }
    })

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(pages, LayoutParams(1, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) resize(w)
    }

    private fun resize(viewportWidth: Int) {
        documentWidth = (viewportWidth * zoom).toInt().coerceAtLeast(1)
        pages.layoutParams = pages.layoutParams.apply { width = documentWidth }
        onDocumentWidthChanged?.invoke()
    }

    fun zoomAt(value: Float, focusX: Float, focusY: Float) {
        if (width == 0) return
        val next = value.coerceIn(1f, 5f)
        if (next == zoom) return
        val manager = pages.layoutManager as LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition()
        val top = manager.findViewByPosition(position)?.top ?: 0
        val factor = next / zoom
        val horizontal = ((scrollX + focusX) * factor - focusX).toInt()
        val vertical = (focusY - (focusY - top) * factor).toInt()
        pages.stopScroll()
        zoom = next
        resize(width)
        if (position >= 0) manager.scrollToPositionWithOffset(position, vertical)
        post { scrollTo(horizontal.coerceIn(0, (documentWidth - width).coerceAtLeast(0)), 0) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pinching = false
            doubleTapped = false
        }
        // Cancel the current scroll before consuming the two-finger gesture.
        if (event.pointerCount > 1 && !pinching) {
            pinching = true
            pages.stopScroll()
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        scaler.onTouchEvent(event)
        if (!pinching) gestures.onTouchEvent(event)
        if (doubleTapped && !pinching) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (pinching || doubleTapped) return true
        return super.dispatchTouchEvent(event)
    }
}
