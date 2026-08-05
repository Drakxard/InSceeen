package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleCatalogTest {
    @Test fun parsesPublicModuleIndex() {
        val modules = ModuleCatalog.parse(
            """{"modules":[{"id":"ingles-vocabulario","nombre":"Vocabulario de Inglés","entry":"modules/ingles-vocabulario/index.html"}]}""",
        )

        assertEquals("ingles-vocabulario", modules.single().id)
        assertEquals("Vocabulario de Inglés", modules.single().name)
    }

    @Test fun rejectsAPathOutsideTheModuleDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleCatalog.parse(
                """{"modules":[{"id":"unsafe","nombre":"Unsafe","entry":"../unsafe.html"}]}""",
            )
        }
    }
}
