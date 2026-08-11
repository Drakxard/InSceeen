package com.inscreen.mic

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

    private fun source(name: String, content: String) = File(temporary, "$name.jpg").apply { writeText(content) }
}
