package com.inscreen.mic

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AppRelease(
    val tag: String,
    val version: String,
    val apkUrl: String,
    val notes: String,
)

internal object GitHubUpdateClient {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Drakxard/InSceeen/releases/latest"
    private const val APK_NAME = "InScreenMic.apk"

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun check(currentVersion: String, callback: (Result<AppRelease?>) -> Unit) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "InScreenMic/$currentVersion")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    callback(runCatching {
                        if (!it.isSuccessful) error("GitHub respondió ${it.code}")
                        val release = parseRelease(it.body?.string().orEmpty())
                        release.takeIf { candidate -> isNewer(candidate.version, currentVersion) }
                    })
                }
            }
        })
    }

    internal fun parseRelease(raw: String): AppRelease {
        val payload = JSONObject(raw)
        val tag = payload.getString("tag_name").trim()
        val version = tag.removePrefix("v").removePrefix("V")
        require(version.matches(Regex("\\d+(?:\\.\\d+){1,3}"))) { "Versión inválida: $tag" }
        val assets = payload.getJSONArray("assets")
        val apk = (0 until assets.length())
            .asSequence()
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").equals(APK_NAME, ignoreCase = true) }
            ?: error("La release no contiene $APK_NAME")
        return AppRelease(
            tag = tag,
            version = version,
            apkUrl = apk.getString("browser_download_url"),
            notes = payload.optString("body").trim(),
        )
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = versionParts(candidate)
        val currentParts = versionParts(current)
        val length = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until length) {
            val next = candidateParts.getOrElse(index) { 0 }
            val installed = currentParts.getOrElse(index) { 0 }
            if (next != installed) return next > installed
        }
        return false
    }

    private fun versionParts(value: String): List<Int> = value
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}
