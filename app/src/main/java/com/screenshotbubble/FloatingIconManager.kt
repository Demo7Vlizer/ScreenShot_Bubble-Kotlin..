package com.screenshotbubble

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.sqrt

class FloatingIconManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callback: Callback
) {

    interface Callback {
        fun onScreenshotRequested()
        fun onCloseRequested()
    }

    private var floatingIcon: View? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var closeTarget: View? = null
    private var closeTargetParams: WindowManager.LayoutParams? = null
    private var thumbnailPopup: View? = null
    private var thumbnailPopupParams: WindowManager.LayoutParams? = null

    private var isDragging = false
    private var isLongPressActive = false
    private var isCaptureInProgress = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialViewX = 0
    private var initialViewY = 0

    private var closeTargetAnimator: android.animation.ObjectAnimator? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        showCloseTarget()
        isLongPressActive = true
    }

    private val density: Float = context.resources.displayMetrics.density

    fun createFloatingIcon() {
        val icon = LayoutInflater.from(context).inflate(R.layout.floating_icon, null)
        icon.setOnTouchListener(FloatingIconTouchListener())

        val screenSize = getScreenSize()

        iconParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenSize.x - dpToPx(44) - dpToPx(16)
            y = dpToPx(48)
        }

        windowManager.addView(icon, iconParams)
        floatingIcon = icon
    }

    fun showFloatingIcon() {
        floatingIcon?.visibility = View.VISIBLE
    }

    fun hideFloatingIcon() {
        floatingIcon?.visibility = View.GONE
    }

    fun showThumbnailPreview(uri: Uri) {
        val icon = floatingIcon ?: return
        val params = iconParams ?: return

        val popup = LayoutInflater.from(context).inflate(R.layout.thumbnail_popup, null)
        val thumbnailIv = popup.findViewById<ImageView>(R.id.thumbnail_image)

        val bmp = try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream?.close()

            val scale = maxOf(opts.outWidth / 120, opts.outHeight / 120, 1)

            val sampleOpts = BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, sampleOpts)
            }
        } catch (e: Exception) {
            null
        }

        bmp?.let { thumbnailIv.setImageBitmap(it) }

        val popupParams = WindowManager.LayoutParams(
            dpToPx(80), dpToPx(80),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + (icon.width - dpToPx(80)) / 2
            y = params.y - dpToPx(80) - dpToPx(4)
        }

        try {
            windowManager.addView(popup, popupParams)
        } catch (e: Exception) {
            return
        }

        thumbnailPopup = popup
        thumbnailPopupParams = popupParams

        popup.alpha = 0f
        popup.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        popup.postDelayed({
            popup.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    removeThumbnailPopup()
                }
                .start()
        }, 2000)
    }

    fun showErrorMessage(message: String) {
        val icon = floatingIcon ?: return
        val params = iconParams ?: return

        val popup = LayoutInflater.from(context).inflate(R.layout.thumbnail_popup, null)
        val thumbnailIv = popup.findViewById<ImageView>(R.id.thumbnail_image)
        thumbnailIv.visibility = View.GONE

        val textView = android.widget.TextView(context).apply {
            text = message
            textSize = 12f
            setTextColor(0xFFE63946.toInt())
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        (popup as? android.widget.FrameLayout)?.addView(textView)

        val popupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + (icon.width - dpToPx(80)) / 2
            y = params.y - dpToPx(80) - dpToPx(4)
        }

        try {
            windowManager.addView(popup, popupParams)
        } catch (e: Exception) {
            return
        }

        thumbnailPopup = popup
        thumbnailPopupParams = popupParams

        popup.alpha = 0f
        popup.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        popup.postDelayed({
            popup.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    removeThumbnailPopup()
                }
                .start()
        }, 2000)
    }

    fun onConfigurationChanged() {
        val icon = floatingIcon ?: return
        val params = iconParams ?: return
        val screenSize = getScreenSize()
        val iconWidth = icon.width
        val twentyDp = dpToPx(20)

        params.x = params.x.coerceIn(-iconWidth + twentyDp, screenSize.x - twentyDp)
        params.y = params.y.coerceIn(0, screenSize.y - icon.height - twentyDp)

        windowManager.updateViewLayout(icon, params)
    }

    fun cleanup() {
        removeThumbnailPopup()
        removeCloseTarget()
        floatingIcon?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingIcon = null
        iconParams = null
        closeTargetAnimator?.cancel()
        closeTargetAnimator = null
    }

    fun setCaptureInProgress(inProgress: Boolean) {
        isCaptureInProgress = inProgress
    }

    private fun removeThumbnailPopup() {
        thumbnailPopup?.let {
            it.animate().cancel()
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        thumbnailPopup = null
        thumbnailPopupParams = null
    }

    private fun removeCloseTarget() {
        closeTarget?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        closeTarget = null
        closeTargetParams = null
        closeTargetAnimator?.cancel()
        closeTargetAnimator = null
    }

    private fun showCloseTarget() {
        val target = LayoutInflater.from(context).inflate(R.layout.close_target, null)

        val screenSize = getScreenSize()

        closeTargetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = dpToPx(64)
        }

        try {
            windowManager.addView(target, closeTargetParams)
        } catch (e: Exception) {
            return
        }

        closeTarget = target

        startCloseTargetPulse()
    }

    private fun startCloseTargetPulse() {
        val target = closeTarget ?: return
        val animator = android.animation.ObjectAnimator.ofFloat(target, "scaleX", 1.0f, 1.15f, 1.0f)
        animator.duration = 400
        animator.repeatCount = android.animation.ValueAnimator.INFINITE
        animator.repeatMode = android.animation.ValueAnimator.RESTART
        animator.start()

        val animatorY = android.animation.ObjectAnimator.ofFloat(target, "scaleY", 1.0f, 1.15f, 1.0f)
        animatorY.duration = 400
        animatorY.repeatCount = android.animation.ValueAnimator.INFINITE
        animatorY.repeatMode = android.animation.ValueAnimator.RESTART
        animatorY.start()

        closeTargetAnimator = animator
    }

    private fun snapToEdge(animate: Boolean) {
        val icon = floatingIcon ?: return
        val params = iconParams ?: return
        val screenSize = getScreenSize()
        val iconWidth = icon.width

        val leftSnap = dpToPx(16)
        val rightSnap = screenSize.x - iconWidth - dpToPx(16)

        val centerX = params.x + iconWidth / 2f
        val targetX = if (centerX < screenSize.x / 2f) leftSnap else rightSnap

        val startX = params.x
        if (animate && abs(startX - targetX) > dpToPx(2)) {
            val animator = android.animation.ValueAnimator.ofFloat(startX.toFloat(), targetX.toFloat())
            animator.duration = 200
            animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            animator.addUpdateListener { animation ->
                params.x = (animation.animatedValue as Float).toInt()
                try {
                    windowManager.updateViewLayout(icon, params)
                } catch (_: Exception) {}
            }
            animator.start()
        } else {
            params.x = targetX
            try {
                windowManager.updateViewLayout(icon, params)
            } catch (_: Exception) {}
        }
    }

    private fun getScreenSize(): Point {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * density).toInt()
    }

    inner class FloatingIconTouchListener : View.OnTouchListener {

        private var lastRawX = 0f
        private var lastRawY = 0f
        private var totalMovement = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialViewX = iconParams?.x ?: 0
                    initialViewY = iconParams?.y ?: 0
                    totalMovement = 0f
                    isDragging = false
                    mainHandler.postDelayed(longPressRunnable, 300)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    totalMovement += sqrt(dx * dx + dy * dy)

                    if (totalMovement > dpToPx(10)) {
                        if (!isDragging) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        isDragging = true
                    }

                    if (isDragging) {
                        val params = iconParams ?: return true
                        val icon = floatingIcon ?: return true
                        val screenSize = getScreenSize()
                        val iconWidth = icon.width
                        val iconHeight = icon.height
                        val twentyDp = dpToPx(20)

                        params.x = (params.x + dx).toInt()
                            .coerceIn(-iconWidth + twentyDp, screenSize.x - twentyDp)
                        params.y = (params.y + dy).toInt()
                            .coerceIn(0, screenSize.y - iconHeight - twentyDp)

                        try {
                            windowManager.updateViewLayout(icon, params)
                        } catch (_: Exception) {}

                        if (isLongPressActive && closeTarget != null) {
                            checkDropZoneOverlap()
                        }
                    }

                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (isLongPressActive && closeTarget != null) {
                        if (isIconOverCloseTarget()) {
                            removeCloseTarget()
                            isLongPressActive = false
                            callback.onCloseRequested()
                            return true
                        }
                        removeCloseTarget()
                        isLongPressActive = false
                    }

                    if (!isDragging && totalMovement < dpToPx(10)) {
                        if (!isCaptureInProgress) {
                            callback.onScreenshotRequested()
                        }
                    } else if (isDragging) {
                        snapToEdge(true)
                    }

                    isDragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressActive) {
                        removeCloseTarget()
                        isLongPressActive = false
                    }
                    isDragging = false
                    return true
                }
            }
            return false
        }
    }

    private fun checkDropZoneOverlap() {
        val icon = floatingIcon ?: return
        val target = closeTarget ?: return
        val iconParams = iconParams ?: return
        val targetParams = closeTargetParams ?: return

        val iconCenterX = iconParams.x + icon.width / 2f
        val iconCenterY = iconParams.y + icon.height / 2f

        val screenSize = getScreenSize()
        val targetCenterX = screenSize.x / 2f
        val targetCenterY = screenSize.y - dpToPx(64) - target.height / 2f

        val distance = sqrt(
            (iconCenterX - targetCenterX) * (iconCenterX - targetCenterX) +
                    (iconCenterY - targetCenterY) * (iconCenterY - targetCenterY)
        )

        if (distance < dpToPx(48)) {
            target.scaleX = 1.2f
            target.scaleY = 1.2f
        } else {
            target.scaleX = 1.0f
            target.scaleY = 1.0f
        }
    }

    private fun isIconOverCloseTarget(): Boolean {
        val icon = floatingIcon ?: return false
        val target = closeTarget ?: return false
        val iParams = iconParams ?: return false
        val tParams = closeTargetParams ?: return false

        val iconCenterX = iParams.x + icon.width / 2f
        val iconCenterY = iParams.y + icon.height / 2f

        val screenSize = getScreenSize()
        val targetCenterX = screenSize.x / 2f
        val targetCenterY = screenSize.y - dpToPx(64) - target.height / 2f

        val distance = sqrt(
            (iconCenterX - targetCenterX) * (iconCenterX - targetCenterX) +
                    (iconCenterY - targetCenterY) * (iconCenterY - targetCenterY)
        )

        return distance < dpToPx(48)
    }
}
