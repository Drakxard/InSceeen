package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Test

class SynthesisWidgetTest {
    @Test fun `widget targets downloadable synthesis module`() {
        val module = SynthesisWidgetResolveActivity.SYNTHESIS_MODULE
        assertEquals("sintesis", module.id)
        assertEquals("Síntesis", module.name)
        assertEquals("modules/sintesis/index.html", module.entry)
    }
}
