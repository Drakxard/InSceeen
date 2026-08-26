package com.inscreen.mic

import java.net.URI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object LinkWidgetPolicy {
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            if ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
            ) {
                uri.toASCIIString()
            } else {
                null
            }
        }.getOrNull()
    }

    fun parseHexColor(raw: String): Int? {
        val value = raw.trim().removePrefix("#")
        if (!value.matches(HEX_COLOR)) return null
        return (0xFF000000L or value.toLong(16)).toInt()
    }

    fun formatHexColor(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    fun contrastingTextColor(background: Int): Int {
        val red = linear((background shr 16) and 0xFF)
        val green = linear((background shr 8) and 0xFF)
        val blue = linear(background and 0xFF)
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        val blackContrast = (luminance + 0.05) / 0.05
        val whiteContrast = 1.05 / (luminance + 0.05)
        return if (blackContrast >= whiteContrast) BLACK else WHITE
    }

    fun textSizeSp(name: String, widthDp: Int, heightDp: Int): Float {
        val byHeight = heightDp * 0.42f
        val estimatedCharacters = max(name.trim().length, 2)
        val byWidth = widthDp / (estimatedCharacters * 0.56f)
        return min(byHeight, byWidth).coerceIn(12f, 40f)
    }

    private fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val HEX_COLOR = Regex("[0-9A-Fa-f]{6}")
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1
}
