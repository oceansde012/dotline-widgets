package io.github.oceansde012.dotline

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists so that the user can grant notification access, which MediaSessionManager.getActiveSessions()
 * requires. Notifications are not stored; media notifications of players are only used to complete a
 * weak media session (title, cover) in memory.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        MediaTracker.ensureStarted(this)
        try {
            activeNotifications?.forEach { MediaTracker.onNotification(this, it) }
        } catch (e: Exception) {
            android.util.Log.w("NdListener", "activeNotifications", e)
        }
        MediaTracker.render(this, forceFull = true)
        Ticker.tick(this)
    }

    override fun onListenerDisconnected() {
        MediaTracker.stop()
        if (MediaTracker.hasAccess(this)) {
            // unbound by the system (e.g. low memory) while access is still granted: ask to come back
            requestRebind(MediaTracker.listenerComponent(this))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = MediaTracker.onNotification(this, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = MediaTracker.onNotification(this, sbn, removed = true)
}
