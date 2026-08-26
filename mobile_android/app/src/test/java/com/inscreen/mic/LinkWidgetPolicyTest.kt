package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkWidgetPolicyTest {
    @Test fun normalizesWebUrlsAndAddsHttpsWhenMissing() {
        assertEquals("https://example.com/path", LinkWidgetPolicy.normalizeUrl("example.com/path"))
        assertEquals("http://example.com/a", LinkWidgetPolicy.normalizeUrl(" http://example.com/a "))
        assertEquals(
            "https://notebook.google.com/notebook/73a15072-1c25-431f-b919-a886bd49ec69",
            LinkWidgetPolicy.normalizeUrl(
                "https://notebook.google.com/notebook/73a15072-1c25-431f-b919-a886bd49ec69",
            ),
        )
    }

    @Test fun rejectsEmptyUnsupportedAndMalformedUrls() {
        assertNull(LinkWidgetPolicy.normalizeUrl(""))
        assertNull(LinkWidgetPolicy.normalizeUrl("ftp://example.com/file"))
        assertNull(LinkWidgetPolicy.normalizeUrl("not a url"))
    }

    @Test fun parsesAndFormatsOpaqueHexColors() {
        assertEquals(0xFFA8EF00.toInt(), LinkWidgetPolicy.parseHexColor("#a8ef00"))
        assertEquals("#A8EF00", LinkWidgetPolicy.formatHexColor(0xFFA8EF00.toInt()))
        assertNull(LinkWidgetPolicy.parseHexColor("#12345"))
        assertNull(LinkWidgetPolicy.parseHexColor("#GG0000"))
    }

    @Test fun choosesReadableTextAndScalesLongLabelsDown() {
        assertEquals(0xFF000000.toInt(), LinkWidgetPolicy.contrastingTextColor(0xFFA8EF00.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), LinkWidgetPolicy.contrastingTextColor(0xFF202020.toInt()))
        assertTrue(
            LinkWidgetPolicy.textSizeSp("Programación", 120, 60) <
                LinkWidgetPolicy.textSizeSp("EO", 120, 60),
        )
    }
}
