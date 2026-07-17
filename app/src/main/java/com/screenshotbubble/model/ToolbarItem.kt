package com.screenshotbubble.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class ToolbarItem(
    val id: Int,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val isActive: Boolean,
    val showComingSoon: Boolean
)
