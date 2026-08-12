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

    internal fun importSubjects(context: Context, incoming: JSONArray): Int {
        val updated = importSubjects(load(context), incoming) ?: return 0
        AprioriUpdates.publish(context, save(context, updated.first))
        return updated.second
    }

    internal fun importSubjects(raw: String, incoming: JSONArray): Pair<String, Int>? {
        if (incoming.length() !in 1..100) return null
        val state = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val subjects = state.optJSONArray("subjects") ?: return null
        val ring = state.optJSONArray("ring") ?: return null
        val known = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it)?.optString("id")?.trim()?.takeIf(String::isNotEmpty) }.toMutableSet()
        val imported = linkedSetOf<String>()
        var count = 0
        for (index in 0 until incoming.length()) {
            val source = incoming.optJSONObject(index) ?: return null
            val id = source.optString("id").trim()
            val name = source.optString("name").trim()
            val color = source.optString("color").trim()
            if (id.isBlank() || name.isBlank() || color.isBlank() || !imported.add(id)) return null
            val existing = (0 until subjects.length()).asSequence().mapNotNull(subjects::optJSONObject).firstOrNull { it.optString("id") == id }
            if (existing != null) {
                existing.put("name", name)
                existing.put("color", color)
            } else {
                subjects.put(JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("color", color)
                    put("baseWeight", 1)
                    put("evaluations", JSONArray())
                })
                known.add(id)
                ring.put(id)
            }
            count += 1
        }
        val normalized = runCatching { validateAndNormalize(state.toString()) }.getOrNull() ?: return null
        return normalized to count
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
            val modules = subject.optJSONArray("modules") ?: JSONArray().also { migrated ->
                subject.optJSONObject("module")?.let(migrated::put)
                subject.put("modules", migrated)
            }
            val moduleIds = mutableSetOf<String>()
            for (moduleIndex in 0 until modules.length()) {
                val module = modules.optJSONObject(moduleIndex) ?: error("Módulo inválido")
                require(module.optString("id").isNotBlank() && module.optString("nombre").isNotBlank() &&
                    module.optString("entry").matches(Regex("modules/[a-z0-9][a-z0-9-]{0,79}/index\\.html")))
                require(moduleIds.add(module.optString("id")))
            }
            subject.remove("module")
            subject.remove("moduleId")
            subject.remove("moduleName")
            subject.remove("moduleEntry")
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
                remove("modules")
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

    internal fun addModule(context: Context, subjectId: String, module: ModuleCatalog.Module): Boolean {
        val updated = addModule(load(context), subjectId, module) ?: return false
        AprioriUpdates.publish(context, save(context, updated))
        return true
    }

    internal fun addModule(raw: String, subjectId: String, module: ModuleCatalog.Module): String? =
        updateModules(raw, subjectId) { modules ->
            if ((0 until modules.length()).none { modules.optJSONObject(it)?.optString("id") == module.id }) {
                modules.put(moduleJson(module))
            }
        }

    internal fun removeModule(context: Context, subjectId: String, moduleId: String): Boolean {
        val updated = removeModule(load(context), subjectId, moduleId) ?: return false
        AprioriUpdates.publish(context, save(context, updated))
        return true
    }

    internal fun removeModule(raw: String, subjectId: String, moduleId: String): String? =
        updateModules(raw, subjectId) { modules ->
            for (index in modules.length() - 1 downTo 0) {
                if (modules.optJSONObject(index)?.optString("id") == moduleId) modules.remove(index)
            }
        }

    private fun updateModules(raw: String, subjectId: String, update: (JSONArray) -> Unit): String? {
        val state = JSONObject(raw)
        val subjects = state.optJSONArray("subjects") ?: return null
        val subject = (0 until subjects.length())
            .asSequence()
            .mapNotNull(subjects::optJSONObject)
            .firstOrNull { it.optString("id") == subjectId }
            ?: return null
        val modules = subject.optJSONArray("modules") ?: JSONArray().also { subject.put("modules", it) }
        update(modules)
        return validateAndNormalize(state.toString())
    }

    private fun moduleJson(module: ModuleCatalog.Module) = JSONObject().apply {
        put("id", module.id); put("nombre", module.name); put("entry", module.entry)
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
