package com.inscreen.mic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Base64

internal object ModuleCatalog {
    private const val CONTENTS_BASE = "https://api.github.com/repos/Drakxard/InSceeen/contents/"
    const val INDEX_URL = "${CONTENTS_BASE}modules/index.json?ref=main"
    private const val RAW_BASE = "https://raw.githubusercontent.com/Drakxard/InSceeen/main/"
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    data class Module(val id: String, val name: String, val entry: String) {
        fun url(): String = RAW_BASE + entry
    }

    fun load(callback: (Result<List<Module>>) -> Unit) {
        val request = Request.Builder().url(INDEX_URL).header("User-Agent", "InScreenMic").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result ->
                    callback(runCatching {
                        if (!result.isSuccessful) error("GitHub respondió ${result.code}")
                        parse(content(result.body?.string().orEmpty()))
                    })
                }
            }
        })
    }

    fun loadHtml(module: Module, callback: (Result<String>) -> Unit) {
        val request = Request.Builder()
            .url("${CONTENTS_BASE}${module.entry}?ref=main")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "InScreenMic")
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(Result.failure(error))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result ->
                    callback(runCatching {
                        if (!result.isSuccessful) error("GitHub respondió ${result.code}")
                        content(result.body?.string().orEmpty())
                    })
                }
            }
        })
    }

    private fun content(raw: String): String {
        val payload = JSONObject(raw)
        require(payload.optString("encoding") == "base64") { "Contenido GitHub inválido" }
        return Base64.getDecoder().decode(payload.getString("content").replace(Regex("\\s"), ""))
            .toString(Charsets.UTF_8)
    }

    internal fun parse(raw: String): List<Module> {
        val source = JSONObject(raw)
        val items: JSONArray = source.optJSONArray("modules") ?: error("Falta modules")
        val ids = mutableSetOf<String>()
        return (0 until items.length()).map { index ->
            val item = items.optJSONObject(index) ?: error("Módulo inválido")
            val id = item.optString("id").trim()
            val name = item.optString("nombre").trim()
            val entry = item.optString("entry").trim()
            require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}"))) { "id inválido" }
            require(name.isNotEmpty() && entry.matches(Regex("modules/[a-z0-9][a-z0-9-]{0,79}/index\\.html"))) {
                "Módulo inválido"
            }
            require(ids.add(id)) { "id duplicado" }
            Module(id, name, entry)
        }
    }
}
