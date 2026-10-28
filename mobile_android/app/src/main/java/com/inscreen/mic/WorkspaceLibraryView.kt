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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class WorkspaceLibraryView(context: Context, private val scan: () -> Unit, private val home: () -> Unit) : WebView(context) {
    private val loading = AtomicBoolean(false)
    private val updatingWeb = AtomicBoolean(false)
    private val bootingWeb = AtomicBoolean(false)
    private val checkedWeb = AtomicBoolean(false)
    private val inventories = ConcurrentHashMap<String, JSONObject>()
    private val pendingPdfs = ConcurrentHashMap<String, Boolean>()
    @Volatile private var closed = false
    var atHome = true
        private set
    private val webPackages = CursadoWebPackage(context)
    @Volatile private var packageDirectory: File? = null
    init {
        setBackgroundColor(Color.rgb(238, 239, 240))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = true
        settings.setSupportZoom(true)
        addJavascriptInterface(Bridge(), "CursadoNative")
        webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val uri = url?.let { android.net.Uri.parse(it) }
                atHome = uri?.host == HOST && uri.path.orEmpty() in listOf("", "/", "/index.html")
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val external = request.url.scheme != "https" || request.url.host != HOST
                if (external && request.hasGesture()) Bridge().openExternal(request.url.toString())
                return external
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != HOST) return empty()
                if (!uri.path.orEmpty().startsWith("/files/")) {
                    val directory = packageDirectory ?: return empty()
                    if (uri.path.orEmpty().startsWith("/api/")) return empty()
                    val path = uri.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
                    val routes = setOf("estudio", "sintesis", "practice/viewer", "native/pdf", "pizarra", "pizarra/")
                    val target = File(directory, if (path in routes) { if (path.startsWith("pizarra")) "pizarra/index.html" else "index.html" } else path).canonicalFile
                    if (!target.path.startsWith(directory.path + File.separator) || !target.isFile || target.name == "complete.json") return empty()
                    val mime = when (target.extension) {
                        "js", "mjs" -> "text/javascript"
                        "css" -> "text/css"
                        "html" -> "text/html"
                        "json" -> "application/json"
                        "wasm" -> "application/wasm"
                        "svg" -> "image/svg+xml"
                        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.extension) ?: "application/octet-stream"
                    }
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-cache"), target.inputStream())
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
        startWeb()
    }
    private fun empty() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    private fun evaluate(script: String) { if (!closed) evaluateJavascript(script, null) }
    private fun message(message: String) = evaluate("window.LibraryMessage?.(${JSONObject.quote(message)})")
    private fun startWeb() {
        if (!bootingWeb.compareAndSet(false, true)) return
        thread(name = "cursado-web-start") {
            try {
                val directory = webPackages.installed(WorkspaceCredentialStore(context).load()?.baseUrl).canonicalFile
                post { if (!closed) { packageDirectory = directory; loadUrl("https://$HOST/"); refresh() } }
            } catch (error: Exception) {
                post { if (!closed) {
                    loadDataWithBaseURL("https://$HOST/", "<p>No se pudo abrir Cursado.</p><button aria-label='Reintentar' onclick='CursadoNative.refresh()'>↻</button>", "text/html", "UTF-8", null)
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                } }
            } finally { bootingWeb.set(false) }
        }
    }
    private fun checkWebUpdate() {
        if (closed || !updatingWeb.compareAndSet(false, true)) return
        thread(name = "cursado-web-update") {
            try { runCatching { WorkspaceCredentialStore(context).load()?.let {
                val baseUrl = it.baseUrl
                val prepared = webPackages.update(baseUrl) { !closed && WorkspaceCredentialStore(context).load()?.baseUrl == baseUrl }
                if (prepared) post { if (!closed) Toast.makeText(context, "Cursado actualizado. Se usará en la próxima apertura.", Toast.LENGTH_LONG).show() }
                packageDirectory?.let { directory -> webPackages.prune(directory) }
            } } }
            finally { updatingWeb.set(false) }
        }
    }
    fun refresh() {
        if (closed) return
        if (packageDirectory == null) { startWeb(); return }
        if (checkedWeb.compareAndSet(false, true)) checkWebUpdate()
        if (!loading.compareAndSet(false, true)) return
        thread(name = "workspace-library-refresh") {
            try {
                val credentials = WorkspaceCredentialStore(context).load() ?: return@thread
                WorkspaceLibraryWorker.schedule(context)
                val store = WorkspaceLibraryStore(context, credentials)
                if (store.online()) store.sync(false)
                post { evaluate("window.LibraryReload?.()") }
            } catch (_: Exception) {
                // Background refresh must leave the offline library usable and quiet.
                // The scheduled worker retries when connectivity is available.
            }
            finally { loading.set(false) }
        }
    }
    fun paired() { checkedWeb.set(false); inventories.clear(); evaluate("window.LibraryReload?.()"); refresh() }
    fun back(): Boolean {
        evaluate("window.dispatchEvent(new CustomEvent('cursado-native-back',{cancelable:true}))")
        return true
    }
    fun close() { closed = true; pendingPdfs.clear(); removeJavascriptInterface("CursadoNative"); destroy() }
    private inner class Bridge {
        @JavascriptInterface fun cancelPdf(requestId: String) { pendingPdfs.remove(requestId) }
        @JavascriptInterface fun openPdf(id: String, revision: String, requestId: String) {
            if (!requestId.matches(Regex("[a-zA-Z0-9-]{1,100}"))) return
            pendingPdfs[requestId] = true
            fun result(error: String? = null) {
                pendingPdfs.remove(requestId)
                val detail = JSONObject().put("requestId", requestId)
                if (error != null) detail.put("error", error)
                post { evaluate("window.dispatchEvent(new CustomEvent('cursado-native-pdf-result',{detail:$detail}))") }
            }
            thread(name = "workspace-native-pdf") {
                try {
                    val inventory = inventories[revision] ?: error("Actualizá la biblioteca.")
                    val credentials = WorkspaceCredentialStore(context).load() ?: error("Vinculá la biblioteca.")
                    require(credentials.workspaceId == inventory.getString("workspaceId"))
                    val files = inventory.getJSONArray("files")
                    val file = (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull {
                        it.getString("id") == id && it.getString("mime").substringBefore(';') == "application/pdf"
                    } ?: error("El PDF no está en esta publicación.")
                    val saved = WorkspaceLibraryStore(context, credentials).ensureFile(inventory, file)
                    val sessions = File(context.cacheDir, "library-pdf").apply { mkdirs() }
                    val session = File(sessions, "${java.util.UUID.randomUUID()}.json")
                    session.writeText(JSONObject().put("path", saved.absolutePath).put("fileId", id).put("manifest", inventory).toString())
                    post {
                        if (closed || pendingPdfs.remove(requestId) != true) { session.delete(); return@post }
                        try {
                            context.startActivity(android.content.Intent(context, WorkspacePdfActivity::class.java).putExtra("session", session.absolutePath))
                            result()
                        } catch (error: Exception) { session.delete(); result(error.message ?: "No se pudo abrir el PDF.") }
                    }
                } catch (error: Exception) { result(error.message ?: "No se pudo abrir el PDF.") }
            }
        }
        @JavascriptInterface fun openExternal(url: String) {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return
            if (url.length > 10000 || uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return
            post { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
        }
        @JavascriptInterface fun home() { post { this@WorkspaceLibraryView.home() } }
        @JavascriptInterface fun openHtml(id: String, revision: String) {
            thread(name = "workspace-library-html") {
                try {
                    val inventory = inventories[revision] ?: error("Actualizá la biblioteca.")
                    val credentials = WorkspaceCredentialStore(context).load() ?: error("Vinculá la biblioteca.")
                    require(credentials.workspaceId == inventory.getString("workspaceId"))
                    val files = inventory.getJSONArray("files")
                    val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("id") == id && it.getString("mime") == "text/html" }
                    val saved = WorkspaceLibraryStore(context, credentials).ensureFile(inventory, file)
                    post { context.startActivity(android.content.Intent(context, WorkspaceHtmlActivity::class.java).putExtra("path", saved.absolutePath)) }
                } catch (error: Exception) { post { message(error.message ?: "No se pudo abrir Verlo.") } }
            }
        }
        @JavascriptInterface fun snapshot(): String = try {
            val credentials = WorkspaceCredentialStore(context).load()
            if (credentials == null) JSONObject().put("bridgeVersion", 1).put("linked", false).toString()
            else {
                val store = WorkspaceLibraryStore(context, credentials)
                val manifest = store.visible()
                val result = JSONObject().put("bridgeVersion", 1).put("linked", true).put("name", credentials.name).put("online", store.online()).put("wifi", store.wifi())
                if (manifest != null) {
                    inventories[manifest.getString("revision")] = manifest
                    val available = JSONObject()
                    val files = manifest.getJSONArray("files")
                    for (i in 0 until files.length()) { val file = files.getJSONObject(i); available.put(file.getString("id"), store.downloaded(file)) }
                    result.put("manifest", manifest).put("available", available)
                }
                result.toString()
            }
        } catch (error: Exception) { JSONObject().put("bridgeVersion", 1).put("linked", false).put("error", error.message).toString() }
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
