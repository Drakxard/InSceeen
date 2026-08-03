package com.inscreen.mic

import android.graphics.Color
import java.text.Normalizer

internal object WidgetCardRenderer {
    private const val DEFAULT_COLOR = "#45ff1a"

    data class Presentation(val label: String, val color: Int)

    fun present(head: AprioriStore.QueueHead?): Presentation = Presentation(
        label = head?.let { acronym(it.name) } ?: "Sin materias",
        color = head?.let { parseColor(it.color) } ?: Color.WHITE,
    )

    private fun acronym(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return "?"
        val plain = Normalizer.normalize(clean, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val allWords = plain.split(" ")
        val stopWords = setOf("de", "del", "la", "las", "el", "los", "y", "e", "en")
        val relevant = allWords.filterNot { it.lowercase() in stopWords }.ifEmpty { allWords }
        return if (relevant.size == 1) relevant.first().take(3).uppercase()
        else relevant.take(3).joinToString("") { it.take(1) }.uppercase()
    }

    private fun parseColor(raw: String?): Int = runCatching {
        Color.parseColor(raw ?: DEFAULT_COLOR)
    }.getOrDefault(Color.rgb(69, 255, 26))
}
