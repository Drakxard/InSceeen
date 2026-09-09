package com.inscreen.mic

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class SynthesisWidgetResolveActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("ResourceType")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        var config = SynthesisWidgetStore.load(this, widgetId)
        if (config == null) { finish(); return }
        val credentials = ProviderCredentialStore(this).load()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(52, 24, 13)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(8, 6, 8, 6) }
        val back = Button(this).apply { text = "‹"; textSize = 25f; contentDescription = "Volver" }
        header.addView(back, LinearLayout.LayoutParams(52, 52))
        header.addView(TextView(this).apply { text = "Semana"; setTextColor(Color.rgb(255, 244, 220)); setPadding(8, 0, 7, 0) })
        val weeksSpinner = Spinner(this)
        header.addView(weeksSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = Button(this).apply { text = "↻"; textSize = 21f; contentDescription = "Actualizar síntesis" }
        header.addView(refresh, LinearLayout.LayoutParams(52, 52))
        root.addView(header)
        val status = TextView(this).apply { setTextColor(Color.rgb(234, 214, 190)); setPadding(16, 4, 16, 7) }
        root.addView(status)
        val viewer = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(ReaderBridge(), "Reader")
        }
        web = viewer
        root.addView(viewer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val cacheKey = MessageDigest.getInstance("SHA-256").digest(((credentials?.baseUrl ?: "") + (credentials?.token ?: "") + config.subjectId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cache = File(filesDir, "synthesis-cache/$cacheKey").apply { mkdirs() }
        val client = credentials?.let { ProviderClient(it.baseUrl, it.token) }
        val readerModule = ModuleCatalog.Module("sintesis", "Síntesis", "modules/sintesis/index.html")
        val moduleCache = ModuleCache.from(this)
        var readerTemplate = File(moduleCache.directory(config.subjectId, readerModule.id), "reader.html")
            .takeIf(File::isFile)?.readText(Charsets.UTF_8)
        viewer.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (uri.scheme in listOf("http", "https") && uri.host != "synthesis.local") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url ?: return emptyResponse()
                if (uri.host != "synthesis.local") return emptyResponse()
                if (uri.path == "/assets/background") return runCatching {
                    WebResourceResponse("image/jpeg", null, assets.open("sintesis/sintesis-fondo.jpg"))
                }.getOrElse { emptyResponse() }
                if (uri.path == "/assets/plaque") return WebResourceResponse("image/png", null, resources.openRawResource(R.drawable.synthesis_plaque))
                val imageId = uri.lastPathSegment.orEmpty()
                if (uri.path != "/images/$imageId" || !imageId.matches(Regex("[a-zA-Z0-9-]{1,100}")) || client == null) return emptyResponse()
                return runCatching {
                    val file = File(cache, "image-$imageId")
                    val typeFile = File(cache, "image-$imageId.type")
                    val pair = if (file.isFile && typeFile.isFile) typeFile.readText() to file.readBytes()
                    else client.readSynthesisImage(imageId).also { (type, bytes) -> file.writeBytes(bytes); typeFile.writeText(type) }
                    WebResourceResponse(pair.first, null, ByteArrayInputStream(pair.second))
                }.getOrElse { emptyResponse() }
            }
        }

        var selectedWeek = -1
        fun loadWeek(week: Int) {
            if (week !in 0..9999) return
            selectedWeek = week; refresh.isEnabled = false; status.text = "Actualizando…"
            Thread {
                var cached = false
                val result = runCatching {
                    val file = File(cache, "week-$week.json")
                    val workspace = if (client == null && file.isFile) { cached = true; JSONObject(file.readText()) }
                    else try {
                        (client ?: throw ProviderWidgetException("provider_not_configured", false)).readSynthesis(config!!.subjectId, week).also {
                            SynthesisDocumentRenderer.render(it)
                            val temporary = File(cache, "week-$week.tmp"); temporary.writeText(it.toString())
                            if (!temporary.renameTo(file)) { file.writeText(temporary.readText()); temporary.delete() }
                        }
                    } catch (error: Exception) {
                        if ((error is ProviderWidgetException && error.transient || client == null) && file.isFile) { cached = true; JSONObject(file.readText()) } else throw error
                    }
                    SynthesisDocumentRenderer.render(workspace, readerTemplate)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed || selectedWeek != week) return@runOnUiThread
                    refresh.isEnabled = true
                    result.fold(onSuccess = {
                        viewer.loadDataWithBaseURL("https://synthesis.local/", it, "text/html", "utf-8", null)
                        status.text = if (cached) "Sin conexión · copia local · ${config!!.subjectName}" else config!!.subjectName
                    }, onFailure = { status.text = "No se pudo cargar la Síntesis de la semana $week." })
                }
            }.start()
        }

        fun showWeeks(weeks: List<Int>) {
            val sorted = weeks.distinct().filter { it in 0..9999 }.sortedDescending()
            if (sorted.isEmpty()) { status.text = "Todavía no hay semanas sincronizadas."; refresh.isEnabled = true; return }
            weeksSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sorted.map { it.toString() })
            weeksSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val week = sorted.getOrNull(position) ?: return
                    if (week != selectedWeek) loadWeek(week)
                }
            }
            weeksSpinner.setSelection(0)
            if (selectedWeek != sorted.first()) loadWeek(sorted.first())
        }

        fun refreshWeeks() {
            refresh.isEnabled = false; status.text = "Buscando semanas…"
            Thread {
                val result = runCatching { (client ?: throw ProviderWidgetException("provider_not_configured", false)).listSynthesisWeeks(config!!.subjectId) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.fold(onSuccess = { weeks ->
                        config = config!!.copy(weeks = weeks, status = "")
                        SynthesisWidgetStore.save(this, widgetId, config!!)
                        showWeeks(weeks)
                    }, onFailure = { showWeeks(config!!.weeks) })
                }
            }.start()
        }
        back.setOnClickListener { navigateBack() }
        refresh.setOnClickListener { selectedWeek = -1; refreshWeeks() }
        fun updateReaderModule(startWhenReady: Boolean) {
            if (startWhenReady) { status.text = "Actualizando lector…"; refresh.isEnabled = false }
            ModuleCatalog.loadPackage(readerModule) { loaded -> runOnUiThread {
                loaded.onSuccess { modulePackage ->
                    runCatching {
                        moduleCache.write(config!!.subjectId, readerModule, modulePackage.files, modulePackage.version)
                        readerTemplate = File(moduleCache.directory(config!!.subjectId, readerModule.id), "reader.html")
                            .takeIf(File::isFile)?.readText(Charsets.UTF_8)
                    }
                }
                if (startWhenReady) refreshWeeks()
            } }
        }
        if (readerTemplate == null) updateReaderModule(true)
        else { refreshWeeks(); updateReaderModule(false) }
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))
    private fun navigateBack() {
        val viewer = web ?: return finish()
        viewer.evaluateJavascript("typeof window.readerBack==='function' ? window.readerBack() : false") {
            if (it == "false" || it == "null") finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { navigateBack() }

    private inner class ReaderBridge {
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    override fun onDestroy() { web?.removeJavascriptInterface("Reader"); web?.destroy(); web = null; super.onDestroy() }
}
