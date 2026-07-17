package com.screenshotbubble.features.modules

import android.content.Context
import android.widget.Toast
import com.screenshotbubble.features.FeatureModule

class LongScreenshotModule : FeatureModule {
    override val id: Int = 3
    override val isAvailable: Boolean = false

    override fun execute(context: Context) {
        Toast.makeText(context, "Long Screenshot — Coming Soon", Toast.LENGTH_SHORT).show()
    }

    override fun getDisplayName(): String = "Long Screenshot"
}
