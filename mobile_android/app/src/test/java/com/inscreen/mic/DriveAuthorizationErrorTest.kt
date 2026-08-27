package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorizationErrorTest {
    @Test
    fun developerErrorExplainsOAuthConfigurationAndIncludesSigningFingerprint() {
        val message = DriveAuthorizationError.message(10, "AA:BB:CC")

        assertTrue(message.contains("credencial OAuth"))
        assertTrue(message.contains("com.inscreen.mic"))
        assertTrue(message.contains("AA:BB:CC"))
        assertTrue(message.contains("código 10"))
    }

    @Test
    fun cancellationIsNotReportedAsAnUnknownAuthorizationFailure() {
        assertEquals(
            "La autorización de Google fue cancelada. (código 12501)",
            DriveAuthorizationError.message(12501),
        )
    }

    @Test
    fun unknownGoogleStatusKeepsItsDiagnosticCode() {
        assertEquals(
            "Google no pudo autorizar el acceso a Drive. (código 999)",
            DriveAuthorizationError.message(999),
        )
    }
}
