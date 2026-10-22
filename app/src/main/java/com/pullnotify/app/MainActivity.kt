package com.pullnotify.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pullnotify", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()
    private val purple = 0xFF6750A4.toInt()
    private val ink = 0xFF211F26.toInt()
    private val muted = 0xFF625D69.toInt()

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (Build.VERSION.SDK_INT >= 23) window.decorView.post { render() } }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(26), dp(22), dp(28)); setBackgroundColor(0xFFF7F5FA.toInt()) }
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
        addText(root, "PullNotify", 30f, ink, true)
        addText(root, "A tiny character cue for notifications from the apps you choose.", 15f, muted, false)
        addText(root, "SETUP", 12f, purple, true)
        button(root, "1. Enable notification access") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        button(root, "2. Allow display over other apps") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else Toast.makeText(this, "Overlay permission is already allowed", Toast.LENGTH_SHORT).show()
        }
        addText(root, "CHOOSE APPS", 12f, purple, true)
        val apps = try { packageManager.getInstalledApplications(0).filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }.map { it.packageName to packageManager.getApplicationLabel(it).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() } } catch (_: Exception) { emptyList() }
        selected.addAll(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
        if (apps.isEmpty()) addText(root, "No launchable apps found.", 14f, muted, false)
        apps.forEach { (pkg, label) ->
            val cb = CheckBox(this).apply { text = label; textSize = 15f; setTextColor(ink); isChecked = selected.contains(pkg); setOnCheckedChangeListener { _, checked -> if (checked) selected.add(pkg) else selected.remove(pkg); prefs.edit().putStringSet("selected_apps", selected.toSet()).apply() } }
            root.addView(cb, ViewGroup.LayoutParams(-1, dp(46)))
        }
        button(root, "Test setup") {
            val hasOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
            if (!hasOverlay) Toast.makeText(this, "Allow display-over-other-apps first", Toast.LENGTH_LONG).show()
            else Toast.makeText(this, "Setup looks ready. Send a notification from a selected app to test filtering.", Toast.LENGTH_LONG).show()
        }
        addText(root, "Privacy: PullNotify filters by app package. It does not save or transmit notification text.", 12f, muted, false)
        addText(root, "Android controls the real notification banner. Third-party apps cannot reliably move that system-owned banner; overlay behavior varies by device.", 12f, muted, false)
    }
    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean) {
        parent.addView(TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(8), 0, dp(8)) })
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply { text = label; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(purple); setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(7); bottomMargin = dp(7) })
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
