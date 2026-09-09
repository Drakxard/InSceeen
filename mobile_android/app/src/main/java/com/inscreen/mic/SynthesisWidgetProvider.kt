package com.inscreen.mic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SynthesisWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
        refresh(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, intArrayOf(id))
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { SynthesisWidgetStore.delete(context, it) }
    }

    private fun refresh(context: Context, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val credentials = ProviderCredentialStore(context).load()
                val workers = ids.map { id -> Thread worker@{
                    val config = SynthesisWidgetStore.load(context, id) ?: return@worker
                    val result = runCatching {
                        if (credentials == null) throw ProviderWidgetException("provider_not_configured", false)
                        ProviderClient(credentials.baseUrl, credentials.token,
                            OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()).listSynthesisWeeks(config.subjectId)
                    }
                    // A response for an old subject must not change a reconfigured widget.
                    if (SynthesisWidgetStore.load(context, id)?.subjectId != config.subjectId) return@worker
                    val next = result.fold(
                        onSuccess = { config.copy(weeks = it, status = if (it.isEmpty()) "Todavía no hay semanas sincronizadas." else "") },
                        onFailure = {
                            val transient = (it as? ProviderWidgetException)?.transient == true
                            config.copy(weeks = if (transient) config.weeks else emptyList(),
                                status = if (transient) "Sin conexión. Tocá ↻ para reintentar." else "Revisá la vinculación del proveedor.")
                        },
                    )
                    SynthesisWidgetStore.save(context, id, next)
                    update(context, AppWidgetManager.getInstance(context), id)
                }.also { it.start() } }
                workers.forEach { it.join() }
            } finally { pending.finish() }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "com.inscreen.mic.SYNTHESIS_REFRESH"

        fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val config = SynthesisWidgetStore.load(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.synthesis_widget)
            views.setTextViewText(R.id.synthesis_widget_title, config?.subjectName ?: "Elegir materia")
            val target = if (config == null) SynthesisWidgetConfigureActivity::class.java else SynthesisWidgetResolveActivity::class.java
            views.setOnClickPendingIntent(R.id.synthesis_widget_root,
                PendingIntent.getActivity(context, appWidgetId,
                    Intent(context, target).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
