package dev.home.folddesk

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Feeds the taskbar tray and the cover screen with the current notifications. */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        current = this
        publish()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    override fun onListenerDisconnected() {
        if (current == this) current = null
        val desk = (application as FoldDeskApp).desk
        desk.notificationPackages = emptyList()
        desk.coverNotifications = emptyList()
    }

    private fun publish() {
        val active = try {
            activeNotifications ?: return
        } catch (e: SecurityException) {
            return
        }
        val desk = (application as FoldDeskApp).desk
        desk.notificationPackages = active
            .filter { it.isClearable }
            .sortedByDescending { it.postTime }
            .map { it.packageName }
            .distinct()
        desk.coverNotifications = CoverNotification.from(active.toList())
        desk.mergeNews(News.stories(this, active.toList()))
    }

    companion object {
        /** The connected listener, for dismissing from the cover screen. */
        private var current: NotificationWatcher? = null

        fun dismiss(keys: List<String>) {
            val watcher = current ?: return
            try {
                watcher.cancelNotifications(keys.toTypedArray())
            } catch (e: SecurityException) {
                // Access was revoked; nothing to dismiss.
            }
        }
    }
}
