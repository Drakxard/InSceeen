package com.inscreen.mic

import android.content.Context

internal data class LinkWidgetConfig(
    val name: String,
    val color: Int,
    val url: String,
    val mode: String = LinkWidgetStore.MODE_MANUAL,
    val subjectId: String = "",
    val subjectName: String = "",
    val targetKind: String = "",
    val cachedUrl: String = "",
    val cachedFolderId: String = "",
)

internal object LinkWidgetStore {
    private const val PREFERENCES = "link_widget_preferences"

    fun save(context: Context, appWidgetId: Int, config: LinkWidgetConfig) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(key(appWidgetId, "name"), config.name)
            .putInt(key(appWidgetId, "color"), config.color)
            .putString(key(appWidgetId, "url"), config.url)
            .putString(key(appWidgetId, "mode"), config.mode)
            .putString(key(appWidgetId, "subject_id"), config.subjectId)
            .putString(key(appWidgetId, "subject_name"), config.subjectName)
            .putString(key(appWidgetId, "target_kind"), config.targetKind)
            .putString(key(appWidgetId, "cached_url"), config.cachedUrl)
            .putString(key(appWidgetId, "cached_folder_id"), config.cachedFolderId)
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): LinkWidgetConfig? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val name = preferences.getString(key(appWidgetId, "name"), null)?.takeIf { it.isNotBlank() }
            ?: return null
        val mode = preferences.getString(key(appWidgetId, "mode"), MODE_MANUAL)
            ?.takeIf { it == MODE_MANUAL || it == MODE_SYNCED } ?: MODE_MANUAL
        val url = preferences.getString(key(appWidgetId, "url"), "").orEmpty()
        if (mode == MODE_MANUAL && url.isBlank()) return null
        return LinkWidgetConfig(
            name = name,
            color = preferences.getInt(key(appWidgetId, "color"), DEFAULT_COLOR),
            url = url,
            mode = mode,
            subjectId = preferences.getString(key(appWidgetId, "subject_id"), "").orEmpty(),
            subjectName = preferences.getString(key(appWidgetId, "subject_name"), "").orEmpty(),
            targetKind = preferences.getString(key(appWidgetId, "target_kind"), "").orEmpty(),
            cachedUrl = preferences.getString(key(appWidgetId, "cached_url"), "").orEmpty(),
            cachedFolderId = preferences.getString(key(appWidgetId, "cached_folder_id"), "").orEmpty(),
        )
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(key(appWidgetId, "name"))
            .remove(key(appWidgetId, "color"))
            .remove(key(appWidgetId, "url"))
            .remove(key(appWidgetId, "mode"))
            .remove(key(appWidgetId, "subject_id"))
            .remove(key(appWidgetId, "subject_name"))
            .remove(key(appWidgetId, "target_kind"))
            .remove(key(appWidgetId, "cached_url"))
            .remove(key(appWidgetId, "cached_folder_id"))
            .apply()
    }

    fun isFolderUsedByAnotherWidget(context: Context, folderId: String, excludedWidgetId: Int): Boolean {
        if (folderId.isBlank()) return false
        val suffix = "_cached_folder_id"
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.any { (name, value) ->
            name.endsWith(suffix) && name != key(excludedWidgetId, "cached_folder_id") && value == folderId
        }
    }

    private fun key(appWidgetId: Int, field: String) = "${appWidgetId}_$field"

    const val DEFAULT_COLOR: Int = -0x571100
    const val MODE_MANUAL = "manual"
    const val MODE_SYNCED = "synced"
    const val TARGET_NOTEBOOK_LM = "notebooklm"
    const val TARGET_MATERIALS = "materials"
}
