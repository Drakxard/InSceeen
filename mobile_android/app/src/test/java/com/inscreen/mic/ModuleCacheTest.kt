package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModuleCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val first = ModuleCatalog.Module("modulo-a", "Módulo A", "modules/modulo-a/index.html")
    private val second = ModuleCatalog.Module("modulo-b", "Módulo B", "modules/modulo-b/index.html")

    @Test fun persistsAndIsolatesHtmlBySubject() {
        val cache = ModuleCache.at(temporary.newFolder("modules"))
        cache.write("materia-1", first, "<h1>uno</h1>")
        cache.write("materia-2", first, "<h1>dos</h1>")

        assertEquals("<h1>uno</h1>", cache.read("materia-1", first))
        assertEquals("<h1>dos</h1>", cache.read("materia-2", first))
        assertNull(cache.read("materia-1", second))
    }

    @Test fun replacingAssignmentRemovesOldModuleAndDeleteIsPerSubject() {
        val root = temporary.newFolder("modules")
        val cache = ModuleCache.at(root)
        cache.write("materia-1", first, "anterior")
        cache.write("materia-1", second, "nuevo")
        cache.write("materia-2", first, "otra")

        assertNull(cache.read("materia-1", first))
        assertEquals("nuevo", cache.read("materia-1", second))
        cache.remove("materia-1")
        assertNull(cache.read("materia-1", second))
        assertEquals("otra", cache.read("materia-2", first))
    }

    @Test fun reconcileRemovesDeletedSubjectsOnly() {
        val cache = ModuleCache.at(temporary.newFolder("modules"))
        cache.write("keep", first, "guardado")
        cache.write("delete", first, "borrar")

        cache.reconcile(setOf("keep"))

        assertEquals("guardado", cache.read("keep", first))
        assertNull(cache.read("delete", first))
    }
}
