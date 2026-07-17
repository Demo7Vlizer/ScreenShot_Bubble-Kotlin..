package com.screenshotbubble.features.modules

import android.content.Context
import com.screenshotbubble.features.FeatureModule

class ScreenshotModule : FeatureModule {
    override val id: Int = 1
    override val isAvailable: Boolean = true

    override fun execute(context: Context) {
    }

    override fun getDisplayName(): String = "Screenshot"
}
