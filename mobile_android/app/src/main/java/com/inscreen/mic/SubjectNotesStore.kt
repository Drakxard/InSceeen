package com.inscreen.mic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
            File(File(directory, MARKER_CACHE), "$photoName.md").delete()
            File(directory, MARKER_CACHE).let { if (it.listFiles().isNullOrEmpty()) it.delete() }
        }
        removeSubjectDirectoryIfEmpty(subjectId)
        true
    }

    fun markerText(subjectId: String, sessionId: String, photoName: String): String? = synchronized(GLOBAL_LOCK) {
        if (!safeName(sessionId) || !safeName(photoName)) return@synchronized null
        val directory = File(subjectDirectory(subjectId), sessionId)
        val session = readSession(directory) ?: return@synchronized null
        if (session.photos.none { it.name == photoName }) return@synchronized null
        runCatching { File(File(directory, MARKER_CACHE), "$photoName.md").readText(Charsets.UTF_8).trim() }
            .getOrNull()?.takeIf(String::isNotBlank)
    }

    fun saveMarkerText(subjectId: String, sessionId: String, photoName: String, markdown: String) = synchronized(GLOBAL_LOCK) {
        require(safeName(sessionId) && safeName(photoName) && markdown.isNotBlank())
        val directory = File(subjectDirectory(subjectId), sessionId)
        val session = readSession(directory) ?: error("El conjunto ya no existe")
        require(session.photos.any { it.name == photoName })
        val cache = File(directory, MARKER_CACHE).apply { mkdirs() }
        check(cache.isDirectory)
        val target = File(cache, "$photoName.md")
        val temporary = File(cache, "$photoName.md.tmp")
        temporary.writeText(markdown.trim(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun markerInventory(subjectId: String, sessionId: String): String = synchronized(GLOBAL_LOCK) {
        if (!safeName(sessionId)) return@synchronized markerFailure("session_not_found")
        runCatching {
            val session = readSession(File(subjectDirectory(subjectId), sessionId))
                ?: return@synchronized markerFailure("session_not_found")
            val files = session.photos.foldIndexed(JSONArray()) { index, items, photo ->
                items.put(JSONObject()
                    .put("numero", index + 1)
                    .put("nombre", "${index + 1}.txt")
                    .put("id", photo.name))
            }
            JSONObject()
                .put("ok", true)
                .put("conjunto", JSONObject().put("id", session.id).put("createdAt", session.createdAt))
                .put("archivos", files)
                .toString()
        }.getOrElse { markerFailure("storage_error") }
    }

    fun markerFile(subjectId: String, sessionId: String, number: Int): String = synchronized(GLOBAL_LOCK) {
        if (number <= 0) return@synchronized markerFailure("invalid_file_number")
        if (!safeName(sessionId)) return@synchronized markerFailure("session_not_found")
        runCatching {
            val session = readSession(File(subjectDirectory(subjectId), sessionId))
                ?: return@synchronized markerFailure("session_not_found")
            val photo = session.photos.getOrNull(number - 1)
                ?: return@synchronized markerFailure("file_not_found")
            val markerFile = File(File(subjectDirectory(subjectId), session.id), MARKER_CACHE)
                .resolve("${photo.name}.md")
            if (!markerFile.isFile) return@synchronized markerFailure("transcription_not_ready")
            val content = runCatching { markerFile.readText(Charsets.UTF_8).trim() }
                .getOrElse { return@synchronized markerFailure("storage_error") }
                .takeIf(String::isNotBlank)
                ?: return@synchronized markerFailure("transcription_not_ready")
            JSONObject()
                .put("ok", true)
                .put("conjuntoId", session.id)
                .put("archivo", JSONObject()
                    .put("numero", number)
                    .put("nombre", "$number.txt")
                    .put("id", photo.name)
                    .put("hash", sha256(content))
                    .put("contenido", content))
                .toString()
        }.getOrElse { markerFailure("storage_error") }
    }

    fun studyState(subjectId: String, sessionId: String, moduleId: String): String = synchronized(GLOBAL_LOCK) {
        if (!safeName(sessionId) || !safeModuleId(moduleId)) return@synchronized markerFailure("invalid_study_scope")
        val sessionDirectory = File(subjectDirectory(subjectId), sessionId)
        val session = readSession(sessionDirectory) ?: return@synchronized markerFailure("session_not_found")
        val stateFile = File(File(sessionDirectory, STUDY_CACHE), "$moduleId.json")
        val state = if (!stateFile.isFile) null else runCatching {
            validateStudyState(stateFile.readText(Charsets.UTF_8), session.id)
        }.getOrElse { return@synchronized markerFailure("invalid_study_state") }
        JSONObject()
            .put("ok", true)
            .put("conjuntoId", session.id)
            .put("estado", state ?: JSONObject.NULL)
            .toString()
    }

    fun saveStudyState(subjectId: String, sessionId: String, moduleId: String, rawState: String): String = synchronized(GLOBAL_LOCK) {
        if (!safeName(sessionId) || !safeModuleId(moduleId)) return@synchronized markerFailure("invalid_study_scope")
        if (rawState.toByteArray(Charsets.UTF_8).size > MAX_STUDY_STATE_BYTES) {
            return@synchronized markerFailure("study_state_too_large")
        }
        val sessionDirectory = File(subjectDirectory(subjectId), sessionId)
        val session = readSession(sessionDirectory) ?: return@synchronized markerFailure("session_not_found")
        val state = runCatching { validateStudyState(rawState, session.id) }
            .getOrElse { return@synchronized markerFailure("invalid_study_state") }
        return@synchronized runCatching {
            val directory = File(sessionDirectory, STUDY_CACHE).apply { mkdirs() }
            check(directory.isDirectory)
            val target = File(directory, "$moduleId.json")
            val temporary = File(directory, "$moduleId.json.tmp")
            temporary.writeText(state.toString(), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            JSONObject().put("ok", true).put("conjuntoId", session.id).toString()
        }.getOrElse { markerFailure("storage_error") }
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
    private fun validateStudyState(rawState: String, sessionId: String): JSONObject {
        require(rawState.isNotBlank())
        val state = JSONObject(rawState)
        require(state.optInt("version", -1) == STUDY_FORMAT_VERSION)
        require(state.optString("conjuntoId") == sessionId)
        val pages = state.optJSONObject("paginas") ?: error("Falta paginas")
        val pageKeys = pages.keys()
        while (pageKeys.hasNext()) {
            val pageId = pageKeys.next()
            require(safeName(pageId))
            val page = pages.optJSONObject(pageId) ?: error("Pagina invalida")
            require(page.optString("sourceHash").matches(Regex("[0-9a-f]{64}")))
            val cards = page.optJSONArray("tarjetas") ?: error("Falta tarjetas")
            var previousOrder = 0
            for (index in 0 until cards.length()) {
                val card = cards.optJSONObject(index) ?: error("Tarjeta invalida")
                val id = card.optString("id")
                val order = card.optInt("orden", -1)
                val header = card.optString("cabecera")
                require(id.isNotBlank() && id.length <= 200 && order > previousOrder)
                require(header.isNotBlank() && header.length <= 160 && !header.contains('\n') && !header.contains('\r'))
                if (!card.isNull("respuesta")) require(card.getString("respuesta").length <= MAX_ANSWER_CHARS)
                if (!card.isNull("respuestaActualizada")) require(card.getLong("respuestaActualizada") >= 0L)
                previousOrder = order
            }
        }
        return state
    }

    private fun markerFailure(error: String) = JSONObject()
        .put("ok", false).put("archivos", JSONArray()).put("error", error).toString()
    private fun safeSubjectId(subjectId: String) = MessageDigest.getInstance("SHA-256")
        .digest(subjectId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun safeName(value: String) = value.isNotBlank() && value.none { it == '/' || it == '\\' } && value != "." && value != ".."
    private fun safeModuleId(value: String) = value.matches(Regex("[a-z0-9][a-z0-9-]{0,79}"))

    companion object {
        private const val FORMAT_VERSION = 1
        private const val STUDY_FORMAT_VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val MARKER_CACHE = ".marker"
        private const val STUDY_CACHE = ".study"
        private const val MAX_STUDY_STATE_BYTES = 1024 * 1024
        private const val MAX_ANSWER_CHARS = 50_000
        private val GLOBAL_LOCK = Any()
        fun from(context: android.content.Context) = SubjectNotesStore(File(context.filesDir, "subject-notes"))
    }
}
