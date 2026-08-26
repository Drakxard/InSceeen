package com.inscreen.mic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.RemoteViews

class LinkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        update(context, manager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { LinkWidgetStore.delete(context, it) }
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val config = LinkWidgetStore.load(context, appWidgetId) ?: return
            val options = manager.getAppWidgetOptions(appWidgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                .coerceAtLeast(48)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48)
                .coerceAtLeast(48)
            val views = RemoteViews(context.packageName, R.layout.link_widget)
            views.setImageViewBitmap(
                R.id.link_widget_background,
                LinkWidgetRenderer.oval(config.color, widthDp, heightDp),
            )
            views.setTextViewText(R.id.link_widget_label, config.name)
            views.setTextColor(
                R.id.link_widget_label,
                LinkWidgetPolicy.contrastingTextColor(config.color),
            )
            views.setTextViewTextSize(
                R.id.link_widget_label,
                TypedValue.COMPLEX_UNIT_SP,
                LinkWidgetPolicy.textSizeSp(config.name, widthDp, heightDp),
            )
            val description = context.getString(R.string.link_widget_content_description, config.name)
            views.setContentDescription(R.id.link_widget_root, description)
            views.setOnClickPendingIntent(
                R.id.link_widget_root,
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    Intent(Intent.ACTION_VIEW, Uri.parse(config.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
