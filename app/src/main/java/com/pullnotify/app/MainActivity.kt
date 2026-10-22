package com.pullnotify.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pullnotify", MODE_PRIVATE) }
    private val ink = 0xFF20202A.toInt()
    private val muted = 0xFF6C6A76.toInt()
    private val accent = 0xFF7557D9.toInt()
    private val canvas = 0xFFF7F6FC.toInt()
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var recentText: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::root.isInitialized) refreshStatus() }

    private fun render() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(canvas) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(canvas)
        }
        scroll.addView(root)
        setContentView(scroll)

        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply {
            text = "✦"; textSize = 29f; gravity = Gravity.CENTER; setTextColor(-1)
            background = rounded(accent, 18); layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        brand.addView(logo)
        val brandWords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        addText(brandWords, "PullNotify", 27f, ink, true)
        addText(brandWords, "Your notifications, your vibe.", 13f, muted, false)
        brand.addView(brandWords)
        root.addView(brand)
        addText(root, "Make every ping feel personal.", 22f, ink, true, 18)
        addText(root, "PullNotify detects notifications from apps you select and can show a small animated cue. Notification text is never saved.", 14f, muted, false)

        val statusCard = card()
        addText(statusCard, "QUICK STATUS", 12f, accent, true)
        statusText = addText(statusCard, "Checking permissions…", 14f, ink, true)
        root.addView(statusCard)

        val actions = card()
        addText(actions, "GET STARTED", 12f, accent, true)
        actionButton(actions, "Enable notification access") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        actionButton(actions, "Allow animated pop-up cue") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Pop-up permission is already allowed", Toast.LENGTH_SHORT).show()
            }
        }
        actionButton(actions, "Preview animated cue") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow pop-up permission first", Toast.LENGTH_LONG).show()
            } else CueOverlay.show(this, "PullNotify", "Your preview is working ✨")
        }
        root.addView(actions)

        val appCard = card()
        addText(appCard, "YOUR APPS", 12f, accent, true)
        addText(appCard, "Choose which apps can trigger a cue.", 13f, muted, false)
        val search = EditText(this).apply {
            hint = "Search installed apps"; textSize = 14f; singleLine = true
            setPadding(dp(12), dp(4), dp(12), dp(4)); background = rounded(0xFFFFFFFF.toInt(), 12)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); renderAppChecks(appList) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        appCard.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        appCard.addView(appList, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val allRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actionButton(allRow, "Select all") { saveSelection(apps.map { it.first }.toSet()); renderAppChecks(appList) }
        actionButton(allRow, "Clear") { saveSelection(emptySet()); renderAppChecks(appList) }
        appCard.addView(allRow)
        root.addView(appCard)
        addText(root, "RECENT ACTIVITY", 12f, accent, true, 18)
        val recentCard = card()
        recentText = addText(recentCard, "No selected-app notifications yet. Send a notification from a selected app to test it.", 14f, muted, false)
        root.addView(recentCard)

        val settingsCard = card()
        addText(settingsCard, "CUE SETTINGS", 12f, accent, true)
        addToggle(settingsCard, "Animated cue pop-ups", "cues_enabled", true)
        addToggle(settingsCard, "Vibrate on selected notifications", "vibrate_enabled", false)
        addToggle(settingsCard, "Play cue sound", "sound_enabled", false)
        addToggle(settingsCard, "Pause all processing", "paused", false, invert = true)
        root.addView(settingsCard)
        addText(root, "PRIVACY BY DESIGN", 12f, accent, true, 18)
        addText(root, "Only the app name and time are kept in a short on-device activity list. Notification message content is not read, stored, or sent to a server. Android may restrict pop-ups in the background.", 12f, muted, false)
        refreshStatus()
    }

    private val appList by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private val apps by lazy {
        try {
            packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName to packageManager.getApplicationLabel(it).toString() }
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    private fun renderAppChecks(target: LinearLayout) {
        target.removeAllViews()
        val selected = prefs.getStringSet("selected_apps", emptySet())?.toSet() ?: emptySet()
        val visible = apps.filter { it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }.take(100)
        if (visible.isEmpty()) addText(target, "No matching apps.", 13f, muted, false)
        visible.forEach { (pkg, label) ->
            val row = CheckBox(this).apply {
                text = label; textSize = 14f; setTextColor(ink); isChecked = selected.contains(pkg)
                setPadding(dp(2), dp(3), dp(2), dp(3))
                setOnCheckedChangeListener { _, checked ->
                    val current = (prefs.getStringSet("selected_apps", emptySet()) ?: emptySet()).toMutableSet()
                    if (checked) current.add(pkg) else current.remove(pkg)
                    saveSelection(current)
                }
            }
            target.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }
        if (apps.size > 100) addText(target, "Showing first 100 matches. Search to find more.", 12f, muted, false)
    }

    private fun saveSelection(values: Set<String>) {
        prefs.edit().putStringSet("selected_apps", values.toSet()).apply()
    }

    private fun addToggle(parent: LinearLayout, label: String, key: String, default: Boolean, invert: Boolean = false) {
        val check = CheckBox(this).apply {
            text = label; textSize = 14f; setTextColor(ink)
            isChecked = if (invert) !prefs.getBoolean(key, default) else prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, if (invert) !checked else checked).apply() }
        }
        parent.addView(check, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val listenerEnabled = try {
            val enabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            enabled.contains(packageName)
        } catch (_: Exception) { false }
        val overlayEnabled = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        statusText.text = "Notification access: " + if (listenerEnabled) "ON" else "OFF" +
            "\nAnimated cue permission: " + if (overlayEnabled) "ON" else "OFF" +
            "\nSelected apps: " + (prefs.getStringSet("selected_apps", emptySet())?.size ?: 0)
        val history = try { JSONArray(prefs.getString("recent_events", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val lines = mutableListOf<String>()
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            lines.add("• ${item.optString("app")}  ·  ${item.optString("time")}")
        }
        recentText.text = if (lines.isEmpty()) "No selected-app notifications yet. Send a notification from a selected app to test it." else lines.joinToString("\n")
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(0xFFFFFFFF.toInt(), 20)
        elevation = dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10); lp.bottomMargin = dp(4); layoutParams = lp
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean, top: Int = 2): TextView {
        val text = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(top), 0, dp(7))
        }
        parent.addView(text)
        return text
    }

    private fun actionButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 14f; setTextColor(-1)
            background = rounded(accent, 14); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(5); bottomMargin = dp(5) })
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
