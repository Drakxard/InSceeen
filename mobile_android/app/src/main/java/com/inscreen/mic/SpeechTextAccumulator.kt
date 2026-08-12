package com.inscreen.mic

internal class SpeechTextAccumulator {
    private val segments = mutableListOf<String>()
    private var partial = ""

    fun reset() {
        segments.clear()
        partial = ""
    }

    fun updatePartial(value: String): String {
        partial = normalize(value)
        return text()
    }

    fun commit(value: String): String {
        val normalized = normalize(value).ifBlank { partial }
        if (normalized.isNotBlank()) segments += normalized
        partial = ""
        return text()
    }

    fun commitPartial(): String = commit("")

    fun text(): String = (segments + partial.takeIf(String::isNotBlank))
        .filterNotNull()
        .joinToString(" ")
        .trim()

    private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ")
}
