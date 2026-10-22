package com.pullnotify.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object CueOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var currentManager: WindowManager? = null
    private val purple = 0xFF7557D9.toInt()

    fun show(context: Context, title: String, subtitle: String) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return
        handler.post {
            try {
                currentView?.let { old -> try { currentManager?.removeView(old) } catch (_: Exception) {} }
                val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(15), dp(12), dp(15), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE); cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), 0xFFE6E0F5.toInt())
                    }
                    elevation = dp(10).toFloat()
                }
                val mark = TextView(context).apply {
                    text = "✦"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(purple); cornerRadius = dp(15).toFloat() }
                }
                card.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
                val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                words.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(0xFF20202A.toInt()); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
                words.addView(TextView(context).apply { text = subtitle; textSize = 12f; setTextColor(0xFF6C6A76.toInt()); maxLines = 1 })
                card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                card.alpha = 0f
                card.translationY = -dp(18).toFloat()
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = dp(14); y = dp(48); width = context.resources.displayMetrics.widthPixels - dp(28) }
                manager.addView(card, params)
                currentManager = manager; currentView = card
                card.animate().alpha(1f).translationY(0f).setDuration(220).start()
                handler.postDelayed({
                    if (currentView === card) {
                        card.animate().alpha(0f).translationY(-dp(14).toFloat()).setDuration(180).withEndAction {
                            try { manager.removeView(card) } catch (_: Exception) {}
                            if (currentView === card) { currentView = null; currentManager = null }
                        }.start()
                    }
                }, 2800)
            } catch (_: Exception) {}
        }
    }
}
