package com.inscreen.mic

import android.content.Context
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

data class PairConfig(
    val host: String,
    val setupPort: Int,
    val httpsPort: Int,
    val token: String,
    val caSha256: String,
    val caDerBase64: String = "",
) {
    companion object {
        fun fromLink(link: String): PairConfig {
            val uri = URI(link)
            require(uri.scheme == "inscreen" && uri.host == "pair") { "Enlace de vinculación inválido." }
            val query = (uri.rawQuery ?: "").split("&")
                .filter { it.isNotBlank() }
                .associate {
                    val parts = it.split("=", limit = 2)
                    URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
                        URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
                }
            val host = query["host"].orEmpty()
            val token = query["token"].orEmpty()
            val fingerprint = query["ca_sha256"].orEmpty().lowercase()
            val setupPort = query["setup_port"]?.toIntOrNull() ?: 0
            val httpsPort = query["https_port"]?.toIntOrNull() ?: 0
            require(host.isNotBlank() && token.length >= 32) { "Faltan datos de la PC." }
            require(setupPort in 1..65535 && httpsPort in 1..65535) { "Puerto inválido." }
            require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Huella del certificado inválida." }
            return PairConfig(host, setupPort, httpsPort, token, fingerprint)
        }
    }

    fun caDownloadUrl(): String =
        "http://$host:$setupPort/ca.crt?token=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
}

object PairingStore {
    private const val PREFS = "inscreen_pairing"

    fun downloadAndVerifyCa(config: PairConfig): PairConfig {
        val connection = URL(config.caDownloadUrl()).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "La PC rechazó el certificado (${connection.responseCode})."
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            require(sha256Hex(bytes) == config.caSha256) {
                "La huella del certificado no coincide. Vuelve a escanear el QR."
            }
            return config.copy(caDerBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally {
            connection.disconnect()
        }
    }

    fun save(context: Context, config: PairConfig) {
        require(config.caDerBase64.isNotBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", config.host)
            .putInt("setup_port", config.setupPort)
            .putInt("https_port", config.httpsPort)
            .putString("token", config.token)
            .putString("ca_sha256", config.caSha256)
            .putString("ca_der", config.caDerBase64)
            .apply()
    }

    fun load(context: Context): PairConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString("host", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val fingerprint = prefs.getString("ca_sha256", null) ?: return null
        val caDer = prefs.getString("ca_der", null) ?: return null
        val setupPort = prefs.getInt("setup_port", 0)
        val httpsPort = prefs.getInt("https_port", 0)
        if (setupPort !in 1..65535 || httpsPort !in 1..65535) return null
        return PairConfig(host, setupPort, httpsPort, token, fingerprint, caDer)
    }

    fun updateEndpoint(context: Context, config: PairConfig, host: String, httpsPort: Int): PairConfig {
        require(host.isNotBlank() && httpsPort in 1..65535)
        return config.copy(host = host, httpsPort = httpsPort).also { save(context, it) }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
