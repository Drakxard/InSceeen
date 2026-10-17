package com.inscreen.mic

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import java.io.ByteArrayInputStream
import java.io.File

/** Saved HTML runs in its own WebView, with no native bridge or network access. */
class WorkspaceHtmlActivity : Activity() {
    private var viewer: WebView? = null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(intent.getStringExtra("path") ?: run { finish(); return }).canonicalFile
        val root = File(filesDir, "workspace-library").canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !WorkspaceLibraryStore.validHash(file.name) || !file.isFile) { finish(); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply { text = "‹ Volver a la biblioteca"; setOnClickListener { finish() } })
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        viewer = web
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        val policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; frame-src 'none'; form-action 'none'\">"
        val html = file.readText(Charsets.UTF_8)
        val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        val start = head.find(html)
        val document = if (start != null) html.substring(0, start.range.last + 1) + policy + html.substring(start.range.last + 1) else policy + html
        web.loadDataWithBaseURL("https://saved-html.inscreen.local/", document, "text/html", "UTF-8", null)
    }
    override fun onDestroy() { viewer?.destroy(); viewer = null; super.onDestroy() }
}
