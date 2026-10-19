package com.inscreen.mic

import android.content.Context
import android.util.AtomicFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A web release is installed completely before its pointer can become visible. */
internal class CursadoWebPackage(
    private val root: File,
    private val bundledManifest: () -> JSONObject,
    private val bundledFile: (String, String) -> InputStream,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
) {
    constructor(context: Context) : this(
        File(context.filesDir, "cursado-web"),
        { JSONObject(context.assets.open("native-web/current.json").bufferedReader().use { it.readText() }) },
        { revision, path -> context.assets.open("native-web/releases/$revision/$path") },
    )

    private fun activeDirectory(): File? {
        val pointer = File(root, "active")
        if (pointer.isFile) {
            val revision = pointer.readText()
            if (WorkspaceLibraryStore.validHash(revision)) {
                val directory = File(root, revision)
                if (File(directory, "complete.json").isFile) return directory
            }
        }
        return null
    }
    // Called only by the startup worker. Resuming a PDF never switches this pointer.
    fun installed(baseUrl: String? = null): File = synchronized(lock) {
        root.mkdirs()
        val manifest = bundledManifest()
        val revision = manifest.getString("revision")
        val bundled = File(root, "bundled-revision")
        val active = activeDirectory()
        if (!needsBundledInstall(revision, bundled.takeIf { it.isFile }?.readText(), active != null)) {
            val pending = File(root, "pending.json")
            if (pending.isFile) {
                val prepared = runCatching { JSONObject(pending.readText()) }.getOrNull()
                val next = prepared?.optString("revision").orEmpty()
                if (prepared?.optString("baseUrl") == baseUrl && WorkspaceLibraryStore.validHash(next) && File(root, "$next/complete.json").isFile) {
                    writePointer(next)
                }
                pending.delete()
            }
            return@synchronized activeDirectory()!!
        }
        val directory = try {
            install(manifest) { path -> bundledFile(revision, path) }.also { writePointer(revision) }
        } catch (error: Exception) { return@synchronized active ?: throw error }
        // Mark only after verification and activation. A failed APK upgrade retries
        // on next open; subsequent OTA updates survive normal app restarts.
        val marker = AtomicFile(bundled)
        val output = marker.startWrite()
        try { output.write(revision.toByteArray()); marker.finishWrite(output) }
        catch (error: Exception) { marker.failWrite(output); throw error }
        File(root, "pending.json").delete()
        directory
    }

    fun update(baseUrl: String, stillCurrent: () -> Boolean = { true }): Boolean = synchronized(downloadLock) {
        require(WorkspaceLink.validBase(baseUrl))
        val manifest = client.newCall(Request.Builder().url("$baseUrl/native-web/current.json").build()).execute().use { response ->
            check(response.isSuccessful) { "No se pudo actualizar Cursado." }
            val body = response.body ?: error("Paquete vacío.")
            val bytes = body.byteStream().use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) { val count = input.read(chunk); if (count < 0) break; require(buffer.size() + count <= 2_000_000); buffer.write(chunk, 0, count) }
                buffer.toByteArray()
            }
            require(bytes.size <= 2_000_000)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        validate(manifest)
        val revision = manifest.getString("revision")
        val unchanged = synchronized(lock) {
            val pending = File(root, "pending.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            if (activeDirectory()?.name == revision) {
                File(root, "pending.json").delete()
                true
            } else pending?.optString("revision") == revision && pending.optString("baseUrl") == baseUrl && File(root, "$revision/complete.json").isFile
        }
        if (unchanged) return@synchronized false
        install(manifest) { path ->
            val url = "$baseUrl/native-web/releases/${manifest.getString("revision")}/$path"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) { response.close(); error("Descarga de interfaz interrumpida.") }
            val body = response.body ?: run { response.close(); error("Recurso vacío.") }
            object : java.io.FilterInputStream(body.byteStream()) { override fun close() { super.close(); response.close() } }
        }
        synchronized(lock) {
            check(stillCurrent()) { "La biblioteca cambió durante la actualización." }
            WorkspaceLibraryStore.atomicJson(File(root, "pending.json"), JSONObject().put("revision", revision).put("baseUrl", baseUrl))
        }
        true
    }

    fun prune(pinned: File) = synchronized(downloadLock) { synchronized(lock) {
        val active = activeDirectory()?.canonicalFile
        val pending = File(root, "pending.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).getString("revision") }.getOrNull() }
        root.listFiles()?.filter { it.isDirectory && WorkspaceLibraryStore.validHash(it.name) && it.name != pending && it.canonicalFile != active && it.canonicalFile != pinned.canonicalFile }?.forEach { it.deleteRecursively() }
        root.listFiles()?.filter { it.isDirectory && it.name.endsWith(".part") && WorkspaceLibraryStore.validHash(it.name.removeSuffix(".part")) }?.forEach { it.deleteRecursively() }
    } }

    private fun install(manifest: JSONObject, source: (String) -> InputStream): File {
        validate(manifest)
        val revision = manifest.getString("revision")
        val destination = File(root, revision)
        if (!File(destination, "complete.json").isFile) {
            val staging = File(root, "$revision.part")
            staging.deleteRecursively(); check(staging.mkdirs())
            try {
                val files = manifest.getJSONArray("files")
                val required = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("size") }
                check(root.usableSpace > required + 16 * 1024 * 1024) { "No hay espacio para actualizar la interfaz. Se conserva la anterior." }
                for (i in 0 until files.length()) {
                    val item = files.getJSONObject(i)
                    val file = File(staging, item.getString("path")); file.parentFile!!.mkdirs()
                    source(item.getString("path")).use { input -> copyVerified(item, input, file) }
                }
                WorkspaceLibraryStore.atomicJson(File(staging, "complete.json"), manifest)
                check(staging.renameTo(destination))
            } finally { staging.deleteRecursively() }
        }
        return destination
    }

    private fun writePointer(revision: String) {
        val pointer = AtomicFile(File(root, "active")); val output = pointer.startWrite()
        try { output.write(revision.toByteArray()); pointer.finishWrite(output) }
        catch (error: Exception) { pointer.failWrite(output); throw error }
    }

    companion object {
        private val lock = Any()
        private val downloadLock = Any()
        fun needsBundledInstall(bundledRevision: String, installedBundle: String?, hasActive: Boolean) =
            !hasActive || installedBundle != bundledRevision
        fun copyVerified(item: JSONObject, input: InputStream, file: File) {
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            try {
                file.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count; require(size <= item.getLong("size"))
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                require(size == item.getLong("size") && WorkspaceLibraryStore.hex(digest.digest()) == item.getString("hash")) { "Interfaz incompleta o dañada." }
            } catch (error: Exception) { file.delete(); throw error }
        }
        fun validPath(path: String) = path.length in 1..500 && path.split('/').all { it.matches(Regex("[a-zA-Z0-9_.-]+")) && it != "." && it != ".." } && path != "complete.json"
        fun validate(manifest: JSONObject) {
            require(manifest.getInt("version") == 1 && manifest.getInt("bridgeVersion") == 1) { "Esta interfaz necesita otra versión de InScreen." }
            require(WorkspaceLibraryStore.validHash(manifest.getString("revision")))
            val files = manifest.getJSONArray("files"); require(files.length() in 1..6000)
            val paths = mutableSetOf<String>(); var total = 0L
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i); val path = file.getString("path")
                require(validPath(path) && paths.add(path) && WorkspaceLibraryStore.validHash(file.getString("hash")))
                val size = file.getLong("size"); require(size in 0..64L * 1024 * 1024); total += size
            }
            require(total <= 256L * 1024 * 1024 && paths.containsAll(listOf("index.html", "app.js", "app.css", "pizarra/index.html")))
        }
    }
}
