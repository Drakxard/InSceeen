package com.inscreen.mic

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class WorkspaceLibraryView(context: Context, private val scan: () -> Unit, private val home: () -> Unit) : WebView(context) {
    private val loading = AtomicBoolean(false)
    private val inventories = ConcurrentHashMap<String, JSONObject>()
    private var closed = false
    private val loader = WebViewAssetLoader.Builder().setDomain(HOST).addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    init {
        setBackgroundColor(Color.rgb(238, 239, 240))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(true)
        addJavascriptInterface(Bridge(), "Library")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame || request.url.scheme != "https" || request.url.host != HOST || !request.url.path.orEmpty().startsWith("/assets/library/pdfjs/web/viewer.html")
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != HOST) return empty()
                loader.shouldInterceptRequest(uri)?.let {
                    if (uri.path.orEmpty().endsWith(".mjs")) it.mimeType = "text/javascript"
                    if (uri.path.orEmpty().endsWith(".wasm")) it.mimeType = "application/wasm"
                    return it
                }
                val segments = uri.pathSegments
                if (segments.size != 3 || segments[0] != "files") return empty()
                return runCatching {
                    val inventory = inventories[segments[1]] ?: error("Revisión no abierta.")
                    val files = inventory.getJSONArray("files")
                    val file = (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull { it.getString("id") == segments[2] } ?: error("Archivo desconocido.")
                    val credentials = WorkspaceCredentialStore(context).load() ?: error("Sin vínculo.")
                    require(credentials.workspaceId == inventory.getString("workspaceId"))
                    val saved = WorkspaceLibraryStore(context, credentials).ensureFile(inventory, file)
                    val mime = file.getString("mime").substringBefore(';')
                    WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript")) "UTF-8" else null, 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Content-Security-Policy" to "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox"), saved.inputStream())
                }.getOrElse { error ->
                    post { message(error.message ?: "No se pudo abrir el archivo.") }
                    WebResourceResponse("text/plain", "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream("Archivo no disponible sin conexión.".toByteArray()))
                }
            }
        }
        loadUrl("https://$HOST/assets/library/index.html")
    }
    private fun empty() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    private fun evaluate(script: String) { if (!closed) evaluateJavascript(script, null) }
    private fun message(message: String) = evaluate("window.LibraryMessage?.(${JSONObject.quote(message)})")
    fun refresh() {
        val credentials = runCatching { WorkspaceCredentialStore(context).load() }.getOrElse { post { message("No se pudo abrir el vínculo. Volvé a vincular la biblioteca.") }; return } ?: return
        WorkspaceLibraryWorker.schedule(context)
        if (!loading.compareAndSet(false, true)) return
        thread(name = "workspace-library-refresh") {
            try {
                val store = WorkspaceLibraryStore(context, credentials)
                if (store.online()) store.sync(false)
                post { evaluate("window.LibraryReload?.()") }
            } catch (error: Exception) { post { message(error.message ?: "Sin conexión; se conserva la copia descargada.") } }
            finally { loading.set(false) }
        }
    }
    fun paired() { inventories.clear(); evaluate("window.LibraryReload?.()"); refresh() }
    fun back(): Boolean {
        evaluate("window.LibraryBack?.()")
        return true
    }
    fun close() { closed = true; removeJavascriptInterface("Library"); destroy() }
    private inner class Bridge {
        @JavascriptInterface fun home() { post { this@WorkspaceLibraryView.home() } }
        @JavascriptInterface fun openHtml(id: String, revision: String) {
            thread(name = "workspace-library-html") {
                try {
                    val inventory = inventories[revision] ?: error("Actualizá la biblioteca.")
                    val credentials = WorkspaceCredentialStore(context).load() ?: error("Vinculá la biblioteca.")
                    val files = inventory.getJSONArray("files")
                    val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("id") == id && it.getString("mime") == "text/html" }
                    val saved = WorkspaceLibraryStore(context, credentials).ensureFile(inventory, file)
                    post { context.startActivity(android.content.Intent(context, WorkspaceHtmlActivity::class.java).putExtra("path", saved.absolutePath)) }
                } catch (error: Exception) { post { message(error.message ?: "No se pudo abrir Verlo.") } }
            }
        }
        @JavascriptInterface fun snapshot(): String = try {
            val credentials = WorkspaceCredentialStore(context).load()
            if (credentials == null) JSONObject().put("linked", false).toString()
            else {
                val store = WorkspaceLibraryStore(context, credentials)
                val manifest = store.visible()
                val result = JSONObject().put("linked", true).put("name", credentials.name).put("online", store.online()).put("wifi", store.wifi())
                if (manifest != null) {
                    inventories[manifest.getString("revision")] = manifest
                    val available = JSONObject()
                    val files = manifest.getJSONArray("files")
                    for (i in 0 until files.length()) { val file = files.getJSONObject(i); available.put(file.getString("id"), store.downloaded(file)) }
                    result.put("manifest", manifest).put("available", available)
                }
                result.toString()
            }
        } catch (error: Exception) { JSONObject().put("linked", false).put("error", error.message).toString() }
        @JavascriptInterface fun pair() { post { scan() } }
        @JavascriptInterface fun refresh() { post { this@WorkspaceLibraryView.refresh() } }
        @JavascriptInterface fun download(id: String, revision: String) {
            if (!WorkspaceLibraryStore.validHash(id)) return
            thread(name = "workspace-library-open") {
                try {
                    val inventory = inventories[revision] ?: error("Actualizá la biblioteca.")
                    val credentials = WorkspaceCredentialStore(context).load() ?: error("Vinculá la biblioteca.")
                    val files = inventory.getJSONArray("files")
                    val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("id") == id }
                    WorkspaceLibraryStore(context, credentials).ensureFile(inventory, file)
                    post { evaluate("window.LibraryDownloaded?.(${JSONObject.quote(id)},${JSONObject.quote(revision)})") }
                } catch (error: Exception) { post { message(error.message ?: "No se pudo descargar el archivo.") } }
            }
        }
        @JavascriptInterface fun unlink() { post {
            AlertDialog.Builder(context).setTitle("¿Quitar biblioteca?").setMessage("Se eliminará la biblioteca descargada de este celular. La pila y la conexión de InScreen se conservan.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Quitar") { _, _ ->
                    thread(name = "workspace-library-unlink") {
                        synchronized(WorkspaceLibraryStore.lock) {
                            val credentials = WorkspaceCredentialStore(context).load()
                            WorkspaceLibraryWorker.cancel(context)
                            WorkspaceCredentialStore(context).clear()
                            credentials?.let { WorkspaceLibraryStore(context, it).directory.deleteRecursively() }
                        }
                        post { inventories.clear(); evaluate("window.LibraryReload?.()") }
                    }
                }.show()
        } }
    }
    companion object { private const val HOST = "workspace.inscreen.local" }
}
