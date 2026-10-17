package com.inscreen.mic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class CursadoWebPackageTest {
    private fun manifest(): JSONObject = JSONObject().put("version",1).put("bridgeVersion",1).put("revision","a".repeat(64)).put("files",JSONArray().apply {
        listOf("index.html","app.js","app.css","pizarra/index.html").forEach { put(JSONObject().put("path",it).put("size",10).put("hash","b".repeat(64))) }
    })
    private fun reject(action: () -> Unit) { try { action(); fail("Debe rechazar el paquete") } catch (_: IllegalArgumentException) { } }
    @Test fun validatesCompleteCompatiblePackage() { CursadoWebPackage.validate(manifest()) }
    @Test fun rejectsIncompatibleAndIncompletePackages() {
        reject { CursadoWebPackage.validate(manifest().put("bridgeVersion",2)) }
        reject { CursadoWebPackage.validate(manifest().put("files",JSONArray())) }
        val duplicate=manifest(); duplicate.getJSONArray("files").put(duplicate.getJSONArray("files").getJSONObject(0))
        reject { CursadoWebPackage.validate(duplicate) }
    }
    @Test fun rejectsTraversalUrlsAndReservedPaths() {
        listOf("../private","/index.html","a/../../secret","https://evil.test/app.js","a\\b.js","complete.json","a//b").forEach { assertFalse(it,CursadoWebPackage.validPath(it)) }
        assertTrue(CursadoWebPackage.validPath("pdfjs/locale/es-AR/viewer.ftl"))
    }
    @Test fun rejectsOversizedOrUnverifiedResources() {
        val invalid=manifest(); invalid.getJSONArray("files").getJSONObject(0).put("size",100L*1024*1024)
        reject { CursadoWebPackage.validate(invalid) }
        val hash=manifest(); hash.getJSONArray("files").getJSONObject(0).put("hash","unverified")
        reject { CursadoWebPackage.validate(hash) }
    }
    @Test fun verifiesDownloadsAndRemovesInterruptedOrCorruptFiles() {
        val directory = Files.createTempDirectory("cursado-web-test").toFile()
        try {
            val file=File(directory,"app.js")
            val bytes="hello".toByteArray()
            val item=JSONObject().put("size",bytes.size).put("hash",WorkspaceLibraryStore.sha256(bytes))
            CursadoWebPackage.copyVerified(item,bytes.inputStream(),file)
            assertArrayEquals(bytes,file.readBytes())
            reject { CursadoWebPackage.copyVerified(item,"wrong".byteInputStream(),file) }
            assertFalse(file.exists())
            reject { CursadoWebPackage.copyVerified(item,"hel".byteInputStream(),file) }
            assertFalse(file.exists())
            val broken=object:InputStream(){override fun read():Int=throw IOException("interrupted")}
            try { CursadoWebPackage.copyVerified(item,broken,file);fail("Debe fallar") } catch (_:IOException) { }
            assertFalse(file.exists())
        } finally {directory.deleteRecursively()}
    }
}
