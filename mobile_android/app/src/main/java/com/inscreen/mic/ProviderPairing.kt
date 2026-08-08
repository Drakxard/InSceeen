package com.inscreen.mic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProviderPairLink(val baseUrl: String, val token: String) {
    companion object {
        fun fromLink(link: String): ProviderPairLink {
            val uri = URI(link)
            require(uri.scheme == "inscreen" && uri.host == "provider-pair") { "QR de proveedor invalido." }
            val query = (uri.rawQuery ?: "").split("&").filter(String::isNotBlank).associate {
                val parts = it.split("=", limit = 2)
                URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
            }
            val baseUrl = query["base_url"].orEmpty().trimEnd('/')
            val token = query["token"].orEmpty()
            val endpoint = URI(baseUrl)
            require(endpoint.scheme == "https" && endpoint.host.isNotBlank() && endpoint.userInfo == null) { "URL de proveedor invalida." }
            require(token.startsWith("ipb1.") && token.length in 64..16_000) { "Paquete de vinculacion invalido." }
            return ProviderPairLink(baseUrl, token)
        }
    }
}

data class ProviderCredentials(val baseUrl: String, val token: String)
data class ProviderPairingResult(val credentials: ProviderCredentials, val groqApiKey: String)

internal class ProviderCredentialStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun installationId(): String = preferences.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(KEY_INSTALLATION_ID, it).apply()
    }

    fun load(): ProviderCredentials? = runCatching {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        ProviderCredentials(json.getString("baseUrl"), json.getString("token"))
    }.getOrNull()

    fun save(credentials: ProviderCredentials) {
        require(credentials.baseUrl.startsWith("https://") && credentials.token.startsWith("ipc1."))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
        val plaintext = JSONObject().put("baseUrl", credentials.baseUrl).put("token", credentials.token).toString()
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
        runCatching {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        }
    }

    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Missing provider encryption key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object {
        private const val PREFERENCES = "inscreen_provider_secure_settings"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_CIPHERTEXT = "provider_ciphertext"
        private const val KEY_IV = "provider_iv"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "inscreen_provider_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal class ProviderPairingClient(private val client: OkHttpClient = OkHttpClient()) {
    fun redeem(link: ProviderPairLink, installationId: String): ProviderPairingResult {
        val body = JSONObject().put("token", link.token).put("installationId", installationId).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("${link.baseUrl}/api/inscreen/provider/pairing/redeem").post(body).build()
        client.newCall(request).execute().use { response ->
            val payload = JSONObject(response.body?.string().orEmpty())
            require(response.isSuccessful) { payload.optString("error", "No se pudo vincular el proveedor.") }
            val baseUrl = payload.getString("providerBaseUrl").trimEnd('/')
            val providerToken = payload.getString("providerToken")
            val groqApiKey = payload.getString("groqApiKey")
            require(baseUrl == link.baseUrl && providerToken.startsWith("ipc1.") && groqApiKey.isNotBlank())
            return ProviderPairingResult(ProviderCredentials(baseUrl, providerToken), groqApiKey)
        }
    }
}
