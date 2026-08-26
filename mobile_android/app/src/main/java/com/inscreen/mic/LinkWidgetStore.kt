package com.inscreen.mic

import android.content.Context

internal data class LinkWidgetConfig(
    val name: String,
    val color: Int,
    val url: String,
)

internal object LinkWidgetStore {
    private const val PREFERENCES = "link_widget_preferences"

    fun save(context: Context, appWidgetId: Int, config: LinkWidgetConfig) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(key(appWidgetId, "name"), config.name)
            .putInt(key(appWidgetId, "color"), config.color)
            .putString(key(appWidgetId, "url"), config.url)
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): LinkWidgetConfig? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val name = preferences.getString(key(appWidgetId, "name"), null)?.takeIf { it.isNotBlank() }
            ?: return null
        val url = preferences.getString(key(appWidgetId, "url"), null)?.takeIf { it.isNotBlank() }
            ?: return null
        return LinkWidgetConfig(
            name = name,
            color = preferences.getInt(key(appWidgetId, "color"), DEFAULT_COLOR),
            url = url,
        )
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(key(appWidgetId, "name"))
            .remove(key(appWidgetId, "color"))
            .remove(key(appWidgetId, "url"))
            .apply()
    }

    private fun key(appWidgetId: Int, field: String) = "${appWidgetId}_$field"

    const val DEFAULT_COLOR: Int = -0x571100
}
