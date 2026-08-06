package com.inscreen.mic

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class ModuleCache private constructor(private val root: File) {
    @Synchronized
    fun read(subjectId: String, module: ModuleCatalog.Module): String? {
        val directory = subjectDirectory(subjectId)
        val metadata = runCatching { JSONObject(File(directory, METADATA).readText()) }.getOrNull() ?: return null
        if (metadata.optString("subjectId") != subjectId ||
            metadata.optString("moduleId") != module.id ||
            metadata.optString("entry") != module.entry
        ) return null
        val htmlFile = File(directory, htmlName(module.id))
        return runCatching { htmlFile.readText(Charsets.UTF_8) }.getOrNull()?.takeIf(String::isNotBlank)
    }

    @Synchronized
    fun write(subjectId: String, module: ModuleCatalog.Module, html: String) {
        require(subjectId.isNotBlank() && html.isNotBlank())
        val directory = subjectDirectory(subjectId).apply { mkdirs() }
        check(directory.isDirectory)
        val htmlFile = File(directory, htmlName(module.id))
        val htmlTemporary = File(directory, "${htmlFile.name}.tmp")
        htmlTemporary.writeText(html, Charsets.UTF_8)
        replace(htmlTemporary, htmlFile)

        val metadataFile = File(directory, METADATA)
        val metadataTemporary = File(directory, "$METADATA.tmp")
        metadataTemporary.writeText(
            JSONObject()
                .put("subjectId", subjectId)
                .put("moduleId", module.id)
                .put("name", module.name)
                .put("entry", module.entry)
                .toString(),
            Charsets.UTF_8,
        )
        replace(metadataTemporary, metadataFile)
        directory.listFiles()?.filter { it.extension == "html" && it.name != htmlFile.name }
            ?.forEach(File::delete)
    }

    @Synchronized
    fun remove(subjectId: String) {
        subjectDirectory(subjectId).deleteRecursively()
    }

    @Synchronized
    fun reconcile(subjectIds: Set<String>) {
        if (!root.exists()) return
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val subjectId = runCatching {
                JSONObject(File(directory, METADATA).readText()).optString("subjectId")
            }.getOrDefault("")
            if (subjectId.isBlank() || subjectId !in subjectIds) directory.deleteRecursively()
        }
    }

    private fun subjectDirectory(subjectId: String): File = File(root, sha256(subjectId))

    private fun replace(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val METADATA = "module.json"
        private fun htmlName(moduleId: String) = "$moduleId.html"
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun from(context: Context) = ModuleCache(File(context.filesDir, "subject-modules"))
        internal fun at(root: File) = ModuleCache(root)
    }
}
