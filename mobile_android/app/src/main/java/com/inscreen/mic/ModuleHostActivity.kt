package com.inscreen.mic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class ModuleHostActivity : Activity() {
    private lateinit var subjectId: String
    private var subjectName = ""
    private var providerSubjectSegment = ""
    private var notesSessionId: String? = null
    private var moduleWebView: WebView? = null
    private var moduleSpeech: ModuleSpeechController? = null
    private var pendingVoiceStart: PendingVoiceStart? = null
    private val providerCache by lazy { ProviderCache.from(this) }
    private val groqCredentialStore by lazy { GroqCredentialStore(this) }
    private val providerClient by lazy {
        ProviderCredentialStore(this).load()?.let { ProviderClient(it.baseUrl, it.token) }
    }
    private val moduleCache by lazy { ModuleCache.from(this) }
    private val notesStore by lazy { SubjectNotesStore.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID).orEmpty()
        notesSessionId = intent.getStringExtra(EXTRA_NOTES_SESSION_ID)?.trim()?.takeIf(String::isNotEmpty)
        val subject = AprioriStore.subject(AprioriStore.load(this), subjectId)
        if (subject == null) {
            showMissingSubject()
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

    private fun showMissingSubject() {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(TextView(this@ModuleHostActivity).apply {
                text = "La materia ya no existe."
                textSize = 19f
                gravity = Gravity.CENTER
            })
            addView(Button(this@ModuleHostActivity).apply {
                text = "VOLVER A INSCREEN"
                setOnClickListener {
                    startActivity(Intent(this@ModuleHostActivity, MainActivity::class.java))
                    finish()
                }
            })
        })
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
                    moduleCache.remove(subjectId)
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
        if (!persistAssignment) {
            moduleCache.read(subjectId, module)?.let { cached ->
                showModuleHtml(module, cached)
                return
            }
        }
        setContentView(TextView(this).apply {
            text = "Abriendo módulo…"
            textSize = 17f
            gravity = Gravity.CENTER
        })
        ModuleCatalog.loadPackage(module) { loaded -> runOnUiThread {
            loaded.fold(
                onSuccess = { packageFiles ->
                    runCatching {
                        moduleCache.write(subjectId, module, packageFiles.files)
                        if (persistAssignment) {
                            check(AprioriStore.assignModule(this@ModuleHostActivity, subjectId, module))
                        }
                    }.fold(
                        onSuccess = { showModuleHtml(module, packageFiles.html) },
                        onFailure = { showModuleLoadError(module, persistAssignment) },
                    )
                },
                onFailure = { showModuleLoadError(module, persistAssignment) },
            )
        }}
    }

    private fun showModuleLoadError(module: ModuleCatalog.Module, persistAssignment: Boolean) {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(TextView(this@ModuleHostActivity).apply {
                text = "No se pudo descargar y guardar el módulo. La asignación anterior no fue modificada."
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 18)
            })
            addView(Button(this@ModuleHostActivity).apply {
                text = "REINTENTAR"
                setOnClickListener { showModule(module, persistAssignment) }
            })
            addView(Button(this@ModuleHostActivity).apply {
                text = "ELEGIR OTRO MÓDULO"
                setOnClickListener { showPicker() }
            })
        })
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
              if(!Number.isInteger(normalizedStage)||normalizedStage<0){
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
            const history=(type)=>{
              if(typeof type!=="boolean")return Promise.resolve({ok:false,archivos:[],error:"invalid_type"});
              return new Promise((resolve)=>{
                const id=String(++sequence);
                pending.set(id,resolve);
                native.cachedHistory(id,type);
              });
            };
            const notes=(operation,number)=>{
              if(operation==="apunte"&&(typeof number!=="number"||!Number.isInteger(number)||number<=0)){
                return Promise.resolve({ok:false,archivos:[],error:"invalid_file_number"});
              }
              return new Promise((resolve)=>{
                const id=String(++sequence);
                pending.set(id,resolve);
                if(operation==="apuntes")native.noteFiles(id);
                else native.noteFile(id,number);
              });
            };
            const studyState=(operation,state)=>{
              if(operation==="guardar"){
                let serialized;
                try{serialized=JSON.stringify(state);}catch(_){return Promise.resolve({ok:false,error:"invalid_study_state"});}
                if(serialized.length>1048576)return Promise.resolve({ok:false,error:"study_state_too_large"});
                return new Promise((resolve)=>{
                  const id=String(++sequence);pending.set(id,resolve);native.saveNoteStudyState(id,serialized);
                });
              }
              return new Promise((resolve)=>{
                const id=String(++sequence);pending.set(id,resolve);native.noteStudyState(id);
              });
            };
            const voice=(operation,options)=>new Promise((resolve)=>{
              const id=String(++sequence);pending.set(id,resolve);
              if(operation==="estado")native.voiceStatus(id);
              else if(operation==="iniciar")native.voiceStart(id,options?.permitirServicioSistema===true);
              else if(operation==="detener")native.voiceStop(id);
              else native.voiceCancel(id);
            });
            const deleteCachedLine=(type,stage,number,line)=>{
              if(typeof type!=="boolean")return Promise.resolve({ok:false,archivos:[],error:"invalid_type"});
              const validInteger=(value,positive)=>typeof value==="number"&&Number.isSafeInteger(value)&&
                value<2147483648&&(positive?value>0:value>=0);
              if(!validInteger(stage,false))return Promise.resolve({ok:false,archivos:[],error:"invalid_stage"});
              if(!validInteger(number,true))return Promise.resolve({ok:false,archivos:[],error:"invalid_file_number"});
              if(!validInteger(line,true))return Promise.resolve({ok:false,archivos:[],error:"invalid_line_number"});
              return new Promise((resolve)=>{
                const id=String(++sequence);
                pending.set(id,resolve);
                native.deleteCachedLine(id,type,stage,number,line);
              });
            };
            const latestTranslation=(lastFile)=>{
              if(lastFile!==false&&lastFile!==null&&lastFile!==undefined&&
                 (typeof lastFile!=="string"||!/^[1-9][0-9]*\.txt$/.test(lastFile))){
                return Promise.resolve({ok:false,archivos:[],error:"invalid_last_file"});
              }
              return new Promise((resolve)=>{
                const id=String(++sequence);
                pending.set(id,resolve);
                native.requestLatestTranslation(id,typeof lastFile==="string"?lastFile:"");
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
            window.__InScreenVoiceEvent=(payload)=>{
              try{window.dispatchEvent(new CustomEvent("inscreen:voz",{detail:JSON.parse(payload)}));}
              catch(_){window.dispatchEvent(new CustomEvent("inscreen:voz",{detail:{estado:"error",texto:"",error:"invalid_voice_event"}}));}
            };
            window.InScreen={module:{
              context:()=>JSON.parse(native.context()),
              respyPreg:unavailable,
              paginasLeidas:(day)=>request("paginasLeidas",day),
              traduccion:(cursor)=>typeof cursor==="number"?request("traduccion",cursor):latestTranslation(cursor),
              archivos:(type,stage)=>cached("archivos",type,stage),
              archivo:(type,stage,number)=>cached("archivo",type,stage,number),
              borrarLinea:(type,stage,number,line)=>deleteCachedLine(type,stage,number,line),
              historial:(type)=>history(type),
              apuntes:()=>notes("apuntes"),
              apunte:(number)=>notes("apunte",number),
              apuntesEstado:()=>studyState("leer"),
              guardarApuntesEstado:(state)=>studyState("guardar",state),
              vozEstado:()=>voice("estado"),
              vozIniciar:(options={})=>voice("iniciar",options),
              vozDetener:()=>voice("detener"),
              vozCancelar:()=>voice("cancelar"),
              consulta:(question,content)=>query(question,content)
            }};
            delete window.InScreenModuleNative;
          })();</script>
        """.trimIndent()
        val head = Regex("(?i)<head[^>]*>").find(html)
        val bootstrapped = if (head == null) bootstrap + html else
            html.substring(0, head.range.last + 1) + bootstrap + html.substring(head.range.last + 1)
        moduleSpeech?.destroy()
        moduleSpeech = null
        val view = WebView(this)
        moduleWebView = view
        moduleSpeech = ModuleSpeechController(this) { payload -> runOnUiThread {
            if (isFinishing || moduleWebView !== view) return@runOnUiThread
            val quotedPayload = JSONObject.quote(payload)
            view.evaluateJavascript("window.__InScreenVoiceEvent($quotedPayload)", null)
        } }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/module-assets/",
                WebViewAssetLoader.InternalStoragePathHandler(this, moduleCache.directory(subjectId)),
            )
            .build()
        view.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?) =
                    request?.url?.let(assets::shouldInterceptRequest)
            }
            addJavascriptInterface(
                ModuleBridge(
                    subjectId,
                    subjectName,
                    providerSubjectSegment,
                    module.id,
                    notesSessionId,
                    notesStore,
                    providerCache,
                    groqCredentialStore,
                    providerClient,
                    voiceStatusAction = { reply -> runOnUiThread { reply(voiceStatus()) } },
                    voiceStartAction = { requestId, allowSystem, deliver ->
                        runOnUiThread { startVoice(requestId, allowSystem, deliver) }
                    },
                    voiceStopAction = { reply -> runOnUiThread {
                        reply(moduleSpeech?.stop() ?: voiceFailure("recognizer_unavailable"))
                    } },
                    voiceCancelAction = { reply -> runOnUiThread {
                        reply(moduleSpeech?.cancel() ?: JSONObject().put("ok", true).toString())
                    } },
                ) { requestId, payload ->
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
            loadDataWithBaseURL("https://appassets.androidplatform.net/module-assets/", bootstrapped, "text/html", "utf-8", null)
        }
        setContentView(view)
    }

    override fun onDestroy() {
        pendingVoiceStart = null
        moduleSpeech?.destroy()
        moduleSpeech = null
        moduleWebView?.destroy()
        moduleWebView = null
        super.onDestroy()
    }

    override fun onStop() {
        moduleSpeech?.stopForBackground()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MODULE_AUDIO) return
        val pending = pendingVoiceStart ?: return
        pendingVoiceStart = null
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val payload = if (granted) moduleSpeech?.start(pending.allowSystemRecognizer)
            ?: voiceFailure("recognizer_unavailable")
        else voiceFailure("microphone_permission_denied")
        pending.deliver(pending.requestId, payload)
    }

    private fun voiceStatus(): String = moduleSpeech?.status(
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    ) ?: voiceFailure("recognizer_unavailable")

    private fun startVoice(requestId: String, allowSystemRecognizer: Boolean, deliver: (String, String) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            deliver(requestId, moduleSpeech?.start(allowSystemRecognizer) ?: voiceFailure("recognizer_unavailable"))
            return
        }
        if (pendingVoiceStart != null) {
            deliver(requestId, voiceFailure("voice_busy"))
            return
        }
        pendingVoiceStart = PendingVoiceStart(requestId, allowSystemRecognizer, deliver)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MODULE_AUDIO)
    }

    private fun voiceFailure(error: String) = JSONObject().put("ok", false).put("error", error).toString()

    private class ModuleBridge(
        private val id: String,
        private val name: String,
        private val subjectSegment: String,
        private val moduleId: String,
        private val notesSessionId: String?,
        private val notesStore: SubjectNotesStore,
        private val cache: ProviderCache,
        private val groqCredentials: GroqCredentialStore,
        private val providerClient: ProviderClient?,
        private val voiceStatusAction: ((String) -> Unit) -> Unit,
        private val voiceStartAction: (String, Boolean, (String, String) -> Unit) -> Unit,
        private val voiceStopAction: ((String) -> Unit) -> Unit,
        private val voiceCancelAction: ((String) -> Unit) -> Unit,
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
            val client = providerClient ?: return deliver(requestId, providerNotConfigured(day))
            client.request(kind, subjectSegment, day) { payload ->
                val merged = if (JSONObject(payload).optBoolean("ok", false)) {
                    cache.merge(id, kind == "traduccion", payload)
                } else payload
                deliver(requestId, merged)
            }
        }

        @JavascriptInterface fun requestLatestTranslation(requestId: String, lastFile: String) {
            if (requestId.length !in 1..64) return
            val client = providerClient ?: return deliver(requestId, providerNotConfigured(-1))
            client.requestLatestTranslation(subjectSegment, lastFile.ifBlank { null }) { payload ->
                val merged = if (JSONObject(payload).optBoolean("ok", false)) cache.merge(id, true, payload) else payload
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

        @JavascriptInterface fun cachedHistory(requestId: String, type: Boolean) {
            if (requestId.length !in 1..64) return
            deliver(requestId, cache.history(id, type))
        }

        @JavascriptInterface fun deleteCachedLine(requestId: String, type: Boolean, stage: Int, number: Int, line: Int) {
            if (requestId.length !in 1..64) return
            deliver(requestId, cache.deleteLine(id, type, stage, number, line))
        }

        @JavascriptInterface fun noteFiles(requestId: String) {
            if (requestId.length !in 1..64) return
            val sessionId = notesSessionId ?: return deliver(requestId, notesFailure("notes_session_not_selected"))
            deliver(requestId, notesStore.markerInventory(id, sessionId))
        }

        @JavascriptInterface fun noteFile(requestId: String, number: Int) {
            if (requestId.length !in 1..64) return
            val sessionId = notesSessionId ?: return deliver(requestId, notesFailure("notes_session_not_selected"))
            deliver(requestId, notesStore.markerFile(id, sessionId, number))
        }

        @JavascriptInterface fun noteStudyState(requestId: String) {
            if (requestId.length !in 1..64) return
            val sessionId = notesSessionId ?: return deliver(requestId, notesFailure("notes_session_not_selected"))
            deliver(requestId, notesStore.studyState(id, sessionId, moduleId))
        }

        @JavascriptInterface fun saveNoteStudyState(requestId: String, state: String) {
            if (requestId.length !in 1..64) return
            val sessionId = notesSessionId ?: return deliver(requestId, notesFailure("notes_session_not_selected"))
            deliver(requestId, notesStore.saveStudyState(id, sessionId, moduleId, state))
        }

        @JavascriptInterface fun voiceStatus(requestId: String) {
            if (requestId.length !in 1..64) return
            voiceStatusAction { deliver(requestId, it) }
        }

        @JavascriptInterface fun voiceStart(requestId: String, allowSystemRecognizer: Boolean) {
            if (requestId.length !in 1..64) return
            voiceStartAction(requestId, allowSystemRecognizer, deliver)
        }

        @JavascriptInterface fun voiceStop(requestId: String) {
            if (requestId.length !in 1..64) return
            voiceStopAction { deliver(requestId, it) }
        }

        @JavascriptInterface fun voiceCancel(requestId: String) {
            if (requestId.length !in 1..64) return
            voiceCancelAction { deliver(requestId, it) }
        }

        private fun notesFailure(error: String): String = JSONObject()
            .put("ok", false).put("archivos", org.json.JSONArray()).put("error", error).toString()

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
        private const val REQUEST_MODULE_AUDIO = 904
        internal const val EXTRA_SUBJECT_ID = "subject_id"
        internal const val EXTRA_NOTES_SESSION_ID = "notes_session_id"
        private const val EXTRA_SELECTED_MODULE = "selected_module"
        internal fun intent(context: Context, subjectId: String): Intent =
            Intent(context, ModuleHostActivity::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(context: Context, subjectId: String) = context.startActivity(intent(context, subjectId))

        internal fun notesIntent(context: Context, subjectId: String, sessionId: String): Intent =
            intent(context, subjectId).putExtra(EXTRA_NOTES_SESSION_ID, sessionId)

        fun openFromNotes(context: Context, subjectId: String, sessionId: String) =
            context.startActivity(notesIntent(context, subjectId, sessionId))

        internal fun openSelected(context: Context, subjectId: String, module: ModuleCatalog.Module) = context.startActivity(
            intent(context, subjectId)
                .putExtra(EXTRA_SELECTED_MODULE, ModuleSelection.serialize(module))
        )
    }

    private data class PendingVoiceStart(
        val requestId: String,
        val allowSystemRecognizer: Boolean,
        val deliver: (String, String) -> Unit,
    )
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
