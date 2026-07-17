package com.screenshotbubble.features.modules

import android.content.Context
import android.widget.Toast
import com.screenshotbubble.features.FeatureModule

class ScreenRecordModule : FeatureModule {
    override val id: Int = 2
    override val isAvailable: Boolean = false

    override fun execute(context: Context) {
        Toast.makeText(context, "Screen Recording — Coming Soon", Toast.LENGTH_SHORT).show()
    }

    override fun getDisplayName(): String = "Screen Recording"
}
