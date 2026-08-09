package com.inscreen.mic

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object AppBackup {
    private const val FORMAT_VERSION = 1
    private const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    private val backedUpDirectories = listOf("subject-modules", "provider-cache")

    data class Restored(val apriori: String, val webStorage: JSONObject)

    fun export(context: Context, output: OutputStream, webStorage: JSONObject) {
        ZipOutputStream(output.buffered()).use { zip ->
            writeText(zip, "manifest.json", JSONObject()
                .put("format", "inscreen-backup")
                .put("version", FORMAT_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .toString())
            writeText(zip, "apriori.json", AprioriStore.load(context))
            writeText(zip, "web-storage.json", webStorage.toString())
            backedUpDirectories.forEach { name ->
                val directory = File(context.filesDir, name)
                if (directory.isDirectory) addDirectory(zip, directory, "files/$name/")
            }
        }
    }

    fun import(context: Context, input: InputStream): Restored {
        val staging = File(context.cacheDir, "backup-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = entry.name.replace('\\', '/')
                    require(normalized.isNotBlank() && !normalized.startsWith('/') &&
                        normalized.split('/').none { it == ".." }) { "Ruta invÃ¡lida en el respaldo" }
                    val target = File(staging, normalized).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Ruta invÃ¡lida" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { destination ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                total += count
                                require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Respaldo demasiado grande" }
                                destination.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(requiredText(staging, "manifest.json"))
            require(manifest.optString("format") == "inscreen-backup" && manifest.optInt("version") == FORMAT_VERSION) {
                "Formato de respaldo incompatible"
            }
            val apriori = AprioriStore.validateAndNormalize(requiredText(staging, "apriori.json"))
            val webStorage = JSONObject(requiredText(staging, "web-storage.json"))
            backedUpDirectories.forEach { name ->
                val source = File(staging, "files/$name")
                val destination = File(context.filesDir, name)
                if (destination.exists()) destination.deleteRecursively()
                if (source.isDirectory) source.copyRecursively(destination, overwrite = true)
            }
            return Restored(apriori, webStorage)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun requiredText(root: File, name: String): String =
        File(root, name).takeIf(File::isFile)?.readText(Charsets.UTF_8) ?: error("Falta $name")

    private fun writeText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        directory.walkTopDown().filter(File::isFile).forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry(prefix + relative))
            file.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
