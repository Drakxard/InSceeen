package com.inscreen.mic

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class SynthesisWeeksService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return object : RemoteViewsFactory {
            private var weeks = emptyList<Int>()
            override fun onCreate() { onDataSetChanged() }
            override fun onDataSetChanged() { weeks = SynthesisWidgetStore.load(this@SynthesisWeeksService, id)?.weeks.orEmpty() }
            override fun onDestroy() {}
            override fun getCount() = weeks.size
            override fun getViewAt(position: Int): RemoteViews? {
                val week = weeks.getOrNull(position) ?: return null
                return RemoteViews(packageName, R.layout.synthesis_week_row).apply {
                    setTextViewText(R.id.synthesis_week_label, "Semana $week")
                    setOnClickFillInIntent(R.id.synthesis_week_label, Intent().putExtra("weekNumber", week))
                }
            }
            override fun getLoadingView(): RemoteViews? = null
            override fun getViewTypeCount() = 1
            override fun getItemId(position: Int) = weeks.getOrNull(position)?.toLong() ?: -1L
            override fun hasStableIds() = true
        }
    }
}
