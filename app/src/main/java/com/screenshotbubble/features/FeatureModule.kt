package com.screenshotbubble.features

import android.content.Context

interface FeatureModule {
    val id: Int
    val isAvailable: Boolean
    fun execute(context: Context)
    fun getDisplayName(): String
}
