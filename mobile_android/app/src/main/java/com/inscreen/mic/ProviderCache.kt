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
            if (source.has("nuevaEtapa")) mergeIncremental(subjectId, transcription, source).toString()
            else mergeLegacy(subjectId, transcription, source).toString()
        }.getOrElse { failure("storage_error") }
    }

    private fun mergeLegacy(subjectId: String, transcription: Boolean, source: JSONObject): JSONObject {
        val stage = source.optInt("etapa", -1)
        if (stage < 0) return JSONObject(failure("invalid_stage"))
        val added = writeAtomically(listOf(stage to normalizedFiles(source.optJSONArray("archivos"))), subjectId, transcription, null)
        return inventory(subjectId, transcription, stage, includeContent = true)
            .put("nuevos", added).put("hayNuevos", source.optBoolean("hayNuevos", added > 0))
    }

    private fun mergeIncremental(subjectId: String, transcription: Boolean, source: JSONObject): JSONObject {
        val typeDirectory = typeDirectory(subjectId, transcription).apply { mkdirs() }
        val currentFiles = normalizedFiles(source.optJSONArray("archivos"))
        val currentStage = activeStage(typeDirectory)
        require(currentFiles.isEmpty() || currentStage != null) { "missing_active_stage" }

        val newStageSource = source.optJSONObject("nuevaEtapa")
        val newStage = newStageSource?.optInt("etapa", -1)?.takeIf { it >= 0 }
        val newFiles = normalizedFiles(newStageSource?.optJSONArray("archivos"))
        if (newStageSource != null) {
            require(newStage != null && newFiles.isNotEmpty() && newFiles.first().first == "1.txt") { "invalid_new_stage" }
            require(currentStage == null || newStage > currentStage) { "invalid_stage_transition" }
        }

        val groups = buildList {
            if (currentStage != null && currentFiles.isNotEmpty()) add(currentStage to currentFiles)
            if (newStage != null) add(newStage to newFiles)
        }
        val added = writeAtomically(groups, subjectId, transcription, newStage)
        return JSONObject().put("ok", true)
            .put("hayNuevos", source.optBoolean("hayNuevos", added > 0))
            .put("nuevos", added)
            .put("archivos", source.optJSONArray("archivos") ?: JSONArray())
            .put("nuevaEtapa", newStageSource ?: JSONObject.NULL)
    }

    private fun normalizedFiles(source: JSONArray?): List<Pair<String, String>> {
        val files = mutableListOf<Pair<String, String>>()
        val input = source ?: JSONArray()
        for (index in 0 until input.length()) {
            val item = input.optJSONObject(index) ?: continue
            val name = item.optString("nombre").trim()
            if (FILE_NAME.matches(name)) files += name to item.optString("contenido")
        }
        return files.sortedBy { it.first.substringBefore('.').toInt() }
    }

    private fun writeAtomically(
        groups: List<Pair<Int, List<Pair<String, String>>>>,
        subjectId: String,
        transcription: Boolean,
        nextActiveStage: Int?,
    ): Int {
        val temporaryFiles = mutableListOf<File>()
        val createdFiles = mutableListOf<File>()
        val prepared = mutableListOf<Pair<File, File>>()
        try {
            for ((stage, files) in groups) {
                val directory = stageDirectory(subjectId, transcription, stage).apply { mkdirs() }
                for ((name, content) in files) {
                    val target = File(directory, name)
                    if (target.exists()) continue
                    val temporary = File(directory, ".$name.${System.nanoTime()}.tmp")
                    temporary.writeText(content, Charsets.UTF_8)
                    temporaryFiles += temporary
                    prepared += temporary to target
                }
            }
            for ((temporary, target) in prepared) {
                if (target.exists()) { temporary.delete(); continue }
                move(temporary, target)
                temporaryFiles.remove(temporary)
                createdFiles += target
            }
            if (nextActiveStage != null) writeActiveStage(typeDirectory(subjectId, transcription), nextActiveStage)
            return createdFiles.size
        } catch (error: Exception) {
            temporaryFiles.forEach(File::delete)
            createdFiles.forEach(File::delete)
            throw error
        }
    }

    fun list(subjectId: String, transcription: Boolean, stage: Int): String = synchronized(lock) {
        if (stage < 0) return@synchronized failure("invalid_stage")
        runCatching { inventory(subjectId, transcription, stage, includeContent = false).toString() }
            .getOrElse { failure("storage_error") }
    }

    fun read(subjectId: String, transcription: Boolean, stage: Int, number: Int): String = synchronized(lock) {
        if (stage < 0) return@synchronized failure("invalid_stage")
        if (number <= 0) return@synchronized failure("invalid_file_number")
        val file = File(stageDirectory(subjectId, transcription, stage), "$number.txt")
        if (!file.isFile) return@synchronized failure("file_not_found")
        runCatching {
            JSONObject().put("ok", true).put("tipo", if (transcription) "transcripcion" else "pagina")
                .put("etapa", stage)
                .put("archivo", JSONObject().put("numero", number).put("nombre", file.name).put("contenido", file.readText()))
                .toString()
        }.getOrElse { failure("storage_error") }
    }

    fun history(subjectId: String, transcription: Boolean): String = synchronized(lock) {
        runCatching {
            val items = JSONArray()
            typeDirectory(subjectId, transcription).listFiles()?.filter(File::isDirectory)
                ?.mapNotNull { directory -> directory.name.toIntOrNull()?.let { it to directory } }
                ?.sortedBy { it.first }?.forEach { (stage, directory) ->
                    directory.listFiles()?.filter { it.isFile && FILE_NAME.matches(it.name) }
                        ?.sortedBy { it.name.substringBefore('.').toInt() }?.forEach { file ->
                            items.put(JSONObject().put("id", "$stage:${file.name}").put("etapa", stage)
                                .put("numero", file.name.substringBefore('.').toInt()).put("nombre", file.name)
                                .put("contenido", file.readText()))
                        }
                }
            JSONObject().put("ok", true).put("tipo", if (transcription) "transcripcion" else "pagina")
                .put("archivos", items).toString()
        }.getOrElse { failure("storage_error") }
    }

    fun reconcileSubjects(subjectIds: Set<String>) = synchronized(lock) {
        if (!root.exists()) return@synchronized
        val allowed = subjectIds.map(::safeSubjectId).toSet()
        root.listFiles()?.filter(File::isDirectory)?.filter { it.name !in allowed }?.forEach(File::deleteRecursively)
    }

    private fun inventory(subjectId: String, transcription: Boolean, stage: Int, includeContent: Boolean): JSONObject {
        val files = stageDirectory(subjectId, transcription, stage).listFiles()
            ?.filter { it.isFile && FILE_NAME.matches(it.name) }
            ?.sortedBy { it.name.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }.orEmpty()
        val items = JSONArray()
        for (file in files) items.put(JSONObject().put("numero", file.name.substringBefore('.').toInt()).put("nombre", file.name).apply {
            if (includeContent) put("contenido", file.readText())
        })
        return JSONObject().put("ok", true).put("tipo", if (transcription) "transcripcion" else "pagina")
            .put("etapa", stage).put("archivos", items)
    }

    private fun activeStage(directory: File): Int? = runCatching { File(directory, ACTIVE_STAGE).readText().trim().toInt() }.getOrNull()
        ?: directory.listFiles()?.filter(File::isDirectory)?.mapNotNull { it.name.toIntOrNull() }?.maxOrNull()

    private fun writeActiveStage(directory: File, stage: Int) {
        directory.mkdirs()
        val target = File(directory, ACTIVE_STAGE)
        val temporary = File(directory, "$ACTIVE_STAGE.tmp")
        temporary.writeText(stage.toString(), Charsets.UTF_8)
        move(temporary, target)
    }

    private fun move(source: File, target: File) {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun typeDirectory(subjectId: String, transcription: Boolean) =
        File(File(root, safeSubjectId(subjectId)), if (transcription) "transcripcion" else "pagina")
    private fun stageDirectory(subjectId: String, transcription: Boolean, stage: Int) = File(typeDirectory(subjectId, transcription), stage.toString())
    private fun safeSubjectId(subjectId: String) = MessageDigest.getInstance("SHA-256")
        .digest(subjectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun failure(error: String) = JSONObject().put("ok", false).put("archivos", JSONArray()).put("error", error).toString()

    companion object {
        private const val ACTIVE_STAGE = ".active-stage"
        private val GLOBAL_LOCK = Any()
        private val FILE_NAME = Regex("[1-9][0-9]*\\.txt")
        fun from(context: Context) = ProviderCache(File(context.filesDir, "provider-cache"))
    }
}
