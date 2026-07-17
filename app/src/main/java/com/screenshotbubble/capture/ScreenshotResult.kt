package com.screenshotbubble.capture

sealed class ScreenshotResult {
    data class Success(val uri: android.net.Uri, val count: Int) : ScreenshotResult()
    data class Error(val message: String) : ScreenshotResult()
}
