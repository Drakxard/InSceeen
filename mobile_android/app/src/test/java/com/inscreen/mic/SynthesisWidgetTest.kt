package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SynthesisWidgetTest {
    @Test fun `widget loads actual weeks descending with subject identity and bearer authorization`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"weeks":[{"weekNumber":2},{"weekNumber":12},{"weekNumber":0},{"weekNumber":2},{"weekNumber":-1}]}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"workspace":{"version":2,"document":{"type":"doc","content":[]}}}"""))
        server.start()
        try {
            val client = ProviderClient(server.url("/").toString(), "test-token")
            assertEquals(listOf(12, 2, 0), client.listSynthesisWeeks("algebra"))
            assertEquals(2, client.readSynthesis("algebra", 12).getInt("version"))
            val list = server.takeRequest()
            assertEquals("/api/inscreen/provider/synthesis", list.requestUrl?.encodedPath)
            assertEquals("algebra", list.requestUrl?.queryParameter("subjectId"))
            assertEquals("Bearer test-token", list.getHeader("Authorization"))
            val document = server.takeRequest()
            assertEquals("algebra", document.requestUrl?.queryParameter("subjectId"))
            assertEquals("12", document.requestUrl?.queryParameter("weekNumber"))
            assertEquals("Bearer test-token", document.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun `reader preserves rich content and blocks executable content`() {
        val workspace = JSONObject("""{"version":2,"document":{"type":"doc","content":[
            {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"<script>alert(1)</script>","marks":[{"type":"bold"}]}]},
            {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableCell","attrs":{"colspan":2},"content":[{"type":"paragraph","content":[{"type":"text","text":"Contenido"}]}]}]}]},
            {"type":"image","attrs":{"src":"synthesis-local-image:abc-123"}},
            {"type":"image","attrs":{"src":"https://evil.test/tracker"}},
            {"type":"paragraph","content":[{"type":"text","text":"Enlace","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}
        ]}}""")
        val html = SynthesisDocumentRenderer.render(workspace)
        assertTrue(html.contains("<h2><strong>&lt;script&gt;"))
        assertTrue(html.contains("<table><tr><td colspan=\"2\""))
        assertTrue(html.contains("https://synthesis.local/images/abc-123"))
        assertFalse(html.contains("evil.test"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("<script>"))
    }
}
