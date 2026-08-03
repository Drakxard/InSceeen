package com.inscreen.mic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AprioriWidgetPolicyTest {
    @Test fun blocksRepeatedTapUntilAnimationEnds() {
        assertFalse(AprioriWidgetProvider.canConsume(now = 120, lockedUntil = 700, hasHead = true))
        assertTrue(AprioriWidgetProvider.canConsume(now = 700, lockedUntil = 700, hasHead = true))
        assertFalse(AprioriWidgetProvider.canConsume(now = 800, lockedUntil = 0, hasHead = false))
    }

    @Test fun usesAnimatedFractureOnlyWhereAnimatedImageDrawableExists() {
        assertFalse(AprioriWidgetProvider.supportsFractureAnimation(sdk = 27))
        assertTrue(AprioriWidgetProvider.supportsFractureAnimation(sdk = 28))
    }
}
