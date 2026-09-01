package com.inscreen.mic

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class DriveExplorerActivity : Activity() {
    private lateinit var rootId: String
    private lateinit var title: String
    private lateinit var cache: DriveCache
    private lateinit var pathLabel: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: ListView
    private val path = mutableListOf<Folder>()
    private var items = emptyList<DriveItem>()
    private var afterAuthorization: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootId = intent.getStringExtra(EXTRA_ROOT_ID).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Material" }
        if (rootId.isBlank()) { finish(); return }
        cache = DriveCache(this)
        path += Folder(rootId, title)
        buildUi()
        load(rootId)
    }

    override fun onBackPressed() {
        if (path.size <= 1) super.onBackPressed() else {
            path.removeAt(path.lastIndex)
            load(path.last().id)
        }
    }

    @Deprecated("Legacy callback required by Google AuthorizationClient resolution")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTHORIZATION_REQUEST) return
        if (data == null) {
            showError(if (resultCode == RESULT_CANCELED) {
                "La autorización de Google fue cancelada."
            } else {
                "Google cerró la autorización sin devolver una respuesta."
            })
            afterAuthorization = null
            return
        }
        try {
            continueAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data))
        } catch (error: ApiException) {
            showError(authorizationError(error))
            afterAuthorization = null
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 247))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        header.addView(Button(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.rgb(90, 120, 55))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackPressed() }
        }, LinearLayout.LayoutParams(dp(54), dp(50)))
        root.addView(header)
        pathLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(55, 65, 60))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { if (path.size > 1) { path.subList(1, path.size).clear(); load(rootId) } }
        }
        root.addView(pathLabel)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(status)
        list = ListView(this).apply {
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ -> open(items[position]) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderPath()
    }

    private fun load(folderId: String) {
        renderPath()
        val stored = cache.loadListing(rootId, folderId)
        if (stored != null) render(stored, offline = true)
        setBusy(true, if (stored == null) "Cargando Drive…" else "Actualizando…")
        authorize { token ->
            Thread {
                runCatching { DriveApiClient(token).list(folderId) }
                    .onSuccess { loaded ->
                        cache.saveListing(rootId, folderId, loaded)
                        runOnUiThread { render(loaded, offline = false) }
                    }
                    .onFailure {
                        runOnUiThread {
                            if (stored != null) render(stored, offline = true)
                            else showError("Sin conexión y esta carpeta todavía no fue visitada.")
                        }
                    }
            }.start()
        }
    }

    private fun render(values: List<DriveItem>, offline: Boolean) {
        items = values
        list.adapter = ItemAdapter(values)
        setBusy(false, when {
            values.isEmpty() && offline -> "Sin conexión. No hay contenido guardado."
            values.isEmpty() -> "Esta carpeta está vacía."
            offline -> "Mostrando la última copia disponible."
            else -> ""
        })
    }

    private fun open(item: DriveItem) {
        if (item.isFolder) {
            path += Folder(item.effectiveId, item.name)
            load(item.effectiveId)
            return
        }
        val existing = cache.downloaded(rootId, item)
        if (existing != null) { openLocal(existing, effectiveMime(item)); return }
        setBusy(true, "Preparando ${item.name}…")
        authorize { token ->
            Thread {
                runCatching {
                    DriveApiClient(token).download(item, cache.destination(rootId, item)) { copied, total ->
                        val text = if (total != null && total > 0) "Descargando ${copied * 100 / total}%" else "Descargando…"
                        runOnUiThread { status.text = text }
                    }
                }.onSuccess { (file, mime) -> runOnUiThread { setBusy(false, ""); openLocal(file, mime) } }
                    .onFailure { runOnUiThread { showError("No se pudo descargar ${item.name}.") } }
            }.start()
        }
    }

    private fun authorize(action: (String) -> Unit) {
        afterAuthorization = action
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    try {
                        startIntentSenderForResult(result.pendingIntent!!.intentSender, AUTHORIZATION_REQUEST, null, 0, 0, 0)
                    } catch (error: Exception) {
                        afterAuthorization = null
                        showError("No se pudo abrir la autorización de Google: ${error.message ?: error.javaClass.simpleName}")
                    }
                } else continueAuthorized(result)
            }
            .addOnFailureListener { error ->
                afterAuthorization = null
                val message = authorizationError(error)
                val local = cache.loadListing(rootId, path.last().id)
                if (local != null) {
                    render(local, offline = true)
                    status.text = "Mostrando la copia guardada. $message"
                } else showError(message)
            }
    }

    private fun continueAuthorized(result: AuthorizationResult) {
        val token = result.accessToken
        val action = afterAuthorization
        afterAuthorization = null
        if (token.isNullOrBlank() || action == null) showError("Google no entregó autorización para Drive.") else action(token)
    }

    private fun authorizationError(error: Throwable): String {
        val statusCode = (error as? ApiException)?.statusCode
        return DriveAuthorizationError.message(statusCode, if (statusCode == 10) signingSha1() else null)
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val certificate = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
        MessageDigest.getInstance("SHA-1").digest(certificate)
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }.getOrNull()

    private fun openLocal(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.drive-files", file)
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(open) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No hay una aplicación compatible para abrir este archivo.", Toast.LENGTH_LONG).show() }
    }

    private fun effectiveMime(item: DriveItem) = DriveItemPolicy.export(item)?.first ?: item.mimeType

    private fun renderPath() { pathLabel.text = path.joinToString("  ›  ") { it.name } }
    private fun setBusy(value: Boolean, message: String) { progress.visibility = if (value) View.VISIBLE else View.GONE; status.text = message }
    private fun showError(message: String) { setBusy(false, message) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class ItemAdapter(private val values: List<DriveItem>) : BaseAdapter() {
        override fun getCount() = values.size
        override fun getItem(position: Int) = values[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@DriveExplorerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                addView(TextView(context).apply { id = android.R.id.text1; textSize = 18f; setTextColor(Color.rgb(20, 25, 22)) })
                addView(TextView(context).apply { id = android.R.id.text2; textSize = 13f; setTextColor(Color.GRAY) })
            }
            val item = values[position]
            row.findViewById<TextView>(android.R.id.text1).text = (if (item.isFolder) "📁  " else "📄  ") + item.name
            row.findViewById<TextView>(android.R.id.text2).text = details(item)
            return row
        }
    }

    private fun details(item: DriveItem): String {
        if (item.isFolder) return "Carpeta"
        val size = item.size?.let { formatSize(it) }.orEmpty()
        val date = runCatching { DateFormat.getDateInstance(DateFormat.SHORT).format(Date.from(Instant.parse(item.modifiedTime))) }.getOrDefault("")
        return listOf(size, date).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private data class Folder(val id: String, val name: String)

    companion object {
        private const val EXTRA_ROOT_ID = "drive_root_id"
        private const val EXTRA_TITLE = "drive_title"
        private const val AUTHORIZATION_REQUEST = 8401
        private const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        fun intent(context: Context, rootId: String, title: String) = Intent(context, DriveExplorerActivity::class.java)
            .putExtra(EXTRA_ROOT_ID, rootId).putExtra(EXTRA_TITLE, title)
    }
}
