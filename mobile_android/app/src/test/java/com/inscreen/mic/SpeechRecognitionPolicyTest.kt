package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRecognitionPolicyTest {
    @Test fun requiresPermissionBeforeChoosingAnEngine() {
        assertEquals(
            SpeechRecognitionPolicy.Decision.PERMISSION_REQUIRED,
            SpeechRecognitionPolicy.decide(false, true, true, true),
        )
    }

    @Test fun alwaysPrefersOnDeviceRecognition() {
        assertEquals(
            SpeechRecognitionPolicy.Decision.ON_DEVICE,
            SpeechRecognitionPolicy.decide(true, true, true, false),
        )
    }

    @Test fun requiresConsentBeforeUsingSystemRecognizer() {
        assertEquals(
            SpeechRecognitionPolicy.Decision.CONSENT_REQUIRED,
            SpeechRecognitionPolicy.decide(true, false, true, false),
        )
        assertEquals(
            SpeechRecognitionPolicy.Decision.SYSTEM,
            SpeechRecognitionPolicy.decide(true, false, true, true),
        )
    }

    @Test fun reportsDevicesWithoutAnyRecognizer() {
        assertEquals(
            SpeechRecognitionPolicy.Decision.UNAVAILABLE,
            SpeechRecognitionPolicy.decide(true, false, false, true),
        )
    }
}
