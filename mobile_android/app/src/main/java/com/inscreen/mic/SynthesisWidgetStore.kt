package com.inscreen.mic

import android.content.Context
import org.json.JSONObject

internal data class SynthesisWidgetConfig(val subjectId: String, val subjectName: String, val weeks: List<Int> = emptyList(), val status: String = "")

internal object SynthesisWidgetStore {
    private fun preferences(context: Context) = context.getSharedPreferences("synthesis_widgets", Context.MODE_PRIVATE)
    fun load(context: Context, id: Int): SynthesisWidgetConfig? = runCatching {
        val value = JSONObject(preferences(context).getString(id.toString(), null) ?: return null)
        val weeks = value.optJSONArray("weeks")
        SynthesisWidgetConfig(value.getString("subjectId"), value.getString("subjectName"),
            (0 until (weeks?.length() ?: 0)).map { weeks!!.getInt(it) }.filter { it in 0..9999 }.distinct().sortedDescending(),
            value.optString("status"))
    }.getOrNull()

    fun save(context: Context, id: Int, config: SynthesisWidgetConfig) {
        preferences(context).edit().putString(id.toString(), JSONObject()
            .put("subjectId", config.subjectId).put("subjectName", config.subjectName)
            .put("weeks", org.json.JSONArray(config.weeks)).put("status", config.status).toString()).apply()
    }
    fun delete(context: Context, id: Int) { preferences(context).edit().remove(id.toString()).apply() }
}
