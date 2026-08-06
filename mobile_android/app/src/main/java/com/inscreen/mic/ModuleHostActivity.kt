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
    private var providerSubjectSegment = ""
    private var moduleWebView: WebView? = null
    private val providerCache by lazy { ProviderCache.from(this) }
    private val groqCredentialStore by lazy { GroqCredentialStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID).orEmpty()
        val subject = AprioriStore.subject(AprioriStore.load(this), subjectId)
        if (subject == null) {
            finish()
            return
        }
        subjectName = subject.optString("name")
        providerSubjectSegment = subject.optString(
            "providerSubjectSegment",
            ProviderSubject.segment(subjectName),
        )
        intent.getStringExtra(EXTRA_SELECTED_MODULE)?.let { raw ->
            val selected = runCatching { ModuleSelection.parse(raw) }.getOrNull()
            if (selected != null) {
                showModule(selected, persistAssignment = true)
                return
            }
        }
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
            const pending=new Map();
            let sequence=0;
            const request=(kind,day)=>{
              const normalizedDay=Number(day);
              if(!Number.isInteger(normalizedDay)||normalizedDay<0||normalizedDay>6){
                return Promise.resolve({ok:false,archivos:[],error:"invalid_day"});
              }
              return new Promise((resolve)=>{
              const id=String(++sequence);
              pending.set(id,resolve);
              native.request(id,kind,normalizedDay);
              });
            };
            const cached=(operation,type,stage,number)=>{
              const normalizedStage=Number(stage);
              if(typeof type!=="boolean")return Promise.resolve({ok:false,archivos:[],error:"invalid_type"});
              if(!Number.isInteger(normalizedStage)||normalizedStage<=0){
                return Promise.resolve({ok:false,archivos:[],error:"invalid_stage"});
              }
              if(operation==="archivo"&&(!Number.isInteger(Number(number))||Number(number)<=0)){
                return Promise.resolve({ok:false,archivos:[],error:"invalid_file_number"});
              }
              return new Promise((resolve)=>{
                const id=String(++sequence);
                pending.set(id,resolve);
                if(operation==="archivos")native.cachedFiles(id,type,normalizedStage);
                else native.cachedFile(id,type,normalizedStage,Number(number));
              });
            };
            const query=(question,content)=>{
              if(typeof question!=="string"||typeof content!=="string"){
                return Promise.resolve({ok:false,contenido:"",error:"invalid_arguments"});
              }
              if(!question.trim())return Promise.resolve({ok:false,contenido:"",error:"empty_question"});
              if(question.length>12000||content.length>200000){
                return Promise.resolve({ok:false,contenido:"",error:"content_too_large"});
              }
              return new Promise((resolve)=>{
                const id=String(++sequence);
                pending.set(id,resolve);
                native.groqQuery(id,question,content);
              });
            };
            window.__InScreenProviderResolve=(id,payload)=>{
              const resolve=pending.get(String(id));
              if(!resolve)return;
              pending.delete(String(id));
              try{resolve(JSON.parse(payload));}
              catch(_){resolve({ok:false,archivos:[],error:"invalid_response"});}
            };
            window.InScreen={module:{
              context:()=>JSON.parse(native.context()),
              respyPreg:unavailable,
              paginasLeidas:(day)=>request("paginasLeidas",day),
              traduccion:(day)=>request("traduccion",day),
              archivos:(type,stage)=>cached("archivos",type,stage),
              archivo:(type,stage,number)=>cached("archivo",type,stage,number),
              consulta:(question,content)=>query(question,content)
            }};
            delete window.InScreenModuleNative;
          })();</script>
        """.trimIndent()
        val head = Regex("(?i)<head[^>]*>").find(html)
        val bootstrapped = if (head == null) bootstrap + html else
            html.substring(0, head.range.last + 1) + bootstrap + html.substring(head.range.last + 1)
        val view = WebView(this)
        moduleWebView = view
        view.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(
                ModuleBridge(subjectId, subjectName, providerSubjectSegment, providerCache, groqCredentialStore) { requestId, payload ->
                    runOnUiThread {
                        if (isFinishing || moduleWebView !== view) return@runOnUiThread
                        val quotedId = JSONObject.quote(requestId)
                        val quotedPayload = JSONObject.quote(payload)
                        view.evaluateJavascript(
                            "window.__InScreenProviderResolve($quotedId,$quotedPayload)",
                            null,
                        )
                    }
                },
                "InScreenModuleNative",
            )
            loadDataWithBaseURL(module.url(), bootstrapped, "text/html", "utf-8", null)
        }
        setContentView(view)
    }

    override fun onDestroy() {
        moduleWebView = null
        super.onDestroy()
    }

    private class ModuleBridge(
        private val id: String,
        private val name: String,
        private val subjectSegment: String,
        private val cache: ProviderCache,
        private val groqCredentials: GroqCredentialStore,
        private val deliver: (String, String) -> Unit,
    ) {
        @JavascriptInterface fun context(): String = JSONObject()
            .put("id", id)
            .put("nombre", name)
            .put("providerSubjectSegment", subjectSegment)
            .toString()
        @JavascriptInterface fun providerNotConfigured(day: Int): String = JSONObject()
            .put("ok", false).put("archivos", org.json.JSONArray())
            .put("error", "provider_not_configured").put("day", day).toString()

        @JavascriptInterface fun request(requestId: String, kind: String, day: Int) {
            if (requestId.length !in 1..64) return
            ProviderClient.shared.request(kind, subjectSegment, day) { payload ->
                val merged = if (JSONObject(payload).optBoolean("ok", false)) {
                    cache.merge(id, kind == "traduccion", payload)
                } else payload
                deliver(requestId, merged)
            }
        }

        @JavascriptInterface fun cachedFiles(requestId: String, type: Boolean, stage: Int) {
            if (requestId.length !in 1..64) return
            deliver(requestId, cache.list(id, type, stage))
        }

        @JavascriptInterface fun cachedFile(requestId: String, type: Boolean, stage: Int, number: Int) {
            if (requestId.length !in 1..64) return
            deliver(requestId, cache.read(id, type, stage, number))
        }

        @JavascriptInterface fun groqQuery(requestId: String, question: String, content: String) {
            if (requestId.length !in 1..64) return
            val normalizedQuestion = question.trim()
            when {
                normalizedQuestion.isEmpty() -> deliver(requestId, GroqClient.failureJson("empty_question"))
                question.length > 12_000 || content.length > 200_000 ->
                    deliver(requestId, GroqClient.failureJson("content_too_large"))
                else -> {
                    val apiKey = groqCredentials.apiKey()
                    val model = groqCredentials.model()
                    if (apiKey == null || model == null) {
                        deliver(requestId, GroqClient.failureJson("groq_not_configured"))
                    } else {
                        GroqClient.shared.query(apiKey, model, normalizedQuestion, content) { payload ->
                            deliver(requestId, payload)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SUBJECT_ID = "subject_id"
        private const val EXTRA_SELECTED_MODULE = "selected_module"
        fun open(context: Context, subjectId: String) = context.startActivity(
            Intent(context, ModuleHostActivity::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        internal fun openSelected(context: Context, subjectId: String, module: ModuleCatalog.Module) = context.startActivity(
            Intent(context, ModuleHostActivity::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
                .putExtra(EXTRA_SELECTED_MODULE, ModuleSelection.serialize(module))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal object ModuleSelection {
    fun parse(raw: String): ModuleCatalog.Module {
        val value = JSONObject(raw)
        val id = value.optString("id").trim()
        val name = value.optString("nombre").trim()
        val entry = value.optString("entry").trim()
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}")))
        require(name.isNotEmpty())
        require(entry.matches(Regex("modules/[a-z0-9][a-z0-9-]{0,79}/index\\.html")))
        return ModuleCatalog.Module(id, name, entry)
    }

    fun serialize(module: ModuleCatalog.Module): String = JSONObject()
        .put("id", module.id).put("nombre", module.name).put("entry", module.entry).toString()
}
