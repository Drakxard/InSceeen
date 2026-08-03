package com.inscreen.mic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryProtocolTest {
    private val token = "test-token-abcdefghijklmnopqrstuvwxyz012345"
    private val nonce = "00112233445566778899aabbccddeeff"
    private val fingerprint = "ab".repeat(32)
    private val config = PairConfig(
        host = "192.168.1.8",
        setupPort = 8764,
        httpsPort = 8765,
        token = token,
        caSha256 = fingerprint,
        caDerBase64 = "certificate",
    )

    @Test
    fun hmacMatchesPythonVector() {
        assertEquals(
            "4b4fe73eda090bbd4af911007d61d43fba662a70e308a14f9ac90364dfc4f597",
            DiscoveryProtocol.proof(token, "discover", nonce, fingerprint),
        )
        assertEquals(
            "44730c3a84ae7b4a6a7a30da153651f7fdb9b6a000af139505bb91d70afcac80",
            DiscoveryProtocol.proof(token, "discover.response", nonce, fingerprint, 8765),
        )
    }

    @Test
    fun requestAndResponseRequireMatchingIdentityNonceAndProof() {
        val request = JSONObject(DiscoveryProtocol.buildRequest(config, nonce))
        assertEquals("discover", request.getString("type"))
        assertEquals(fingerprint, request.getString("ca_sha256"))

        val response = JSONObject()
            .put("v", 1)
            .put("type", "discover.response")
            .put("nonce", nonce)
            .put("ca_sha256", fingerprint)
            .put("https_port", 8765)
            .put(
                "proof",
                DiscoveryProtocol.proof(
                    token,
                    "discover.response",
                    nonce,
                    fingerprint,
                    8765,
                ),
            )
        assertNotNull(DiscoveryProtocol.parseResponse(response.toString(), config, nonce))
        assertNull(
            DiscoveryProtocol.parseResponse(
                response.put("proof", "0".repeat(64)).toString(),
                config,
                nonce,
            ),
        )
        assertNull(DiscoveryProtocol.parseResponse(response.toString(), config, "f".repeat(32)))
    }
}
