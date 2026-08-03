package com.inscreen.mic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerGesturePolicyTest {
    @Test
    fun acceptsOnlyTouchesStartingAtHorizontalEdges() {
        assertTrue(PagerGesturePolicy.beginsAtHorizontalEdge(0f, 360f, 20f))
        assertTrue(PagerGesturePolicy.beginsAtHorizontalEdge(20f, 360f, 20f))
        assertTrue(PagerGesturePolicy.beginsAtHorizontalEdge(340f, 360f, 20f))
        assertTrue(PagerGesturePolicy.beginsAtHorizontalEdge(360f, 360f, 20f))

        assertFalse(PagerGesturePolicy.beginsAtHorizontalEdge(20.1f, 360f, 20f))
        assertFalse(PagerGesturePolicy.beginsAtHorizontalEdge(180f, 360f, 20f))
        assertFalse(PagerGesturePolicy.beginsAtHorizontalEdge(339.9f, 360f, 20f))
    }

    @Test
    fun rejectsInvalidBounds() {
        assertFalse(PagerGesturePolicy.beginsAtHorizontalEdge(0f, 0f, 20f))
        assertFalse(PagerGesturePolicy.beginsAtHorizontalEdge(0f, 360f, 0f))
        assertFalse(PagerGesturePolicy.beginsAtHorizontalEdge(-1f, 360f, 20f))
    }
}
