package com.screenshotbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat

class ScreenshotService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_bubble_service"
        const val ACTION_STOP_SERVICE = "com.screenshotbubble.ACTION_STOP_SERVICE"
        private const val REOPEN_NOTIFICATION_ID = 1002
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
    }

    private var mediaProjection: MediaProjection? = null
    private var floatingIconManager: FloatingIconManager? = null
    private var screenshotCapture: ScreenshotCapture? = null
    private var mediaProjectionCallback: MediaProjectionCallback? = null

    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                floatingIconManager?.onConfigurationChanged()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configurationReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(configurationReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_CODE) || !intent.hasExtra(EXTRA_DATA)) {
            handleRestartWithoutData()
            return START_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            handleRestartWithoutData()
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            handleMissingProjection()
            return START_STICKY
        }

        val callback = MediaProjectionCallback()
        mediaProjectionCallback = callback
        mediaProjection?.registerCallback(callback, null)

        screenshotCapture = ScreenshotCapture()

        floatingIconManager = FloatingIconManager(
            this,
            getSystemService(WINDOW_SERVICE) as android.view.WindowManager,
            object : FloatingIconManager.Callback {
                override fun onScreenshotRequested() {
                    takeScreenshot()
                }
                override fun onCloseRequested() {
                    stopSelf()
                }
            }
        )

        try {
            floatingIconManager?.createFloatingIcon()
        } catch (e: Exception) {
            handleMissingProjection()
            return START_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        floatingIconManager?.cleanup()
        floatingIconManager = null
        val cb = mediaProjectionCallback
        val mp = mediaProjection
        if (cb != null && mp != null) {
            mp.unregisterCallback(cb)
        }
        mediaProjection?.stop()
        mediaProjection = null
        screenshotCapture = null
        try {
            unregisterReceiver(configurationReceiver)
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun takeScreenshot() {
        val manager = floatingIconManager ?: return
        val projection = mediaProjection ?: return
        val capture = screenshotCapture ?: return

        manager.setCaptureInProgress(true)
        manager.hideFloatingIcon()

        mainHandler.postDelayed({
            val uri = capture.captureScreenshot(projection, this@ScreenshotService)

            manager.showFloatingIcon()
            manager.setCaptureInProgress(false)

            if (uri != null) {
                manager.showThumbnailPreview(uri)
            } else {
                manager.showErrorMessage(getString(R.string.error_save_failed))
            }
        }, 100)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun handleRestartWithoutData() {
        val reopenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, reopenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_reopen_title))
            .setContentText(getString(R.string.notification_reopen_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleMissingProjection() {
        showReopenNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleProjectionStopped() {
        floatingIconManager?.cleanup()
        floatingIconManager = null
        mediaProjection = null
        screenshotCapture = null
        showReopenNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, StopServiceReceiver::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun showReopenNotification() {
        val reopenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, reopenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_reopen_title))
            .setContentText(getString(R.string.notification_reopen_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(REOPEN_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            handleProjectionStopped()
        }
    }
}

class StopServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ScreenshotService.ACTION_STOP_SERVICE) {
            val service = Intent(context, ScreenshotService::class.java)
            context.stopService(service)
        }
    }
}
