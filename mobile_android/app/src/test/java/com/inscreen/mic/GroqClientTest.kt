package com.inscreen.mic

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class GroqClientTest {
    @Test fun listsSortedUniqueModelsWithBearerToken() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"z"},{"id":"a"},{"id":"a"}]}"""))
        server.start()
        try {
            val latch = CountDownLatch(1)
            var models = emptyList<String>()
            GroqClient(server.url("/openai/v1").toString(), OkHttpClient()).models("secret") {
                models = it.getOrThrow(); latch.countDown()
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("a", "z"), models)
            val request = server.takeRequest()
            assertEquals("/openai/v1/models", request.requestUrl?.encodedPath)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun sendsStructuredQueryAndNormalizesAnswer() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"respuesta"}}]}"""))
        server.start()
        try {
            val latch = CountDownLatch(1)
            var result = ""
            GroqClient(server.url("/openai/v1").toString(), OkHttpClient())
                .query("secret", "modelo-1", "¿qué es?", "material") { result = it; latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertEquals("modelo-1", body.getString("model"))
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("PREGUNTA:"))
            assertEquals("respuesta", JSONObject(result).getString("contenido"))
        } finally { server.shutdown() }
    }

    @Test fun mapsAuthenticationAndEmptyResponses() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        server.start()
        try {
            val outputs = Collections.synchronizedList(mutableListOf<String>())
            val latch = CountDownLatch(2)
            val client = GroqClient(server.url("/").toString(), OkHttpClient())
            client.query("bad", "m", "q", "") { outputs += it; latch.countDown() }
            client.query("ok", "m", "q", "") { outputs += it; latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(setOf("authentication_error", "empty_response"), outputs.map { JSONObject(it).getString("error") }.toSet())
        } finally { server.shutdown() }
    }
}
