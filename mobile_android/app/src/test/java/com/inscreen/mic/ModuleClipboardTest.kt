package com.inscreen.mic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleClipboardTest {
    @Test fun returnsClipboardText() {
        val result = JSONObject(ModuleClipboard.payload { "Tema:Respuesta" })
        assertTrue(result.getBoolean("ok"))
        assertEquals("Tema:Respuesta", result.getString("texto"))
    }

    @Test fun representsAnEmptyClipboardAsSuccessfulEmptyText() {
        val result = JSONObject(ModuleClipboard.payload { null })
        assertTrue(result.getBoolean("ok"))
        assertEquals("", result.getString("texto"))
    }

    @Test fun reportsClipboardReadFailures() {
        val result = JSONObject(ModuleClipboard.payload { error("unavailable") })
        assertFalse(result.getBoolean("ok"))
        assertEquals("clipboard_read_failed", result.getString("error"))
    }
}
