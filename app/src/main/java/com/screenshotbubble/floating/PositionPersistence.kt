package com.screenshotbubble.floating

import android.content.Context
import android.content.SharedPreferences

class PositionPersistence(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("screenshot_bubble_widget", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_X = "last_x"
        private const val KEY_LAST_Y = "last_y"
        private const val KEY_DOCK_POSITION = "dock_position"
    }

    fun savePosition(x: Int, y: Int, position: DockPosition) {
        prefs.edit()
            .putInt(KEY_LAST_X, x)
            .putInt(KEY_LAST_Y, y)
            .putString(KEY_DOCK_POSITION, position.name)
            .apply()
    }

    fun restorePosition(): PositionData? {
        if (!prefs.contains(KEY_LAST_X)) return null
        val x = prefs.getInt(KEY_LAST_X, 0)
        val y = prefs.getInt(KEY_LAST_Y, 0)
        val posName = prefs.getString(KEY_DOCK_POSITION, DockPosition.RIGHT.name) ?: DockPosition.RIGHT.name
        val position = try {
            DockPosition.valueOf(posName)
        } catch (_: Exception) {
            DockPosition.RIGHT
        }
        return PositionData(x, y, position)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    data class PositionData(
        val x: Int,
        val y: Int,
        val position: DockPosition
    )
}
