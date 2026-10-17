package com.inscreen.mic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal data class WorkspaceLink(val baseUrl: String, val token: String) {
    companion object {
        fun parse(link: String): WorkspaceLink {
            val uri = URI(link)
            require(uri.scheme == "inscreen" && uri.host == "workspace-pair") { "QR de biblioteca inválido." }
            val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            val base = query["base_url"].orEmpty().trimEnd('/')
            require(validBase(base)) { "La biblioteca necesita una dirección HTTPS válida." }
            val token = query["token"].orEmpty()
            require(token.startsWith("wl1.") && token.length in 64..16000) { "Credencial de biblioteca inválida." }
            return WorkspaceLink(base, token)
        }
        fun validBase(base: String): Boolean = runCatching {
            val uri = URI(base)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")
        }.getOrDefault(false)
    }
}
internal data class WorkspaceCredentials(val baseUrl: String, val token: String, val workspaceId: String, val name: String) {
    fun json() = JSONObject().put("baseUrl", baseUrl).put("token", token).put("workspaceId", workspaceId).put("name", name)
}
internal class WorkspaceCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("workspace_library_secure", Context.MODE_PRIVATE)
    fun installationId(): String = preferences.getString("installationId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("installationId", it).commit() }
    private fun key(create: Boolean): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(ALIAS, null)?.let { return it }
        check(create) { "Falta la clave de biblioteca." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): WorkspaceCredentials? {
        val encrypted = preferences.getString("encrypted", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)))
        val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        return WorkspaceCredentials(json.getString("baseUrl"), json.getString("token"), json.getString("workspaceId"), json.getString("name"))
    }
    fun save(value: WorkspaceCredentials) {
        require(WorkspaceLink.validBase(value.baseUrl) && value.token.startsWith("wl1.") && WorkspaceLibraryStore.validId(value.workspaceId))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val encrypted = cipher.doFinal(value.json().toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun clear() { preferences.edit().remove("encrypted").remove("iv").commit() }
    companion object { private const val ALIAS = "inscreen_workspace_library_v1" }
}
internal class WorkspaceHttpException(val status: Int, message: String) : IOException(message)
internal class WorkspaceLibraryStore(private val context: Context, val credentials: WorkspaceCredentials) {
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).followRedirects(false).build()
    val directory = File(context.filesDir, "workspace-library/${sha256((credentials.baseUrl + "/" + credentials.workspaceId).toByteArray())}").apply { mkdirs() }
    private val blobs = File(directory, "blobs").apply { mkdirs() }
    fun online(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    fun wifi(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    fun request(action: String, json: JSONObject? = null): JSONObject {
        val request = Request.Builder().url("${credentials.baseUrl}/api/inscreen/workspace/$action").header("Authorization", "Bearer ${credentials.token}")
        if (json != null) request.post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            val result = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { throw IOException("Respuesta de biblioteca inválida.") }
            if (!response.isSuccessful) throw WorkspaceHttpException(response.code, result.optString("error", "No se pudo conectar con Cursado."))
            return result
        }
    }
    private fun read(name: String): JSONObject? = synchronized(lock) {
        val atomic = AtomicFile(File(directory, name))
        if (!atomic.baseFile.exists() && !File(directory, "$name.bak").exists()) return@synchronized null
        JSONObject(String(atomic.readFully(), Charsets.UTF_8))
    }
    fun active(): JSONObject? = read("active.json")
    fun catalog(): JSONObject? = read("catalog.json")
    fun visible(): JSONObject? = if (online()) catalog() ?: active() else active() ?: catalog()
    fun downloaded(file: JSONObject): Boolean = blob(file).let { it.isFile && it.length() == file.getLong("size") }
    fun blob(file: JSONObject): File {
        val hash = file.getString("hash"); require(validHash(hash))
        return File(blobs, hash)
    }
    private fun currentSelection() = WorkspaceCredentialStore(context).load()?.let { it.workspaceId == credentials.workspaceId && it.baseUrl == credentials.baseUrl && it.token == credentials.token } == true
    fun sync(downloadAll: Boolean, progress: (String) -> Unit = {}): Boolean {
        if (!currentSelection()) return false
        progress("Buscando novedades…")
        val inventory = request("manifest").getJSONObject("manifest")
        validateManifest(inventory, credentials.workspaceId)
        synchronized(lock) {
            if (!currentSelection()) return false
            atomicJson(File(directory, "catalog.json"), inventory)
        }
        if (!downloadAll || !wifi()) return true
        val files = inventory.getJSONArray("files")
        for (index in 0 until files.length()) {
            if (!currentSelection() || !wifi()) return false
            progress("Descargando ${index + 1}/${files.length()}")
            ensureFile(inventory, files.getJSONObject(index))
        }
        synchronized(lock) {
        if (!currentSelection()) return false
        val previous = active()
        if (previous != null && previous.optString("revision") != inventory.getString("revision")) atomicJson(File(directory, "previous.json"), previous)
        atomicJson(File(directory, "active.json"), inventory)
        // Old revisions are retained until the new snapshot is fully verified.
        val keep = (0 until files.length()).map { files.getJSONObject(it).getString("hash") }.toMutableSet()
        read("previous.json")?.optJSONArray("files")?.let { old -> for (i in 0 until old.length()) keep.add(old.getJSONObject(i).getString("hash")) }
        blobs.listFiles()?.filter { it.name !in keep && !it.name.endsWith(".part") }?.forEach { it.delete() }
        }
        progress("Disponible sin conexión")
        return true
    }
    fun ensureFile(inventory: JSONObject, file: JSONObject): File = synchronized(downloadLocks.getOrPut(file.getString("hash")) { Any() }) {
        val destination = blob(file)
        if (downloaded(file)) return@synchronized destination
        if (!online()) throw IOException("Este archivo todavía no está disponible sin conexión. Conectate a Wi-Fi para descargarlo.")
        val expected = file.getLong("size")
        if (directory.usableSpace < expected + 16 * 1024 * 1024) throw IOException("No hay espacio suficiente. Liberá almacenamiento y reintentá.")
        val result = request("download?id=${file.getString("id")}&revision=${inventory.getString("revision")}")
        val download = result.getString("url")
        val uri = URI(download)
        require(uri.scheme == "https" && uri.host.endsWith(".r2.cloudflarestorage.com") && uri.rawUserInfo == null) { "Dirección de descarga inválida." }
        val temporary = File(blobs, "${file.getString("hash")}.part")
        try {
            client.newCall(Request.Builder().url(download).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Falló la descarga (${response.code}). Reintentá.")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                response.body!!.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        received += count
                        if (received > expected) throw IOException("El archivo recibido supera el tamaño esperado.")
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                if (received != expected || hex(digest.digest()) != file.getString("hash")) throw IOException("La descarga quedó incompleta o dañada. Reintentá.")
            }
            synchronized(lock) {
                check(currentSelection()) { "La biblioteca cambió durante la descarga." }
                check(temporary.renameTo(destination)) { "No se pudo guardar el archivo descargado." }
            }
            destination
        } finally { temporary.delete() }
    }
    companion object {
        val lock = Any()
        private val downloadLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
        fun validId(value: String) = value.matches(Regex("[a-zA-Z0-9_-]{16,100}"))
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun atomicJson(file: File, value: JSONObject) = synchronized(lock) {
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
        fun validateManifest(manifest: JSONObject, workspaceId: String) {
            require(manifest.getInt("version") == 1 && manifest.getString("workspaceId") == workspaceId && validId(manifest.getString("revision"))) { "Biblioteca incompatible." }
            val files = manifest.getJSONArray("files"); require(files.length() <= 50000)
            val ids = mutableSetOf<String>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                require(validHash(f.getString("hash")) && validHash(f.getString("id")) && ids.add(f.getString("id")) && f.getLong("size") in 0..20L * 1024 * 1024 * 1024) { "Inventario inválido." }
            }
            val items = manifest.getJSONArray("items"); require(items.length() <= 100000)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                require(!item.has("fileId") || item.getString("fileId") in ids) { "Referencia de archivo inválida." }
            }
        }
        fun redeem(context: Context, link: WorkspaceLink): WorkspaceCredentials {
            val credentials = WorkspaceCredentials(link.baseUrl, link.token, "temporary-library-id", "Cursado")
            val result = WorkspaceLibraryStore(context, credentials).request("pairing/redeem", JSONObject().put("token", link.token).put("installationId", WorkspaceCredentialStore(context).installationId()).put("name", android.os.Build.MODEL))
            require(result.getString("baseUrl") == link.baseUrl)
            val next = WorkspaceCredentials(link.baseUrl, result.getString("token"), result.getString("workspaceId"), result.getString("name"))
            require(validId(next.workspaceId) && next.token.startsWith("wl1."))
            val probe = WorkspaceLibraryStore(context, next).request("manifest").getJSONObject("manifest")
            validateManifest(probe, next.workspaceId)
            return next
        }
    }
}
