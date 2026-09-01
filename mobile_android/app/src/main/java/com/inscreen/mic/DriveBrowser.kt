package com.inscreen.mic

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object DriveLinkPolicy {
    private val id = Regex("^[A-Za-z0-9_-]{10,}$")

    fun folderId(raw: String): String? {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "drive.google.com" && host != "docs.google.com") return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val marker = segments.indexOf("folders")
        val candidate = when {
            marker >= 0 -> segments.getOrNull(marker + 1)
            uri.path == "/open" -> uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.firstOrNull() == "id") pieces.getOrNull(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } else null
            }
            else -> null
        }?.trim().orEmpty()
        return candidate.takeIf { id.matches(it) }
    }
}

internal data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val modifiedTime: String,
    val version: String,
    val shortcutTargetId: String? = null,
    val shortcutTargetMimeType: String? = null,
) {
    val effectiveId: String get() = shortcutTargetId ?: id
    val effectiveMimeType: String get() = shortcutTargetMimeType ?: mimeType
    val isFolder: Boolean get() = effectiveMimeType == FOLDER_MIME

    companion object { const val FOLDER_MIME = "application/vnd.google-apps.folder" }
}

internal object DriveItemPolicy {
    fun sorted(items: List<DriveItem>): List<DriveItem> = items.sortedWith(
        compareBy<DriveItem> { !it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

    fun export(item: DriveItem): Pair<String, String>? = when (item.mimeType) {
        "application/vnd.google-apps.document" -> "application/pdf" to ensureExtension(item.name, "pdf")
        "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ensureExtension(item.name, "xlsx")
        "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ensureExtension(item.name, "pptx")
        "application/vnd.google-apps.drawing" -> "application/pdf" to ensureExtension(item.name, "pdf")
        else -> null
    }

    private fun ensureExtension(name: String, extension: String) =
        if (name.lowercase(Locale.ROOT).endsWith(".$extension")) name else "$name.$extension"
}

internal class DriveApiClient(
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build(),
) {
    fun list(folderId: String): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val url = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                .appendQueryParameter("q", "'$folderId' in parents and trashed = false")
                .appendQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,version,md5Checksum,shortcutDetails(targetId,targetMimeType))")
                .appendQueryParameter("pageSize", "1000")
                .appendQueryParameter("supportsAllDrives", "true")
                .appendQueryParameter("includeItemsFromAllDrives", "true")
                .apply { pageToken?.let { appendQueryParameter("pageToken", it) } }
                .build().toString()
            val body = execute(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
            val payload = JSONObject(body)
            val files = payload.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val value = files.getJSONObject(index)
                val shortcut = value.optJSONObject("shortcutDetails")
                result += DriveItem(
                    value.getString("id"), value.getString("name"), value.getString("mimeType"),
                    value.optString("size").toLongOrNull(), value.optString("modifiedTime"),
                    value.optString("md5Checksum").ifBlank { value.optString("version") },
                    shortcut?.optString("targetId")?.takeIf { it.isNotBlank() },
                    shortcut?.optString("targetMimeType")?.takeIf { it.isNotBlank() },
                )
            }
            pageToken = payload.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return DriveItemPolicy.sorted(result)
    }

    fun download(item: DriveItem, destination: File, progress: (Long, Long?) -> Unit): Pair<File, String> {
        val exported = DriveItemPolicy.export(item)
        val url = if (exported != null) {
            "https://www.googleapis.com/drive/v3/files/${item.id}/export?mimeType=" +
                URLEncoder.encode(exported.first, StandardCharsets.UTF_8.name())
        } else "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media&supportsAllDrives=true"
        val target = if (exported == null) destination else {
            val extension = exported.second.substringAfterLast('.', "bin")
            File(destination.parentFile, destination.name.substringBeforeLast('.', destination.name) + ".$extension")
        }
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("drive_http_${response.code}")
            val body = response.body ?: throw IOException("empty_body")
            val total = body.contentLength().takeIf { it >= 0 }
            body.byteStream().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        progress(copied, total)
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("cache_commit_failed")
        }
        return target to (exported?.first ?: item.mimeType.ifBlank { "application/octet-stream" })
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("drive_http_${response.code}")
        response.body?.string() ?: throw IOException("empty_body")
    }
}

internal class DriveCache(private val context: Context) {
    private val metadata = File(context.filesDir, "drive-browser")
    private val downloads = File(context.cacheDir, "drive-browser")

    fun saveListing(rootId: String, folderId: String, items: List<DriveItem>) {
        val payload = JSONArray()
        items.forEach { item -> payload.put(JSONObject().put("id", item.id).put("name", item.name)
            .put("mimeType", item.mimeType).put("size", item.size ?: JSONObject.NULL)
            .put("modifiedTime", item.modifiedTime).put("version", item.version)
            .put("shortcutTargetId", item.shortcutTargetId ?: JSONObject.NULL)
            .put("shortcutTargetMimeType", item.shortcutTargetMimeType ?: JSONObject.NULL)) }
        metadataFile(rootId, folderId).apply { parentFile?.mkdirs(); writeText(payload.toString()) }
    }

    fun loadListing(rootId: String, folderId: String): List<DriveItem>? = runCatching {
        val file = metadataFile(rootId, folderId)
        if (!file.exists()) return null
        val array = JSONArray(file.readText())
        DriveItemPolicy.sorted((0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            DriveItem(item.getString("id"), item.getString("name"), item.getString("mimeType"),
                item.optLong("size").takeIf { !item.isNull("size") }, item.optString("modifiedTime"), item.optString("version"),
                item.optString("shortcutTargetId").takeIf { !item.isNull("shortcutTargetId") && it.isNotBlank() },
                item.optString("shortcutTargetMimeType").takeIf { !item.isNull("shortcutTargetMimeType") && it.isNotBlank() })
        })
    }.getOrNull()

    fun downloaded(rootId: String, item: DriveItem): File? {
        val directory = File(downloads, safe(rootId))
        val prefix = "${safe(item.id)}-${safe(item.version)}-"
        return directory.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") && it.name.startsWith(prefix) }
    }

    fun destination(rootId: String, item: DriveItem): File {
        val directory = File(downloads, safe(rootId)).apply { mkdirs() }
        directory.listFiles()?.filter { it.name.startsWith("${safe(item.id)}-") }?.forEach(File::delete)
        return File(directory, "${safe(item.id)}-${safe(item.version)}-${safeName(item.name)}")
    }

    fun removeRoot(rootId: String) {
        File(metadata, safe(rootId)).deleteRecursively()
        File(downloads, safe(rootId)).deleteRecursively()
    }

    fun clearAll() { metadata.deleteRecursively(); downloads.deleteRecursively() }

    private fun metadataFile(rootId: String, folderId: String) = File(File(metadata, safe(rootId)), "${safe(folderId)}.json")
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun safeName(value: String) = value.replace(Regex("[\\/:*?\"<>|]"), "_").take(140).ifBlank { "archivo" }
}
