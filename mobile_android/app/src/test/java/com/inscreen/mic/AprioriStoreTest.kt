package com.inscreen.mic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AprioriStoreTest {
    @Test
    fun derivesAndPersistsProviderSubjectSegment() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Lógica y Computabilidad"}],
            "ring":["a"]
        }""".trimIndent()

        val subject = JSONObject(AprioriStore.validateAndNormalize(raw))
            .getJSONArray("subjects").getJSONObject(0)
        assertEquals("logicaycomputabilidad", subject.getString("providerSubjectSegment"))
    }

    @Test
    fun readsQueueHeadWithNameAndColor() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra","color":"#123456"}],
            "ring":["a","a"]
        }""".trimIndent()

        val head = AprioriStore.queueHead(raw)
        assertEquals("Álgebra", head?.name)
        assertEquals("#123456", head?.color)
        assertEquals(2, head?.ticketCount)
    }

    @Test
    fun consumesExactlyOneQueueAppearance() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra"},{"id":"b","name":"Física"}],
            "ring":["a","b","a"]
        }""".trimIndent()

        val consumed = JSONObject(AprioriStore.consumeHead(raw)).getJSONArray("ring")
        assertEquals(listOf("b", "a", "a"), (0 until consumed.length()).map(consumed::getString))
        val empty = AprioriStore.EMPTY_STATE
        assertEquals(empty, AprioriStore.consumeHead(empty))
    }

    @Test
    fun rotatingQueuePreservesEveryModuleAssignment() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[
                {"id":"a","name":"Álgebra","modules":[{"id":"one","nombre":"Uno","entry":"modules/one/index.html"}]},
                {"id":"b","name":"Física","modules":[{"id":"two","nombre":"Dos","entry":"modules/two/index.html"}]}
            ],
            "ring":["a","b","a"]
        }""".trimIndent()

        val rotated = AprioriStore.consumeHead(raw)

        assertEquals("one", AprioriStore.subject(rotated, "a")?.getJSONArray("modules")?.getJSONObject(0)?.getString("id"))
        assertEquals("two", AprioriStore.subject(rotated, "b")?.getJSONArray("modules")?.getJSONObject(0)?.getString("id"))
    }

    @Test
    fun findsOnlySubjectsAssignedToTheRequestedModule() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[
                {"id":"a","name":"Álgebra","modules":[{"id":"shared","nombre":"Compartido","entry":"modules/shared/index.html"}]},
                {"id":"b","name":"Física","modules":[{"id":"other","nombre":"Otro","entry":"modules/other/index.html"},{"id":"shared","nombre":"Compartido","entry":"modules/shared/index.html"}]},
                {"id":"c","name":"Química","modules":[{"id":"other","nombre":"Otro","entry":"modules/other/index.html"}]}
            ],
            "ring":["a","b","c"]
        }""".trimIndent()

        assertEquals(listOf("a", "b"), AprioriStore.subjectIdsAssignedTo(raw, "shared"))
        assertEquals(emptyList<String>(), AprioriStore.subjectIdsAssignedTo(raw, "missing"))
    }

    @Test
    fun peeksNextQueueHeadWithoutChangingTheState() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[
                {"id":"a","name":"Álgebra","color":"#123456"},
                {"id":"b","name":"Física","color":"#abcdef"}
            ],
            "ring":["a","b","a"]
        }""".trimIndent()

        val next = AprioriStore.nextQueueHead(raw)

        assertEquals("Física", next?.name)
        assertEquals("#abcdef", next?.color)
        assertEquals("Álgebra", AprioriStore.queueHead(raw)?.name)
    }

    @Test
    fun nextQueueHeadHandlesSingleSubjectAndEmptyQueue() {
        val single = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra","color":"#123456"}],
            "ring":["a"]
        }""".trimIndent()

        assertEquals("Álgebra", AprioriStore.nextQueueHead(single)?.name)
        assertEquals(null, AprioriStore.nextQueueHead(AprioriStore.EMPTY_STATE))
    }

    @Test
    fun acceptsBackupWithSeparatedRows() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra"},{"id":"b","name":"Física"}],
            "ring":["a","b"],
            "dockRows":[["a"],[],["b"]]
        }""".trimIndent()

        val normalized = JSONObject(AprioriStore.validateAndNormalize(raw))
        assertEquals(3, normalized.getJSONArray("dockRows").length())
    }

    @Test
    fun preservesModuleAssignmentInTheAprioriState() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra","module":{"id":"algebra-vf","nombre":"V o F","entry":"modules/algebra-vf/index.html"}}],
            "ring":["a"]
        }""".trimIndent()

        val subject = AprioriStore.subject(AprioriStore.validateAndNormalize(raw), "a")
        assertEquals("algebra-vf", subject?.optJSONArray("modules")?.optJSONObject(0)?.optString("id"))
        assertEquals("V o F", subject?.optJSONArray("modules")?.optJSONObject(0)?.optString("nombre"))
    }

    @Test
    fun assignsAndRemovesModuleOnThePersistedStateObject() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra"}],
            "ring":["a"]
        }""".trimIndent()
        val module = ModuleCatalog.Module("algebra-vf", "V o F", "modules/algebra-vf/index.html")

        val assigned = AprioriStore.addModule(raw, "a", module)
        assertEquals("algebra-vf", AprioriStore.subject(assigned!!, "a")?.optJSONArray("modules")?.optJSONObject(0)?.optString("id"))

        val removed = AprioriStore.removeModule(assigned, "a", module.id)
        assertEquals(0, AprioriStore.subject(removed!!, "a")?.optJSONArray("modules")?.length())
    }

    @Test
    fun addsMultipleModulesWithoutDuplicates() {
        val raw = """{"version":3,"settings":{"cycleSize":20,"urgencyK":14},"subjects":[{"id":"a","name":"Álgebra"}],"ring":["a"]}"""
        val first = ModuleCatalog.Module("first", "Primero", "modules/first/index.html")
        val second = ModuleCatalog.Module("second", "Segundo", "modules/second/index.html")

        val withFirst = AprioriStore.addModule(raw, "a", first)!!
        val withBoth = AprioriStore.addModule(withFirst, "a", second)!!
        val withoutDuplicate = AprioriStore.addModule(withBoth, "a", first)!!
        val modules = AprioriStore.subject(withoutDuplicate, "a")!!.getJSONArray("modules")

        assertEquals(2, modules.length())
        assertEquals("first", modules.getJSONObject(0).getString("id"))
        assertEquals("second", modules.getJSONObject(1).getString("id"))
    }

    @Test
    fun clearsAllLegacyModuleAssignments() {
        val raw = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[
                {"id":"a","name":"Álgebra","moduleId":"old","moduleName":"Viejo","moduleEntry":"modules/old/index.html"},
                {"id":"b","name":"Física","moduleId":"partial","moduleEntry":"modules/partial/index.html"},
                {"id":"c","name":"Química"}
            ],
            "ring":["a","b","c"]
        }""".trimIndent()

        val cleared = JSONObject(AprioriStore.clearModuleAssignments(raw))
        val subjects = cleared.getJSONArray("subjects")
        for (index in 0 until subjects.length()) {
            val subject = subjects.getJSONObject(index)
            assertEquals(false, subject.has("moduleId"))
            assertEquals(false, subject.has("moduleName"))
            assertEquals(false, subject.has("moduleEntry"))
            assertEquals(false, subject.has("module"))
        }
    }

    @Test
    fun rejectsLegacyStateInsteadOfMigratingIt() {
        val legacy = """{"version":1,"subjects":[],"ring":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            AprioriStore.validateAndNormalize(legacy)
        }
    }

    @Test
    fun rejectsBackupWithUnknownOrDuplicatedSubjects() {
        val unknown = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra"}],
            "ring":["missing"]
        }""".trimIndent()
        val duplicated = """{
            "version":3,"settings":{"cycleSize":20,"urgencyK":14},
            "subjects":[{"id":"a","name":"Álgebra"}],
            "ring":["a"],
            "dockRows":[["a"],[],["a"]]
        }""".trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            AprioriStore.validateAndNormalize(unknown)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AprioriStore.validateAndNormalize(duplicated)
        }
    }
}
