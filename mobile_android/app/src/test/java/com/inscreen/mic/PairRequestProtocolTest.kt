package com.inscreen.mic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairRequestProtocolTest {
    @Test
    fun buildsVersionedRequestWithoutSecrets() {
        val request = JSONObject(
            PairRequestProtocol.build("00112233445566778899aabbccddeeff")
        )
        assertEquals(1, request.getInt("v"))
        assertEquals("pair.request", request.getString("type"))
        assertEquals("00112233445566778899aabbccddeeff", request.getString("nonce"))
        assertEquals(3, request.length())
    }

    @Test
    fun rejectsInvalidNonce() {
        assertThrows(IllegalArgumentException::class.java) {
            PairRequestProtocol.build("invalid")
        }
    }

    @Test
    fun extractsHeadSubjectFromAprioriState() {
        val raw = """{"version":1,"subjects":[{"id":"a","name":"Álgebra"},{"id":"b","name":"Física"}],"ring":["b","a"],"dockSplitIndex":1}"""
        assertEquals("Física", AprioriStore.activeSubject(raw))
        assertEquals("", AprioriStore.activeSubject(AprioriStore.EMPTY_STATE))
    }
}
