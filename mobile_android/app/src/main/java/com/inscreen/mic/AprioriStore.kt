package com.inscreen.mic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AprioriStore {
    data class QueueHead(val name: String, val color: String, val ticketCount: Int = 1)

    private const val PREFS = "apriori_private"
    private const val KEY_STATE = "state"
    const val EMPTY_STATE = """{"version":3,"subjects":[],"ring":[],"weightSignature":"","dockSplitIndex":0,"dockRows":[],"settings":{"cycleSize":20,"urgencyK":14}}"""

    @Synchronized
    fun load(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY_STATE, EMPTY_STATE) ?: EMPTY_STATE
        val normalized = runCatching { validateAndNormalize(raw) }.getOrNull()
        if (normalized != null) return normalized
        preferences.edit().putString(KEY_STATE, EMPTY_STATE).commit()
        return EMPTY_STATE
    }

    @Synchronized
    fun save(context: Context, raw: String): String {
        val normalized = validateAndNormalize(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, normalized).apply()
        return normalized
    }

    internal fun validateAndNormalize(raw: String): String {
        val parsed = JSONObject(raw)
        require(parsed.optInt("version") == 3)
        val subjects = parsed.optJSONArray("subjects") ?: error("Faltan las materias")
        val ring = parsed.optJSONArray("ring") ?: error("Falta la cola")
        val subjectIds = linkedSetOf<String>()
        for (index in 0 until subjects.length()) {
            val subject = subjects.optJSONObject(index) ?: error("Materia inválida")
            val id = subject.optString("id").trim()
            val name = subject.optString("name").trim()
            require(id.isNotEmpty() && name.isNotEmpty())
            require(subjectIds.add(id))
            subject.put("providerSubjectSegment", ProviderSubject.segment(name))
            require(subject.optInt("baseWeight", 1) in 1..100)
            val evaluations = subject.optJSONArray("evaluations") ?: JSONArray()
            for (evaluationIndex in 0 until evaluations.length()) {
                val evaluation = evaluations.optJSONObject(evaluationIndex) ?: error("Evaluación inválida")
                require(evaluation.optString("id").isNotBlank())
                require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(evaluation.optString("date")))
            }
        }
        for (index in 0 until ring.length()) {
            require(subjectIds.contains(ring.optString(index)))
        }
        parsed.optJSONArray("dockRows")?.let { rows -> validateDockRows(rows, subjectIds) }
        val settings = parsed.optJSONObject("settings") ?: error("Faltan los ajustes")
        require(settings.optInt("cycleSize") in 1..100)
        require(settings.optDouble("urgencyK", Double.NaN).let { !it.isNaN() && it in 0.0..100.0 })
        return parsed.toString()
    }

    internal fun clearModuleAssignments(raw: String): String {
        val state = JSONObject(raw)
        val subjects = state.optJSONArray("subjects") ?: return raw
        for (index in 0 until subjects.length()) {
            subjects.optJSONObject(index)?.apply {
                remove("moduleId")
                remove("moduleName")
                remove("moduleEntry")
                remove("module")
            }
        }
        return state.toString()
    }

    private fun validateDockRows(rows: JSONArray, subjectIds: Set<String>) {
        require(rows.length() <= 10)
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

    fun subject(raw: String, id: String): JSONObject? {
        return try {
            val subjects = JSONObject(raw).optJSONArray("subjects") ?: return null
            (0 until subjects.length())
                .asSequence()
                .mapNotNull { subjects.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
        } catch (_: Exception) {
            null
        }
    }

    internal fun assignModule(context: Context, subjectId: String, module: ModuleCatalog.Module?): Boolean {
        val updated = assignModule(load(context), subjectId, module) ?: return false
        AprioriUpdates.publish(context, save(context, updated))
        return true
    }

    internal fun assignModule(raw: String, subjectId: String, module: ModuleCatalog.Module?): String? {
        val state = JSONObject(raw)
        val subjects = state.optJSONArray("subjects") ?: return null
        val subject = (0 until subjects.length())
            .asSequence()
            .mapNotNull(subjects::optJSONObject)
            .firstOrNull { it.optString("id") == subjectId }
            ?: return null
        if (module == null) {
            subject.remove("moduleId")
            subject.remove("moduleName")
            subject.remove("moduleEntry")
            subject.remove("module")
        } else {
            subject.put("module", JSONObject().apply {
                put("id", module.id)
                put("nombre", module.name)
                put("entry", module.entry)
            })
        }
        return validateAndNormalize(state.toString())
    }

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
