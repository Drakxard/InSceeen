package com.inscreen.mic

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class ProviderClientTest {
    @Test
    fun derivesProviderSegmentsFromVisibleNames() {
        assertEquals("algebra2", ProviderSubject.segment("Álgebra 2"))
        assertEquals("calculo2", ProviderSubject.segment("Cálculo 2"))
        assertEquals("logicaycomputabilidad", ProviderSubject.segment("Lógica y Computabilidad"))
        assertEquals("ecuacionesdiferenciales", ProviderSubject.segment("Ecuaciones Diferenciales"))
    }

    @Test
    fun sendsAuthorizationSubjectAndDay() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"etapa":1,"archivos":[]}"""))
        server.start()
        try {
            val client = ProviderClient(server.url("/").toString(), "secret", OkHttpClient())
            val latch = CountDownLatch(1)
            var payload = ""
            client.request("paginasLeidas", "ecuordinarias", 6) { payload = it; latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val request = server.takeRequest()
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("/api/inscreen/provider/paginas-leidas", request.requestUrl?.encodedPath)
            assertEquals("ecuordinarias", request.requestUrl?.queryParameter("materia"))
            assertEquals("6", request.requestUrl?.queryParameter("dia"))
            assertTrue(JSONObject(payload).getBoolean("ok"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mapsTranslationAndNormalizesFiles() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"etapa":2,"archivos":[{"nombre":"1.txt","contenido":"Texto"}]}"""
        ))
        server.start()
        try {
            val client = ProviderClient(server.url("/").toString(), "secret", OkHttpClient())
            val latch = CountDownLatch(1)
            var payload = ""
            client.request("traduccion", "ecuordinarias", 4) { payload = it; latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("/api/inscreen/provider/traducciones", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("Texto", JSONObject(payload).getJSONArray("archivos").getJSONObject(0).getString("contenido"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun requestsLatestTranslationWithOptionalFileCursor() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"hayNuevos":true,"archivos":[{"nombre":"9.txt","contenido":"Previous week"}],"nuevaEtapa":{"etapa":15,"archivos":[{"nombre":"1.txt","contenido":"New week"}]}}"""
        ))
        server.start()
        try {
            val client = ProviderClient(server.url("/").toString(), "secret", OkHttpClient())
            val latch = CountDownLatch(1)
            var payload = ""
            client.requestLatestTranslation("ingles", "8.txt") { payload = it; latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val request = server.takeRequest()
            assertEquals(null, request.requestUrl?.queryParameter("dia"))
            assertEquals("8.txt", request.requestUrl?.queryParameter("ultimo"))
            val normalized = JSONObject(payload)
            assertTrue(normalized.getBoolean("hayNuevos"))
            assertEquals("9.txt", normalized.getJSONArray("archivos").getJSONObject(0).getString("nombre"))
            assertEquals(15, normalized.getJSONObject("nuevaEtapa").getInt("etapa"))
            assertEquals("1.txt", normalized.getJSONObject("nuevaEtapa").getJSONArray("archivos").getJSONObject(0).getString("nombre"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsInvalidDayWithoutNetworkCall() {
        val server = MockWebServer()
        server.start()
        try {
            val client = ProviderClient(server.url("/").toString(), "secret", OkHttpClient())
            var payload = ""
            client.request("paginasLeidas", "ecuordinarias", 7) { payload = it }
            assertEquals("invalid_day", JSONObject(payload).getString("error"))
            assertFalse(server.requestCount > 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun completesConcurrentRequestsIndependently() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"etapa":1,"archivos":[{"nombre":"1.txt","contenido":"A"}]}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"etapa":1,"archivos":[{"nombre":"2.txt","contenido":"B"}]}"""))
        server.start()
        try {
            val client = ProviderClient(server.url("/").toString(), "secret", OkHttpClient())
            val latch = CountDownLatch(2)
            val results = Collections.synchronizedList(mutableListOf<String>())
            client.request("paginasLeidas", "algebra2", 6) { results.add(it); latch.countDown() }
            client.request("traduccion", "algebra2", 4) { results.add(it); latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(2, results.size)
            assertTrue(results.all { JSONObject(it).getBoolean("ok") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reportsInvalidJsonAndHttpFailures() {
        val client = ProviderClient("https://example.test", "secret", OkHttpClient())
        assertEquals("invalid_response", JSONObject(client.normalizeResponse("not-json")).getString("error"))
        assertEquals("invalid_stage", JSONObject(client.normalizeResponse("""{"ok":true,"archivos":[]}""")).getString("error"))
        assertTrue(JSONObject(client.normalizeResponse("""{"ok":true,"etapa":0,"archivos":[]}""")).getBoolean("ok"))
        assertFalse(JSONObject(client.normalizeResponse("""{"ok":false,"archivos":[]}""")).getBoolean("ok"))
    }
}
