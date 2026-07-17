package com.screenshotbubble.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.screenshotbubble.R

class ScreenshotFeedback(
    private val context: Context,
    private val windowManager: WindowManager,
    private val density: Float
) {

    private var feedbackView: View? = null
    private var feedbackParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(atX: Int, atY: Int) {
        hide()

        val view = LayoutInflater.from(context).inflate(R.layout.screenshot_feedback, null)
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = atX - view.measuredWidth / 2 + dpToPx(22)
            y = atY - dpToPx(8)
        }

        try {
            windowManager.addView(view, feedbackParams)
        } catch (_: Exception) {
            return
        }

        feedbackView = view

        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .withEndAction {
                mainHandler.postDelayed({
                    view.animate()
                        .alpha(0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(250)
                        .withEndAction { hide() }
                        .start()
                }, 900)
            }
            .start()
    }

    fun hide() {
        feedbackView?.let {
            it.animate().cancel()
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        feedbackView = null
        feedbackParams = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * density).toInt()
    }
}
