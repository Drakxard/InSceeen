package com.inscreen.mic

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class DiscoveryResponse(val httpsPort: Int)

object DiscoveryProtocol {
    const val VERSION = 1
    const val MAX_PACKET_BYTES = 2_048

    fun newNonce(): String = ByteArray(16).also(SecureRandom()::nextBytes).toHex()

    fun buildRequest(config: PairConfig, nonce: String): String {
        require(isValidNonce(nonce))
        val fingerprint = config.caSha256.lowercase()
        return JSONObject()
            .put("v", VERSION)
            .put("type", "discover")
            .put("nonce", nonce)
            .put("ca_sha256", fingerprint)
            .put("proof", proof(config.token, "discover", nonce, fingerprint))
            .toString()
    }

    fun parseResponse(raw: String, config: PairConfig, expectedNonce: String): DiscoveryResponse? {
        val message = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val nonce = message.optString("nonce")
        val fingerprint = message.optString("ca_sha256").lowercase()
        val httpsPort = message.optInt("https_port", 0)
        val receivedProof = message.optString("proof")
        if (
            message.optInt("v", -1) != VERSION ||
            message.optString("type") != "discover.response" ||
            nonce != expectedNonce ||
            !isValidNonce(nonce) ||
            fingerprint != config.caSha256.lowercase() ||
            httpsPort !in 1..65535
        ) return null
        val expected = proof(
            config.token,
            "discover.response",
            nonce,
            fingerprint,
            httpsPort,
        )
        if (!MessageDigest.isEqual(receivedProof.toByteArray(), expected.toByteArray())) return null
        return DiscoveryResponse(httpsPort)
    }

    fun proof(
        token: String,
        kind: String,
        nonce: String,
        caSha256: String,
        httpsPort: Int? = null,
    ): String {
        val parts = mutableListOf(kind, VERSION.toString(), nonce, caSha256.lowercase())
        if (httpsPort != null) parts += httpsPort.toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(parts.joinToString("|").toByteArray(Charsets.US_ASCII)).toHex()
    }

    private fun isValidNonce(nonce: String): Boolean =
        nonce.length == 32 && nonce.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
