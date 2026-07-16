package com.screenshotbubble

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenshotCapture {

    fun captureScreenshot(mediaProjection: MediaProjection?, context: Context): Uri? {
        if (mediaProjection == null) return null

        val metrics = getRealMetrics(context)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        if (width <= 0 || height <= 0) return null

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val latch = CountDownLatch(1)
        val thread = HandlerThread("capture").apply { start() }
        val handler = Handler(thread.looper)

        var capturedBitmap: Bitmap? = null
        var captureError: Exception? = null

        imageReader.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        capturedBitmap = imageToBitmap(image)
                    } finally {
                        image.close()
                    }
                }
            } catch (e: Exception) {
                captureError = e
            } finally {
                latch.countDown()
            }
        }, handler)

        var virtualDisplay: VirtualDisplay? = null
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "capture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
        } catch (e: Exception) {
            captureError = e
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS)

        virtualDisplay?.release()
        imageReader.close()
        thread.quitSafely()

        val bmp = capturedBitmap ?: return null

        return saveToMediaStore(bmp, context)
    }

    private fun getRealMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        buffer.rewind()
        if (rowStride == width * pixelStride) {
            var i = 0
            while (i < pixels.size && buffer.hasRemaining()) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    val off = rowStart + col * pixelStride
                    val r = buffer.get(off).toInt() and 0xFF
                    val g = buffer.get(off + 1).toInt() and 0xFF
                    val b = buffer.get(off + 2).toInt() and 0xFF
                    val a = buffer.get(off + 3).toInt() and 0xFF
                    pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun saveToMediaStore(bitmap: Bitmap, context: Context): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Screenshot_$timestamp.png"

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
            return null
        } ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            return uri
        } catch (e: Exception) {
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }
}
