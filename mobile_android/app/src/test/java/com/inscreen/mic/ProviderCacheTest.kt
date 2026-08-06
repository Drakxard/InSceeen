package com.inscreen.mic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.concurrent.thread

class ProviderCacheTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun mergesOnlyNewFilesAndNeverOverwrites() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        val first = payload(1, (1..5).associateWith { "original-$it" })
        val second = payload(1, (1..7).associateWith { "updated-$it" })

        assertEquals(5, JSONObject(cache.merge("subject-a", false, first)).getInt("nuevos"))
        val merged = JSONObject(cache.merge("subject-a", false, second))
        assertEquals(2, merged.getInt("nuevos"))
        assertEquals(7, merged.getJSONArray("archivos").length())
        assertEquals(
            "original-1",
            JSONObject(cache.read("subject-a", false, 1, 1)).getJSONObject("archivo").getString("contenido"),
        )
    }

    @Test
    fun sortsNumericallyAndSeparatesTypeStageAndSubject() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        cache.merge("subject-a", false, payload(1, linkedMapOf(10 to "ten", 2 to "two", 1 to "one")))
        cache.merge("subject-a", true, payload(1, mapOf(1 to "transcription")))
        cache.merge("subject-a", false, payload(2, mapOf(1 to "stage-two")))
        cache.merge("subject-b", false, payload(1, mapOf(1 to "other-subject")))

        val names = JSONObject(cache.list("subject-a", false, 1)).getJSONArray("archivos")
        assertEquals(listOf("1.txt", "2.txt", "10.txt"), (0 until names.length()).map { names.getJSONObject(it).getString("nombre") })
        assertEquals("transcription", content(cache.read("subject-a", true, 1, 1)))
        assertEquals("stage-two", content(cache.read("subject-a", false, 2, 1)))
        assertEquals("other-subject", content(cache.read("subject-b", false, 1, 1)))
    }

    @Test
    fun rejectsInvalidStageNamesAndNumbers() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        val invalidNames = """{"ok":true,"etapa":1,"archivos":[
            {"nombre":"../1.txt","contenido":"unsafe"},{"nombre":"name.txt","contenido":"invalid"}
        ]}"""
        assertEquals(0, JSONObject(cache.merge("subject", false, invalidNames)).getInt("nuevos"))
        assertTrue(JSONObject(cache.merge("subject", false, payload(0, mapOf(1 to "day-zero")))).getBoolean("ok"))
        assertEquals("day-zero", content(cache.read("subject", false, 0, 1)))
        assertEquals("invalid_stage", JSONObject(cache.merge("subject", false, payload(7, emptyMap()))).getString("error"))
        assertEquals("invalid_file_number", JSONObject(cache.read("subject", false, 1, 0)).getString("error"))
        assertEquals("file_not_found", JSONObject(cache.read("subject", false, 1, 1)).getString("error"))
    }

    @Test
    fun concurrentMergesDoNotCorruptInventory() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        val left = thread { cache.merge("subject", false, payload(1, (1..5).associateWith { "left-$it" })) }
        val right = thread { cache.merge("subject", false, payload(1, (4..8).associateWith { "right-$it" })) }
        left.join(); right.join()
        assertEquals(8, JSONObject(cache.list("subject", false, 1)).getJSONArray("archivos").length())
    }

    @Test
    fun reconciliationDeletesOnlyRemovedSubjects() {
        val root = temporary.newFolder("cache")
        val cache = ProviderCache(root)
        cache.merge("keep", false, payload(1, mapOf(1 to "keep")))
        cache.merge("remove", false, payload(1, mapOf(1 to "remove")))
        cache.reconcileSubjects(setOf("keep"))
        assertTrue(JSONObject(cache.read("keep", false, 1, 1)).getBoolean("ok"))
        assertFalse(JSONObject(cache.read("remove", false, 1, 1)).getBoolean("ok"))
    }

    private fun payload(stage: Int, files: Map<Int, String>): String = JSONObject()
        .put("ok", true).put("etapa", stage)
        .put("archivos", files.entries.fold(org.json.JSONArray()) { items, (number, value) ->
            items.put(JSONObject().put("nombre", "$number.txt").put("contenido", value))
        }).toString()

    private fun content(raw: String): String = JSONObject(raw).getJSONObject("archivo").getString("contenido")
}
