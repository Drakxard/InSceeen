package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStatePolicyTest {
    @Test fun aggregatePrefersAnyPlayingSession() {
        assertEquals("playing", MediaStatePolicy.aggregate(listOf("paused", "playing", "none")))
        assertEquals("paused", MediaStatePolicy.aggregate(listOf("none", "paused")))
        assertEquals("none", MediaStatePolicy.aggregate(listOf("stopped", "none")))
    }

    @Test fun ensurePausedIsIdempotentAndNeverPlays() {
        val playing = MediaStatePolicy.decide("ensure_paused", listOf("paused", "playing"))
        assertTrue(playing.ok)
        assertEquals(1, playing.controllerIndex)
        assertEquals("pause", playing.command)

        val paused = MediaStatePolicy.decide("ensure_paused", listOf("paused"))
        assertTrue(paused.ok)
        assertEquals("none", paused.command)

        assertFalse(MediaStatePolicy.decide("ensure_paused", listOf("none")).ok)
    }

    @Test fun toggleControlsOnlyKnownPlayingOrPausedState() {
        assertEquals("pause", MediaStatePolicy.decide("toggle", listOf("playing")).command)
        assertEquals("play", MediaStatePolicy.decide("toggle", listOf("paused")).command)
        assertFalse(MediaStatePolicy.decide("toggle", listOf("stopped")).ok)
    }
}
