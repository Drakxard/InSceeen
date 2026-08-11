package com.inscreen.mic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class SubjectNotesStore(private val root: File) {
    data class Photo(val sessionId: String, val name: String, val file: File, val createdAt: Long)
    data class Session(val id: String, val createdAt: Long, val photos: List<Photo>)

    fun commit(subjectId: String, createdAt: Long, sources: List<File>): Session = synchronized(GLOBAL_LOCK) {
        require(subjectId.isNotBlank() && sources.isNotEmpty())
        require(sources.all { it.isFile && it.length() > 0L })
        val subjectDirectory = subjectDirectory(subjectId).apply { mkdirs() }
        check(subjectDirectory.isDirectory)
        val sessionId = "${createdAt}-${UUID.randomUUID()}"
        val staging = File(subjectDirectory, ".tmp-$sessionId")
        val destination = File(subjectDirectory, sessionId)
        try {
            check(staging.mkdir())
            val names = sources.mapIndexed { index, source ->
                val name = "%04d.jpg".format(index + 1)
                source.inputStream().buffered().use { input ->
                    File(staging, name).outputStream().buffered().use(input::copyTo)
                }
                name
            }
            writeManifest(staging, sessionId, createdAt, names)
            check(staging.renameTo(destination)) { "No se pudo confirmar la sesión" }
            return readSession(destination) ?: error("La sesión guardada no es válida")
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun sessions(subjectId: String): List<Session> = synchronized(GLOBAL_LOCK) {
        val directory = subjectDirectory(subjectId)
        if (!directory.isDirectory) return emptyList()
        directory.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".tmp-") }
            ?.mapNotNull(::readSession)
            ?.sortedByDescending(Session::createdAt)
            ?.let { return@synchronized it }
        emptyList()
    }

    fun deletePhoto(subjectId: String, sessionId: String, photoName: String): Boolean = synchronized(GLOBAL_LOCK) {
        if (!safeName(sessionId) || !safeName(photoName)) return@synchronized false
        val directory = File(subjectDirectory(subjectId), sessionId)
        val session = readSession(directory) ?: return@synchronized false
        if (session.photos.none { it.name == photoName }) return@synchronized false
        val remaining = session.photos.map(Photo::name).filterNot { it == photoName }
        if (remaining.isEmpty()) {
            directory.deleteRecursively()
        } else {
            writeManifest(directory, session.id, session.createdAt, remaining)
            File(directory, photoName).delete()
        }
        removeSubjectDirectoryIfEmpty(subjectId)
        true
    }

    fun reconcileSubjects(subjectIds: Set<String>) = synchronized(GLOBAL_LOCK) {
        if (!root.isDirectory) return@synchronized
        val allowed = subjectIds.filter(String::isNotBlank).map(::safeSubjectId).toSet()
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            if (directory.name !in allowed) directory.deleteRecursively()
            else directory.listFiles()?.filter { it.isDirectory && it.name.startsWith(".tmp-") }
                ?.forEach(File::deleteRecursively)
        }
    }

    private fun readSession(directory: File): Session? = runCatching {
        val manifest = JSONObject(File(directory, MANIFEST).readText(Charsets.UTF_8))
        val id = manifest.getString("id")
        require(id == directory.name && safeName(id))
        val createdAt = manifest.getLong("createdAt")
        val images = manifest.getJSONArray("images")
        val photos = (0 until images.length()).map { index ->
            val name = images.getString(index)
            require(safeName(name) && name.endsWith(".jpg", ignoreCase = true))
            val file = File(directory, name)
            require(file.isFile && file.length() > 0L)
            Photo(id, name, file, createdAt)
        }
        require(photos.isNotEmpty())
        Session(id, createdAt, photos)
    }.getOrNull()

    private fun writeManifest(directory: File, id: String, createdAt: Long, names: List<String>) {
        val temporary = File(directory, "$MANIFEST.tmp")
        temporary.writeText(
            JSONObject()
                .put("version", FORMAT_VERSION)
                .put("id", id)
                .put("createdAt", createdAt)
                .put("images", JSONArray(names))
                .toString(),
            Charsets.UTF_8,
        )
        val manifest = File(directory, MANIFEST)
        if (!manifest.exists()) {
            check(temporary.renameTo(manifest)) { "No se pudo actualizar la sesión" }
            return
        }
        val backup = File(directory, "$MANIFEST.bak")
        backup.delete()
        check(manifest.renameTo(backup)) { "No se pudo actualizar la sesión" }
        if (temporary.renameTo(manifest)) backup.delete() else {
            backup.renameTo(manifest)
            error("No se pudo actualizar la sesión")
        }
    }

    private fun removeSubjectDirectoryIfEmpty(subjectId: String) {
        val directory = subjectDirectory(subjectId)
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
    }

    private fun subjectDirectory(subjectId: String) = File(root, safeSubjectId(subjectId))
    private fun safeSubjectId(subjectId: String) = MessageDigest.getInstance("SHA-256")
        .digest(subjectId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun safeName(value: String) = value.isNotBlank() && value.none { it == '/' || it == '\\' } && value != "." && value != ".."

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MANIFEST = "manifest.json"
        private val GLOBAL_LOCK = Any()
        fun from(context: android.content.Context) = SubjectNotesStore(File(context.filesDir, "subject-notes"))
    }
}
