package com.dopix.app.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Captures screenshots using MediaProjection API.
 * Must be initialized with a valid result from MediaProjection permission request.
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 420

    fun init(resultCode: Int, data: Intent) {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    fun captureScreen(callback: (Bitmap?) -> Unit) {
        if (mediaProjection == null) {
            callback(null)
            return
        }

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DopixCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, Handler(Looper.getMainLooper())
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop to actual screen size
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                if (cropped != bitmap) bitmap.recycle()

                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null

                callback(cropped)
            } else {
                callback(null)
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 60): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
