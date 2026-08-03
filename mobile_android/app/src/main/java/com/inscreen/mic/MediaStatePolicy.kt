package com.inscreen.mic

internal object MediaStatePolicy {
    data class Decision(
        val ok: Boolean,
        val controllerIndex: Int = -1,
        val command: String = "none",
        val error: String = "",
    )

    fun aggregate(states: List<String>): String = when {
        states.contains("playing") -> "playing"
        states.contains("paused") -> "paused"
        else -> "none"
    }

    fun decide(action: String, states: List<String>): Decision {
        val playingIndex = states.indexOf("playing")
        val pausedIndex = states.indexOf("paused")
        return when (action) {
            "ensure_paused" -> when {
                playingIndex >= 0 -> Decision(true, playingIndex, "pause")
                pausedIndex >= 0 -> Decision(true)
                else -> Decision(false, error = "No hay música reproduciéndose en el celular.")
            }
            "toggle" -> when {
                playingIndex >= 0 -> Decision(true, playingIndex, "pause")
                pausedIndex >= 0 -> Decision(true, pausedIndex, "play")
                else -> Decision(false, error = "No hay una sesión multimedia pausada o reproduciéndose.")
            }
            else -> Decision(false, error = "Comando multimedia desconocido.")
        }
    }
}
