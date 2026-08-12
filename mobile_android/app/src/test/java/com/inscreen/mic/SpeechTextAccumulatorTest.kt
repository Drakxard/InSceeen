package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextAccumulatorTest {
    @Test fun replacesPartialAndCombinesCommittedSegments() {
        val accumulator = SpeechTextAccumulator()
        assertEquals("primera idea", accumulator.updatePartial("  primera   idea "))
        assertEquals("primera idea completa", accumulator.updatePartial("primera idea completa"))
        assertEquals("primera idea completa", accumulator.commit("primera idea completa"))
        assertEquals("primera idea completa segunda", accumulator.updatePartial("segunda"))
        assertEquals("primera idea completa segunda parte", accumulator.commit("segunda parte"))
    }

    @Test fun keepsIntentionalRepeatedSegmentsAndCanCommitLastPartial() {
        val accumulator = SpeechTextAccumulator()
        accumulator.commit("se repite")
        accumulator.commit("se repite")
        accumulator.updatePartial("cierre")
        assertEquals("se repite se repite cierre", accumulator.commitPartial())
        accumulator.reset()
        assertEquals("", accumulator.text())
    }
}
