package com.screenshotbubble.features.modules

import android.content.Context
import android.widget.Toast
import com.screenshotbubble.features.FeatureModule

class OcrModule : FeatureModule {
    override val id: Int = 4
    override val isAvailable: Boolean = false

    override fun execute(context: Context) {
        Toast.makeText(context, "OCR Text Capture — Coming Soon", Toast.LENGTH_SHORT).show()
    }

    override fun getDisplayName(): String = "OCR"
}
