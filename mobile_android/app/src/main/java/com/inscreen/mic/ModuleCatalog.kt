package com.inscreen.mic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

internal object ModuleCatalog {
    private const val CONTENTS_BASE = "https://api.github.com/repos/Drakxard/InSceeen/contents/"
    const val INDEX_URL = "${CONTENTS_BASE}modules/index.json?ref=main"
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    data class Module(val id: String, val name: String, val entry: String)
    data class Package(val html: String, val files: Map<String, ByteArray>)

    fun load(callback: (Result<List<Module>>) -> Unit) {
        val request = Request.Builder().url(INDEX_URL).header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result -> callback(runCatching {
                    if (!result.isSuccessful) error("GitHub respondió ${result.code}")
                    parse(content(result.body?.string().orEmpty()).toString(Charsets.UTF_8))
                }) }
            }
        })
    }

    /** Descarga todos los archivos dentro de la carpeta que contiene el entry del módulo. */
    fun loadPackage(module: Module, callback: (Result<Package>) -> Unit) {
        val treeRequest = Request.Builder()
            .url("https://api.github.com/repos/Drakxard/InSceeen/git/trees/main?recursive=1")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "InScreenMic").build()
        client.newCall(treeRequest).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result ->
                    val files = runCatching {
                        if (!result.isSuccessful) error("GitHub respondió ${result.code} al buscar el módulo")
                        val prefix = module.entry.substringBeforeLast('/') + "/"
                        val found = JSONObject(result.body?.string().orEmpty()).getJSONArray("tree")
                            .let { tree -> (0 until tree.length()).asSequence().mapNotNull(tree::optJSONObject)
                                .filter { it.optString("type") == "blob" && it.optString("path").startsWith(prefix) }
                                .map { it.optString("path") to it.optString("sha") }.toList() }
                        require(found.any { it.first == module.entry }) { "El módulo no existe en GitHub" }
                        require(found.size <= 128) { "El módulo tiene demasiados archivos" }
                        found
                    }.getOrElse { callback(Result.failure(it)); return }
                    downloadFiles(module, files, 0, linkedMapOf(), callback)
                }
            }
        })
    }

    private fun downloadFiles(module: Module, files: List<Pair<String, String>>, index: Int, downloaded: MutableMap<String, ByteArray>, callback: (Result<Package>) -> Unit) {
        if (index == files.size) {
            val entry = module.entry.substringAfterLast('/')
            val html = downloaded[entry]?.toString(Charsets.UTF_8)
                ?: return callback(Result.failure(IllegalStateException("Falta index.html")))
            callback(Result.success(Package(html, downloaded)))
            return
        }
        val (path, sha) = files[index]
        val prefix = module.entry.substringBeforeLast('/') + "/"
        val relative = path.removePrefix(prefix)
        if (relative.isBlank() || relative.split('/').any { it == "." || it == ".." }) {
            callback(Result.failure(IllegalArgumentException("Ruta de recurso inválida"))); return
        }
        loadBlob("https://api.github.com/repos/Drakxard/InSceeen/git/blobs/$sha") { result ->
            result.fold(
                onSuccess = { downloaded[relative] = it; downloadFiles(module, files, index + 1, downloaded, callback) },
                onFailure = { callback(Result.failure(it)) },
            )
        }
    }

    private fun loadBlob(url: String, callback: (Result<ByteArray>) -> Unit) {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.use { result -> callback(runCatching {
                if (!result.isSuccessful) error("GitHub respondió ${result.code}")
                content(result.body?.string().orEmpty())
            }) } }
        })
    }

    private fun content(raw: String): ByteArray {
        val payload = JSONObject(raw)
        require(payload.optString("encoding") == "base64") { "Contenido GitHub inválido" }
        return Base64.getDecoder().decode(payload.getString("content").replace(Regex("\\s"), ""))
    }

    internal fun parse(raw: String): List<Module> {
        val items: JSONArray = JSONObject(raw).optJSONArray("modules") ?: error("Falta modules")
        val ids = mutableSetOf<String>()
        return (0 until items.length()).map { index ->
            val item = items.optJSONObject(index) ?: error("Módulo inválido")
            val id = item.optString("id").trim(); val name = item.optString("nombre").trim(); val entry = item.optString("entry").trim()
            require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}"))) { "id inválido" }
            require(name.isNotEmpty() && entry.matches(Regex("modules/[a-z0-9][a-z0-9-]{0,79}/index\\.html"))) { "Módulo inválido" }
            require(ids.add(id)) { "id duplicado" }; Module(id, name, entry)
        }
    }
}
