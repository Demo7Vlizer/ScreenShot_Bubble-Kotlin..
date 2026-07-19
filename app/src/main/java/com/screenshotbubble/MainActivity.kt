package com.screenshotbubble

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.screenshotbubble.capture.ScreenshotSaver
import com.screenshotbubble.databinding.ActivityMainBinding
import com.screenshotbubble.service.ScreenshotService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "screenshot_bubble_widget"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SCREENSHOT_DELAY = "screenshot_delay"
        private const val MODE_LIGHT = "light"
        private const val MODE_DARK = "dark"
        private const val MODE_SYSTEM = "system"
        private const val DELAY_INSTANT = "instant"
        private const val DELAY_3S = "3"
        private const val DELAY_5S = "5"
        private const val DELAY_10S = "10"
    }

    private lateinit var binding: ActivityMainBinding
    private var overlayGranted = false
    private var captureGranted = false
    private var isServiceRunning = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            captureGranted = true
            startScreenshotService(result.resultCode, result.data!!)
            refreshDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnCapture.setOnClickListener { requestCapturePermission() }
        binding.btnService.setOnClickListener { toggleService() }
        binding.appearanceCard.setOnClickListener { showThemeSheet() }
        binding.screenshotModeRow.setOnClickListener { showScreenshotModeSheet() }

        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
        refreshDashboard()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
                    .launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun getThemeMode(): String = getPrefs().getString(KEY_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM

    private fun getScreenshotDelay(): String = getPrefs().getString(KEY_SCREENSHOT_DELAY, DELAY_INSTANT) ?: DELAY_INSTANT

    private fun applyTheme() {
        when (getThemeMode()) {
            MODE_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            MODE_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun showScreenshotModeSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_screenshot_mode, null)
        dialog.setContentView(sheet)

        val modeViews = listOf(
            sheet.findViewById<TextView>(R.id.mode_instant),
            sheet.findViewById<TextView>(R.id.mode_3s),
            sheet.findViewById<TextView>(R.id.mode_5s),
            sheet.findViewById<TextView>(R.id.mode_10s)
        )

        modeViews.forEach { view ->
            view.setOnClickListener {
                val tag = when (view.id) {
                    R.id.mode_instant -> DELAY_INSTANT
                    R.id.mode_3s -> DELAY_3S
                    R.id.mode_5s -> DELAY_5S
                    R.id.mode_10s -> DELAY_10S
                    else -> DELAY_INSTANT
                }
                getPrefs().edit().putString(KEY_SCREENSHOT_DELAY, tag).apply()
                updateScreenshotModeLabel()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun updateScreenshotModeLabel() {
        val label = when (getScreenshotDelay()) {
            DELAY_3S -> getString(R.string.screenshot_mode_3s)
            DELAY_5S -> getString(R.string.screenshot_mode_5s)
            DELAY_10S -> getString(R.string.screenshot_mode_10s)
            else -> getString(R.string.screenshot_mode_instant)
        }
        binding.modeLabel.text = label
        binding.modeValue.text = label
    }

    private fun showThemeSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_theme, null)
        dialog.setContentView(sheet)

        sheet.findViewById<TextView>(R.id.theme_light).setOnClickListener {
            getPrefs().edit().putString(KEY_THEME_MODE, MODE_LIGHT).apply()
            dialog.dismiss()
            recreate()
        }
        sheet.findViewById<TextView>(R.id.theme_dark).setOnClickListener {
            getPrefs().edit().putString(KEY_THEME_MODE, MODE_DARK).apply()
            dialog.dismiss()
            recreate()
        }
        sheet.findViewById<TextView>(R.id.theme_system).setOnClickListener {
            getPrefs().edit().putString(KEY_THEME_MODE, MODE_SYSTEM).apply()
            dialog.dismiss()
            recreate()
        }

        dialog.show()
    }

    private fun refreshDashboard() {
        updatePermissionRow(
            icon = binding.overlayIcon,
            titleView = binding.overlayTitle,
            badgeView = binding.overlayBadge,
            actionBtn = binding.btnOverlay,
            granted = overlayGranted,
            titleGranted = getString(R.string.permission_overlay_granted),
            titlePending = getString(R.string.permission_overlay_title),
            actionLabel = getString(R.string.permission_overlay_action)
        )
        updatePermissionRow(
            icon = binding.captureIcon,
            titleView = binding.captureTitle,
            badgeView = binding.captureBadge,
            actionBtn = binding.btnCapture,
            granted = captureGranted,
            titleGranted = getString(R.string.permission_capture_granted),
            titlePending = getString(R.string.permission_capture_title),
            actionLabel = getString(R.string.permission_capture_action)
        )

        val allReady = overlayGranted && captureGranted
        binding.btnService.visibility = if (allReady) View.VISIBLE else View.INVISIBLE
        if (allReady) {
            binding.btnService.text = if (isServiceRunning)
                getString(R.string.service_stop) else getString(R.string.service_start)
        }

        loadScreenshotCount()
        updateStatusBadge()
        updateThemeLabel()
        updateScreenshotModeLabel()
    }

    private fun updateThemeLabel() {
        val modeName = when (getThemeMode()) {
            MODE_LIGHT -> getString(R.string.appearance_light)
            MODE_DARK -> getString(R.string.appearance_dark)
            else -> getString(R.string.appearance_system)
        }
        binding.appearanceMode.text = modeName
        binding.appearanceDesc.text = modeName
    }

    private fun updatePermissionRow(
        icon: View,
        titleView: TextView,
        badgeView: TextView,
        actionBtn: MaterialButton,
        granted: Boolean,
        titleGranted: String,
        titlePending: String,
        actionLabel: String
    ) {
        if (granted) {
            icon.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.success_bg))
            titleView.text = titleGranted
            titleView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            badgeView.apply {
                text = getString(R.string.badge_granted)
                setTextColor(ContextCompat.getColor(context, R.color.success))
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.success_bg)
                visibility = View.VISIBLE
            }
            actionBtn.visibility = View.GONE
        } else {
            icon.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.warning_bg))
            titleView.text = titlePending
            titleView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            badgeView.apply {
                text = getString(R.string.badge_required)
                setTextColor(ContextCompat.getColor(context, R.color.warning))
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.warning_bg)
                visibility = View.VISIBLE
            }
            actionBtn.text = actionLabel
            actionBtn.visibility = View.VISIBLE
        }
    }

    private fun loadScreenshotCount() {
        val count = ScreenshotSaver(this).getTotalCount()
        binding.statsCount.text = count.toString()
        binding.statsLabel.text = if (count == 0)
            getString(R.string.stats_empty) else getString(R.string.stats_title)
    }

    private fun updateStatusBadge() {
        val active = isServiceRunning
        binding.statusBadge.text = if (active)
            getString(R.string.service_active) else getString(R.string.service_inactive)
        binding.statusBadge.backgroundTintList = ContextCompat.getColorStateList(
            this, if (active) R.color.success_bg else R.color.divider
        )
        binding.statusBadge.setTextColor(
            ContextCompat.getColor(
                this, if (active) R.color.success else R.color.text_tertiary
            )
        )
        binding.statusBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestCapturePermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toggleService() {
        if (isServiceRunning) {
            stopService(Intent(this, ScreenshotService::class.java))
            isServiceRunning = false
            refreshDashboard()
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun startScreenshotService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenshotService::class.java).apply {
            putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenshotService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        refreshDashboard()
    }
}
