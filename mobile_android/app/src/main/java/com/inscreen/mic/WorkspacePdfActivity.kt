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
import android.widget.LinearLayout
import android.widget.TextView
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
    private var pdfHash = ""
    private val pageSizes = mutableMapOf<Int, Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(232, 234, 237)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply { text = "Abriendo PDF…"; setTextColor(Color.BLACK); setPadding(20, 12, 20, 12) }
        toolbar.addView(status, LinearLayout.LayoutParams(0, 56.dp, 1f))
        val verlo = Button(this).apply { text = "Verlo"; visibility = View.GONE; contentDescription = "Explicaciones guardadas" }
        toolbar.addView(verlo)
        layout.addView(toolbar)
        pages = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WorkspacePdfActivity)
            setItemViewCacheSize(0)
            recycledViewPool.setMaxRecycledViews(0, 2)
            contentDescription = "Páginas del PDF"
        }
        layout.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
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
    private inner class PageHolder(val frame: FrameLayout, val image: ZoomableImageView, val label: TextView) : RecyclerView.ViewHolder(frame) {
        var request: Future<*>? = null
        var index = -1
    }
    private inner class PdfAdapter(private val count: Int) : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val image = ZoomableImageView(this@WorkspacePdfActivity).apply { setBackgroundColor(Color.WHITE) }
            val label = TextView(this@WorkspacePdfActivity).apply { gravity = Gravity.CENTER; setTextColor(Color.BLACK) }
            val frame = FrameLayout(this@WorkspacePdfActivity).apply {
                layoutParams = RecyclerView.LayoutParams(-1, 500).apply { bottomMargin = 8.dp }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(label, FrameLayout.LayoutParams(-1, -1))
            }
            return PageHolder(frame, image, label)
        }
        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.request?.cancel(false)
            holder.index = position
            holder.image.setImageDrawable(null)
            holder.image.contentDescription = "Página ${position + 1}"
            holder.label.text = "Página ${position + 1}…"
            holder.label.visibility = View.VISIBLE
            val width = pages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            fun display(bitmap: Bitmap) {
                if (stopped || holder.index != position) return
                val ratio = bitmap.height.toFloat() / bitmap.width
                pageSizes[position] = ratio
                holder.frame.layoutParams = holder.frame.layoutParams.apply { height = (width * ratio).toInt().coerceAtLeast(1) }
                holder.image.setImageBitmap(bitmap)
                holder.label.visibility = View.GONE
            }
            holder.frame.layoutParams = holder.frame.layoutParams.apply { height = (width * (pageSizes[position] ?: 1.414f)).toInt() }
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
