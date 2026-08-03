package com.inscreen.mic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AprioriStore {
    data class QueueHead(val name: String, val color: String, val ticketCount: Int = 1)

    private const val PREFS = "apriori_private"
    private const val KEY_STATE = "state"
    const val EMPTY_STATE = """{"version":1,"subjects":[],"ring":[],"weightSignature":"","dockSplitIndex":0,"dockRows":[]}"""

    @Synchronized
    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATE, EMPTY_STATE) ?: EMPTY_STATE

    @Synchronized
    fun save(context: Context, raw: String): String {
        val normalized = validateAndNormalize(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, normalized).apply()
        return normalized
    }

    internal fun validateAndNormalize(raw: String): String {
        val parsed = JSONObject(raw)
        require(parsed.optInt("version") == 1)
        val subjects = parsed.optJSONArray("subjects") ?: error("Faltan las materias")
        val ring = parsed.optJSONArray("ring") ?: error("Falta la cola")
        val subjectIds = linkedSetOf<String>()
        for (index in 0 until subjects.length()) {
            val subject = subjects.optJSONObject(index) ?: error("Materia inválida")
            val id = subject.optString("id").trim()
            require(id.isNotEmpty() && subject.optString("name").trim().isNotEmpty())
            require(subjectIds.add(id))
        }
        for (index in 0 until ring.length()) {
            require(subjectIds.contains(ring.optString(index)))
        }
        parsed.optJSONArray("dockRows")?.let { rows -> validateDockRows(rows, subjectIds) }
        return parsed.toString()
    }

    private fun validateDockRows(rows: JSONArray, subjectIds: Set<String>) {
        val placed = mutableSetOf<String>()
        for (rowIndex in 0 until rows.length()) {
            val row = rows.optJSONArray(rowIndex) ?: error("Fila inválida")
            require(row.length() <= 4)
            for (itemIndex in 0 until row.length()) {
                val id = row.optString(itemIndex)
                require(subjectIds.contains(id) && placed.add(id))
            }
        }
    }

    fun activeSubject(raw: String): String {
        return try {
            val state = JSONObject(raw)
            val head = state.optJSONArray("ring")?.optString(0).orEmpty()
            val subjects = state.optJSONArray("subjects") ?: return ""
            (0 until subjects.length())
                .asSequence()
                .mapNotNull { subjects.optJSONObject(it) }
                .firstOrNull { it.optString("id") == head }
                ?.optString("name")
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun activeSubject(context: Context): String = activeSubject(load(context))

    fun queueHead(raw: String): QueueHead? {
        return try {
            val state = JSONObject(raw)
            val head = state.optJSONArray("ring")?.optString(0).orEmpty()
            if (head.isEmpty()) return null
            val subjects = state.optJSONArray("subjects") ?: return null
            val subject = (0 until subjects.length())
                .asSequence()
                .mapNotNull { subjects.optJSONObject(it) }
                .firstOrNull { it.optString("id") == head }
                ?: return null
            val ticketCount = (0 until (state.optJSONArray("ring")?.length() ?: 0))
                .count { state.optJSONArray("ring")?.optString(it) == head }
            QueueHead(
                subject.optString("name").trim(),
                subject.optString("color", "#45ff1a").trim(),
                ticketCount,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun queueHead(context: Context): QueueHead? = queueHead(load(context))

    fun nextQueueHead(raw: String): QueueHead? = queueHead(consumeHead(raw))

    fun nextQueueHead(context: Context): QueueHead? = nextQueueHead(load(context))

    @Synchronized
    fun consumeHead(context: Context): String {
        val current = load(context)
        val consumed = consumeHead(current)
        if (consumed == current) return current
        return save(context, consumed)
    }

    internal fun consumeHead(raw: String): String {
        val state = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return raw
        }
        val ring = state.optJSONArray("ring") ?: return raw
        if (ring.length() == 0) return raw
        val consumed = ring.optString(0)
        ring.remove(0)
        ring.put(consumed)
        return state.toString()
    }
}
