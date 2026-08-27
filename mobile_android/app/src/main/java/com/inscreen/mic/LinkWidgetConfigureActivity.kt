package com.inscreen.mic

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class LinkWidgetConfigureActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var nameInput: EditText
    private lateinit var colorInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var preview: TextView
    private lateinit var manualContainer: LinearLayout
    private lateinit var syncedContainer: LinearLayout
    private lateinit var subjectSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var syncStatus: TextView
    private var mode = LinkWidgetStore.MODE_MANUAL
    private var providerSubjects = emptyList<ProviderWidgetSubject>()
    private var existingConfig: LinkWidgetConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        buildContent(LinkWidgetStore.load(this, appWidgetId))
    }

    private fun buildContent(existing: LinkWidgetConfig?) {
        existingConfig = existing
        mode = existing?.mode ?: LinkWidgetStore.MODE_MANUAL
        existing?.takeIf { it.subjectId.isNotBlank() }?.let { saved ->
            providerSubjects = listOf(ProviderWidgetSubject(saved.subjectId, saved.subjectName.ifBlank { saved.subjectId }, "#A8EF00", true, true, null))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(Color.rgb(7, 14, 10))
        }
        content.addView(TextView(this).apply {
            text = if (existing == null) "NUEVO ACCESO DIRECTO" else "EDITAR ACCESO DIRECTO"
            textSize = 22f
            setTextColor(ACCENT)
            setPadding(0, 0, 0, dp(18))
        })

        nameInput = field(
            label = "Nombre",
            hint = "Ej.: Programación o EO",
            value = existing?.name.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            maxLength = 80,
            container = content,
        )

        content.addView(label("Color"))
        val palette = intArrayOf(
            Color.rgb(168, 239, 0),
            Color.rgb(144, 221, 237),
            Color.rgb(255, 193, 7),
            Color.rgb(255, 112, 140),
            Color.rgb(126, 104, 238),
            Color.rgb(48, 190, 112),
            Color.rgb(245, 245, 245),
            Color.rgb(45, 45, 45),
        )
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@LinkWidgetConfigureActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                palette.forEach { color ->
                    addView(Button(this@LinkWidgetConfigureActivity).apply {
                        contentDescription = "Elegir color ${LinkWidgetPolicy.formatHexColor(color)}"
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = 0
                        minimumHeight = 0
                        backgroundTintList = ColorStateList.valueOf(color)
                        setOnClickListener {
                            colorInput.setText(LinkWidgetPolicy.formatHexColor(color))
                            colorInput.setSelection(colorInput.text.length)
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        marginEnd = dp(8)
                    })
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        colorInput = field(
            label = "Color hexadecimal",
            hint = "#A8EF00",
            value = LinkWidgetPolicy.formatHexColor(existing?.color ?: LinkWidgetStore.DEFAULT_COLOR),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            maxLength = 7,
            container = content,
        ).apply {
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(7))
        }

        preview = TextView(this).apply {
            text = existing?.name?.takeIf { it.isNotBlank() } ?: "Vista previa"
            textSize = 28f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(12), 0, dp(12), 0)
        }
        content.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(96),
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(18)
        })

        content.addView(label("Tipo de enlace"))
        val providerConfigured = ProviderCredentialStore(this).load() != null
        if (!providerConfigured) mode = LinkWidgetStore.MODE_MANUAL
        val manualRadio = RadioButton(this).apply { text = "Manual"; setTextColor(Color.WHITE); id = View.generateViewId() }
        val syncedRadio = RadioButton(this).apply {
            text = "Sincronizado con Cursado"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
            isEnabled = providerConfigured
        }
        content.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(manualRadio)
            addView(syncedRadio)
            check(if (mode == LinkWidgetStore.MODE_SYNCED && providerConfigured) syncedRadio.id else manualRadio.id)
            setOnCheckedChangeListener { _, checked ->
                mode = if (checked == syncedRadio.id) LinkWidgetStore.MODE_SYNCED else LinkWidgetStore.MODE_MANUAL
                updateModeVisibility()
            }
        })
        if (!providerConfigured) content.addView(TextView(this).apply {
            text = "Vinculá el proveedor desde la pantalla principal de InScreen para habilitar la sincronización."
            setTextColor(Color.rgb(180, 195, 185))
            setPadding(0, 0, 0, dp(12))
        })

        manualContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        urlInput = field(
            label = "Enlace",
            hint = "https://notebook.google.com/notebook/…",
            value = existing?.url.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            maxLength = 2048,
            container = manualContainer,
        )
        content.addView(manualContainer)

        syncedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        syncedContainer.addView(label("Materia"))
        subjectSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@LinkWidgetConfigureActivity, android.R.layout.simple_spinner_dropdown_item, providerSubjects.map { it.name })
        }
        syncedContainer.addView(subjectSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        syncedContainer.addView(label("Destino"))
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@LinkWidgetConfigureActivity, android.R.layout.simple_spinner_dropdown_item, listOf("NotebookLM", "Material"))
            setSelection(if (existing?.targetKind == LinkWidgetStore.TARGET_MATERIALS) 1 else 0)
        }
        syncedContainer.addView(targetSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        syncStatus = TextView(this).apply {
            setTextColor(Color.rgb(180, 195, 185))
            text = if (providerConfigured) "Cargando materias del proveedor…" else "Vinculá el proveedor desde InScreen para sincronizar widgets."
            setPadding(0, dp(6), 0, dp(12))
        }
        syncedContainer.addView(syncStatus)
        content.addView(syncedContainer)
        updateModeVisibility()
        if (providerConfigured) loadProviderSubjects()

        content.addView(Button(this).apply {
            text = if (existing == null) "CREAR WIDGET" else "GUARDAR CAMBIOS"
            textSize = 16f
            setTextColor(Color.BLACK)
            backgroundTintList = ColorStateList.valueOf(ACCENT)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(12)
        })

        nameInput.addTextChangedListener(SimpleTextWatcher {
            preview.text = it.trim().ifEmpty { "Vista previa" }
        })
        colorInput.addTextChangedListener(SimpleTextWatcher { updatePreviewColor(it) })
        updatePreviewColor(colorInput.text.toString())

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun save() {
        nameInput.error = null
        colorInput.error = null
        urlInput.error = null
        val name = nameInput.text.toString().trim()
        val color = LinkWidgetPolicy.parseHexColor(colorInput.text.toString())
        val normalizedUrl = if (mode == LinkWidgetStore.MODE_MANUAL) LinkWidgetPolicy.normalizeUrl(urlInput.text.toString()) else null
        var valid = true
        if (name.isEmpty()) {
            nameInput.error = "Ingresá un nombre"
            valid = false
        }
        if (color == null) {
            colorInput.error = "Usá un color como #A8EF00"
            valid = false
        }
        if (mode == LinkWidgetStore.MODE_MANUAL && normalizedUrl == null) {
            urlInput.error = "Ingresá un enlace web válido"
            valid = false
        }
        if (!valid) return

        val config = if (mode == LinkWidgetStore.MODE_SYNCED) {
            val subject = providerSubjects.getOrNull(subjectSpinner.selectedItemPosition)
            if (subject == null) {
                syncStatus.text = "No hay una materia disponible para sincronizar."
                return
            }
            val targetKind = if (targetSpinner.selectedItemPosition == 1) LinkWidgetStore.TARGET_MATERIALS else LinkWidgetStore.TARGET_NOTEBOOK_LM
            val keepCache = existingConfig?.takeIf { it.subjectId == subject.id && it.targetKind == targetKind }?.cachedUrl.orEmpty()
            LinkWidgetConfig(name, color!!, "", mode, subject.id, subject.name, targetKind, keepCache)
        } else {
            LinkWidgetConfig(name, color!!, normalizedUrl!!)
        }
        LinkWidgetStore.save(this, appWidgetId, config)
        LinkWidgetProvider.update(this, AppWidgetManager.getInstance(this), appWidgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    private fun updatePreviewColor(raw: String) {
        val color = LinkWidgetPolicy.parseHexColor(raw) ?: return
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(100).toFloat()
            setColor(color)
        }
        preview.setTextColor(LinkWidgetPolicy.contrastingTextColor(color))
    }

    private fun updateModeVisibility() {
        if (!::manualContainer.isInitialized || !::syncedContainer.isInitialized) return
        manualContainer.visibility = if (mode == LinkWidgetStore.MODE_MANUAL) android.view.View.VISIBLE else android.view.View.GONE
        syncedContainer.visibility = if (mode == LinkWidgetStore.MODE_SYNCED) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun loadProviderSubjects() {
        val credentials = ProviderCredentialStore(this).load() ?: return
        Thread {
            try {
                val loaded = ProviderClient(credentials.baseUrl, credentials.token).listWidgetSubjects()
                runOnUiThread {
                    providerSubjects = loaded
                    subjectSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, loaded.map { it.name })
                    val selected = loaded.indexOfFirst { it.id == existingConfig?.subjectId }.takeIf { it >= 0 } ?: 0
                    if (loaded.isNotEmpty()) subjectSpinner.setSelection(selected)
                    syncStatus.text = if (loaded.isEmpty()) "Publicá las materias desde Cursado 2026." else "El destino se resolverá al tocar el widget."
                }
            } catch (_: ProviderWidgetException) {
                runOnUiThread { syncStatus.text = "No se pudo cargar el catálogo del proveedor." }
            }
        }.start()
    }

    private fun field(
        label: String,
        hint: String,
        value: String,
        inputType: Int,
        maxLength: Int,
        container: LinearLayout,
    ): EditText {
        container.addView(label(label))
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.rgb(130, 145, 135))
            setTextColor(Color.WHITE)
            setText(value)
            this.inputType = inputType
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setSelectAllOnFocus(false)
        }
        container.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) })
        return input
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.WHITE)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val ACCENT = Color.rgb(69, 255, 26)
    }
}
