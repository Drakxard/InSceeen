package com.inscreen.mic

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class SynthesisWidgetConfigureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        content.addView(TextView(this).apply { text = "Materia de la síntesis"; textSize = 24f })
        val subjects = Spinner(this)
        content.addView(subjects)
        val status = TextView(this).apply { text = "Cargando materias…"; setPadding(0, 20, 0, 20) }
        content.addView(status)
        val save = Button(this).apply { text = "CREAR WIDGET"; isEnabled = false }
        content.addView(save)
        val retry = Button(this).apply { text = "ACTUALIZAR MATERIAS" }
        content.addView(retry)
        setContentView(content)
        var catalog = emptyList<ProviderWidgetSubject>()
        fun load() {
            val credentials = ProviderCredentialStore(this).load()
            if (credentials == null) { status.text = "Vinculá el proveedor desde InScreen para cargar tus materias."; return }
            retry.isEnabled = false
            Thread {
                val result = runCatching { ProviderClient(credentials.baseUrl, credentials.token).listWidgetSubjects() }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    retry.isEnabled = true
                    result.fold(onSuccess = {
                        catalog = it
                        subjects.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, it.map { subject -> subject.name })
                        val existing = SynthesisWidgetStore.load(this, id)
                        val index = it.indexOfFirst { subject -> subject.id == existing?.subjectId }
                        if (index >= 0) subjects.setSelection(index)
                        save.isEnabled = it.isNotEmpty()
                        status.text = if (it.isEmpty()) "Publicá tus materias desde Cursado." else "El widget abrirá la Síntesis de esta materia en la última semana sincronizada."
                    }, onFailure = { status.text = "No se pudieron cargar las materias. Revisá la conexión y el proveedor." })
                }
            }.start()
        }
        retry.setOnClickListener { load() }
        save.setOnClickListener {
            val subject = catalog.getOrNull(subjects.selectedItemPosition) ?: return@setOnClickListener
            SynthesisWidgetStore.save(this, id, SynthesisWidgetConfig(subject.id, subject.name, status = "Actualizando…"))
            SynthesisWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
            sendBroadcast(Intent(this, SynthesisWidgetProvider::class.java).setAction(SynthesisWidgetProvider.ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
        load()
    }
}
