package com.pullnotify.app

import android.app.Notification
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/** Filters ongoing/system noise and suppresses repeated updates to the same notification key. */
object NotificationFilter {
    private val lastSeen = ConcurrentHashMap<String, Long>()
    fun shouldHandle(sbn: StatusBarNotification): Boolean {
        if (sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return false
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        val now = SystemClock.elapsedRealtime()
        val key = sbn.key
        val previous = lastSeen.put(key, now)
        if (previous != null && now - previous < 1800L) return false
        if (lastSeen.size > 256) {
            val cutoff = now - 60_000L
            lastSeen.entries.removeIf { it.value < cutoff }
        }
        return true
    }
}
