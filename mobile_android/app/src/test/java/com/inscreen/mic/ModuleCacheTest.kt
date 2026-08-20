package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModuleCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val first = ModuleCatalog.Module("modulo-a", "Módulo A", "modules/modulo-a/index.html")
    private val second = ModuleCatalog.Module("modulo-b", "Módulo B", "modules/modulo-b/index.html")

    @Test fun persistsAndIsolatesHtmlBySubject() {
        val cache = ModuleCache.at(temporary.newFolder("modules"))
        cache.write("materia-1", first, mapOf("index.html" to "<h1>uno</h1>".toByteArray(), "styles.css" to "body{}".toByteArray()))
        cache.write("materia-2", first, mapOf("index.html" to "<h1>dos</h1>".toByteArray()))

        assertEquals("<h1>uno</h1>", cache.read("materia-1", first))
        assertEquals("<h1>dos</h1>", cache.read("materia-2", first))
        assertEquals("body{}", File(cache.directory("materia-1", first.id), "styles.css").readText())
        assertNull(cache.read("materia-1", second))
    }

    @Test fun modulesCoexistAndDeleteIsPerSubjectAndModule() {
        val root = temporary.newFolder("modules")
        val cache = ModuleCache.at(root)
        cache.write("materia-1", first, mapOf("index.html" to "anterior".toByteArray()))
        cache.write("materia-1", second, mapOf("index.html" to "nuevo".toByteArray()))
        cache.write("materia-2", first, mapOf("index.html" to "otra".toByteArray()))

        assertEquals("anterior", cache.read("materia-1", first))
        assertEquals("nuevo", cache.read("materia-1", second))
        cache.remove("materia-1", second.id)
        assertNull(cache.read("materia-1", second))
        assertEquals("anterior", cache.read("materia-1", first))
        assertEquals("otra", cache.read("materia-2", first))
    }

    @Test fun distributesOnePackageToTheRequestedSubjectsOnly() {
        val cache = ModuleCache.at(temporary.newFolder("modules"))
        cache.write("sin-asignar", first, mapOf("index.html" to "anterior".toByteArray()))
        val files = mapOf("index.html" to "actualizado".toByteArray(), "app.js" to "nuevo".toByteArray())

        assertEquals(emptySet<String>(), cache.writeToSubjects(listOf("materia-1", "materia-2", "materia-1"), first, files))
        assertEquals("actualizado", cache.read("materia-1", first))
        assertEquals("actualizado", cache.read("materia-2", first))
        assertEquals("nuevo", File(cache.directory("materia-2", first.id), "app.js").readText())
        assertEquals("anterior", cache.read("sin-asignar", first))
    }

    @Test fun reportsAnIsolatedWriteFailureWithoutStoppingOtherSubjects() {
        val cache = ModuleCache.at(temporary.newFolder("modules"))
        val failures = cache.writeToSubjects(listOf("", "materia-ok"), first, mapOf("index.html" to "nuevo".toByteArray()))

        assertEquals(setOf(""), failures)
        assertEquals("nuevo", cache.read("materia-ok", first))
    }

    @Test fun reconcileRemovesDeletedSubjectsOnly() {
        val cache = ModuleCache.at(temporary.newFolder("modules"))
        cache.write("keep", first, mapOf("index.html" to "guardado".toByteArray()))
        cache.write("delete", first, mapOf("index.html" to "borrar".toByteArray()))

        cache.reconcile(setOf("keep"))

        assertEquals("guardado", cache.read("keep", first))
        assertNull(cache.read("delete", first))
    }
}
