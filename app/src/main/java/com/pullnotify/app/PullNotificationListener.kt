package com.pullnotify.app

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class PullNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        if (sbn.packageName == packageName) return
        val prefs = getSharedPreferences("pullnotify", MODE_PRIVATE)
        if (prefs.getBoolean("paused", false)) return
        val selected = prefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        if (!selected.contains(sbn.packageName)) return
        if (!NotificationFilter.shouldHandle(sbn)) return

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName.substringAfterLast('.') }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sbn.postTime))
        val history = try { JSONArray(prefs.getString("recent_events", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val updated = JSONArray()
        updated.put(JSONObject().put("app", appLabel).put("time", time))
        for (i in 0 until minOf(history.length(), 9)) updated.put(history.optJSONObject(i) ?: continue)
        prefs.edit().putString("recent_events", updated.toString()).apply()

        if (prefs.getBoolean("vibrate_enabled", false)) {
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") vibrator.vibrate(45)
            } catch (_: Exception) {}
        }
        if (prefs.getBoolean("sound_enabled", false)) {
            try {\n                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)\n                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100)\n                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 180L)\n            } catch (_: Exception) {}
        }
        if (prefs.getBoolean("cues_enabled", true) && Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(this)) {
            CueOverlay.show(this, appLabel, "New notification · $time")
        }
        sendBroadcast(android.content.Intent("com.pullnotify.app.ACTIVITY_UPDATED").setPackage(packageName))
    }
}
