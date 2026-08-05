package com.inscreen.mic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import org.json.JSONObject

class ModuleHostActivity : Activity() {
    private lateinit var subjectId: String
    private var subjectName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID).orEmpty()
        val subject = AprioriStore.subject(AprioriStore.load(this), subjectId)
        if (subject == null) {
            finish()
            return
        }
        subjectName = subject.optString("name")
        val assignedModule = subject.optJSONObject("module")
        val entry = assignedModule?.optString("entry").orEmpty()
        if (entry.isBlank()) showPicker() else showModule(ModuleCatalog.Module(
            assignedModule?.optString("id").orEmpty(), assignedModule?.optString("nombre").orEmpty(), entry,
        ))
    }

    private fun showPicker(notice: String? = null) {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply { text = "Buscar módulo para $subjectName"; textSize = 21f }
        val search = EditText(this).apply { hint = "Buscar"; isSingleLine = true }
        val status = TextView(this).apply { text = notice ?: "Cargando módulos…"; setPadding(0, 18, 0, 8) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(title); page.addView(search); page.addView(status)
        val assigned = AprioriStore.subject(AprioriStore.load(this), subjectId)
            ?.optJSONObject("module")?.optString("id").orEmpty()
        if (assigned.isNotBlank()) {
            page.addView(Button(this).apply {
                text = "Quitar módulo"
                setOnClickListener {
                    AprioriStore.assignModule(this@ModuleHostActivity, subjectId, null)
                    text = "Módulo quitado"
                    isEnabled = false
                }
            })
        }
        page.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        ModuleCatalog.load { loaded -> runOnUiThread {
            loaded.fold(onSuccess = { modules ->
                fun render(query: String = "") {
                    val matching = modules.filter { it.name.contains(query, true) || it.id.contains(query, true) }
                    results.removeAllViews()
                    status.text = when {
                        modules.isEmpty() -> "No hay módulos disponibles en GitHub."
                        matching.isEmpty() -> "No se encontraron módulos."
                        else -> "${matching.size} módulo(s)"
                    }
                    matching.forEach { module ->
                        val item = TextView(this).apply {
                            text = "${module.name}\nUSAR"
                            textSize = 17f
                            setPadding(18, 22, 18, 22)
                            setOnClickListener {
                                showModule(module, persistAssignment = true)
                            }
                        }
                        results.addView(item)
                    }
                }
                search.setOnEditorActionListener { _, _, _ -> render(search.text.toString()); true }
                search.addTextChangedListener(SimpleTextWatcher { render(it) })
                render()
            }, onFailure = { error -> status.text = "No se pudo cargar el catálogo: ${error.message ?: "error"}" })
        }}
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun showModule(module: ModuleCatalog.Module, persistAssignment: Boolean = false) {
        setContentView(TextView(this).apply {
            text = "Abriendo módulo…"
            textSize = 17f
            gravity = Gravity.CENTER
        })
        ModuleCatalog.loadHtml(module) { loaded -> runOnUiThread {
            loaded.fold(
                onSuccess = { html ->
                    if (persistAssignment) {
                        AprioriStore.assignModule(this@ModuleHostActivity, subjectId, module)
                    }
                    showModuleHtml(module, html)
                },
                onFailure = { error ->
                    showPicker("No se pudo cargar el módulo desde GitHub. Elegí otro módulo.")
                },
            )
        }}
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun showModuleHtml(module: ModuleCatalog.Module, html: String) {
        val bootstrap = """
          <script>(function(){
            const native=window.InScreenModuleNative;
            const unavailable=(day)=>Promise.resolve(JSON.parse(native.providerNotConfigured(day)));
            window.InScreen={module:{
              context:()=>JSON.parse(native.context()),
              respyPreg:unavailable,paginasLeidas:unavailable,traduccion:unavailable
            }};
            delete window.InScreenModuleNative;
          })();</script>
        """.trimIndent()
        val head = Regex("(?i)<head[^>]*>").find(html)
        val bootstrapped = if (head == null) bootstrap + html else
            html.substring(0, head.range.last + 1) + bootstrap + html.substring(head.range.last + 1)
        val view = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(ModuleBridge(subjectId, subjectName), "InScreenModuleNative")
            loadDataWithBaseURL(module.url(), bootstrapped, "text/html", "utf-8", null)
        }
        setContentView(view)
    }

    private class ModuleBridge(private val id: String, private val name: String) {
        @JavascriptInterface fun context(): String = JSONObject().put("id", id).put("nombre", name).toString()
        @JavascriptInterface fun providerNotConfigured(day: Int): String = JSONObject()
            .put("ok", false).put("error", "provider_not_configured").put("day", day).toString()
    }

    companion object {
        private const val EXTRA_SUBJECT_ID = "subject_id"
        fun open(context: Context, subjectId: String) = context.startActivity(
            Intent(context, ModuleHostActivity::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
