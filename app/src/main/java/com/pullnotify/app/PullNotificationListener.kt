package com.pullnotify.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PullNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val prefs = getSharedPreferences("pullnotify", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return
        val selected = prefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        if (selected.contains(sbn.packageName)) {
            // Deliberately process only the package name; notification text is not stored.
            prefs.edit().putLong("last_selected_notification_at", System.currentTimeMillis()).apply()
        }
    }
}