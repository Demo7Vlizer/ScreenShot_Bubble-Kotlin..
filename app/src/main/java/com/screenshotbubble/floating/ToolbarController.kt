package com.screenshotbubble.floating

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.screenshotbubble.R
import com.screenshotbubble.model.ToolbarItem

class ToolbarController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val density: Float
) {

    private var toolbarView: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    var state: ToolbarState = ToolbarState.COLLAPSED

    private val toolbarItems: List<ToolbarItem> = listOf(
        ToolbarItem(1, R.drawable.ic_toolbar_screenshot, R.string.toolbar_screenshot, isActive = true, showComingSoon = false),
        ToolbarItem(2, R.drawable.ic_toolbar_screenrecord, R.string.toolbar_screenrecord, isActive = false, showComingSoon = true),
        ToolbarItem(3, R.drawable.ic_toolbar_longshot, R.string.toolbar_longshot, isActive = false, showComingSoon = true),
        ToolbarItem(4, R.drawable.ic_toolbar_ocr, R.string.toolbar_ocr, isActive = false, showComingSoon = true),
        ToolbarItem(5, R.drawable.ic_toolbar_settings, R.string.toolbar_settings, isActive = false, showComingSoon = true)
    )

    private var onItemClick: ((ToolbarItem) -> Unit)? = null

    fun setOnItemClickListener(listener: (ToolbarItem) -> Unit) {
        onItemClick = listener
    }

    fun toggle(anchorX: Int, anchorY: Int, anchorWidth: Int, anchorHeight: Int, edge: DockPosition) {
        if (state == ToolbarState.EXPANDED) {
            hide()
        } else {
            show(anchorX, anchorY, anchorWidth, anchorHeight, edge)
        }
    }

    fun show(anchorX: Int, anchorY: Int, anchorWidth: Int, anchorHeight: Int, edge: DockPosition) {
        if (state == ToolbarState.EXPANDED) {
            updatePosition(anchorX, anchorY, anchorWidth, anchorHeight, edge)
            return
        }

        val toolbar = LayoutInflater.from(context).inflate(R.layout.floating_toolbar, null)
        val container = toolbar.findViewById<LinearLayout>(R.id.toolbar_items_container)

        for (item in toolbarItems) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.toolbar_item, container, false)
            val icon = itemView.findViewById<ImageView>(R.id.toolbar_item_icon)
            val label = itemView.findViewById<TextView>(R.id.toolbar_item_label)

            icon.setImageResource(item.iconRes)
            icon.alpha = if (item.isActive) 1f else 0.5f

            label.text = if (item.showComingSoon) {
                context.getString(R.string.coming_soon)
            } else {
                context.getString(item.labelRes)
            }

            itemView.setOnClickListener {
                onItemClick?.invoke(item)
            }

            val spacer = View(context)
            spacer.layoutParams = LinearLayout.LayoutParams(dpToPx(6), 0)
            if (container.childCount > 0) {
                container.addView(spacer)
            }
            container.addView(itemView)
        }

        val toolbarWidth = dpToPx(316)
        val toolbarHeight = dpToPx(72)

        if (toolbar.measuredWidth == 0) {
            toolbar.measure(
                View.MeasureSpec.makeMeasureSpec(toolbarWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(toolbarHeight, View.MeasureSpec.AT_MOST)
            )
        }

        toolbarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = calculateToolbarX(anchorX, anchorWidth, toolbarWidth, edge)
            y = anchorY - toolbarHeight - dpToPx(8)
            if (y < dpToPx(24)) {
                y = anchorY + anchorHeight + dpToPx(8)
            }
        }

        try {
            windowManager.addView(toolbar, toolbarParams)
        } catch (e: Exception) {
            return
        }

        toolbarView = toolbar
        state = ToolbarState.EXPANDED

        toolbar.alpha = 0f
        toolbar.scaleX = 0.9f
        toolbar.scaleY = 0.9f
        toolbar.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()
    }

    fun updatePosition(anchorX: Int, anchorY: Int, anchorWidth: Int, anchorHeight: Int, edge: DockPosition) {
        val toolbar = toolbarView ?: return
        val params = toolbarParams ?: return
        val toolbarWidth = toolbar.measuredWidth.coerceAtLeast(dpToPx(200))

        params.x = calculateToolbarX(anchorX, anchorWidth, toolbarWidth, edge)
        params.y = anchorY - toolbar.measuredHeight - dpToPx(8)
        if (params.y < dpToPx(24)) {
            params.y = anchorY + anchorHeight + dpToPx(8)
        }

        try { windowManager.updateViewLayout(toolbar, params) } catch (_: Exception) {}
    }

    fun hide() {
        if (state == ToolbarState.COLLAPSED) return
        val toolbar = toolbarView ?: return

        toolbar.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(150)
            .withEndAction {
                try { windowManager.removeView(toolbar) } catch (_: Exception) {}
                toolbarView = null
                toolbarParams = null
                state = ToolbarState.COLLAPSED
            }
            .start()
    }

    fun isVisible(): Boolean = state == ToolbarState.EXPANDED

    fun destroy() {
        toolbarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        toolbarView = null
        toolbarParams = null
        state = ToolbarState.COLLAPSED
    }

    private fun calculateToolbarX(anchorX: Int, anchorWidth: Int, toolbarWidth: Int, edge: DockPosition): Int {
        if (edge == DockPosition.NONE) {
            return anchorX - toolbarWidth / 2 + anchorWidth / 2
        }
        return if (edge == DockPosition.LEFT) {
            anchorX + anchorWidth + dpToPx(4)
        } else {
            anchorX - toolbarWidth - dpToPx(4)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * density).toInt()
    }
}
