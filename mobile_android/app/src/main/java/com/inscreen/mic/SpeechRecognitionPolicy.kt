package com.inscreen.mic

internal object SpeechRecognitionPolicy {
    enum class Decision { PERMISSION_REQUIRED, ON_DEVICE, CONSENT_REQUIRED, SYSTEM, UNAVAILABLE }

    fun decide(
        permissionGranted: Boolean,
        onDeviceAvailable: Boolean,
        systemAvailable: Boolean,
        allowSystemRecognizer: Boolean,
    ): Decision = when {
        !permissionGranted -> Decision.PERMISSION_REQUIRED
        onDeviceAvailable -> Decision.ON_DEVICE
        systemAvailable && !allowSystemRecognizer -> Decision.CONSENT_REQUIRED
        systemAvailable -> Decision.SYSTEM
        else -> Decision.UNAVAILABLE
    }
}
