package com.inscreen.mic

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class SubjectNotesGalleryActivity : ComponentActivity() {
    private lateinit var subjectId: String
    private lateinit var store: SubjectNotesStore
    private var sessions = emptyList<SubjectNotesStore.Session>()
    private var viewer: ViewPager2? = null
    private var viewerTitle: TextView? = null
    private var selectedSessionId: String? = null
    private var selectedCheckBox: CheckBox? = null
    private var copyToolbar: View? = null
    private var copyButton: Button? = null
    private var startButton: Button? = null
    private var copyDefaultTint: ColorStateList? = null
    private var copyDefaultTextColors: ColorStateList? = null
    private var markerProgressDialog: AlertDialog? = null
    private var processing = false
    private val sessionChecks = mutableListOf<CheckBox>()
    private val allPhotos get() = sessions.flatMap(SubjectNotesStore.Session::photos)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID).orEmpty()
        if (AprioriStore.subject(AprioriStore.load(this), subjectId) == null) {
            finish()
            return
        }
        store = SubjectNotesStore.from(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewer != null) showOverview() else finish()
            }
        })
        showOverview()
    }

    private fun reload() { sessions = store.sessions(subjectId) }

    private fun showOverview() {
        viewer = null
        viewerTitle = null
        reload()
        if (sessions.none { it.id == selectedSessionId }) selectedSessionId = null
        selectedCheckBox = null
        sessionChecks.clear()
        val subjectName = AprioriStore.subject(AprioriStore.load(this), subjectId)?.optString("name") ?: "Materia"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = "Apuntes · $subjectName"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        })
        if (sessions.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "Todavía no hay fotos guardadas."
                textSize = 16f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(80), dp(12), dp(80))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            val spanish = Locale.forLanguageTag("es-AR")
            val dayFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", spanish)
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", spanish)
            sessions.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }
                .forEach { (day, daySessions) ->
                    content.addView(TextView(this).apply {
                        text = day.format(dayFormatter).replaceFirstChar { it.titlecase(spanish) }
                        textSize = 18f
                        setTextColor(Color.BLACK)
                        setPadding(0, dp(12), 0, dp(7))
                    })
                    daySessions.forEach { session ->
                        val heading = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        heading.addView(TextView(this).apply {
                            text = "Conjunto · ${Instant.ofEpochMilli(session.createdAt).atZone(ZoneId.systemDefault()).format(timeFormatter)}"
                            textSize = 13f
                            setTextColor(Color.DKGRAY)
                            setPadding(0, dp(5), 0, dp(5))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        val check = CheckBox(this).apply {
                            contentDescription = "Seleccionar conjunto"
                            isChecked = session.id == selectedSessionId
                            isEnabled = !processing
                        }
                        sessionChecks.add(check)
                        if (check.isChecked) selectedCheckBox = check
                        check.setOnCheckedChangeListener { _, checked -> selectSession(session.id, check, checked) }
                        heading.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
                        content.addView(heading)
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        session.photos.forEach { photo ->
                            val globalIndex = allPhotos.indexOfFirst { it.sessionId == photo.sessionId && it.name == photo.name }
                            row.addView(ImageButton(this).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setImageBitmap(NotesImageTools.decode(photo.file, dp(92), dp(92)))
                                contentDescription = "Abrir foto"
                                setBackgroundColor(Color.LTGRAY)
                                setOnClickListener { showViewer(globalIndex) }
                            }, LinearLayout.LayoutParams(dp(92), dp(92)).apply { marginEnd = dp(7) })
                        }
                        content.addView(HorizontalScrollView(this).apply {
                            isHorizontalScrollBarEnabled = false
                            addView(row)
                        }, LinearLayout.LayoutParams(-1, dp(100)))
                    }
                }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(Color.rgb(245, 245, 245))
            visibility = if (selectedSessionId == null) View.GONE else View.VISIBLE
        }
        val copy = Button(this).apply {
            text = "Copy"
            contentDescription = "Copiar conjunto con Marker"
            isEnabled = !processing
            setOnClickListener { copySelectedSession() }
        }
        copyDefaultTint = copy.backgroundTintList
        copyDefaultTextColors = copy.textColors
        copyButton = copy
        copyToolbar = actions
        actions.addView(copy, LinearLayout.LayoutParams(-2, dp(48)))
        val start = Button(this).apply {
            text = "Start"
            contentDescription = "Iniciar módulo con este conjunto"
            isEnabled = !processing
            setOnClickListener { startSelectedSession() }
        }
        startButton = start
        actions.addView(start, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        root.addView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(60)))
        setContentView(root)
    }

    private fun selectSession(sessionId: String, check: CheckBox, checked: Boolean) {
        if (processing) return
        if (checked) {
            selectedSessionId = sessionId
            selectedCheckBox?.takeIf { it !== check }?.isChecked = false
            selectedCheckBox = check
            copyToolbar?.visibility = View.VISIBLE
            resetCopyButton()
        } else if (selectedSessionId == sessionId) {
            selectedSessionId = null
            selectedCheckBox = null
            copyToolbar?.visibility = View.GONE
            resetCopyButton()
        }
    }

    private fun copySelectedSession() {
        if (processing) return
        val session = sessions.firstOrNull { it.id == selectedSessionId } ?: return
        val cached = session.photos.map { store.markerText(subjectId, session.id, it.name) }
        if (cached.all { it != null }) {
            copyMarkdown(cached.filterNotNull().joinToString("\n\n"))
            return
        }
        val credentials = ProviderCredentialStore(this).load()
        if (credentials == null) {
            Toast.makeText(this, "Vinculá el proveedor para usar Marker", Toast.LENGTH_LONG).show()
            return
        }
        val client = ProviderClient(credentials.baseUrl, credentials.token)
        beginProcessing()
        copyButton?.apply {
            isEnabled = false
            text = "Copy ${cached.count { it != null }}/${session.photos.size}"
            backgroundTintList = copyDefaultTint
        }
        processMarkerSession(
            session,
            client,
            onProgress = { completed -> copyButton?.text = "Copy $completed/${session.photos.size}" },
            onSuccess = { results -> copyMarkdown(results.joinToString("\n\n")); finishProcessing() },
            onFailure = { error ->
                Toast.makeText(this, markerErrorMessage(error), Toast.LENGTH_LONG).show()
                resetActionButtons()
                finishProcessing()
            },
        )
    }

    private fun startSelectedSession() {
        if (processing) return
        val session = sessions.firstOrNull { it.id == selectedSessionId } ?: return
        val cached = session.photos.map { store.markerText(subjectId, session.id, it.name) }
        if (cached.all { it != null }) {
            ModuleHostActivity.openFromNotes(this, subjectId, session.id)
            return
        }
        val credentials = ProviderCredentialStore(this).load()
        if (credentials == null) {
            Toast.makeText(this, "Vinculá el proveedor para usar Marker", Toast.LENGTH_LONG).show()
            return
        }
        val client = ProviderClient(credentials.baseUrl, credentials.token)
        beginProcessing()
        markerProgressDialog = AlertDialog.Builder(this)
            .setTitle("Preparando apuntes")
            .setMessage("${cached.count { it != null }} / ${session.photos.size}")
            .setCancelable(false)
            .create()
            .also { it.show() }
        processMarkerSession(
            session,
            client,
            onProgress = { completed -> markerProgressDialog?.setMessage("$completed / ${session.photos.size}") },
            onSuccess = {
                markerProgressDialog?.dismiss()
                markerProgressDialog = null
                finishProcessing()
                ModuleHostActivity.openFromNotes(this, subjectId, session.id)
            },
            onFailure = { error ->
                markerProgressDialog?.dismiss()
                markerProgressDialog = null
                Toast.makeText(this, markerErrorMessage(error), Toast.LENGTH_LONG).show()
                resetActionButtons()
                finishProcessing()
            },
        )
    }

    private fun processMarkerSession(
        session: SubjectNotesStore.Session,
        client: ProviderClient,
        onProgress: (Int) -> Unit,
        onSuccess: (List<String>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        thread(name = "InScreenMarkerSession") {
            try {
                var completed = session.photos.count { store.markerText(subjectId, session.id, it.name) != null }
                val results = session.photos.map { photo ->
                    store.markerText(subjectId, session.id, photo.name) ?: run {
                        val temporary = File(cacheDir, "marker-${UUID.randomUUID()}.jpg")
                        try {
                            if (!NotesImageTools.writeMarkerJpeg(photo.file, temporary)) {
                                throw ProviderClient.MarkerException("image_prepare_failed")
                            }
                            client.transcribeMarker(temporary).also {
                                store.saveMarkerText(subjectId, session.id, photo.name, it)
                                completed += 1
                                val progress = completed
                                runOnUiThread {
                                    if (!isDestroyed && !isFinishing) onProgress(progress)
                                }
                            }
                        } finally {
                            temporary.delete()
                        }
                    }
                }
                runOnUiThread { if (!isDestroyed && !isFinishing) onSuccess(results) }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed && !isFinishing) onFailure(error) }
            }
        }
    }

    private fun copyMarkdown(markdown: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Apuntes extraídos con Marker", markdown))
        copyButton?.apply {
            text = "Copy"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(36, 160, 80))
            setTextColor(Color.WHITE)
        }
    }

    private fun beginProcessing() {
        processing = true
        sessionChecks.forEach { it.isEnabled = false }
        copyButton?.isEnabled = false
        startButton?.isEnabled = false
    }

    private fun finishProcessing() {
        processing = false
        sessionChecks.forEach { it.isEnabled = true }
        copyButton?.isEnabled = true
        startButton?.isEnabled = true
    }

    private fun resetCopyButton() {
        copyButton?.apply {
            text = "Copy"
            isEnabled = !processing
            backgroundTintList = copyDefaultTint
            copyDefaultTextColors?.let(::setTextColor)
        }
        startButton?.apply { text = "Start"; isEnabled = !processing }
    }

    private fun resetActionButtons() = resetCopyButton()

    override fun onDestroy() {
        markerProgressDialog?.dismiss()
        markerProgressDialog = null
        super.onDestroy()
    }

    private fun markerErrorMessage(error: Exception): String = when ((error as? ProviderClient.MarkerException)?.code) {
        "provider_repair_required" -> "Volvé a vincular el proveedor para habilitar Marker."
        "image_too_large", "image_prepare_failed" -> "No se pudo preparar una de las fotos."
        "network_error" -> "No se pudo conectar con el proveedor."
        "marker_failed", "empty_marker_result" -> "Marker no pudo extraer el contenido."
        else -> "No se pudo copiar el conjunto."
    }

    private fun showViewer(startIndex: Int) {
        if (processing) return
        reload()
        if (allPhotos.isEmpty()) return showOverview()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        toolbar.addView(Button(this).apply {
            text = "‹"
            textSize = 26f
            contentDescription = "Volver a la galería"
            setOnClickListener { showOverview() }
        }, LinearLayout.LayoutParams(dp(60), dp(52)))
        viewerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        toolbar.addView(viewerTitle, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            contentDescription = "Eliminar foto"
            setBackgroundColor(Color.WHITE)
            setOnClickListener { confirmDeleteCurrent() }
        }, LinearLayout.LayoutParams(dp(60), dp(52)))
        root.addView(toolbar)
        viewer = ViewPager2(this).apply {
            adapter = PhotoPagerAdapter(allPhotos)
            setCurrentItem(startIndex.coerceIn(0, allPhotos.lastIndex), false)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateViewerTitle(position)
            })
        }
        root.addView(viewer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateViewerTitle(viewer!!.currentItem)
    }

    private fun updateViewerTitle(position: Int) {
        if (position !in allPhotos.indices) return
        val date = Instant.ofEpochMilli(allPhotos[position].createdAt).atZone(ZoneId.systemDefault())
        viewerTitle?.text = "${position + 1} / ${allPhotos.size} · ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"
    }

    private fun confirmDeleteCurrent() {
        val position = viewer?.currentItem ?: return
        val photo = allPhotos.getOrNull(position) ?: return
        AlertDialog.Builder(this)
            .setTitle("Eliminar foto")
            .setMessage("Esta foto se eliminará definitivamente de los apuntes.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                if (!store.deletePhoto(subjectId, photo.sessionId, photo.name)) {
                    Toast.makeText(this, "No se pudo eliminar la foto", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                reload()
                if (allPhotos.isEmpty()) showOverview() else showViewer(position.coerceAtMost(allPhotos.lastIndex))
            }
            .show()
    }

    private inner class PhotoPagerAdapter(private val photos: List<SubjectNotesStore.Photo>) : RecyclerView.Adapter<PhotoHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PhotoHolder(ZoomableImageView(parent.context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        })
        override fun getItemCount() = photos.size
        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            holder.image.setImageBitmap(NotesImageTools.decode(photos[position].file, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels))
            holder.image.contentDescription = "Foto ${position + 1} de ${photos.size}"
        }
        override fun onViewRecycled(holder: PhotoHolder) { holder.image.setImageDrawable(null) }
    }

    private class PhotoHolder(val image: ZoomableImageView) : RecyclerView.ViewHolder(image)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SUBJECT_ID = "subject_id"
        fun intent(context: Context, subjectId: String) = Intent(context, SubjectNotesGalleryActivity::class.java)
            .putExtra(EXTRA_SUBJECT_ID, subjectId)
    }
}
