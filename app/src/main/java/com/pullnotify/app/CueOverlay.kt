package com.pullnotify.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

object CueOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var currentManager: WindowManager? = null
    private var dismissTask: Runnable? = null
    private data class OverlayRequest(val context: Context, val title: String, val subtitle: String)
    private val pending = java.util.ArrayDeque<OverlayRequest>()

    fun show(context: Context, title: String, subtitle: String) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return
        handler.post {
            if (currentView != null) {
                if (pending.size < 3) pending.addLast(OverlayRequest(context.applicationContext, title, subtitle))
                return@post
            }
            val prefs = context.getSharedPreferences("pullnotify", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("cues_enabled", true) && subtitle != "PREVIEW_FORCE") return@post
            try {
                dismissTask?.let { handler.removeCallbacks(it) }
                currentView?.let { old -> try { currentManager?.removeView(old) } catch (_: Exception) {} }
                (currentView as? CharacterRendererView)?.dispose()

                val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()
                val characterId = CharacterRegistry.find(prefs.getString("selected_character", "bunny")).id
                val speed = when (prefs.getString("animation_speed", "normal")) {
                    "slow" -> .72f
                    "fast" -> 1.42f
                    else -> 1f
                }
                val size = when (prefs.getString("character_size", "medium")) {
                    "small" -> .78f
                    "large" -> 1.22f
                    else -> 1f
                }
                val cycles = prefs.getInt("pull_cycles", 3).coerceIn(1, 5)
                val duration = prefs.getInt("overlay_duration_ms", 5600).toLong().coerceIn(2500L, 10000L)
                val renderer = CharacterRendererView(context, characterId, title, speed, size, cycles, duration)
                renderer.alpha = 0f
                renderer.translationY = -dp(12).toFloat()
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    dp(300),
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = 0
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                }
                manager.addView(renderer, params)
                currentManager = manager
                currentView = renderer
                renderer.animate().alpha(1f).translationY(0f).setDuration(180).start()
                dismissTask = Runnable {
                    if (currentView === renderer) {
                        renderer.animate().alpha(0f).setDuration(170).withEndAction {
                            try { manager.removeView(renderer) } catch (_: Exception) {}
                            renderer.dispose()
                            if (currentView === renderer) {
                                currentView = null; currentManager = null; dismissTask = null
                                val next = if (pending.isEmpty()) null else pending.removeFirst()
                                if (next != null) show(next.context, next.title, next.subtitle)
                            }
                        }.start()
                    }
                }
                handler.postDelayed(dismissTask!!, duration)
            } catch (_: Exception) {
                (currentView as? CharacterRendererView)?.dispose()
                currentView = null
                currentManager = null
            }
        }
    }
}
