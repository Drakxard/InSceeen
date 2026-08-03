package com.inscreen.mic

import org.json.JSONObject

object PairRequestProtocol {
    const val PORT = 8763

    fun build(nonce: String = DiscoveryProtocol.newNonce()): String {
        require(nonce.length == 32 && nonce.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        return JSONObject()
            .put("v", DiscoveryProtocol.VERSION)
            .put("type", "pair.request")
            .put("nonce", nonce.lowercase())
            .toString()
    }
}
