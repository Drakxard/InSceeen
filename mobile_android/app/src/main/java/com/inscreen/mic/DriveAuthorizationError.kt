package com.inscreen.mic

internal object DriveAuthorizationError {
    fun message(statusCode: Int?, signingSha1: String? = null): String {
        val detail = when (statusCode) {
            4 -> "Google necesita que vuelvas a iniciar sesión."
            7 -> "Google no pudo conectarse a la red."
            8 -> "Google tuvo un error interno. Intenta nuevamente."
            10 -> buildString {
                append("La credencial OAuth de InScreen no coincide con este APK. ")
                append("Registra com.inscreen.mic y su firma SHA-1 en Google Cloud")
                if (!signingSha1.isNullOrBlank()) append(": $signingSha1") else append(".")
            }
            13 -> "Google rechazó la solicitud de autorización."
            16, 12501 -> "La autorización de Google fue cancelada."
            17 -> "Los servicios de Google no están disponibles para esta cuenta o dispositivo."
            12500 -> "Google no pudo iniciar sesión. Revisa la cuenta y la configuración OAuth."
            12502 -> "Ya hay una autorización de Google en curso."
            else -> "Google no pudo autorizar el acceso a Drive."
        }
        return if (statusCode == null) detail else "$detail (código $statusCode)"
    }
}
