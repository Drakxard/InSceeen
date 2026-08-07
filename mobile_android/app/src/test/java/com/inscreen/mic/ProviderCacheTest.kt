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
        assertTrue(JSONObject(cache.merge("subject", false, payload(15, mapOf(1 to "week-fifteen")))).getBoolean("ok"))
        assertEquals("invalid_stage", JSONObject(cache.merge("subject", false, payload(-1, emptyMap()))).getString("error"))
        assertEquals("invalid_file_number", JSONObject(cache.read("subject", false, 1, 0)).getString("error"))
        assertEquals("file_not_found", JSONObject(cache.read("subject", false, 1, 1)).getString("error"))
    }

    @Test
    fun historyKeepsRepeatedNamesInDifferentWeeklyStages() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        cache.merge("subject", true, payload(14, mapOf(1 to "week-14", 2 to "later-14")))
        cache.merge("subject", true, payload(15, mapOf(1 to "week-15")))

        val files = JSONObject(cache.history("subject", true)).getJSONArray("archivos")
        assertEquals(3, files.length())
        assertEquals("14:1.txt", files.getJSONObject(0).getString("id"))
        assertEquals("14:2.txt", files.getJSONObject(1).getString("id"))
        assertEquals("15:1.txt", files.getJSONObject(2).getString("id"))
        assertEquals("week-15", files.getJSONObject(2).getString("contenido"))
    }

    @Test
    fun incrementalMergeCompletesCurrentStageThenActivatesTheNewOne() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        cache.merge("subject", true, incremental(emptyMap(), 14 to mapOf(1 to "week-14", 2 to "day-2")))
        val merged = JSONObject(cache.merge("subject", true, incremental(mapOf(3 to "day-3"), 15 to mapOf(1 to "week-15"))))

        assertEquals(2, merged.getInt("nuevos"))
        assertEquals("day-3", content(cache.read("subject", true, 14, 3)))
        assertEquals("week-15", content(cache.read("subject", true, 15, 1)))
        val history = JSONObject(cache.history("subject", true)).getJSONArray("archivos")
        assertEquals(listOf("14:1.txt", "14:2.txt", "14:3.txt", "15:1.txt"),
            (0 until history.length()).map { history.getJSONObject(it).getString("id") })
    }

    @Test
    fun invalidNewStageDoesNotPartiallyWriteCurrentFiles() {
        val cache = ProviderCache(temporary.newFolder("cache"))
        cache.merge("subject", true, incremental(emptyMap(), 14 to mapOf(1 to "week-14")))

        val failed = JSONObject(cache.merge("subject", true, incremental(mapOf(2 to "must-rollback"), 15 to mapOf(2 to "not-first"))))
        assertEquals("storage_error", failed.getString("error"))
        assertEquals("file_not_found", JSONObject(cache.read("subject", true, 14, 2)).getString("error"))

        val next = JSONObject(cache.merge("subject", true, incremental(mapOf(2 to "saved-now"), null)))
        assertEquals(1, next.getInt("nuevos"))
        assertEquals("saved-now", content(cache.read("subject", true, 14, 2)))
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

    private fun incremental(current: Map<Int, String>, newStage: Pair<Int, Map<Int, String>>?): String = JSONObject()
        .put("ok", true).put("hayNuevos", current.isNotEmpty() || newStage != null)
        .put("archivos", fileArray(current))
        .put("nuevaEtapa", newStage?.let { (stage, files) ->
            JSONObject().put("etapa", stage).put("archivos", fileArray(files))
        } ?: JSONObject.NULL).toString()

    private fun fileArray(files: Map<Int, String>) = files.entries.fold(org.json.JSONArray()) { items, (number, value) ->
        items.put(JSONObject().put("nombre", "$number.txt").put("contenido", value))
    }

    private fun content(raw: String): String = JSONObject(raw).getJSONObject("archivo").getString("contenido")
}
