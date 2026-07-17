package com.screenshotbubble.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenshotCapture {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var active = false

    fun captureScreenshotAsync(
        mediaProjection: MediaProjection,
        context: Context,
        onResult: (ScreenshotResult) -> Unit
    ) {
        if (active) {
            onResult(ScreenshotResult.Error("Capture already in progress"))
            return
        }
        active = true

        val metrics = getRealMetrics(context)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        if (width <= 0 || height <= 0) {
            active = false
            onResult(ScreenshotResult.Error("Invalid screen dimensions"))
            return
        }

        Log.d("ScreenshotCapture", "Starting async capture ${width}x${height} @ ${densityDpi}dpi")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val ht = HandlerThread("capture-callback").apply { start() }
        val callbackHandler = Handler(ht.looper)

        imageReader = reader
        handlerThread = ht

        val mainHandler = Handler(Looper.getMainLooper())

        reader.setOnImageAvailableListener({ r ->
            Log.d("ScreenshotCapture", "ImageReader callback fired")
            if (!active) return@setOnImageAvailableListener
            active = false

            val image = r.acquireLatestImage()
            if (image != null) {
                try {
                    val bmp = imageToBitmap(image)
                    Log.d("ScreenshotCapture", "Bitmap created: ${bmp.width}x${bmp.height}")
                    mainHandler.post {
                        val saver = ScreenshotSaver(context)
                        val result = saver.save(bmp)
                        Log.d("ScreenshotCapture", "Save result: $result")
                        cleanup()
                        onResult(result)
                    }
                } catch (e: Exception) {
                    Log.e("ScreenshotCapture", "Bitmap conversion failed", e)
                    mainHandler.post {
                        cleanup()
                        onResult(ScreenshotResult.Error("Bitmap conversion: ${e.message}"))
                    }
                } finally {
                    image.close()
                }
            } else {
                Log.w("ScreenshotCapture", "acquireLatestImage returned null")
                mainHandler.post {
                    cleanup()
                    onResult(ScreenshotResult.Error("No image data"))
                }
            }
        }, callbackHandler)

        try {
            val vd = mediaProjection.createVirtualDisplay(
                "ScreenshotCapture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            virtualDisplay = vd
            Log.d("ScreenshotCapture", "VirtualDisplay created successfully")
        } catch (e: Exception) {
            Log.e("ScreenshotCapture", "VirtualDisplay creation failed", e)
            cleanup()
            active = false
            onResult(ScreenshotResult.Error("VirtualDisplay: ${e.message}"))
        }

        mainHandler.postDelayed({
            if (active) {
                Log.w("ScreenshotCapture", "Capture timed out after 5s")
                active = false
                cleanup()
                onResult(ScreenshotResult.Error("Capture timed out"))
            }
        }, 5000)
    }

    private fun cleanup() {
        active = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
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
}
