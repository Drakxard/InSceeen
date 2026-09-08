package com.inscreen.mic

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class SynthesisWidgetResolveActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val config = SynthesisWidgetStore.load(this, id)
        val week = intent.getIntExtra("weekNumber", -1)
        if (config == null || week !in config.weeks) { finish(); return }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(Button(this).apply { text = "‹"; contentDescription = "Volver"; setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = config.subjectName + " · Semana " + week; textSize = 18f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = Button(this).apply { text = "↻"; contentDescription = "Actualizar síntesis" }
        header.addView(refresh)
        content.addView(header)
        val status = TextView(this).apply { text = "Cargando síntesis…"; setPadding(16, 8, 16, 8) }
        content.addView(status)
        val viewer = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        web = viewer
        content.addView(viewer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
        val credentials = ProviderCredentialStore(this).load()
        if (credentials == null) { status.text = "Vinculá el proveedor desde InScreen."; return }
        val client = ProviderClient(credentials.baseUrl, credentials.token)
        val cacheKey = MessageDigest.getInstance("SHA-256").digest((credentials.baseUrl + credentials.token + config.subjectId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cache = File(filesDir, "synthesis-cache/$cacheKey").apply { mkdirs() }
        viewer.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (uri.scheme in listOf("http", "https") && uri.host != "synthesis.local")
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url
                val imageId = uri?.lastPathSegment.orEmpty()
                if (uri?.host != "synthesis.local" || uri.path != "/images/$imageId" || !imageId.matches(Regex("[a-zA-Z0-9-]{1,100}")))
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))
                return runCatching {
                    val file = File(cache, "image-$imageId")
                    val typeFile = File(cache, "image-$imageId.type")
                    val pair = if (file.isFile && typeFile.isFile) typeFile.readText() to file.readBytes()
                        else client.readSynthesisImage(imageId).also { (type, bytes) ->
                            file.writeBytes(bytes); typeFile.writeText(type)
                        }
                    WebResourceResponse(pair.first, null, ByteArrayInputStream(pair.second))
                }.getOrElse { WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf())) }
            }
        }
        fun load() {
            refresh.isEnabled = false
            status.text = "Actualizando…"
            Thread {
                var cached = false
                val result = runCatching {
                    val file = File(cache, "week-$week.json")
                    val workspace = try {
                        client.readSynthesis(config.subjectId, week).also {
                            SynthesisDocumentRenderer.render(it)
                            val temporary = File(cache, "week-$week.tmp")
                            temporary.writeText(it.toString())
                            if (!temporary.renameTo(file)) error("No se pudo guardar la copia local.")
                        }
                    } catch (error: Exception) {
                        if (error is ProviderWidgetException && error.transient && file.isFile) {
                            cached = true
                            JSONObject(file.readText())
                        } else throw error
                    }
                    SynthesisDocumentRenderer.render(workspace)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refresh.isEnabled = true
                    result.fold(onSuccess = {
                        viewer.loadDataWithBaseURL("https://synthesis.local/", it, "text/html", "utf-8", null)
                        status.text = if (cached) "Sin conexión. Mostrando la copia local." else "Síntesis sincronizada"
                    }, onFailure = { status.text = "No se pudo cargar la síntesis. Revisá la conexión y el proveedor." })
                }
            }.start()
        }
        refresh.setOnClickListener { load() }
        load()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
