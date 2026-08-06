package com.inscreen.mic

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class GroqClient(
    private val baseUrl: String = "https://api.groq.com/openai/v1",
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun models(apiKey: String, callback: (Result<List<String>>) -> Unit) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        execute(request, { response ->
            val data = response.optJSONArray("data") ?: throw GroqFailure("invalid_response")
            val models = buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")?.trim()
                        ?.takeIf(String::isNotEmpty)?.let(::add)
                }
            }.distinct().sorted()
            if (models.isEmpty()) throw GroqFailure("models_empty")
            models
        }, callback)
    }

    fun query(apiKey: String, model: String, question: String, content: String, callback: (String) -> Unit) {
        val prompt = buildString {
            append("MATERIAL:\n")
            append(content.trim().ifEmpty { "[Sin material adicional]" })
            append("\n\nPREGUNTA:\n")
            append(question.trim())
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.1)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        execute(request, { response ->
            response.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
                ?.takeIf(String::isNotEmpty) ?: throw GroqFailure("empty_response")
        }) { result ->
            callback(result.fold(
                onSuccess = { JSONObject().put("ok", true).put("contenido", it).put("modelo", model).toString() },
                onFailure = { failureJson((it as? GroqFailure)?.code ?: "network_error") },
            ))
        }
    }

    private fun <T> execute(request: Request, parse: (JSONObject) -> T, callback: (Result<T>) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = callback(Result.failure(GroqFailure("network_error")))
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val code = when (it.code) {
                        401, 403 -> "authentication_error"
                        429 -> "rate_limited"
                        else -> "http_error"
                    }
                    if (!it.isSuccessful) return callback(Result.failure(GroqFailure(code)))
                    val parsed = runCatching {
                        val raw = it.body?.string().orEmpty()
                        parse(JSONObject(raw))
                    }.recoverCatching { error ->
                        throw if (error is GroqFailure) error else GroqFailure("invalid_response")
                    }
                    callback(parsed)
                }
            }
        })
    }

    private class GroqFailure(val code: String) : RuntimeException(code)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        fun failureJson(code: String): String = JSONObject()
            .put("ok", false).put("contenido", "").put("error", code).toString()
        val shared by lazy { GroqClient() }
    }
}
