package com.inscreen.mic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

class SubjectNotesCaptureActivity : ComponentActivity() {
    private lateinit var subjectId: String
    private lateinit var draftId: String
    private lateinit var draftDirectory: File
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var photoView: ZoomableImageView
    private lateinit var strip: LinearLayout
    private lateinit var shutter: Button
    private lateinit var confirm: Button
    private var imageCapture: ImageCapture? = null
    private var selectedFile: File? = null
    private var committed = false
    private val photos = mutableListOf<File>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Se necesita permiso de cámara para crear apuntes", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID).orEmpty()
        if (AprioriStore.subject(AprioriStore.load(this), subjectId) == null) {
            finish()
            return
        }
        cleanupOldDrafts()
        draftId = savedInstanceState?.getString(STATE_DRAFT_ID) ?: UUID.randomUUID().toString()
        draftDirectory = File(File(cacheDir, "subject-note-drafts"), draftId).apply { mkdirs() }
        photos += draftDirectory.listFiles()?.filter { it.isFile && it.extension.equals("jpg", true) }?.sortedBy { it.name }.orEmpty()
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                draftDirectory.deleteRecursively()
                finish()
            }
        })
        showCamera()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DRAFT_ID, draftId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !committed && ::draftDirectory.isInitialized) draftDirectory.deleteRecursively()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val title = TextView(this).apply {
            text = AprioriStore.subject(AprioriStore.load(this@SubjectNotesCaptureActivity), subjectId)?.optString("name") ?: "Apuntes"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        photoView = ZoomableImageView(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        previewFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        shutter = Button(this).apply {
            text = "●"
            textSize = 31f
            contentDescription = "Sacar foto"
            setOnClickListener { capture() }
        }
        confirm = Button(this).apply {
            text = ">"
            textSize = 28f
            contentDescription = "Guardar conjunto"
            isEnabled = photos.isNotEmpty()
            setOnClickListener { commitSession() }
        }
        actions.addView(shutter, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(12) })
        actions.addView(confirm, LinearLayout.LayoutParams(dp(68), dp(58)))
        root.addView(actions)

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, FrameLayout.LayoutParams(-2, dp(74)))
        }, LinearLayout.LayoutParams(-1, dp(82)))
        setContentView(root)
        renderStrip()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }.onFailure {
                Toast.makeText(this, "No se pudo iniciar la cámara", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val capture = imageCapture ?: return
        shutter.isEnabled = false
        val nextNumber = (photos.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val output = File(draftDirectory, "%04d.jpg".format(nextNumber))
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(output).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    shutter.isEnabled = true
                    photos += output
                    showPhoto(output)
                    renderStrip()
                }
                override fun onError(exception: ImageCaptureException) {
                    shutter.isEnabled = true
                    output.delete()
                    Toast.makeText(this@SubjectNotesCaptureActivity, "No se pudo guardar la foto", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun showCamera() {
        selectedFile = null
        previewView.visibility = View.VISIBLE
        photoView.visibility = View.GONE
        shutter.visibility = View.VISIBLE
        renderStrip()
    }

    private fun showPhoto(file: File) {
        selectedFile = file
        val bitmap = NotesImageTools.decode(file, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (bitmap == null) {
            Toast.makeText(this, "No se pudo abrir la foto", Toast.LENGTH_SHORT).show()
            return
        }
        photoView.setImageBitmap(bitmap)
        previewView.visibility = View.GONE
        photoView.visibility = View.VISIBLE
        shutter.visibility = View.INVISIBLE
    }

    private fun renderStrip() {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        strip.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            contentDescription = "Volver a la cámara"
            setBackgroundColor(if (selectedFile == null) Color.WHITE else Color.DKGRAY)
            setOnClickListener { showCamera() }
        }, tileParams())
        photos.forEach { file ->
            val selected = file == selectedFile
            strip.addView(ImageButton(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = if (selected) "Eliminar foto seleccionada" else "Ver foto"
                if (selected) {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setBackgroundColor(Color.WHITE)
                    setOnClickListener { deleteDraftPhoto(file) }
                } else {
                    setImageBitmap(NotesImageTools.decode(file, dp(64), dp(64)))
                    setBackgroundColor(Color.DKGRAY)
                    setOnClickListener { showPhoto(file); renderStrip() }
                }
            }, tileParams())
        }
        confirm.isEnabled = photos.isNotEmpty()
    }

    private fun deleteDraftPhoto(file: File) {
        val index = photos.indexOf(file)
        if (index < 0 || !file.delete()) return
        photos.removeAt(index)
        val next = photos.getOrNull(index.coerceAtMost(photos.lastIndex))
        if (next == null) showCamera() else showPhoto(next)
        renderStrip()
    }

    private fun commitSession() {
        if (photos.isEmpty()) return
        confirm.isEnabled = false
        runCatching { SubjectNotesStore.from(this).commit(subjectId, System.currentTimeMillis(), photos) }
            .onSuccess {
                committed = true
                draftDirectory.deleteRecursively()
                Toast.makeText(this, "Apuntes guardados", Toast.LENGTH_SHORT).show()
                finish()
            }
            .onFailure {
                confirm.isEnabled = true
                Toast.makeText(this, "No se pudieron guardar los apuntes", Toast.LENGTH_LONG).show()
            }
    }

    private fun cleanupOldDrafts() {
        val cutoff = System.currentTimeMillis() - DRAFT_MAX_AGE_MS
        File(cacheDir, "subject-note-drafts").listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::deleteRecursively)
    }

    private fun tileParams() = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SUBJECT_ID = "subject_id"
        private const val STATE_DRAFT_ID = "draft_id"
        private const val DRAFT_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        fun intent(context: Context, subjectId: String) = Intent(context, SubjectNotesCaptureActivity::class.java)
            .putExtra(EXTRA_SUBJECT_ID, subjectId)
    }
}
