package com.screenshotbubble.features.modules

import android.content.Context
import android.widget.Toast
import com.screenshotbubble.features.FeatureModule

class SettingsModule : FeatureModule {
    override val id: Int = 5
    override val isAvailable: Boolean = false

    override fun execute(context: Context) {
        Toast.makeText(context, "Settings — Coming Soon", Toast.LENGTH_SHORT).show()
    }

    override fun getDisplayName(): String = "Settings"
}
