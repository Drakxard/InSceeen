package com.inscreen.mic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class SynthesisWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.synthesis_widget)
            views.setOnClickPendingIntent(
                R.id.synthesis_widget_root,
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    Intent(context, SynthesisWidgetResolveActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
