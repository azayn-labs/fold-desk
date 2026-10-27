package dev.home.folddesk

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Feeds the taskbar tray with the apps that currently have notifications. */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() = publish()
    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    override fun onListenerDisconnected() {
        (application as FoldDeskApp).desk.notificationPackages = emptyList()
    }

    private fun publish() {
        val active = try {
            activeNotifications ?: return
        } catch (e: SecurityException) {
            return
        }
        (application as FoldDeskApp).desk.notificationPackages = active
            .filter { it.isClearable }
            .sortedByDescending { it.postTime }
            .map { it.packageName }
            .distinct()
    }
}
