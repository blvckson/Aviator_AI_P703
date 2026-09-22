package com.example.aviatorai

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastDetectedMultiplier: Double? = null
    private var lastProcessedTime = 0L

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode =
            intent?.getIntExtra("resultCode", -1) ?: return START_NOT_STICKY

        val data =
            intent.getParcelableExtra<Intent>("data")
                ?: return START_NOT_STICKY

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        mediaProjection =
            projectionManager.getMediaProjection(resultCode, data)

        startCapture()

        return START_STICKY
    }

    private fun startCapture() {

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener(
            { reader ->

                val now = System.currentTimeMillis()

                // Limit OCR frequency so the P703 is not overloaded.
                if (now - lastProcessedTime < 400L) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }

                lastProcessedTime = now

                val image = reader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                try {
                    val imageWidth = image.width
                    val imageHeight = image.height

                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding =
                        rowStride - pixelStride * imageWidth

                    val bitmapWidth =
                        imageWidth + rowPadding / pixelStride

                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth,
                        imageHeight,
                        Bitmap.Config.ARGB_8888
                    )

                    bitmap.copyPixelsFromBuffer(buffer)

                    processFrame(bitmap)

                } catch (_: Exception) {
                    // Ignore damaged frames and continue monitoring.
                } finally {
                    image.close()
                }

            },
            null
        )

        virtualDisplay =
            mediaProjection?.createVirtualDisplay(
                "AviatorAIScreenMonitor",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
    }

    private fun processFrame(bitmap: Bitmap) {

        ocrExecutor.execute {

            try {

                val image =
                    InputImage.fromBitmap(bitmap, 0)

                recognizer.process(image)
                    .addOnSuccessListener { result ->

                        val multiplier =
                            extractMultiplier(result.text)

                        if (multiplier != null) {
                            handleDetectedMultiplier(multiplier)
                        }
                    }
                    .addOnFailureListener {
                        // OCR failure is ignored.
                    }

            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun extractMultiplier(text: String): Double? {

        /*
         * Accept examples such as:
         *
         * 1.25x
         * 2.00x
         * 10.52x
         *
         * OCR may return spaces or uppercase X,
         * so the expression is intentionally tolerant.
         */

        val normalized =
            text
                .replace(',', '.')
                .replace('X', 'x')
                .replace(" ", "")

        val pattern =
            Pattern.compile(
                "(\\d+(?:\\.\\d+)?)x",
                Pattern.CASE_INSENSITIVE
            )

        val matcher =
            pattern.matcher(normalized)

        while (matcher.find()) {

            val value =
                matcher.group(1)?.toDoubleOrNull()
                    ?: continue

            /*
             * Basic sanity validation.
             * Aviator multipliers cannot be below 1.00x.
             */
            if (value >= 1.0 && value <= 1000000.0) {
                return value
            }
        }

        return null
    }

    private fun handleDetectedMultiplier(value: Double) {

        /*
         * Prevent the same OCR result from being
         * reported repeatedly every few hundred ms.
         */
        if (lastDetectedMultiplier == value) {
            return
        }

     lastDetectedMultiplier = value

android.util.Log.d(
    "AviatorAI",
    String.format(
        Locale.US,
        "VALID MULTIPLIER DETECTED: %.2fx",
        value
    )
)

val updateIntent = Intent("com.example.aviatorai.MULTIPLIER_DETECTED").apply {
    putExtra("multiplier", value)
}

sendBroadcast(updateIntent)
    }

    override fun onDestroy() {

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        recognizer.close()
        ocrExecutor.shutdown()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
