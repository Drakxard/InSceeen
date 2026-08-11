package com.inscreen.mic

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
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
    class MarkerException(val code: String) : IOException(code)

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

    fun requestLatestTranslation(subjectSegment: String, lastFile: String?, callback: (String) -> Unit) {
        if (subjectSegment.isBlank()) return callback(failure("invalid_subject"))
        if (lastFile != null && !lastFile.matches(Regex("[1-9][0-9]*\\.txt"))) {
            return callback(failure("invalid_last_file"))
        }
        if (baseUrl.isBlank() || token.isBlank()) return callback(failure("provider_not_configured"))
        val url = runCatching {
            "${baseUrl.trimEnd('/')}/api/inscreen/provider/traducciones".toHttpUrl().newBuilder()
                .addQueryParameter("materia", subjectSegment)
                .apply { if (lastFile != null) addQueryParameter("ultimo", lastFile) }
                .build()
        }.getOrElse { return callback(failure("invalid_provider_url")) }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = callback(failure("network_error"))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result ->
                    if (!result.isSuccessful) return callback(failure("http_${result.code}"))
                    callback(normalizeResponse(result.body?.string().orEmpty()))
                }
            }
        })
    }

    @Throws(MarkerException::class)
    fun transcribeMarker(image: File): String {
        if (baseUrl.isBlank() || token.isBlank()) throw MarkerException("provider_not_configured")
        if (!image.isFile || image.length() <= 0L) throw MarkerException("invalid_image")
        if (image.length() > MAX_MARKER_IMAGE_BYTES) throw MarkerException("image_too_large")
        val url = runCatching {
            "${baseUrl.trimEnd('/')}/api/inscreen/provider/marker-transcribe".toHttpUrl()
        }.getOrElse { throw MarkerException("invalid_provider_url") }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "nota.jpg", image.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()
        val markerClient = client.newBuilder().callTimeout(300, TimeUnit.SECONDS).build()
        return try {
            markerClient.newCall(request).execute().use { response ->
                val payload = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: throw MarkerException("invalid_response")
                if (!response.isSuccessful || !payload.optBoolean("ok", false)) {
                    throw MarkerException(payload.optString("error", "http_${response.code}"))
                }
                payload.optString("markdown").trim().ifBlank { throw MarkerException("empty_marker_result") }
            }
        } catch (error: MarkerException) {
            throw error
        } catch (_: IOException) {
            throw MarkerException("network_error")
        }
    }

    internal fun normalizeResponse(raw: String): String {
        return runCatching {
            val source = JSONObject(raw)
            val ok = source.optBoolean("ok", false)
            fun normalizeFiles(sourceFiles: JSONArray): JSONArray {
                val files = JSONArray()
                for (index in 0 until sourceFiles.length()) {
                    val item = sourceFiles.optJSONObject(index) ?: continue
                    val name = item.optString("nombre").trim()
                    if (name.matches(Regex("[1-9][0-9]*\\.txt"))) {
                        files.put(JSONObject().put("nombre", name).put("contenido", item.optString("contenido")))
                    }
                }
                return files
            }
            if (!ok) return JSONObject().put("ok", false).put("archivos", JSONArray())
                .put("error", source.optString("error", "provider_rejected")).toString()

            val files = normalizeFiles(source.optJSONArray("archivos") ?: JSONArray())
            if (source.has("nuevaEtapa")) {
                val newStageSource = source.optJSONObject("nuevaEtapa")
                val newStage = newStageSource?.let {
                    val stage = it.optInt("etapa", -1)
                    if (stage < 0) return failure("invalid_stage")
                    val stageFiles = normalizeFiles(it.optJSONArray("archivos") ?: JSONArray())
                    if (stageFiles.length() == 0 || stageFiles.getJSONObject(0).optString("nombre") != "1.txt") {
                        return failure("invalid_new_stage")
                    }
                    JSONObject().put("etapa", stage).put("archivos", stageFiles)
                }
                return JSONObject().put("ok", true).put("archivos", files)
                    .put("nuevaEtapa", newStage ?: JSONObject.NULL)
                    .put("hayNuevos", source.optBoolean("hayNuevos", files.length() > 0 || newStage != null))
                    .toString()
            }

            val stage = source.optInt("etapa", -1)
            if (stage < 0) return failure("invalid_stage")
            JSONObject().put("ok", true).put("etapa", stage).put("archivos", files)
                .put("hayNuevos", source.optBoolean("hayNuevos", files.length() > 0)).toString()
        }.getOrElse { failure("invalid_response") }
    }

    private fun failure(error: String): String = JSONObject()
        .put("ok", false).put("archivos", JSONArray()).put("error", error).toString()

    companion object {
        const val MAX_MARKER_IMAGE_BYTES = 4L * 1024L * 1024L
    }

}
