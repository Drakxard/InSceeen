package com.inscreen.mic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class ProviderCache(private val root: File) {
    private val lock = GLOBAL_LOCK

    fun merge(subjectId: String, transcription: Boolean, payload: String): String = synchronized(lock) {
        runCatching {
            val source = JSONObject(payload)
            if (!source.optBoolean("ok", false)) return@synchronized payload
            val stage = source.optInt("etapa", 0)
            if (stage !in 0..6) return@synchronized failure("invalid_stage")
            val directory = stageDirectory(subjectId, transcription, stage)
            directory.mkdirs()
            var added = 0
            val files = source.optJSONArray("archivos") ?: JSONArray()
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                val name = item.optString("nombre").trim()
                if (!FILE_NAME.matches(name)) continue
                val target = File(directory, name)
                if (target.exists()) continue
                val temporary = File(directory, ".$name.${System.nanoTime()}.tmp")
                temporary.writeText(item.optString("contenido"), Charsets.UTF_8)
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    added += 1
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    temporary.delete()
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    if (!target.exists()) {
                        Files.move(temporary.toPath(), target.toPath())
                        added += 1
                    } else temporary.delete()
                }
            }
            inventory(subjectId, transcription, stage, includeContent = true).put("nuevos", added).toString()
        }.getOrElse { failure("storage_error") }
    }

    fun list(subjectId: String, transcription: Boolean, stage: Int): String = synchronized(lock) {
        if (stage !in 0..6) return@synchronized failure("invalid_stage")
        runCatching { inventory(subjectId, transcription, stage, includeContent = false).toString() }
            .getOrElse { failure("storage_error") }
    }

    fun read(subjectId: String, transcription: Boolean, stage: Int, number: Int): String = synchronized(lock) {
        if (stage !in 0..6) return@synchronized failure("invalid_stage")
        if (number <= 0) return@synchronized failure("invalid_file_number")
        val file = File(stageDirectory(subjectId, transcription, stage), "$number.txt")
        if (!file.isFile) return@synchronized failure("file_not_found")
        runCatching {
            JSONObject().put("ok", true)
                .put("tipo", if (transcription) "transcripcion" else "pagina")
                .put("etapa", stage)
                .put("archivo", JSONObject().put("numero", number).put("nombre", file.name).put("contenido", file.readText()))
                .toString()
        }.getOrElse { failure("storage_error") }
    }

    fun reconcileSubjects(subjectIds: Set<String>) = synchronized(lock) {
        if (!root.exists()) return@synchronized
        val allowed = subjectIds.map(::safeSubjectId).toSet()
        root.listFiles()?.filter(File::isDirectory)?.filter { it.name !in allowed }?.forEach(File::deleteRecursively)
    }

    private fun inventory(subjectId: String, transcription: Boolean, stage: Int, includeContent: Boolean): JSONObject {
        val directory = stageDirectory(subjectId, transcription, stage)
        val files = directory.listFiles()
            ?.filter { it.isFile && FILE_NAME.matches(it.name) }
            ?.sortedBy { it.name.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            .orEmpty()
        val items = JSONArray()
        for (file in files) {
            val number = file.name.substringBefore('.').toInt()
            items.put(JSONObject().put("numero", number).put("nombre", file.name).apply {
                if (includeContent) put("contenido", file.readText())
            })
        }
        return JSONObject().put("ok", true)
            .put("tipo", if (transcription) "transcripcion" else "pagina")
            .put("etapa", stage).put("archivos", items)
    }

    private fun stageDirectory(subjectId: String, transcription: Boolean, stage: Int): File =
        File(File(File(root, safeSubjectId(subjectId)), if (transcription) "transcripcion" else "pagina"), stage.toString())

    private fun safeSubjectId(subjectId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(subjectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun failure(error: String): String = JSONObject()
        .put("ok", false).put("archivos", JSONArray()).put("error", error).toString()

    companion object {
        private val GLOBAL_LOCK = Any()
        private val FILE_NAME = Regex("[1-9][0-9]*\\.txt")
        fun from(context: Context) = ProviderCache(File(context.filesDir, "provider-cache"))
    }
}
