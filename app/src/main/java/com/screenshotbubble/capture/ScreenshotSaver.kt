package com.screenshotbubble.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotSaver(private val context: Context) {

    private val prefs = context.getSharedPreferences("screenshot_bubble_widget", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SCREENSHOT_COUNT = "total_screenshots"
    }

    fun save(bitmap: Bitmap): ScreenshotResult {
        val count = incrementCount()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Screenshot_${timestamp}.png"
        Log.d("ScreenshotSaver", "Saving screenshot #$count as $filename")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenshotBubble")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri("external")
        }

        val uri = try {
            context.contentResolver.insert(collectionUri, values)
        } catch (e: Exception) {
            return ScreenshotResult.Error("Failed to insert: ${e.message}")
        } ?: return ScreenshotResult.Error("Failed to create entry")

        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } ?: return ScreenshotResult.Error("Failed to open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            Log.i("ScreenshotSaver", "Saved screenshot #$count → $uri")
            return ScreenshotResult.Success(uri, count)
        } catch (e: Exception) {
            Log.e("ScreenshotSaver", "Failed to save screenshot", e)
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return ScreenshotResult.Error("Failed to save: ${e.message}")
        }
    }

    fun getTotalCount(): Int {
        return prefs.getInt(KEY_SCREENSHOT_COUNT, 0)
    }

    private fun incrementCount(): Int {
        val current = getTotalCount() + 1
        prefs.edit().putInt(KEY_SCREENSHOT_COUNT, current).apply()
        return current
    }
}
