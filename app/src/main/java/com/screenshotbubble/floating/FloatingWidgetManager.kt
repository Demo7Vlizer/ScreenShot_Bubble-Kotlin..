package com.screenshotbubble.floating

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import com.screenshotbubble.R
import com.screenshotbubble.features.FeatureModule
import com.screenshotbubble.features.modules.*
import android.widget.FrameLayout

class FloatingWidgetManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onScreenshotRequested: () -> Unit
) {

    private var floatingIcon: View? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var dragHandler: DragHandler? = null
    private var toolbarController: ToolbarController? = null
    private var screenshotFeedback: ScreenshotFeedback? = null
    private var screenFlashOverlay: ScreenFlashOverlay? = null
    private var positionPersistence: PositionPersistence? = null

    private var thumbnailPopup: View? = null
    private var thumbnailPopupParams: WindowManager.LayoutParams? = null

    private val density: Float = context.resources.displayMetrics.density
    private var isCaptureInProgress = false
    private var lastScreenshotTimeMs = 0L
    private val screenshotDebounceMs = 500L

    private var handleView: View? = null
    private var cameraContainer: View? = null
    private var isExpanded = false
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideDelayMs = 3000L

    private val dockedWidthPx: Int = dpToPx(48)
    private val expandedWidthPx: Int = dpToPx(56)
    private val marginPx: Int = dpToPx(4)

    private val modules: Map<Int, FeatureModule> = mapOf(
        1 to ScreenshotModule(),
        2 to ScreenRecordModule(),
        3 to LongScreenshotModule(),
        4 to OcrModule(),
        5 to SettingsModule()
    )

    fun createFloatingWidget() {
        positionPersistence = PositionPersistence(context)
        val restored = positionPersistence?.restorePosition()

        val icon = LayoutInflater.from(context).inflate(R.layout.floating_icon, null)
        val screenSize = getScreenSize()

        val safeTop = safeInsetTop()
        val safeBottom = safeInsetBottom()
        val iconH = expandedWidthPx

        val initialX: Int
        val initialY: Int
        val initialPosition: DockPosition

        if (restored != null) {
            val maxX = (screenSize.x - dockedWidthPx).coerceAtLeast(0)
            initialX = restored.x.coerceIn(0, maxX)
            initialY = restored.y.coerceIn(
                safeTop + marginPx,
                screenSize.y - iconH - safeBottom - marginPx
            )
            initialPosition = restored.position
            android.util.Log.d("POSITION_RESTORED",
                "screen=${screenSize.x}x${screenSize.y} " +
                "restored_x=${restored.x} restored_y=${restored.y} " +
                "clamped_x=$initialX clamped_y=$initialY " +
                "position=$initialPosition")
        } else {
            initialX = (screenSize.x - dockedWidthPx).coerceAtLeast(0)
            initialY = screenSize.y / 3
            initialPosition = DockPosition.RIGHT
            android.util.Log.d("POSITION_RESTORED",
                "no_saved_data initial_x=$initialX initial_y=$initialY " +
                "screen=${screenSize.x}x${screenSize.y}")
        }

        iconParams = WindowManager.LayoutParams(
            dockedWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        windowManager.addView(icon, iconParams)
        floatingIcon = icon

        icon.alpha = 1.0f

        handleView = icon.findViewById(R.id.handle_pill)
        cameraContainer = icon.findViewById(R.id.camera_container)
        updateHandleGravity(initialPosition)
        cameraContainer?.visibility = View.GONE
        cameraContainer?.alpha = 0f

        screenshotFeedback = ScreenshotFeedback(context, windowManager, density)
        screenFlashOverlay = ScreenFlashOverlay(context, windowManager)

        toolbarController = ToolbarController(context, windowManager, density).apply {
            setOnItemClickListener { item ->
                val module = modules[item.id]
                if (module != null) {
                    if (module.isAvailable) {
                        module.execute(context)
                        if (item.id == 1) {
                            onScreenshotRequested()
                        }
                    } else {
                        Toast.makeText(context, "Coming Soon", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val handler = DragHandler(
            context, windowManager, icon, iconParams!!, density,
            object : DragHandler.Callbacks {
                override fun onDragStart() {
                    if (isExpanded) {
                        collapse()
                    }
                }

                override fun onDragEnd(zone: MagneticZone) {
                    savePosition()
                }

                override fun onTap() {
                    val now = System.currentTimeMillis()
                    if (isCaptureInProgress) {
                        android.util.Log.w("SCREENSHOT_BLOCKED", "Capture already in progress")
                        return
                    }
                    if (isExpanded) {
                        if (now - lastScreenshotTimeMs < screenshotDebounceMs) {
                            android.util.Log.w("SCREENSHOT_BLOCKED", "Debounce active")
                            return
                        }
                        lastScreenshotTimeMs = now
                        onScreenshotRequested()
                    } else {
                        expand()
                    }
                }

                override fun onUndock() {
                    if (toolbarController?.isVisible() == true) {
                        toolbarController?.hide()
                    }
                    val screenSize = getScreenSize()
                    iconParams?.width = expandedWidthPx
                    val pos = dragHandler?.dockPosition
                    if (pos == DockPosition.LEFT) {
                        iconParams?.x = 0
                    } else if (pos == DockPosition.RIGHT) {
                        iconParams?.x = (screenSize.x - expandedWidthPx).coerceAtLeast(0)
                    }
                    try {
                        iconParams?.let { windowManager.updateViewLayout(floatingIcon, it) }
                    } catch (_: Exception) {}
                    updateHandleGravity(DockPosition.NONE)
                }

                override fun onGravityChanged(position: DockPosition) {
                    updateHandleGravity(position)
                }
            }
        )

        handler.initialize(initialX, initialY, initialPosition)
        dragHandler = handler

        icon.setOnTouchListener { v, event ->
            handler.handleTouch(v, event)
        }
    }

    private fun updateHandleGravity(position: DockPosition) {
        val lp = handleView?.layoutParams as? FrameLayout.LayoutParams ?: return
        val newGravity = when (position) {
            DockPosition.LEFT -> Gravity.CENTER_VERTICAL or Gravity.LEFT
            DockPosition.RIGHT -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
            DockPosition.NONE -> Gravity.CENTER
        }
        val oldGravity = lp.gravity
        if (oldGravity != newGravity) {
            lp.gravity = newGravity
            handleView?.layoutParams = lp
        }
    }

    private fun expand() {
        if (isExpanded) return
        isExpanded = true
        cancelAutoHideTimer()

        val screenSize = getScreenSize()
        val isLeft = dragHandler?.dockPosition == DockPosition.LEFT
        iconParams?.width = expandedWidthPx
        if (isLeft) {
            iconParams?.x = 0
        } else {
            iconParams?.x = (screenSize.x - expandedWidthPx).coerceAtLeast(0)
        }
        try { windowManager.updateViewLayout(floatingIcon, iconParams) } catch (_: Exception) {}

        handleView?.let { h ->
            h.animate()
                .alpha(0f)
                .scaleX(0.3f)
                .scaleY(0.3f)
                .setDuration(180)
                .withEndAction {
                    h.visibility = View.INVISIBLE
                }
                .start()
        }

        cameraContainer?.let { c ->
            c.visibility = View.VISIBLE
            c.alpha = 0f
            c.scaleX = 0.6f
            c.scaleY = 0.6f
            c.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .withEndAction {
                    startAutoHideTimer()
                }
                .start()
        }
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        cancelAutoHideTimer()

        val screenSize = getScreenSize()
        val isLeft = dragHandler?.dockPosition == DockPosition.LEFT
        iconParams?.width = dockedWidthPx
        if (isLeft) {
            iconParams?.x = 0
        } else {
            iconParams?.x = (screenSize.x - dockedWidthPx).coerceAtLeast(0)
        }
        try { windowManager.updateViewLayout(floatingIcon, iconParams) } catch (_: Exception) {}

        cameraContainer?.let { c ->
            c.animate()
                .alpha(0f)
                .scaleX(0.6f)
                .scaleY(0.6f)
                .setDuration(150)
                .withEndAction {
                    c.visibility = View.GONE
                }
                .start()
        }

        handleView?.let { h ->
            h.visibility = View.VISIBLE
            h.alpha = 0f
            h.scaleX = 0.3f
            h.scaleY = 0.3f
            h.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        }
    }

    private fun startAutoHideTimer() {
        cancelAutoHideTimer()
        autoHideHandler.postDelayed({
            if (isExpanded) {
                collapse()
            }
        }, autoHideDelayMs)
    }

    private fun cancelAutoHideTimer() {
        autoHideHandler.removeCallbacksAndMessages(null)
    }

    private fun savePosition() {
        val h = dragHandler ?: return
        val screenSize = getScreenSize()
        val maxX = (screenSize.x - dockedWidthPx).coerceAtLeast(0)
        val clampedX = h.getCurrentX().coerceIn(0, maxX)
        android.util.Log.d("SAVE_POSITION",
            "current_x=${h.getCurrentX()} current_y=${h.getCurrentY()} " +
            "clamped_x=$clampedX position=${h.dockPosition}")
        positionPersistence?.savePosition(clampedX, h.getCurrentY(), h.dockPosition)
    }

    private fun toggleToolbar() {
        val h = dragHandler ?: return
        toolbarController?.toggle(
            h.getCurrentX(), h.getCurrentY(),
            h.iconWidth, h.iconHeight,
            h.getEdge()
        )
    }

    fun showScreenshotFeedback() {
        val h = dragHandler ?: return
        screenshotFeedback?.show(h.getCurrentX(), h.getCurrentY())
    }

    fun showScreenFlash() {
        screenFlashOverlay?.flash()
    }

    fun showFloatingIcon() {
        floatingIcon?.visibility = View.VISIBLE
        if (isExpanded) {
            collapse()
        }
    }

    fun hideFloatingIcon() {
        floatingIcon?.visibility = View.GONE
    }

    fun showThumbnailPreview(uri: Uri?) {
        if (uri == null) return
        Toast.makeText(context, "Screenshot saved", Toast.LENGTH_SHORT).show()

        val icon = floatingIcon ?: return
        val params = iconParams ?: return

        val popup = LayoutInflater.from(context).inflate(R.layout.thumbnail_popup, null)
        val thumbnailIv = popup.findViewById<android.widget.ImageView>(R.id.thumbnail_image)

        val bmp = try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream?.close()

            val scale = maxOf(opts.outWidth / 120, opts.outHeight / 120, 1)

            val sampleOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, sampleOpts)
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
        popup.scaleX = 0.8f
        popup.scaleY = 0.8f
        popup.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()

        popup.postDelayed({
            popup.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(250)
                .withEndAction {
                    removeThumbnailPopup()
                }
                .start()
        }, 1500)
    }

    fun showErrorMessage(message: String) {
        val displayMsg = message
        Toast.makeText(context, displayMsg, Toast.LENGTH_SHORT).show()

        val icon = floatingIcon ?: return
        val params = iconParams ?: return

        val popup = LayoutInflater.from(context).inflate(R.layout.thumbnail_popup, null)
        val thumbnailIv = popup.findViewById<android.widget.ImageView>(R.id.thumbnail_image)
        thumbnailIv.visibility = View.GONE

        val textView = android.widget.TextView(context).apply {
            text = displayMsg
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
        popup.scaleX = 0.8f
        popup.scaleY = 0.8f
        popup.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()

        popup.postDelayed({
            popup.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(250)
                .withEndAction {
                    removeThumbnailPopup()
                }
                .start()
        }, 1500)
    }

    fun performHaptic() {
        dragHandler?.performHaptic()
    }

    fun getScreenshotCount(): Int {
        return com.screenshotbubble.capture.ScreenshotSaver(context).getTotalCount()
    }

    fun onConfigurationChanged() {
        dragHandler?.onConfigurationChanged()
    }

    fun setCaptureInProgress(inProgress: Boolean) {
        isCaptureInProgress = inProgress
    }

    fun cleanup() {
        cancelAutoHideTimer()
        savePosition()
        removeThumbnailPopup()
        screenFlashOverlay?.hide()
        screenFlashOverlay = null
        screenshotFeedback?.hide()
        screenshotFeedback = null
        toolbarController?.destroy()
        toolbarController = null
        floatingIcon?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingIcon = null
        iconParams = null
        dragHandler = null
    }

    private fun removeThumbnailPopup() {
        thumbnailPopup?.let {
            it.animate().cancel()
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        thumbnailPopup = null
        thumbnailPopupParams = null
    }

    private fun safeInsetTop(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager.currentWindowMetrics.windowInsets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                ).top
            } catch (_: Exception) {
                getSystemDimension("status_bar_height")
            }
        } else {
            getSystemDimension("status_bar_height")
        }.coerceAtLeast(dpToPx(24))
    }

    private fun safeInsetBottom(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager.currentWindowMetrics.windowInsets.getInsets(
                    WindowInsets.Type.navigationBars()
                ).bottom
            } catch (_: Exception) {
                getSystemDimension("navigation_bar_height")
            }
        } else {
            getSystemDimension("navigation_bar_height")
        }.coerceAtLeast(dpToPx(16))
    }

    private fun getSystemDimension(name: String): Int {
        val resourceId = context.resources.getIdentifier(name, "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
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
}
