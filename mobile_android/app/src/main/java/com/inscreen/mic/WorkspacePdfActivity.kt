package com.inscreen.mic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Native vertical PDF reader. Keeps only visible pages and a bounded bitmap cache. */
class WorkspacePdfActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private var document: NativePdfDocument? = null
    private var sessionFile: File? = null
    @Volatile private var stopped = false
    private lateinit var pages: RecyclerView
    private lateinit var status: TextView
    private lateinit var viewport: PdfDocumentViewport
    private var maximized = false
    private var pdfHash = ""
    private val pageSizes = mutableMapOf<Int, Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = FrameLayout(this).apply { setBackgroundColor(Color.rgb(232, 234, 237)) }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pages = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WorkspacePdfActivity)
            setItemViewCacheSize(0)
            recycledViewPool.setMaxRecycledViews(0, 2)
            contentDescription = "Páginas del PDF"
        }
        viewport = PdfDocumentViewport(this, pages)
        viewport.onDocumentWidthChanged = {
            pages.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount, "size") }
        }
        layout.addView(viewport, FrameLayout.LayoutParams(-1, -1))
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
        val back = control("←", "Volver").apply { setOnClickListener { finish() } }
        layout.addView(back, FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.TOP or Gravity.START).apply {
            topMargin = 8.dp; marginStart = 8.dp
        })
        val maximize = control("⛶", "Maximizar PDF").apply {
            setOnClickListener {
                maximized = !maximized
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (maximized) controller.hide(WindowInsetsCompat.Type.systemBars())
                else controller.show(WindowInsetsCompat.Type.systemBars())
                contentDescription = if (maximized) "Salir de pantalla completa" else "Maximizar PDF"
                text = if (maximized) "⊡" else "⛶"
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
                val opened = NativePdfDocument(file)
                document = opened
                val pageCount = opened.pageCount
                check(pageCount > 0) { "El PDF no contiene páginas." }
                runOnUiThread {
                    if (stopped) return@runOnUiThread
                    pages.adapter = PdfAdapter(pageCount)
                    val selected = savedInstanceState?.getInt("page") ?: getPreferences(MODE_PRIVATE).getInt(pdfHash, 0)
                    pages.scrollToPosition(selected.coerceIn(0, pageCount - 1))
                    updatePage()
                    pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updatePage()
                    })
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
    private fun updatePage() {
        val count = pages.adapter?.itemCount ?: return
        val position = (pages.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        status.text = "${position + 1} / $count"
    }
    private fun showError(message: String) = runOnUiThread { if (!stopped) status.text = message }
    private fun openExplanation(inventory: JSONObject, id: String) {
        worker.execute {
            try {
                val credentials = WorkspaceCredentialStore(this).load() ?: error("Vinculá la biblioteca.")
                require(credentials.workspaceId == inventory.getString("workspaceId"))
                val files = inventory.getJSONArray("files")
                val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("id") == id && it.getString("mime") == "text/html" }
                val saved = WorkspaceLibraryStore(this, credentials).ensureFile(inventory, file)
                runOnUiThread { if (!stopped) startActivity(Intent(this, WorkspaceHtmlActivity::class.java).putExtra("path", saved.absolutePath)) }
            } catch (error: Exception) { showError(error.message ?: "No se pudo abrir Verlo.") }
        }
    }
    private inner class PageHolder(val frame: FrameLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(frame) {
        var request: Future<*>? = null
        var index = -1
    }
    private inner class PdfAdapter(private val count: Int) : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val image = ImageView(this@WorkspacePdfActivity).apply { setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER }
            val label = TextView(this@WorkspacePdfActivity).apply { gravity = Gravity.CENTER; setTextColor(Color.BLACK) }
            val frame = FrameLayout(this@WorkspacePdfActivity).apply {
                layoutParams = RecyclerView.LayoutParams(-1, 500).apply { bottomMargin = 8.dp }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(label, FrameLayout.LayoutParams(-1, -1))
            }
            return PageHolder(frame, image, label)
        }
        private fun resize(holder: PageHolder, position: Int) {
            val width = viewport.documentWidth.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            holder.frame.layoutParams = holder.frame.layoutParams.apply { height = (width * (pageSizes[position] ?: 1.414f)).toInt().coerceAtLeast(1) }
        }
        override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) resize(holder, position)
            else onBindViewHolder(holder, position)
        }
        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.request?.cancel(false)
            holder.index = position
            holder.image.setImageDrawable(null)
            holder.image.contentDescription = "Página ${position + 1}"
            holder.label.text = "Página ${position + 1}…"
            holder.label.visibility = View.VISIBLE
            val width = resources.displayMetrics.widthPixels
            fun display(bitmap: Bitmap) {
                if (stopped || holder.index != position) return
                val ratio = bitmap.height.toFloat() / bitmap.width
                pageSizes[position] = ratio
                resize(holder, position)
                holder.image.setImageBitmap(bitmap)
                holder.label.visibility = View.GONE
            }
            resize(holder, position)
            cache.get(position)?.let { display(it); return }
            holder.request = worker.submit {
                try {
                    if (Thread.currentThread().isInterrupted) return@submit
                    val bitmap = document?.render(position, width * 2) ?: return@submit
                    cache.put(position, bitmap)
                    runOnUiThread { display(bitmap) }
                } catch (error: Exception) {
                    runOnUiThread { if (!stopped && holder.index == position) holder.label.text = "No se pudo mostrar la página ${position + 1}." }
                }
            }
        }
        override fun onViewRecycled(holder: PageHolder) {
            holder.index = -1
            holder.request?.cancel(false)
            holder.image.setImageDrawable(null)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", (pages.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0))
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        if (pdfHash.isNotEmpty()) getPreferences(MODE_PRIVATE).edit().putInt(pdfHash, (pages.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)).apply()
        super.onPause()
    }
    override fun onDestroy() {
        stopped = true
        pages.adapter = null
        worker.execute { document?.close(); document = null; cache.evictAll() }
        worker.shutdown()
        if (isFinishing) sessionFile?.delete()
        super.onDestroy()
    }
}
