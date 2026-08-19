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

    @Test fun acceptsAnEmptyPublicModuleIndex() {
        assertEquals(emptyList<ModuleCatalog.Module>(), ModuleCatalog.parse("""{"modules":[]}"""))
    }

    @Test fun rejectsDuplicatedModuleIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleCatalog.parse(
                """{"modules":[
                    {"id":"same","nombre":"Uno","entry":"modules/uno/index.html"},
                    {"id":"same","nombre":"Dos","entry":"modules/dos/index.html"}
                ]}""",
            )
        }
    }

    @Test fun selectsOnlySafeFilesFromTheRequestedModule() {
        val module = ModuleCatalog.Module("anotaciones", "Anotaciones", "modules/anotaciones/index.html")
        val paths = ModuleCatalog.packagePaths(module, """{
            "truncated":false,
            "tree":[
                {"type":"blob","path":"modules/anotaciones/index.html"},
                {"type":"blob","path":"modules/anotaciones/vendor/fonts/math.woff2"},
                {"type":"blob","path":"modules/ingles/index.html"},
                {"type":"tree","path":"modules/anotaciones/vendor"}
            ]
        }""")

        assertEquals(
            listOf("modules/anotaciones/index.html", "modules/anotaciones/vendor/fonts/math.woff2"),
            paths,
        )
    }

    @Test fun rejectsTruncatedPackageTrees() {
        val module = ModuleCatalog.Module("anotaciones", "Anotaciones", "modules/anotaciones/index.html")
        assertThrows(IllegalArgumentException::class.java) {
            ModuleCatalog.packagePaths(module, """{"truncated":true,"tree":[]}""")
        }
    }

    @Test fun rejectsUnsafePackageFileNames() {
        val module = ModuleCatalog.Module("anotaciones", "Anotaciones", "modules/anotaciones/index.html")
        assertThrows(IllegalArgumentException::class.java) {
            ModuleCatalog.packagePaths(module, """{"truncated":false,"tree":[
                {"type":"blob","path":"modules/anotaciones/index.html"},
                {"type":"blob","path":"modules/anotaciones/bad file.js"}
            ]}""")
        }
    }
}
