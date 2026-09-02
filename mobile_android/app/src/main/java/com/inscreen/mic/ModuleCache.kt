package com.inscreen.mic

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class ModuleCache private constructor(private val root: File) {
    @Synchronized fun read(subjectId: String, module: ModuleCatalog.Module): String? {
        val directory = moduleDirectory(subjectId, module.id)
        val metadata = runCatching { JSONObject(File(directory, METADATA).readText()) }.getOrNull() ?: return null
        if (metadata.optString("subjectId") != subjectId || metadata.optString("moduleId") != module.id || metadata.optString("entry") != module.entry) return null
        return runCatching { File(directory, module.entry.substringAfterLast('/')).readText(Charsets.UTF_8) }.getOrNull()?.takeIf(String::isNotBlank)
    }

    @Synchronized fun version(subjectId: String, module: ModuleCatalog.Module): String? {
        val metadata = runCatching { JSONObject(File(moduleDirectory(subjectId, module.id), METADATA).readText()) }.getOrNull() ?: return null
        if (metadata.optString("subjectId") != subjectId || metadata.optString("moduleId") != module.id || metadata.optString("entry") != module.entry) return null
        return metadata.optString("version").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    /** Guarda la carpeta completa del módulo, conservando las rutas relativas para el WebView. */
    @Synchronized fun write(subjectId: String, module: ModuleCatalog.Module, files: Map<String, ByteArray>, version: String? = null) {
        val entry = module.entry.substringAfterLast('/')
        require(subjectId.isNotBlank() && files[entry]?.isNotEmpty() == true)
        val directory = moduleDirectory(subjectId, module.id).apply { mkdirs() }; check(directory.isDirectory)
        files.forEach { (relative, bytes) ->
            require(relative.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,239}")) && !relative.split('/').any { it == "." || it == ".." }) { "Ruta de recurso inválida" }
            val target = File(directory, relative); target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp"); temporary.writeBytes(bytes); replace(temporary, target)
        }
        val metadataFile = File(directory, METADATA); val metadataTemporary = File(directory, "$METADATA.tmp")
        val metadata = JSONObject().put("subjectId", subjectId).put("moduleId", module.id).put("name", module.name).put("entry", module.entry)
        version?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { metadata.put("version", it) }
        metadataTemporary.writeText(metadata.toString(), Charsets.UTF_8)
        replace(metadataTemporary, metadataFile)
    }

    internal fun writeToSubjects(subjectIds: Collection<String>, module: ModuleCatalog.Module, files: Map<String, ByteArray>, version: String? = null): Set<String> =
        subjectIds.distinct().mapNotNullTo(linkedSetOf()) { subjectId ->
            runCatching { write(subjectId, module, files, version) }.exceptionOrNull()?.let { subjectId }
        }

    fun directory(subjectId: String, moduleId: String): File = moduleDirectory(subjectId, moduleId)
    @Synchronized fun remove(subjectId: String, moduleId: String) { moduleDirectory(subjectId, moduleId).deleteRecursively() }
    @Synchronized fun removeSubject(subjectId: String) { subjectDirectory(subjectId).deleteRecursively() }
    @Synchronized fun reconcile(subjectIds: Set<String>) {
        if (!root.exists()) return
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            if (directory.name !in subjectIds.map(::sha256)) directory.deleteRecursively()
        }
    }

    private fun subjectDirectory(subjectId: String): File = File(root, sha256(subjectId))
    private fun moduleDirectory(subjectId: String, moduleId: String): File = File(subjectDirectory(subjectId), sha256(moduleId))
    private fun replace(temporary: File, target: File) {
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    companion object {
        private const val METADATA = "module.json"
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun from(context: Context) = ModuleCache(File(context.filesDir, "subject-modules"))
        internal fun at(root: File) = ModuleCache(root)
    }
}
