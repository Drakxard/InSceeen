package com.inscreen.mic

import android.service.notification.NotificationListenerService

/**
 * Grants InScreen access to Android media sessions. Notification contents are
 * deliberately ignored; the connection service only uses MediaSessionManager.
 */
class MediaAccessService : NotificationListenerService()
