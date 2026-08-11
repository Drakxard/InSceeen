package com.inscreen.mic

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SubjectNotesGalleryActivity : ComponentActivity() {
    private lateinit var subjectId: String
    private lateinit var store: SubjectNotesStore
    private var sessions = emptyList<SubjectNotesStore.Session>()
    private var viewer: ViewPager2? = null
    private var viewerTitle: TextView? = null
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
                        content.addView(TextView(this).apply {
                            text = "Conjunto · ${Instant.ofEpochMilli(session.createdAt).atZone(ZoneId.systemDefault()).format(timeFormatter)}"
                            textSize = 13f
                            setTextColor(Color.DKGRAY)
                            setPadding(0, dp(5), 0, dp(5))
                        })
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
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(content) })
    }

    private fun showViewer(startIndex: Int) {
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
