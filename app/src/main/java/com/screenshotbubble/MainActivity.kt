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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenshotbubble.service.ScreenshotService

class MainActivity : AppCompatActivity() {

    private lateinit var permissionOverlayButton: Button
    private lateinit var permissionCaptureButton: Button
    private lateinit var allSetContainer: LinearLayout
    private lateinit var overlayDesc: TextView
    private lateinit var captureDesc: TextView

    private var overlayGranted = false
    private var captureGranted = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            captureGranted = true
            startScreenshotService(result.resultCode, result.data!!)
            updateUI()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        checkPermissions()
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayDesc = findViewById(R.id.overlay_description)
        captureDesc = findViewById(R.id.capture_description)
        permissionOverlayButton = findViewById(R.id.button_overlay_permission)
        permissionCaptureButton = findViewById(R.id.button_capture_permission)
        allSetContainer = findViewById(R.id.all_set_container)

        permissionOverlayButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        permissionCaptureButton.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUI()
    }

    private fun checkPermissions() {
        overlayGranted = Settings.canDrawOverlays(this)
    }

    private fun updateUI() {
        if (overlayGranted && captureGranted) {
            permissionOverlayButton.visibility = android.view.View.GONE
            permissionCaptureButton.visibility = android.view.View.GONE
            overlayDesc.visibility = android.view.View.GONE
            captureDesc.visibility = android.view.View.GONE
            allSetContainer.visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.main_title).postDelayed({
                finish()
            }, 1500)
        } else if (overlayGranted) {
            permissionOverlayButton.visibility = android.view.View.GONE
            overlayDesc.visibility = android.view.View.GONE
            permissionCaptureButton.visibility = android.view.View.VISIBLE
            captureDesc.visibility = android.view.View.VISIBLE
            allSetContainer.visibility = android.view.View.GONE
        } else {
            permissionOverlayButton.visibility = android.view.View.VISIBLE
            overlayDesc.visibility = android.view.View.VISIBLE
            permissionCaptureButton.visibility = android.view.View.GONE
            captureDesc.visibility = android.view.View.GONE
            allSetContainer.visibility = android.view.View.GONE
        }
    }

    private fun startScreenshotService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenshotService::class.java).apply {
            putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenshotService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
