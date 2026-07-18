package com.screenshotbubble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.screenshotbubble.capture.ScreenshotSaver
import com.screenshotbubble.databinding.ActivityMainBinding
import com.screenshotbubble.service.ScreenshotService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var overlayGranted = false
    private var captureGranted = false
    private var isServiceRunning = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            captureGranted = true
            startScreenshotService(result.resultCode, result.data!!)
            refreshDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnCapture.setOnClickListener { requestCapturePermission() }
        binding.btnService.setOnClickListener { toggleService() }

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

    private fun refreshDashboard() {
        renderPermissionCard(
            binding.cardOverlay,
            binding.overlayIcon,
            binding.overlayTitle,
            binding.overlayDesc,
            binding.btnOverlay,
            granted = overlayGranted,
            titleGranted = getString(R.string.permission_overlay_granted),
            titlePending = getString(R.string.permission_overlay_title),
            descPending = getString(R.string.permission_overlay_desc),
            actionLabel = getString(R.string.permission_overlay_action)
        )

        renderPermissionCard(
            binding.cardCapture,
            binding.captureIcon,
            binding.captureTitle,
            binding.captureDesc,
            binding.btnCapture,
            granted = captureGranted,
            titleGranted = getString(R.string.permission_capture_granted),
            titlePending = getString(R.string.permission_capture_title),
            descPending = getString(R.string.permission_capture_desc),
            actionLabel = getString(R.string.permission_capture_action)
        )

        val allReady = overlayGranted && captureGranted
        binding.allSetBanner.visibility = if (allReady) View.VISIBLE else View.GONE
        binding.btnService.visibility = if (allReady) View.VISIBLE else View.GONE
        if (allReady) {
            binding.btnService.text = if (isServiceRunning)
                getString(R.string.service_stop) else getString(R.string.service_start)
        }

        loadScreenshotCount()
        updateStatusBadge()
    }

    private fun renderPermissionCard(
        card: View,
        icon: ImageView,
        titleView: TextView,
        descView: TextView,
        actionBtn: MaterialButton,
        granted: Boolean,
        titleGranted: String,
        titlePending: String,
        descPending: String,
        actionLabel: String
    ) {
        if (granted) {
            icon.setImageResource(R.drawable.ic_check_circle)
            icon.backgroundTintList = ContextCompat.getColorStateList(this, R.color.success_green_light)
            titleView.text = titleGranted
            titleView.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            descView.visibility = View.GONE
            actionBtn.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_check)
            icon.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_blue_light)
            titleView.text = titlePending
            titleView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            descView.text = descPending
            descView.visibility = View.VISIBLE
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
        binding.statusBadge.text = if (isServiceRunning)
            getString(R.string.service_active) else getString(R.string.service_inactive)
        binding.statusBadge.backgroundTintList = ContextCompat.getColorStateList(
            this, if (isServiceRunning) R.color.success_green_light else R.color.divider
        )
        binding.statusBadge.setTextColor(
            ContextCompat.getColor(
                this, if (isServiceRunning) R.color.success_green else R.color.text_tertiary
            )
        )
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
