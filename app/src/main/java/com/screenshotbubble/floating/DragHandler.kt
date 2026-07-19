package com.screenshotbubble.floating

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sqrt

class DragHandler(
    private val context: Context,
    private val windowManager: WindowManager,
    private val iconView: View,
    private val iconParams: WindowManager.LayoutParams,
    private val density: Float,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onDragStart()
        fun onDragEnd(zone: MagneticZone)
        fun onTap()
        fun onUndock()
        fun onGravityChanged(position: DockPosition)
    }

    companion object {
        private const val CLICK_THRESHOLD_MS = 400L
        private const val DRAG_THRESHOLD_DP = 8
        private const val EDGE_PEEK_INTERVAL_MS = 18000L
        private const val EDGE_PEEK_DISTANCE_DP = 8
        private const val EDGE_PEEK_DURATION_MS = 350L
    }

    private var isDragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialViewX = 0
    private var initialViewY = 0
    private var totalMovement = 0f
    private var touchDownTime = 0L
    private var snapAnimator: ValueAnimator? = null
    private var undockAnimator: ValueAnimator? = null
    private var peekAnimator: ValueAnimator? = null
    private var peekHandler = Handler(Looper.getMainLooper())
    private var breathingAnimator: ObjectAnimator? = null

    var floatingState: FloatingState = FloatingState.IDLE
    var dockPosition: DockPosition = DockPosition.RIGHT
    var lastMagneticZone: MagneticZone = MagneticZone.MIDDLE_RIGHT

    private val iconSizePx: Int = dpToPx(48)
    val expandedWidthPx: Int = dpToPx(56)
    val dockedWidthPx: Int = dpToPx(48)
    private val marginPx: Int = dpToPx(4)
    private var safeTopInset = 0
    private var safeBottomInset = 0

    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    val iconWidth: Int
        get() = iconView.width.coerceAtLeast(dockedWidthPx)

    val iconHeight: Int
        get() = iconView.height.coerceAtLeast(iconSizePx)

    private val TAG = "DRAG"

    private var lastGravitySide: DockPosition? = null

    private fun syncHandleGravity(position: DockPosition) {
        if (lastGravitySide != position) {
            lastGravitySide = position
            callbacks.onGravityChanged(position)
        }
    }

    fun initialize(x: Int, y: Int, position: DockPosition) {
        resolveSafeInsets()
        val screenSize = getScreenSize()
        dockPosition = position
        floatingState = FloatingState.IDLE

        val isDocked = position != DockPosition.NONE
        iconParams.width = if (isDocked) dockedWidthPx else expandedWidthPx

        iconParams.x = when (position) {
            DockPosition.LEFT -> 0
            DockPosition.RIGHT -> (screenSize.x - iconParams.width).coerceAtLeast(0)
            DockPosition.NONE -> x.coerceIn(0, screenSize.x - iconParams.width)
        }
        iconParams.y = y.coerceIn(
            safeTopInset + marginPx,
            screenSize.y - iconHeight - safeBottomInset - marginPx
        )

        lastMagneticZone = resolveZone(iconParams.x, iconParams.y)
        lastGravitySide = position
        try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
        applyDockAlpha()
        startIdleBreathing()
        startEdgePeek()
    }

    private fun getMinX(): Int = 0

    private fun getMaxX(screenSize: Point): Int = (screenSize.x - iconWidth).coerceAtLeast(0)

    private fun clampX(x: Int, screenSize: Point): Int =
        x.coerceIn(getMinX(), getMaxX(screenSize))

    private fun resolveSafeInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets
                safeTopInset = insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                ).top
                safeBottomInset = insets.getInsets(
                    WindowInsets.Type.navigationBars()
                ).bottom
            } catch (_: Exception) {
                safeTopInset = getSystemDimension("status_bar_height")
                safeBottomInset = getSystemDimension("navigation_bar_height")
            }
        } else {
            safeTopInset = getSystemDimension("status_bar_height")
            safeBottomInset = getSystemDimension("navigation_bar_height")
        }
        if (safeTopInset == 0) safeTopInset = dpToPx(24)
        if (safeBottomInset == 0) safeBottomInset = dpToPx(16)
    }

    private fun getSystemDimension(name: String): Int {
        val resourceId = context.resources.getIdentifier(name, "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    fun handleTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                undockAnimator?.cancel()
                peekHandler.removeCallbacksAndMessages(null)
                stopIdleBreathing()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialViewX = iconParams.x
                initialViewY = iconParams.y
                totalMovement = 0f
                isDragging = false
                touchDownTime = System.currentTimeMillis()

                callbacks.onUndock()

                iconView.animate().scaleX(0.92f).scaleY(0.92f).alpha(1.0f).setDuration(150).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                totalMovement = sqrt(dx * dx + dy * dy)

                if (!isDragging && totalMovement > dpToPx(DRAG_THRESHOLD_DP)) {
                    isDragging = true
                    floatingState = FloatingState.DRAGGING
                    undockAnimator?.cancel()
                    undockAnimator = null
                    if (dockPosition != DockPosition.NONE) {
                        snapUndockedPosition()
                        initialViewX = iconParams.x
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                    callbacks.onDragStart()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }

                if (isDragging) {
                    val screenSize = getScreenSize()
                    val rawX = (initialViewX + dx).toInt()
                    iconParams.x = clampX(rawX, screenSize)
                    iconParams.y = (initialViewY + dy).toInt()
                        .coerceIn(safeTopInset + marginPx, screenSize.y - iconHeight - safeBottomInset - marginPx)
                    try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}

                    if (dockPosition == DockPosition.NONE) {
                        syncHandleGravity(getEdge())
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val touchDuration = System.currentTimeMillis() - touchDownTime

                if (isDragging) {
                    val zone = snapToMagneticZone()
                    callbacks.onDragEnd(zone)
                    performHaptic()
                    v.animate().scaleX(1f).scaleY(1f).alpha(1.0f).setDuration(150)
                        .withEndAction { startIdleBreathing() }
                        .start()
                    startEdgePeek()
                } else if (totalMovement < dpToPx(DRAG_THRESHOLD_DP) && touchDuration < CLICK_THRESHOLD_MS) {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1.0f).setDuration(150)
                        .withEndAction { startIdleBreathing() }
                        .start()
                    callbacks.onTap()
                    startEdgePeek()
                } else {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1.0f).setDuration(150)
                        .withEndAction { startIdleBreathing() }
                        .start()
                    startEdgePeek()
                }

                floatingState = FloatingState.IDLE
                isDragging = false
                return true
            }
        }
        return false
    }

    fun performHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } catch (_: Exception) {}
        }
    }

    private fun applyDockAlpha() {
        if (dockPosition != DockPosition.NONE) {
            iconView.animate().alpha(0.7f).setDuration(300).start()
        } else {
            iconView.animate().alpha(1.0f).setDuration(300).start()
        }
    }

    private fun startIdleBreathing() {
        stopIdleBreathing()
        val pvhX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.03f)
        val pvhY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.03f)
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(iconView, pvhX, pvhY).apply {
            duration = 2000
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopIdleBreathing() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        iconView.scaleX = 1.0f
        iconView.scaleY = 1.0f
    }

    private fun snapUndockedPosition() {
        val screenSize = getScreenSize()
        val handleWidthPx = dpToPx(5)
        val isLeft = dockPosition == DockPosition.LEFT

        iconParams.width = expandedWidthPx

        val targetX = if (isLeft) {
            0
        } else {
            (screenSize.x - expandedWidthPx).coerceAtLeast(0)
        }
        iconParams.x = clampX(targetX, screenSize)
        try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
        dockPosition = DockPosition.NONE
        syncHandleGravity(getEdge())
    }

    private fun undockFromEdge() {
        val screenSize = getScreenSize()
        val startX = iconParams.x
        val isLeft = dockPosition == DockPosition.LEFT
        val targetX = clampX(if (isLeft) marginPx else screenSize.x - iconWidth - marginPx, screenSize)

        val dx = targetX - startX
        if (abs(dx) < dpToPx(2)) return

        undockAnimator?.cancel()
        undockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                iconParams.x = (startX + dx * anim.animatedFraction).toInt()
                try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    iconParams.x = targetX
                    try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
                }
            })
            start()
        }

        dockPosition = DockPosition.NONE
        syncHandleGravity(DockPosition.NONE)
    }

    fun getCurrentX(): Int = iconParams.x
    fun getCurrentY(): Int = iconParams.y

    private fun snapToMagneticZone(): MagneticZone {
        val screenSize = getScreenSize()
        val centers = generateZoneCenters(screenSize)

        val iconCenterX = iconParams.x + iconWidth / 2f
        val iconCenterY = iconParams.y + iconHeight / 2f

        var bestZone = MagneticZone.MIDDLE_RIGHT
        var bestDist = Float.MAX_VALUE

        for ((zone, cx, cy) in centers) {
            val dx = iconCenterX - cx
            val dy = iconCenterY - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < bestDist) {
                bestDist = dist
                bestZone = zone
            }
        }

        dockPosition = if (bestZone.name.endsWith("_LEFT")) DockPosition.LEFT else DockPosition.RIGHT
        syncHandleGravity(dockPosition)

        dockToEdge(screenSize)
        lastMagneticZone = bestZone
        return bestZone
    }

    private fun dockToEdge(screenSize: Point) {
        val isLeft = dockPosition == DockPosition.LEFT

        iconParams.width = dockedWidthPx

        val targetX = if (isLeft) 0 else (screenSize.x - dockedWidthPx).coerceAtLeast(0)
        iconParams.x = targetX
        try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
        applyDockAlpha()
    }

    private fun generateZoneCenters(screenSize: Point): List<Triple<MagneticZone, Float, Float>> {
        val leftX = screenSize.x / 4f
        val rightX = 3f * screenSize.x / 4f
        val thirdH = safeTopInset + (screenSize.y - safeTopInset - safeBottomInset) / 3f
        val bottomY = screenSize.y - iconHeight / 2f - safeBottomInset

        return listOf(
            Triple(MagneticZone.TOP_LEFT, leftX, safeTopInset + iconHeight / 2f + marginPx),
            Triple(MagneticZone.TOP_RIGHT, rightX, safeTopInset + iconHeight / 2f + marginPx),
            Triple(MagneticZone.MIDDLE_LEFT, leftX, thirdH),
            Triple(MagneticZone.MIDDLE_RIGHT, rightX, thirdH),
            Triple(MagneticZone.BOTTOM_LEFT, leftX, bottomY),
            Triple(MagneticZone.BOTTOM_RIGHT, rightX, bottomY)
        )
    }

    private fun getTargetForZone(zone: MagneticZone, screenSize: Point): Pair<Int, Int> {
        val x = if (zone.name.endsWith("_LEFT")) 0 else (screenSize.x - dockedWidthPx).coerceAtLeast(0)
        val y = when (zone) {
            MagneticZone.TOP_LEFT, MagneticZone.TOP_RIGHT -> safeTopInset + marginPx
            MagneticZone.MIDDLE_LEFT, MagneticZone.MIDDLE_RIGHT ->
                safeTopInset + (screenSize.y - safeTopInset - safeBottomInset) / 3
            MagneticZone.BOTTOM_LEFT, MagneticZone.BOTTOM_RIGHT ->
                screenSize.y - iconHeight - safeBottomInset - marginPx
        }
        return Pair(x, y)
    }

    private fun animateToPosition(targetX: Int, targetY: Int, screenSize: Point) {
        val startX = iconParams.x
        val startY = iconParams.y
        val clampedTargetX = clampX(targetX, screenSize)
        val dx = clampedTargetX - startX
        val dy = targetY - startY

        if (abs(dx) < dpToPx(2) && abs(dy) < dpToPx(2)) {
            dockToEdge(screenSize)
            return
        }
    }

    private fun startEdgePeek() {
        peekHandler.removeCallbacksAndMessages(null)
        peekHandler.postDelayed(object : Runnable {
            override fun run() {
                if (dockPosition == DockPosition.NONE || isDragging) {
                    peekHandler.postDelayed(this, EDGE_PEEK_INTERVAL_MS)
                    return
                }
                performEdgePeek()
                peekHandler.postDelayed(this, EDGE_PEEK_INTERVAL_MS)
            }
        }, EDGE_PEEK_INTERVAL_MS)
    }

    private fun performEdgePeek() {
        val startX = iconParams.x
        val isLeft = dockPosition == DockPosition.LEFT
        val peekDistance = dpToPx(EDGE_PEEK_DISTANCE_DP)
        val targetX = if (isLeft) startX + peekDistance else startX - peekDistance

        peekAnimator?.cancel()
        peekAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EDGE_PEEK_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                iconParams.x = if (f < 0.5f) {
                    (startX + (targetX - startX) * f * 2).toInt()
                } else {
                    (targetX + (startX - targetX) * (f - 0.5f) * 2).toInt()
                }
                try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    iconParams.x = startX
                    try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
                }
            })
            start()
        }
    }

    private fun resolveZone(x: Int, y: Int): MagneticZone {
        val screenSize = getScreenSize()
        val centers = generateZoneCenters(screenSize)
        val cx = x + iconWidth / 2f
        val cy = y + iconHeight / 2f
        var best = MagneticZone.MIDDLE_RIGHT
        var bestDist = Float.MAX_VALUE
        for ((zone, zx, zy) in centers) {
            val d = sqrt((cx - zx) * (cx - zx) + (cy - zy) * (cy - zy))
            if (d < bestDist) { bestDist = d; best = zone }
        }
        return best
    }

    fun onConfigurationChanged() {
        resolveSafeInsets()
        val screenSize = getScreenSize()
        iconParams.x = clampX(iconParams.x, screenSize)
        iconParams.y = iconParams.y.coerceIn(safeTopInset + marginPx, screenSize.y - iconHeight - safeBottomInset - marginPx)
        try { windowManager.updateViewLayout(iconView, iconParams) } catch (_: Exception) {}
    }

    fun getEdge(): DockPosition {
        return if (iconParams.x + iconWidth / 2f < getScreenSize().x / 2f) DockPosition.LEFT else DockPosition.RIGHT
    }

    fun getScreenSize(): Point {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    fun destroy() {
        stopIdleBreathing()
        peekHandler.removeCallbacksAndMessages(null)
        peekAnimator?.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * density).toInt()
    }
}
