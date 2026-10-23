package io.github.oceansde012.dotline

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists only so that the user can grant notification access, which MediaSessionManager
 * .getActiveSessions() requires. Notifications themselves are ignored.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        MediaTracker.ensureStarted(this)
        MediaTracker.render(this, forceFull = true)
    }

    override fun onListenerDisconnected() {
        MediaTracker.stop()
        if (MediaTracker.hasAccess(this)) {
            // unbound by the system (e.g. low memory) while access is still granted: ask to come back
            requestRebind(MediaTracker.listenerComponent(this))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
