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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LinkWidgetConfigureActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var nameInput: EditText
    private lateinit var colorInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var preview: TextView

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

        urlInput = field(
            label = "Enlace",
            hint = "https://notebook.google.com/notebook/…",
            value = existing?.url.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            maxLength = 2048,
            container = content,
        )

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
        val normalizedUrl = LinkWidgetPolicy.normalizeUrl(urlInput.text.toString())
        var valid = true
        if (name.isEmpty()) {
            nameInput.error = "Ingresá un nombre"
            valid = false
        }
        if (color == null) {
            colorInput.error = "Usá un color como #A8EF00"
            valid = false
        }
        if (normalizedUrl == null) {
            urlInput.error = "Ingresá un enlace web válido"
            valid = false
        }
        if (!valid) return

        LinkWidgetStore.save(this, appWidgetId, LinkWidgetConfig(name, color!!, normalizedUrl!!))
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
