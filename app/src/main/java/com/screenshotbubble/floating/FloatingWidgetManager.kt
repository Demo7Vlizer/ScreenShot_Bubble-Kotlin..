package com.screenshotbubble.floating

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.screenshotbubble.R
import com.screenshotbubble.features.FeatureModule
import com.screenshotbubble.features.modules.*

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

        val initialX: Int
        val initialY: Int
        val initialPosition: DockPosition

        if (restored != null) {
            initialX = restored.x
            initialY = restored.y
            initialPosition = restored.position
        } else {
            initialX = screenSize.x - dpToPx(24)
            initialY = screenSize.y / 3
            initialPosition = DockPosition.RIGHT
        }

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
            x = initialX
            y = initialY
        }

        windowManager.addView(icon, iconParams)
        floatingIcon = icon

        icon.alpha = 1.0f

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
                    if (now - lastScreenshotTimeMs < screenshotDebounceMs) {
                        android.util.Log.w("SCREENSHOT_BLOCKED", "Debounce active: ${now - lastScreenshotTimeMs}ms since last screenshot")
                        return
                    }
                    android.util.Log.d("CAMERA_ICON_CLICKED", "Tap detected on floating icon")
                    lastScreenshotTimeMs = now
                    android.util.Log.d("SCREENSHOT_TRIGGERED", "Calling onScreenshotRequested")
                    onScreenshotRequested()
                }

                override fun onUndock() {
                    if (toolbarController?.isVisible() == true) {
                        toolbarController?.hide()
                    }
                }
            }
        )

        handler.initialize(initialX, initialY, initialPosition)
        dragHandler = handler

        icon.setOnTouchListener { v, event ->
            handler.handleTouch(v, event)
        }
    }

    private fun savePosition() {
        val h = dragHandler ?: return
        positionPersistence?.savePosition(h.getCurrentX(), h.getCurrentY(), h.dockPosition)
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
