package com.inscreen.mic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun movesExactlyOnePageForHorizontalSwipes() {
        assertEquals(2, PagerGesturePolicy.targetPage(1, 3, 360f, 100f, 280f, 105f, 48f))
        assertEquals(0, PagerGesturePolicy.targetPage(1, 3, 0f, 100f, 80f, 105f, 48f))
    }

    @Test
    fun rejectsShortVerticalAndOutwardBoundarySwipes() {
        assertNull(PagerGesturePolicy.targetPage(1, 3, 0f, 100f, -30f, 100f, 48f))
        assertNull(PagerGesturePolicy.targetPage(1, 3, 0f, 100f, -70f, 180f, 48f))
        assertNull(PagerGesturePolicy.targetPage(0, 3, 0f, 100f, 70f, 100f, 48f))
        assertNull(PagerGesturePolicy.targetPage(2, 3, 360f, 100f, 290f, 100f, 48f))
    }
}
