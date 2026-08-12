package com.inscreen.mic

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SubjectNotesStoreTest {
    private lateinit var temporary: File
    private lateinit var store: SubjectNotesStore

    @Before fun setUp() {
        temporary = Files.createTempDirectory("subject-notes-test").toFile()
        store = SubjectNotesStore(File(temporary, "notes"))
    }

    @After fun tearDown() { temporary.deleteRecursively() }

    @Test fun commitsOrderedPhotosAndListsNewestSessionFirst() {
        val first = source("first", "one")
        val second = source("second", "two")
        val older = store.commit("math/../safe", 1000L, listOf(first, second))
        val newer = store.commit("math/../safe", 2000L, listOf(second))

        assertEquals(listOf(newer.id, older.id), store.sessions("math/../safe").map { it.id })
        assertEquals(listOf("one", "two"), older.photos.map { it.file.readText() })
        assertTrue(older.photos.all { it.file.canonicalPath.startsWith(temporary.canonicalPath) })
    }

    @Test fun deletingLastPhotoRemovesItsSession() {
        val session = store.commit("history", 1000L, listOf(source("photo", "jpeg")))
        assertTrue(store.deletePhoto("history", session.id, session.photos.single().name))
        assertTrue(store.sessions("history").isEmpty())
        assertFalse(store.deletePhoto("history", "../other", "0001.jpg"))
    }

    @Test fun reconcilesRemovedSubjectsAndIncompleteSessions() {
        store.commit("keep", 1000L, listOf(source("keep", "a")))
        store.commit("remove", 1000L, listOf(source("remove", "b")))
        store.reconcileSubjects(setOf("keep"))

        assertEquals(1, store.sessions("keep").size)
        assertTrue(store.sessions("remove").isEmpty())
        assertEquals(1, File(temporary, "notes").listFiles()?.size)
    }

    @Test fun persistsMarkerTextPerPhotoAndRemovesOnlyDeletedPhotoCache() {
        val session = store.commit("math", 1000L, listOf(source("first", "one"), source("second", "two")))
        store.saveMarkerText("math", session.id, "0001.jpg", "# Uno")
        store.saveMarkerText("math", session.id, "0002.jpg", "# Dos")

        assertEquals("# Uno", store.markerText("math", session.id, "0001.jpg"))
        assertEquals("# Dos", store.markerText("math", session.id, "0002.jpg"))
        assertTrue(store.deletePhoto("math", session.id, "0001.jpg"))
        assertEquals(null, store.markerText("math", session.id, "0001.jpg"))
        assertEquals("# Dos", store.markerText("math", session.id, "0002.jpg"))
    }

    @Test fun rejectsMarkerCacheForUnknownPhoto() {
        val session = store.commit("math", 1000L, listOf(source("first", "one")))
        var failed = false
        try { store.saveMarkerText("math", session.id, "other.jpg", "text") } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertEquals(null, store.markerText("math", session.id, "other.jpg"))
    }

    @Test fun exposesMarkerTranscriptionsAsNumberedLogicalTxtFiles() {
        val session = store.commit("math", 1000L, listOf(source("first", "one"), source("second", "two")))
        store.saveMarkerText("math", session.id, "0001.jpg", "# Uno\n\n\$x^2\$")
        store.saveMarkerText("math", session.id, "0002.jpg", "**Dos**")

        val inventory = JSONObject(store.markerInventory("math", session.id))
        val files = inventory.getJSONArray("archivos")
        assertEquals(session.id, inventory.getJSONObject("conjunto").getString("id"))
        assertEquals(1000L, inventory.getJSONObject("conjunto").getLong("createdAt"))
        assertEquals(listOf("1.txt", "2.txt"), (0 until files.length()).map { files.getJSONObject(it).getString("nombre") })
        assertEquals(listOf("0001.jpg", "0002.jpg"), (0 until files.length()).map { files.getJSONObject(it).getString("id") })
        val first = JSONObject(store.markerFile("math", session.id, 1))
        val firstFile = first.getJSONObject("archivo")
        assertEquals("# Uno\n\n\$x^2\$", firstFile.getString("contenido"))
        assertEquals("0001.jpg", firstFile.getString("id"))
        assertTrue(firstFile.getString("hash").matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun markerLogicalNumbersFollowRemainingPhotoOrderAfterDelete() {
        val session = store.commit("math", 1000L, listOf(source("first", "one"), source("second", "two"), source("third", "three")))
        store.saveMarkerText("math", session.id, "0001.jpg", "Uno")
        store.saveMarkerText("math", session.id, "0002.jpg", "Dos")
        store.saveMarkerText("math", session.id, "0003.jpg", "Tres")

        assertTrue(store.deletePhoto("math", session.id, "0001.jpg"))
        val inventory = JSONObject(store.markerInventory("math", session.id)).getJSONArray("archivos")
        assertEquals(listOf("1.txt", "2.txt"), (0 until inventory.length()).map { inventory.getJSONObject(it).getString("nombre") })
        assertEquals("Dos", JSONObject(store.markerFile("math", session.id, 1)).getJSONObject("archivo").getString("contenido"))
        assertEquals("Tres", JSONObject(store.markerFile("math", session.id, 2)).getJSONObject("archivo").getString("contenido"))
    }

    @Test fun markerBridgeStorageReportsNormalizedErrors() {
        val session = store.commit("math", 1000L, listOf(source("first", "one")))

        assertEquals("session_not_found", JSONObject(store.markerInventory("math", "missing")).getString("error"))
        assertEquals("invalid_file_number", JSONObject(store.markerFile("math", session.id, 0)).getString("error"))
        assertEquals("file_not_found", JSONObject(store.markerFile("math", session.id, 2)).getString("error"))
        assertEquals("transcription_not_ready", JSONObject(store.markerFile("math", session.id, 1)).getString("error"))
    }

    @Test fun persistsStudyStateAtomicallyPerSessionAndModule() {
        val first = store.commit("math", 1000L, listOf(source("first", "one")))
        val second = store.commit("math", 2000L, listOf(source("second", "two")))
        val state = JSONObject()
            .put("version", 1)
            .put("conjuntoId", first.id)
            .put("paginas", JSONObject().put("0001.jpg", JSONObject()
                .put("sourceHash", "a".repeat(64))
                .put("tarjetas", org.json.JSONArray().put(JSONObject()
                    .put("id", "0001.jpg:a:1")
                    .put("orden", 1)
                    .put("cabecera", "Causas de la inflación")
                    .put("respuesta", "Mi explicación")
                    .put("respuestaActualizada", 1234L)))))

        assertTrue(JSONObject(store.saveStudyState("math", first.id, "apuntes", state.toString())).getBoolean("ok"))
        val restored = JSONObject(store.studyState("math", first.id, "apuntes")).getJSONObject("estado")
        assertEquals("Mi explicación", restored.getJSONObject("paginas").getJSONObject("0001.jpg")
            .getJSONArray("tarjetas").getJSONObject(0).getString("respuesta"))
        assertTrue(JSONObject(store.studyState("math", first.id, "otro-modulo")).isNull("estado"))
        assertTrue(JSONObject(store.studyState("math", second.id, "apuntes")).isNull("estado"))
    }

    @Test fun rejectsStudyStateForAnotherSessionOrInvalidCards() {
        val session = store.commit("math", 1000L, listOf(source("first", "one")))
        val wrongSession = JSONObject().put("version", 1).put("conjuntoId", "other").put("paginas", JSONObject())
        assertEquals("invalid_study_state", JSONObject(store.saveStudyState("math", session.id, "apuntes", wrongSession.toString())).getString("error"))

        val invalidCard = JSONObject().put("version", 1).put("conjuntoId", session.id)
            .put("paginas", JSONObject().put("0001.jpg", JSONObject()
                .put("sourceHash", "b".repeat(64))
                .put("tarjetas", org.json.JSONArray().put(JSONObject()
                    .put("id", "card").put("orden", 1).put("cabecera", "respuesta\nen dos líneas")))))
        assertEquals("invalid_study_state", JSONObject(store.saveStudyState("math", session.id, "apuntes", invalidCard.toString())).getString("error"))
    }

    private fun source(name: String, content: String) = File(temporary, "$name.jpg").apply { writeText(content) }
}
