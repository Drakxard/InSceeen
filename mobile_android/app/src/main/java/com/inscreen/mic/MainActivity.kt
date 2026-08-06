package com.inscreen.mic

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var stateView: TextView
    private lateinit var connectButton: Button
    private lateinit var mediaControlButton: Button
    private lateinit var updateButton: ImageButton
    private val webViews = mutableListOf<WebView>()
    private var scannerView: View? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var exitDialog: AlertDialog? = null
    private var pairingInProgress = false
    private var lastState = "SIN CONEXIÓN"
    private var updateReceiverRegistered = false
    private var edgeGesture: EdgeGesture? = null

    private val updatePreferences by lazy {
        getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
    }

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == updatePreferences.getLong(KEY_UPDATE_DOWNLOAD_ID, -2L)) {
                handleCompletedUpdateDownload(id)
            }
        }
    }

    private val exportStateLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportAprioriState(uri)
    }

    private val importStateLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importAprioriState(uri)
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showState(intent?.getStringExtra(InScreenService.EXTRA_STATE) ?: "SIN CONEXIÓN")
        }
    }
    private val aprioriReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val saved = intent?.getStringExtra(AprioriUpdates.EXTRA_STATE) ?: AprioriStore.load(this@MainActivity)
            val quoted = JSONObject.quote(saved)
            webViews.forEach { it.evaluateJavascript("window.InScreenApplyState($quoted)", null) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        registerUpdateReceiver()
        registerStateReceiver()
        registerAprioriReceiver()
        val link = intent?.dataString
        if (link.isNullOrBlank()) {
            refreshPairingState()
            connectIfPaired()
        } else {
            handlePairLink(link)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairLink(intent?.dataString)
    }

    override fun onResume() {
        super.onResume()
        if (webViews.isNotEmpty()) {
            val quoted = JSONObject.quote(AprioriStore.load(this))
            webViews.forEach { it.evaluateJavascript("window.InScreenApplyState($quoted)", null) }
        }
        resumePendingUpdateInstall()
        if (::mediaControlButton.isInitialized) {
            updateMediaControlButton()
            if (PairingStore.load(this) != null) {
                startService(
                    Intent(this, InScreenService::class.java)
                        .setAction(InScreenService.ACTION_MEDIA_ACCESS_CHANGED)
                )
            }
        }
    }

    override fun onDestroy() {
        if (updateReceiverRegistered) unregisterReceiver(updateDownloadReceiver)
        unregisterReceiver(stateReceiver)
        unregisterReceiver(aprioriReceiver)
        exitDialog?.dismiss()
        exitDialog = null
        webViews.forEach(WebView::destroy)
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (scannerView != null) {
            closeScanner()
            return
        }
        showExitDialog()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::pager.isInitialized) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val edgeWidth = EDGE_SWIPE_DP * resources.displayMetrics.density
                    edgeGesture = if (
                        scannerView == null &&
                        exitDialog?.isShowing != true &&
                        PagerGesturePolicy.beginsAtHorizontalEdge(event.x, root.width.toFloat(), edgeWidth)
                    ) EdgeGesture(event.x, event.y) else null
                    if (edgeGesture != null) return true
                }
                MotionEvent.ACTION_MOVE -> if (edgeGesture != null) return true
                MotionEvent.ACTION_UP -> edgeGesture?.let { gesture ->
                    val threshold = EDGE_SWIPE_THRESHOLD_DP * resources.displayMetrics.density
                    PagerGesturePolicy.targetPage(
                        pager.currentItem,
                        pager.adapter?.itemCount ?: 0,
                        gesture.x,
                        gesture.y,
                        event.x,
                        event.y,
                        threshold,
                    )?.let { pager.setCurrentItem(it, true) }
                    edgeGesture = null
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (edgeGesture != null) {
                    edgeGesture = null
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val edgeWidth = (EDGE_SWIPE_DP * resources.displayMetrics.density).toInt()
                view.systemGestureExclusionRects = listOf(
                    Rect(0, 0, edgeWidth, view.height),
                    Rect(view.width - edgeWidth, 0, view.width, view.height),
                )
            }
        }
        pager = ViewPager2(this).apply {
            adapter = PagesAdapter()
            offscreenPageLimit = 2
            isUserInputEnabled = false
            setCurrentItem(1, false)
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private inner class PagesAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = 3
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) })

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.container.removeAllViews()
            val page = when (position) {
                0 -> createAprioriWebView("dock")
                1 -> createAprioriWebView("queue")
                else -> createConnectionPage()
            }
            holder.container.addView(page, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAprioriWebView(view: String): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(AprioriBridge(), "InScreenApriori")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/index.html?view=$view")
            webViews += this
        }
    }

    private inner class AprioriBridge {
        @JavascriptInterface fun loadState(): String = AprioriStore.load(this@MainActivity)

        @JavascriptInterface fun saveState(raw: String) {
            try {
                val previous = JSONObject(AprioriStore.load(this@MainActivity))
                val saved = AprioriStore.save(this@MainActivity, raw)
                runOnUiThread { AprioriUpdates.publish(this@MainActivity, saved) }
                val current = JSONObject(saved)
                val subjects = current.optJSONArray("subjects")
                val subjectIds = (0 until (subjects?.length() ?: 0))
                    .mapNotNull { subjects?.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
                    .toSet()
                val currentModules = (0 until (subjects?.length() ?: 0)).associate { index ->
                    val subject = subjects?.optJSONObject(index)
                    subject?.optString("id").orEmpty() to subject?.optJSONObject("module")?.optString("id").orEmpty()
                }
                val previousSubjects = previous.optJSONArray("subjects")
                val removedModuleSubjects = (0 until (previousSubjects?.length() ?: 0)).mapNotNull { index ->
                    val subject = previousSubjects?.optJSONObject(index) ?: return@mapNotNull null
                    val id = subject.optString("id")
                    val oldModule = subject.optJSONObject("module")?.optString("id").orEmpty()
                    id.takeIf { oldModule.isNotBlank() && currentModules[id] != oldModule }
                }
                thread(name = "apriori-cache-cleanup") {
                    ProviderCache.from(this@MainActivity).reconcileSubjects(subjectIds)
                    val moduleCache = ModuleCache.from(this@MainActivity)
                    removedModuleSubjects.forEach(moduleCache::remove)
                    moduleCache.reconcile(subjectIds)
                }
            } catch (_: Exception) { }
        }

        @JavascriptInterface fun openModule(subjectId: String) {
            runOnUiThread { ModuleHostActivity.open(this@MainActivity, subjectId) }
        }

        @JavascriptInterface fun selectModule(subjectId: String, rawModule: String) {
            val selected = runCatching { ModuleSelection.parse(rawModule) }.getOrNull() ?: return
            runOnUiThread { ModuleHostActivity.openSelected(this@MainActivity, subjectId, selected) }
        }
    }

    private fun createConnectionPage(): View {
        val page = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(42, 70, 42, 42)
        }
        content.addView(TextView(this).apply {
            text = ">_ INSCREEN MIC"
            textSize = 24f
            setTextColor(Color.rgb(69, 255, 26))
            gravity = Gravity.CENTER
            contentDescription = "Configurar consultas Groq"
            isClickable = true
            isFocusable = true
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showGroqSettings() }
        }, matchWidth())
        stateView = TextView(this).apply {
            text = lastState
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 80, 0, 55)
        }
        content.addView(stateView, matchWidth())
        connectButton = terminalButton("CONECTAR").also { button ->
            button.setOnClickListener {
                if (PairingStore.load(this) == null) {
                    requestPairing()
                    openScanner()
                } else connect()
            }
            content.addView(button, matchWidth(18))
        }
        terminalButton("DESCONECTAR").also { button ->
            button.setOnClickListener {
                startService(Intent(this, InScreenService::class.java).setAction(InScreenService.ACTION_DISCONNECT))
                showState("DESCONECTADO")
            }
            content.addView(button, matchWidth())
        }
        mediaControlButton = terminalButton("CONTROL MULTIMEDIA").also { button ->
            button.setOnClickListener { explainMediaAccess() }
            content.addView(button, matchWidth(18))
        }
        updateMediaControlButton()
        page.addView(content, FrameLayout.LayoutParams(-1, -1))
        page.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            contentDescription = "Escanear QR"
            setColorFilter(Color.rgb(69, 255, 26))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (PairingStore.load(this@MainActivity) == null) requestPairing()
                openScanner()
            }
        }, FrameLayout.LayoutParams(72, 72, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = 24
            bottomMargin = 24
        })
        val backupControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(backupButton("↓", "Exportar datos") {
                exportStateLauncher.launch("InScreen-apriori.json")
            })
            addView(backupButton("↑", "Importar datos") {
                importStateLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            }, LinearLayout.LayoutParams(64, 64).apply { leftMargin = 10 })
        }
        page.addView(backupControls, FrameLayout.LayoutParams(-2, 72, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 24
            bottomMargin = 24
        })
        updateButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_refresh)
            contentDescription = "Buscar actualización"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setColorFilter(Color.rgb(69, 255, 26))
            setBackgroundColor(Color.TRANSPARENT)
            elevation = dp(4).toFloat()
            setOnClickListener { checkForAppUpdate() }
        }
        page.addView(updateButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            rightMargin = dp(8)
        })
        return page
    }

    private fun showGroqSettings() {
        val store = GroqCredentialStore(this)
        val savedKey = store.apiKey()
        val savedModel = store.model()
        var confirmedKey: String? = savedKey
        var stagedForDeletion = false
        var availableModels = savedModel?.let(::listOf).orEmpty()

        val keyInput = EditText(this).apply {
            hint = if (savedKey == null) "API key" else "API key guardada"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val keyButton = terminalButton(if (savedKey == null) "CONFIRMAR" else "BORRAR")
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(keyButton, LinearLayout.LayoutParams(dp(120), -2).apply { leftMargin = dp(8) })
        }
        val modelLabel = TextView(this).apply { text = "MODELO"; setPadding(0, dp(18), 0, dp(4)) }
        val modelSpinner = Spinner(this)
        fun renderModels(models: List<String>, selected: String? = null) {
            availableModels = models.distinct().sorted()
            modelSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                availableModels,
            )
            modelSpinner.isEnabled = availableModels.isNotEmpty()
            selected?.let { value ->
                availableModels.indexOf(value).takeIf { it >= 0 }?.let(modelSpinner::setSelection)
            }
        }
        renderModels(availableModels, savedModel)
        val status = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(keyRow, matchWidth())
            addView(modelLabel, matchWidth())
            addView(modelSpinner, matchWidth())
            addView(status, matchWidth())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("INSCREEN MIC")
            .setView(body)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("GUARDAR", null)
            .create()

        fun loadModels(key: String, validation: Boolean) {
            status.text = "Consultando modelos…"
            keyButton.isEnabled = false
            GroqClient.shared.models(key) { result -> runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                keyButton.isEnabled = true
                result.fold(onSuccess = { models ->
                    confirmedKey = key
                    stagedForDeletion = false
                    keyInput.text.clear()
                    keyInput.hint = "API key confirmada"
                    keyButton.text = "BORRAR"
                    renderModels(models, savedModel)
                    status.text = "Clave válida. Elegí un modelo."
                }, onFailure = { error ->
                    if (validation) confirmedKey = null
                    status.text = when (error.message) {
                        "authentication_error" -> "La API key no es válida."
                        "rate_limited" -> "Groq limitó temporalmente la solicitud."
                        else -> "No se pudieron obtener los modelos."
                    }
                })
            }}
        }
        keyButton.setOnClickListener {
            if (confirmedKey != null && keyButton.text == "BORRAR") {
                confirmedKey = null
                stagedForDeletion = true
                keyInput.text.clear()
                keyInput.hint = "API key"
                keyButton.text = "CONFIRMAR"
                renderModels(emptyList())
                status.text = "La configuración se borrará al guardar."
            } else {
                val candidate = keyInput.text.toString().trim()
                if (candidate.isEmpty()) status.text = "Pegá una API key para confirmarla."
                else loadModels(candidate, validation = true)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (stagedForDeletion) {
                    store.clear()
                    dialog.dismiss()
                    Toast.makeText(this, "Configuración Groq borrada", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val key = confirmedKey
                val model = availableModels.getOrNull(modelSpinner.selectedItemPosition)
                if (key == null || model == null) {
                    status.text = "Confirmá una API key y elegí un modelo."
                    return@setOnClickListener
                }
                runCatching { store.save(key, model) }.fold(
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(this, "Configuración Groq guardada", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { status.text = "No se pudo guardar la configuración segura." },
                )
            }
            savedKey?.let { loadModels(it, validation = false) }
        }
        dialog.show()
    }

    private fun registerUpdateReceiver() {
        ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        updateReceiverRegistered = true
    }

    private fun checkForAppUpdate() {
        setUpdateBusy(true)
        GitHubUpdateClient.check(BuildConfig.VERSION_NAME) { result ->
            runOnUiThread {
                setUpdateBusy(false)
                result.fold(
                    onSuccess = { release ->
                        if (release == null) {
                            Toast.makeText(this, "Ya tenés la última versión", Toast.LENGTH_SHORT).show()
                        } else {
                            showAvailableUpdate(release)
                        }
                    },
                    onFailure = {
                        Toast.makeText(
                            this,
                            "No se pudo consultar la actualización",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    private fun showAvailableUpdate(release: AppRelease) {
        val notes = release.notes.take(900).ifBlank {
            "Hay una versión nueva disponible para descargar."
        }
        AlertDialog.Builder(this)
            .setTitle("Actualización ${release.tag}")
            .setMessage(notes)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Descargar") { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun downloadUpdate(release: AppRelease) {
        val manager = getSystemService(DownloadManager::class.java)
        val filename = "InScreenMic-${release.version}-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("InScreen ${release.tag}")
            .setDescription("Descargando actualización")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            Toast.makeText(this, "No se pudo iniciar la descarga", Toast.LENGTH_LONG).show()
            return
        }
        updatePreferences.edit()
            .putLong(KEY_UPDATE_DOWNLOAD_ID, id)
            .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
            .apply()
        Toast.makeText(this, "Descargando ${release.tag}…", Toast.LENGTH_LONG).show()
    }

    private fun handleCompletedUpdateDownload(id: Long) {
        val manager = getSystemService(DownloadManager::class.java)
        val status = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@use DownloadManager.STATUS_FAILED
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) requestUpdateInstall(id)
        else {
            updatePreferences.edit().remove(KEY_UPDATE_DOWNLOAD_ID).apply()
            Toast.makeText(this, "Falló la descarga de la actualización", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestUpdateInstall(id: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            updatePreferences.edit()
                .putLong(KEY_UPDATE_DOWNLOAD_ID, id)
                .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, true)
                .apply()
            AlertDialog.Builder(this)
                .setTitle("Permitir actualización")
                .setMessage(
                    "Android necesita autorizar a InScreen para abrir el instalador. " +
                        "Habilitá «Permitir desde esta fuente» y volvé a la app."
                )
                .setNegativeButton("Ahora no", null)
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .show()
            return
        }
        openDownloadedUpdate(id)
    }

    private fun resumePendingUpdateInstall() {
        if (!updatePreferences.getBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) return
        updatePreferences.edit().putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false).apply()
        val id = updatePreferences.getLong(KEY_UPDATE_DOWNLOAD_ID, -1L)
        if (id >= 0L) openDownloadedUpdate(id)
    }

    private fun openDownloadedUpdate(id: Long) {
        val manager = getSystemService(DownloadManager::class.java)
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            Toast.makeText(this, "No se encontró el APK descargado", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(this, "No se pudo abrir el instalador", Toast.LENGTH_LONG).show()
        }
    }

    private fun setUpdateBusy(busy: Boolean) {
        if (!::updateButton.isInitialized) return
        updateButton.isEnabled = !busy
        updateButton.alpha = if (busy) 0.45f else 1f
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hasMediaControlAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun updateMediaControlButton() {
        if (!::mediaControlButton.isInitialized) return
        mediaControlButton.text = if (hasMediaControlAccess()) {
            "CONTROL MULTIMEDIA · ACTIVO"
        } else {
            "HABILITAR CONTROL MULTIMEDIA"
        }
    }

    private fun explainMediaAccess() {
        if (hasMediaControlAccess()) {
            Toast.makeText(this, "El control multimedia ya está habilitado", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Control multimedia")
            .setMessage(
                "Android requiere habilitar el acceso a notificaciones para controlar la música de otras apps. " +
                    "InScreen solo consulta sus sesiones de reproducción: no lee ni transmite el contenido de tus notificaciones."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Abrir ajustes") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure {
                        Toast.makeText(this, "No se pudieron abrir los ajustes", Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }

    private fun backupButton(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 30f
        contentDescription = description
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 4)
        setTextColor(Color.rgb(69, 255, 26))
        setBackgroundColor(Color.rgb(7, 22, 7))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(64, 64)
    }

    private fun exportAprioriState(uri: Uri) {
        thread(name = "InScreenStateExport") {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(AprioriStore.load(this))
                    it.write("\n")
                } ?: error("No se pudo abrir el archivo")
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.isSuccess) "Datos exportados" else "No se pudieron exportar los datos",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun importAprioriState(uri: Uri) {
        thread(name = "InScreenStateImport") {
            val result = runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: error("No se pudo abrir el archivo")
                AprioriStore.save(this, raw)
            }
            runOnUiThread {
                result.onSuccess { saved ->
                    AprioriUpdates.publish(this, saved)
                }
                Toast.makeText(
                    this,
                    if (result.isSuccess) "Datos importados" else "El archivo no es válido",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showExitDialog() {
        if (exitDialog?.isShowing == true) return
        pager.isUserInputEnabled = false
        exitDialog = AlertDialog.Builder(this)
            .setTitle("¿Cerrar InScreen?")
            .setMessage(
                "Cerrar detendrá la conexión y cualquier grabación activa. " +
                    "Para dejarla conectada en segundo plano, presioná Atrás otra vez."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Cerrar app") { _, _ ->
                startService(
                    Intent(this, InScreenService::class.java)
                        .setAction(InScreenService.ACTION_DISCONNECT)
                )
                finishAndRemoveTask()
            }
            .create()
            .also { dialog ->
                dialog.setOnCancelListener { moveTaskToBack(true) }
                dialog.setOnDismissListener { exitDialog = null }
                dialog.show()
            }
    }

    private fun terminalButton(label: String) = Button(this).apply {
        text = label
        setTextColor(Color.rgb(69, 255, 26))
        setBackgroundColor(Color.rgb(7, 22, 7))
    }

    private fun matchWidth(bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }

    private fun registerStateReceiver() {
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(InScreenService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerAprioriReceiver() {
        ContextCompat.registerReceiver(
            this, aprioriReceiver, IntentFilter(AprioriUpdates.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun requestPairing() {
        thread(name = "InScreenPairRequest") {
            val bytes = PairRequestProtocol.build().toByteArray(Charsets.UTF_8)
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    pairBroadcastAddresses().forEach { address ->
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, address, PairRequestProtocol.PORT))
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun pairBroadcastAddresses(): Set<InetAddress> {
        val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { network ->
                network.interfaceAddresses.forEach { link ->
                    link.broadcast?.let(addresses::add)
                    val bytes = link.address?.address
                    if (bytes?.size == 4) {
                        val prefix = link.networkPrefixLength.toInt().coerceIn(0, 32)
                        val value = ByteBuffer.wrap(bytes).int
                        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
                        addresses += InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(value or mask.inv()).array())
                    }
                }
            }
        }
        return addresses
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun openScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (scannerView != null) return
        val previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        scannerView = previewView
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )
            val accepted = AtomicBoolean(false)
            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val link = codes.firstNotNullOfOrNull { it.rawValue }
                        if (link != null && accepted.compareAndSet(false, true)) {
                            val valid = runCatching { PairConfig.fromLink(link) }.isSuccess
                            if (valid) {
                                closeScanner()
                                handlePairLink(link)
                            } else accepted.set(false)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun closeScanner() {
        cameraProvider?.unbindAll()
        scannerView?.let(root::removeView)
        scannerView = null
    }

    private fun handlePairLink(link: String?) {
        if (link.isNullOrBlank() || pairingInProgress) return
        val parsed = runCatching { PairConfig.fromLink(link) }.getOrNull() ?: return
        pairingInProgress = true
        showState("VINCULANDO…")
        thread(name = "InScreenPairing") {
            try {
                val verified = PairingStore.downloadAndVerifyCa(parsed)
                PairingStore.save(this, verified)
                runOnUiThread {
                    pairingInProgress = false
                    showState("PC VINCULADA")
                    connect()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    pairingInProgress = false
                    showState("ERROR DE VINCULACIÓN")
                }
            }
        }
    }

    private fun refreshPairingState() {
        showState(if (PairingStore.load(this) == null) "SIN VINCULAR" else "LISTO PARA CONECTAR")
    }

    private fun connectIfPaired() {
        if (PairingStore.load(this) != null) connect()
    }

    private fun connect() {
        if (PairingStore.load(this) == null) return
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        else startConnectionService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            REQUEST_CAMERA -> if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner()
            REQUEST_PERMISSIONS -> if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            ) startConnectionService()
        }
    }

    private fun startConnectionService() {
        startForegroundService(Intent(this, InScreenService::class.java).setAction(InScreenService.ACTION_CONNECT))
        showState("CONECTANDO…")
        requestBatteryExemption()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun showState(state: String) {
        lastState = state
        if (::stateView.isInitialized) {
            stateView.text = state
            stateView.setTextColor(if (state.contains("GRABANDO")) Color.RED else Color.WHITE)
        }
    }

    companion object {
        private const val EDGE_SWIPE_DP = 20f
        private const val EDGE_SWIPE_THRESHOLD_DP = 48f
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_CAMERA = 101
        private const val UPDATE_PREFERENCES = "github_update"
        private const val KEY_UPDATE_DOWNLOAD_ID = "download_id"
        private const val KEY_AWAITING_INSTALL_PERMISSION = "awaiting_install_permission"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

private data class EdgeGesture(val x: Float, val y: Float)

internal object PagerGesturePolicy {
    fun beginsAtHorizontalEdge(x: Float, width: Float, edgeWidth: Float): Boolean {
        if (width <= 0f || edgeWidth <= 0f) return false
        return x >= 0f && (x <= edgeWidth || x >= width - edgeWidth)
    }

    fun targetPage(
        currentPage: Int,
        pageCount: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        threshold: Float,
    ): Int? {
        if (pageCount <= 0 || currentPage !in 0 until pageCount || threshold <= 0f) return null
        val deltaX = endX - startX
        val deltaY = endY - startY
        if (kotlin.math.abs(deltaX) < threshold || kotlin.math.abs(deltaX) <= kotlin.math.abs(deltaY)) return null
        val target = if (deltaX < 0f) currentPage + 1 else currentPage - 1
        return target.takeIf { it in 0 until pageCount }
    }
}
