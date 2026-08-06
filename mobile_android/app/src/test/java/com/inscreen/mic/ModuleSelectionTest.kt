package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleSelectionTest {
    @Test
    fun parsesAndSerializesValidSelection() {
        val selected = ModuleSelection.parse(
            """{"id":"ingles-vocabulario","nombre":"Inglés","entry":"modules/ingles-vocabulario/index.html"}"""
        )
        assertEquals("ingles-vocabulario", selected.id)
        assertEquals("Inglés", ModuleSelection.parse(ModuleSelection.serialize(selected)).name)
    }

    @Test
    fun rejectsEntriesOutsideModules() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleSelection.parse("""{"id":"x","nombre":"X","entry":"https://example.com/x.html"}""")
        }
    }
}
