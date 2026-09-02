package com.inscreen.mic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

internal object ModuleCatalog {
    private const val REPOSITORY = "Drakxard/InSceeen"
    private const val CONTENTS_BASE = "https://api.github.com/repos/$REPOSITORY/contents/"
    const val INDEX_URL = "${CONTENTS_BASE}modules/index.json?ref=main"
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    data class Module(val id: String, val name: String, val entry: String)
    data class Package(val html: String, val files: Map<String, ByteArray>, val version: String)

    fun load(callback: (Result<List<Module>>) -> Unit) {
        val request = Request.Builder().url(INDEX_URL).header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result -> callback(runCatching {
                    checkResponse(result, "leer el catálogo")
                    parse(content(result.body?.string().orEmpty()).toString(Charsets.UTF_8))
                }) }
            }
        })
    }

    /** Fija un commit con dos consultas API y baja sus recursos desde raw, fuera del cupo por archivo. */
    fun loadPackage(module: Module, callback: (Result<Package>) -> Unit) {
        val request = Request.Builder().url("https://api.github.com/repos/$REPOSITORY/commits/main")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.use { result ->
                val commit = runCatching {
                    checkResponse(result, "resolver la versión del módulo")
                    JSONObject(result.body?.string().orEmpty()).getString("sha").also {
                        require(it.matches(Regex("[0-9a-f]{40}"))) { "Versión de GitHub inválida" }
                    }
                }.getOrElse { callback(Result.failure(it)); return@use }
                loadPackageTree(module, commit, callback)
            }
        })
    }

    private fun loadPackageTree(module: Module, commit: String, callback: (Result<Package>) -> Unit) {
        val request = Request.Builder().url("https://api.github.com/repos/$REPOSITORY/git/trees/$commit?recursive=1")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.use { result ->
                val files = runCatching {
                    checkResponse(result, "buscar los archivos del módulo")
                    packagePaths(module, result.body?.string().orEmpty())
                }.getOrElse { callback(Result.failure(it)); return@use }
                downloadFiles(module, commit, files, 0, linkedMapOf(), callback)
            }
        })
    }

    private fun downloadFiles(module: Module, commit: String, files: List<String>, index: Int, downloaded: MutableMap<String, ByteArray>, callback: (Result<Package>) -> Unit) {
        if (index == files.size) {
            val entry = module.entry.substringAfterLast('/')
            val html = downloaded[entry]?.toString(Charsets.UTF_8)
                ?: return callback(Result.failure(IllegalStateException("Falta index.html")))
            callback(Result.success(Package(html, downloaded, packageVersion(downloaded))))
            return
        }
        val path = files[index]
        val prefix = module.entry.substringBeforeLast('/') + "/"
        val relative = path.removePrefix(prefix)
        loadRaw("https://raw.githubusercontent.com/$REPOSITORY/$commit/$path") { result ->
            result.fold(
                onSuccess = { downloaded[relative] = it; downloadFiles(module, commit, files, index + 1, downloaded, callback) },
                onFailure = { callback(Result.failure(it)) },
            )
        }
    }

    private fun packageVersion(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun loadRaw(url: String, callback: (Result<ByteArray>) -> Unit) {
        val request = Request.Builder().url(url).header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.use { result -> callback(runCatching {
                if (!result.isSuccessful) error("GitHub respondió ${result.code} al descargar un archivo")
                result.body?.bytes() ?: error("GitHub devolvió un archivo vacío")
            }) } }
        })
    }

    internal fun packagePaths(module: Module, raw: String): List<String> {
        val payload = JSONObject(raw)
        require(!payload.optBoolean("truncated")) { "GitHub devolvió un árbol incompleto" }
        val prefix = module.entry.substringBeforeLast('/') + "/"
        val tree = payload.getJSONArray("tree")
        val found = (0 until tree.length()).asSequence().mapNotNull(tree::optJSONObject)
            .filter { it.optString("type") == "blob" && it.optString("path").startsWith(prefix) }
            .map { it.optString("path") }.toList()
        require(found.any { it == module.entry }) { "El módulo no existe en GitHub" }
        require(found.size <= 128) { "El módulo tiene demasiados archivos" }
        found.forEach { path ->
            val relative = path.removePrefix(prefix)
            require(relative.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,239}")) && !relative.split('/').any { it == "." || it == ".." }) { "Ruta de recurso inválida" }
        }
        return found
    }

    private fun checkResponse(response: okhttp3.Response, action: String) {
        if (response.isSuccessful) return
        if (response.header("X-RateLimit-Remaining") == "0") {
            error("GitHub alcanzó su límite temporal. Espera unos minutos y vuelve a intentar")
        }
        error("GitHub respondió ${response.code} al $action")
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
