package com.voicecontrol.app.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationService : NotificationListenerService() {

    companion object {
        private val notifications = mutableListOf<StatusBarNotification>()
        private const val MAX = 20

        fun getSummary(): String {
            val list = synchronized(notifications) { notifications.toList() }
            if (list.isEmpty()) return "No notifications."
            val sb = StringBuilder("Notifications:\n")
            list.forEach { sbn ->
                val extras = sbn.notification?.extras
                val title = extras?.getString(android.app.Notification.EXTRA_TITLE) ?: ""
                val text = extras?.getString(android.app.Notification.EXTRA_TEXT) ?: ""
                val app = sbn.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                sb.appendLine("• $app: $title — $text")
            }
            return sb.toString()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        companion.addNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        companion.removeNotification(sbn)
    }

    private fun addNotification(sbn: StatusBarNotification) {
        synchronized(notifications) {
            notifications.add(sbn)
            while (notifications.size > MAX) notifications.removeAt(0)
        }
    }

    private fun removeNotification(sbn: StatusBarNotification) {
        synchronized(notifications) {
            notifications.remove(sbn)
        }
    }
}
