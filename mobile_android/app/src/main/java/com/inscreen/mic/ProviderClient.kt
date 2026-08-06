package com.inscreen.mic

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object ProviderSubject {
    fun segment(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
}

internal class ProviderClient(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    fun request(kind: String, subjectSegment: String, day: Int, callback: (String) -> Unit) {
        val endpoint = when (kind) {
            "paginasLeidas" -> "paginas-leidas"
            "traduccion" -> "traducciones"
            else -> return callback(failure("unsupported_operation"))
        }
        if (day !in 0..6) return callback(failure("invalid_day"))
        if (subjectSegment.isBlank()) return callback(failure("invalid_subject"))
        if (baseUrl.isBlank() || token.isBlank()) return callback(failure("provider_not_configured"))

        val url = runCatching {
            "${baseUrl.trimEnd('/')}/api/inscreen/provider/$endpoint".toHttpUrl().newBuilder()
                .addQueryParameter("materia", subjectSegment)
                .addQueryParameter("dia", day.toString())
                .build()
        }.getOrElse { return callback(failure("invalid_provider_url")) }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(failure("network_error"))

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result ->
                    if (!result.isSuccessful) {
                        callback(failure("http_${result.code}"))
                        return
                    }
                    callback(normalizeResponse(result.body?.string().orEmpty()))
                }
            }
        })
    }

    internal fun normalizeResponse(raw: String): String {
        return runCatching {
            val source = JSONObject(raw)
            val ok = source.optBoolean("ok", false)
            val stage = source.optInt("etapa", 0)
            if (ok && stage <= 0) return failure("invalid_stage")
            val files = JSONArray()
            val sourceFiles = source.optJSONArray("archivos") ?: JSONArray()
            for (index in 0 until sourceFiles.length()) {
                val item = sourceFiles.optJSONObject(index) ?: continue
                val name = item.optString("nombre").trim()
                val content = item.optString("contenido")
                if (name.matches(Regex("[1-9][0-9]*\\.txt"))) {
                    files.put(JSONObject().put("nombre", name).put("contenido", content))
                }
            }
            JSONObject().put("ok", ok).put("archivos", files).apply {
                if (ok) put("etapa", stage)
                else put("error", source.optString("error", "provider_rejected"))
            }.toString()
        }.getOrElse { failure("invalid_response") }
    }

    private fun failure(error: String): String = JSONObject()
        .put("ok", false).put("archivos", JSONArray()).put("error", error).toString()

    companion object {
        val shared by lazy { ProviderClient(BuildConfig.PROVIDER_BASE_URL, BuildConfig.PROVIDER_TOKEN) }
    }
}
