package com.screenshotbubble.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.screenshotbubble.R

class CountdownOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var overlayView: View? = null
    private var countdownText: TextView? = null
    private var isActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cancelFlag = false

    fun start(seconds: Int, onFinished: () -> Unit) {
        if (isActive) return
        isActive = true
        cancelFlag = false

        val view = LayoutInflater.from(context).inflate(R.layout.countdown_overlay, null)
        countdownText = view.findViewById(R.id.countdown_text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            isActive = false
            onFinished()
            return
        }

        overlayView = view

        countdownText?.text = seconds.toString()
        countdownText?.let {
            it.alpha = 0f
            it.animate()
                .alpha(0.9f)
                .setDuration(200)
                .start()
        }

        runCountdown(seconds) {
            val shouldProceed = !cancelFlag
            hide()
            if (shouldProceed) {
                onFinished()
            }
        }
    }

    private fun runCountdown(remaining: Int, onDone: () -> Unit) {
        if (cancelFlag || !isActive) {
            onDone()
            return
        }

        if (remaining <= 0) {
            onDone()
            return
        }

        val nextValue = remaining - 1
        mainHandler.postDelayed({
            if (cancelFlag || !isActive) {
                onDone()
                return@postDelayed
            }

            if (nextValue > 0) {
                android.util.Log.d("COUNTDOWN_RUNNING", "remaining=${nextValue}s")
                countdownText?.let { tv ->
                    tv.text = nextValue.toString()
                    tv.alpha = 0f
                    tv.scaleX = 0.7f
                    tv.scaleY = 0.7f
                    tv.animate()
                        .alpha(0.9f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .start()
                }
            }

            runCountdown(nextValue, onDone)
        }, 1000L)
    }

    fun hide() {
        cancelFlag = true
        mainHandler.removeCallbacksAndMessages(null)
        overlayView?.let {
            it.animate().cancel()
            try {
                windowManager.removeView(it)
                android.util.Log.d("OVERLAY_REMOVED", "countdown overlay removed")
            } catch (_: Exception) {}
        }
        overlayView = null
        countdownText = null
        isActive = false
    }
}
