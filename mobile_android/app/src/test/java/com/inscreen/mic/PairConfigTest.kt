package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairConfigTest {
    @Test
    fun parsesPairLink() {
        val token = "abcdefghijklmnopqrstuvwxyz1234567890abcd"
        val fingerprint = "ab".repeat(32)
        val config = PairConfig.fromLink(
            "inscreen://pair?host=192.168.1.20&setup_port=8764&https_port=8765" +
                "&token=$token&ca_sha256=$fingerprint"
        )
        assertEquals("192.168.1.20", config.host)
        assertEquals(8764, config.setupPort)
        assertEquals(8765, config.httpsPort)
        assertEquals(token, config.token)
        assertEquals(fingerprint, config.caSha256)
    }

    @Test
    fun rejectsInvalidFingerprint() {
        assertThrows(IllegalArgumentException::class.java) {
            PairConfig.fromLink(
                "inscreen://pair?host=192.168.1.20&setup_port=8764&https_port=8765" +
                    "&token=abcdefghijklmnopqrstuvwxyz1234567890abcd&ca_sha256=bad"
            )
        }
    }

    @Test
    fun computesStableSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PairingStore.sha256Hex("abc".toByteArray()),
        )
    }
}
