package com.inscreen.mic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CursadoWebUpdateTest {
    private val base = "https://cursado.example"
    private val paths = listOf("index.html", "app.js", "app.css", "pizarra/index.html")
    private fun bytes(version: String, path: String) = "$version:$path".toByteArray()
    private fun manifest(version: String) = JSONObject().put("version", 1).put("bridgeVersion", 1)
        .put("revision", WorkspaceLibraryStore.sha256(version.toByteArray()))
        .put("files", JSONArray().apply { paths.forEach { path ->
            val data = bytes(version, path)
            put(JSONObject().put("path", path).put("size", data.size).put("hash", WorkspaceLibraryStore.sha256(data)))
        } })

    private fun fixture(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "web-update-${UUID.randomUUID()}")
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun preparesVerifiesAndActivatesOnlyAtNextStartup() = fixture { root ->
        var remote = "new"
        var resourceRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val data = if (path.endsWith("current.json")) manifest(remote).toString().toByteArray()
                else { resourceRequests++; bytes(remote, path.substringAfter("/releases/").substringAfter('/')) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody()).build()
        }.build()
        fun packages(bundle: String = "old") = CursadoWebPackage(root, { manifest(bundle) }, { _, path -> bytes(bundle, path).inputStream() }, client)
        val store = packages()
        val original = store.installed(base)
        assertTrue(store.update(base))
        assertEquals(original.name, File(root, "active").readText())
        store.prune(original)
        assertTrue(File(root, "${manifest("new").getString("revision")}/complete.json").isFile)
        assertFalse(store.update(base))
        assertEquals(paths.size, resourceRequests)
        val next = packages().installed(base)
        assertEquals(manifest("new").getString("revision"), next.name)
        assertArrayEquals(bytes("new", "app.js"), File(next, "app.js").readBytes())
        assertFalse(File(root, "pending.json").exists())
        assertFalse(store.update(base))
        remote = "newer"
        assertTrue(store.update(base))
        // A different pairing cannot activate a package downloaded from the old host.
        assertEquals(next, packages().installed("https://another.example"))
        assertEquals(manifest("upgrade").getString("revision"), packages("upgrade").installed(base).name)
    }

    @Test fun interruptedCorruptAndStaleDownloadsKeepTheWorkingVersion() = fixture { root ->
        var mode = "interrupted"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (!path.endsWith("current.json") && mode == "interrupted") throw IOException("offline")
            val data = if (path.endsWith("current.json")) manifest("new").toString().toByteArray()
                else if (mode == "corrupt") "broken".toByteArray()
                else bytes("new", path.substringAfter("/releases/").substringAfter('/'))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody()).build()
        }.build()
        fun packages() = CursadoWebPackage(root, { manifest("old") }, { _, path -> bytes("old", path).inputStream() }, client)
        val store = packages()
        val original = store.installed(base)
        for (failure in listOf("interrupted", "corrupt", "stale")) {
            mode = failure
            try { store.update(base) { failure != "stale" }; fail("Download must fail: $failure") } catch (_: IOException) { } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            assertFalse(File(root, "pending.json").exists())
            assertEquals(original, packages().installed(base))
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
        }
        mode = "valid"
        assertTrue(store.update(base))
    }

    @Test fun slowNetworkNeverHoldsTheStartupLock() = fixture { root ->
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            waiting.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(manifest("old").toString().toResponseBody()).build()
        }.build()
        val store = CursadoWebPackage(root, { manifest("old") }, { _, path -> bytes("old", path).inputStream() }, client)
        val original = store.installed(base)
        val downloader = thread { store.update(base) }
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            val opened = CountDownLatch(1)
            val starter = thread { assertEquals(original, store.installed(base)); opened.countDown() }
            assertTrue("Opening cached UI must not wait for network", opened.await(2, TimeUnit.SECONDS))
            starter.join()
        } finally { release.countDown(); downloader.join(10000) }
    }
}
