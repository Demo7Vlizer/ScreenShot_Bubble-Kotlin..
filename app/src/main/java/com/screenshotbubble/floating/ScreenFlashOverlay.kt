package com.screenshotbubble.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class ScreenFlashOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var flashView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun flash() {
        hide()

        val view = View(context).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            alpha = 0f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }

        flashView = view

        view.animate()
            .alpha(1f)
            .setDuration(40)
            .withEndAction {
                mainHandler.postDelayed({
                    view.animate()
                        .alpha(0f)
                        .setDuration(60)
                        .withEndAction { hide() }
                        .start()
                }, 20)
            }
            .start()
    }

    fun hide() {
        flashView?.let {
            it.animate().cancel()
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        flashView = null
    }
}
