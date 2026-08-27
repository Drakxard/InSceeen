package com.inscreen.mic

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

class LinkWidgetResolveActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val config = LinkWidgetStore.load(this, widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || config?.mode != LinkWidgetStore.MODE_SYNCED) {
            finish()
            return
        }
        val credentials = ProviderCredentialStore(this).load()
        if (credentials == null) {
            fail("Vinculá nuevamente el proveedor de InScreen.")
            return
        }
        Thread {
            try {
                val resolved = ProviderClient(credentials.baseUrl, credentials.token)
                    .resolveWidgetTarget(config.subjectId, config.targetKind)
                val normalized = LinkWidgetPolicy.normalizeUrl(resolved.url)
                    ?: throw ProviderWidgetException("invalid_response", true)
                LinkWidgetStore.save(this, widgetId, config.copy(cachedUrl = normalized))
                runOnUiThread { open(normalized) }
            } catch (error: ProviderWidgetException) {
                val fallback = if (error.transient) LinkWidgetPolicy.normalizeUrl(config.cachedUrl) else null
                runOnUiThread {
                    if (fallback != null) open(fallback) else fail(message(error.code))
                }
            }
        }.start()
    }

    private fun open(url: String) {
        val config = LinkWidgetStore.load(this, widgetId)
        if (config?.targetKind == LinkWidgetStore.TARGET_MATERIALS) {
            val folderId = DriveLinkPolicy.folderId(url)
            if (folderId == null) {
                fail("El enlace de Material no apunta a una carpeta de Drive.")
                return
            }
            val changed = config.cachedFolderId.isNotBlank() && config.cachedFolderId != folderId
            if (changed && !LinkWidgetStore.isFolderUsedByAnotherWidget(this, config.cachedFolderId, widgetId)) {
                DriveCache(this).removeRoot(config.cachedFolderId)
            }
            val launch = {
                LinkWidgetStore.save(this, widgetId, config.copy(cachedUrl = url, cachedFolderId = folderId))
                startActivity(DriveExplorerActivity.intent(this, folderId, config.subjectName.ifBlank { config.name }))
                finish()
            }
            if (changed) {
                val revoke = RevokeAccessRequest.builder().setScopes(listOf(Scope(DRIVE_READONLY))).build()
                Identity.getAuthorizationClient(this).revokeAccess(revoke).addOnCompleteListener { launch() }
            } else launch()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_LONG).show() }
        finish()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun message(code: String) = when (code) {
        "target_unavailable" -> "El destino todavía no está disponible."
        "unauthorized", "provider_not_configured" -> "Vinculá nuevamente el proveedor de InScreen."
        else -> "No se pudo actualizar el acceso de InScreen."
    }

    companion object {
        private const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    }
}
