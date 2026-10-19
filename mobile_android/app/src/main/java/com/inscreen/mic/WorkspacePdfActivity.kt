package com.inscreen.mic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Continuous PDFium reader; the library owns tiled rendering, pan and document zoom. */
class WorkspacePdfActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var sessionFile: File? = null
    @Volatile private var stopped = false
    private lateinit var pdfView: PDFView
    private lateinit var status: TextView
    private var maximized = false
    private var pdfHash = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = FrameLayout(this).apply { setBackgroundColor(Color.rgb(232, 234, 237)) }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pdfView = PDFView(this, null).apply {
            setBackgroundColor(Color.rgb(232, 234, 237))
            contentDescription = "Documento PDF"
            minZoom = 1f; midZoom = 2.5f; maxZoom = 5f
        }
        layout.addView(pdfView, FrameLayout.LayoutParams(-1, -1))
        fun control(caption: String, description: String) = Button(this).apply {
            text = caption
            textSize = 24f
            setTextColor(Color.BLACK)
            contentDescription = description
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { setColor(0xEFFFFFFF.toInt()); cornerRadius = 24.dp.toFloat() }
            elevation = 3.dp.toFloat()
        }
        val maximize = control("[ ]", "Maximizar PDF").apply {
            setOnClickListener {
                maximized = !maximized
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (maximized) controller.hide(WindowInsetsCompat.Type.systemBars())
                else controller.show(WindowInsetsCompat.Type.systemBars())
                contentDescription = if (maximized) "Salir de pantalla completa" else "Maximizar PDF"
                text = if (maximized) "][" else "[ ]"
            }
        }
        layout.addView(maximize, FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.TOP or Gravity.END).apply {
            topMargin = 8.dp; marginEnd = 8.dp
        })
        status = TextView(this).apply {
            text = "Abriendo PDF…"; textSize = 12f; setTextColor(Color.BLACK)
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            background = GradientDrawable().apply { setColor(0xDFFFFFFF.toInt()); cornerRadius = 12.dp.toFloat() }
        }
        layout.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 8.dp })
        val verlo = control("✦", "Explicaciones guardadas").apply { visibility = View.GONE }
        layout.addView(verlo, FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = 8.dp; marginEnd = 8.dp
        })
        setContentView(layout)
        worker.execute {
            try {
                val session = File(intent.getStringExtra("session") ?: error("Falta el PDF.")).canonicalFile
                val sessions = File(cacheDir, "library-pdf").canonicalFile
                require(session.parentFile == sessions && session.extension == "json" && session.isFile)
                sessionFile = session
                val data = JSONObject(session.readText())
                val file = File(data.getString("path")).canonicalFile
                val root = File(filesDir, "workspace-library").canonicalFile
                require(file.path.startsWith(root.path + File.separator) && file.parentFile?.name == "blobs" && WorkspaceLibraryStore.validHash(file.name) && file.isFile)
                pdfHash = file.name
                val inventory = data.getJSONObject("manifest")
                val files = inventory.getJSONArray("files")
                val pdf = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("id") == data.getString("fileId") }
                require(pdf.getString("hash") == file.name && pdf.getString("mime").substringBefore(';') == "application/pdf")
                val key = WorkspaceLibraryStore.sha256(("workspace://" + pdf.getString("path")).toByteArray())
                val items = inventory.getJSONArray("items")
                val explanations = (0 until items.length()).map { items.getJSONObject(it) }.filter {
                    it.optString("kind") == "html" && it.optString("id").startsWith("explanation:$key:") && it.has("fileId")
                }
                val selected = savedInstanceState?.getInt("page") ?: getPreferences(MODE_PRIVATE).getInt(pdfHash, 0)
                runOnUiThread {
                    if (stopped || isFinishing || isDestroyed) return@runOnUiThread
                    var restored = false
                    pdfView.fromFile(file)
                        .enableSwipe(true)
                        .swipeHorizontal(false)
                        .enableDoubletap(true)
                        .defaultPage(selected.coerceAtLeast(0))
                        .pageFitPolicy(FitPolicy.WIDTH)
                        .fitEachPage(false)
                        .spacing(8)
                        .autoSpacing(false)
                        .pageSnap(false)
                        .pageFling(false)
                        .onPageChange { page, count -> pdfView.contentDescription = "PDF: p?gina ${page + 1} de $count" }
                        .onRender {
                            if (!restored) {
                                restored = true
                                if (savedInstanceState != null) {
                                    pdfView.zoomTo(savedInstanceState.getFloat("zoom", 1f).coerceIn(1f, 5f))
                                    pdfView.setPositionOffset(savedInstanceState.getFloat("position", 0f))
                                    pdfView.moveTo(savedInstanceState.getFloat("horizontal", 0f) * pdfView.width, pdfView.currentYOffset)
                                    pdfView.loadPages()
                                }
                            }
                            pdfView.tag = "rendered"
                            status.visibility = View.GONE
                        }
                        .onError { showError("No se pudo abrir el PDF.") }
                        .onPageError { page, _ -> showError("No se pudo mostrar la p?gina ${page + 1}.") }
                        .load()
                    if (explanations.isNotEmpty()) {
                        verlo.visibility = View.VISIBLE
                        verlo.setOnClickListener {
                            AlertDialog.Builder(this).setTitle("Verlo").setItems(explanations.map { it.getString("title") }.toTypedArray()) { _, which ->
                                openExplanation(inventory, explanations[which].getString("fileId"))
                            }.show()
                        }
                    }
                }
            } catch (error: Exception) { showError(error.message ?: "No se pudo abrir el PDF.") }
        }
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private fun showError(message: String) = runOnUiThread {
        if (!stopped && !isFinishing && !isDestroyed) { status.text = message; status.visibility = View.VISIBLE }
    }
    private fun openExplanation(inventory: JSONObject, id: String) {
        worker.execute {
            try {
                val credentials = WorkspaceCredentialStore(this).load() ?: error("Vinculá la biblioteca.")
                require(credentials.workspaceId == inventory.getString("workspaceId"))
                val files = inventory.getJSONArray("files")
                val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("id") == id && it.getString("mime") == "text/html" }
                val saved = WorkspaceLibraryStore(this, credentials).ensureFile(inventory, file)
                runOnUiThread { if (!stopped && !isFinishing && !isDestroyed) startActivity(Intent(this, WorkspaceHtmlActivity::class.java).putExtra("path", saved.absolutePath)) }
            } catch (error: Exception) { showError(error.message ?: "No se pudo abrir Verlo.") }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pdfView.currentPage)
        outState.putFloat("zoom", pdfView.zoom)
        outState.putFloat("position", pdfView.positionOffset)
        outState.putFloat("horizontal", if (pdfView.width > 0) pdfView.currentXOffset / pdfView.width else 0f)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        if (pdfHash.isNotEmpty()) getPreferences(MODE_PRIVATE).edit().putInt(pdfHash, pdfView.currentPage).apply()
        super.onPause()
    }
    override fun onDestroy() {
        stopped = true
        pdfView.recycle()
        worker.shutdown()
        if (isFinishing) sessionFile?.delete()
        super.onDestroy()
    }
}
