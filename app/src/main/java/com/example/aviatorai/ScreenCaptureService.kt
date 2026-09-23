```kotlin
package com.example.aviatorai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

class ScreenCaptureService : Service() {

    companion object {

        private const val CHANNEL_ID =
            "aviator_monitor"

        private const val NOTIFICATION_ID =
            1001

        private const val DIAGNOSTIC_ACTION =
            "com.example.aviatorai.DIAGNOSTIC"

        private const val LIVE_MULTIPLIER_ACTION =
            "com.example.aviatorai.MULTIPLIER_LIVE"

        private const val ROUND_COMPLETED_ACTION =
            "com.example.aviatorai.ROUND_COMPLETED"
    }

    private var mediaProjection:
            MediaProjection? = null

    private var virtualDisplay:
            VirtualDisplay? = null

    private var imageReader:
            ImageReader? = null

    private val ocrExecutor =
        Executors.newSingleThreadExecutor()

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private var lastProcessedTime = 0L

    private var lastDiagnosticTime = 0L

    private var frameCount = 0

    private var lastLiveMultiplier:
            Double? = null

    private var lastRecordedMultiplier:
            Double? = null

    private var whiteMultiplierSeen = false

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        sendDiagnostic(
            "Monitor service created"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        sendDiagnostic(
            "Screen capture service started"
        )

        val resultCode =
            intent?.getIntExtra(
                "resultCode",
                -1
            ) ?: -1

        val data =
            intent?.getParcelableExtra<Intent>(
                "data"
            )

        if (resultCode == -1 || data == null) {

            val reason =
                when {
                    intent == null ->
                        "ERROR: service received NULL intent"

                    resultCode == -1 && data == null ->
                        "ERROR: resultCode AND data missing"

                    resultCode == -1 ->
                        "ERROR: resultCode missing"

                    else ->
                        "ERROR: data Intent missing"
                }

            sendDiagnostic(reason)

            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        if (mediaProjection == null) {

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    data
                )
        }

        if (mediaProjection == null) {

            sendDiagnostic(
                "ERROR: MediaProjection unavailable"
            )

            return START_NOT_STICKY
        }

        if (virtualDisplay == null) {

            startCapture()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Aviator Screen Monitor",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Aviator AI screen monitoring"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun createNotification():
            Notification {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "Aviator AI"
                )
                .setContentText(
                    "Screen monitor is running"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .build()

        } else {

            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(
                    "Aviator AI"
                )
                .setContentText(
                    "Screen monitor is running"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .build()
        }
    }

    private fun startCapture() {

        val windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        val metrics =
            DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
            .getMetrics(metrics)

        val width =
            metrics.widthPixels

        val height =
            metrics.heightPixels

        val density =
            metrics.densityDpi

        sendDiagnostic(
            "Display: ${width}x${height}"
        )

        imageReader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

        imageReader?.setOnImageAvailableListener(
            { reader ->

                frameCount++

                val now =
                    System.currentTimeMillis()

                if (
                    now - lastProcessedTime <
                    500L
                ) {

                    reader.acquireLatestImage()
                        ?.close()

                    return@setOnImageAvailableListener
                }

                lastProcessedTime =
                    now

                val image =
                    reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {

                    val imageWidth =
                        image.width

                    val imageHeight =
                        image.height

                    val plane =
                        image.planes[0]

                    val buffer =
                        plane.buffer

                    val pixelStride =
                        plane.pixelStride

                    val rowStride =
                        plane.rowStride

                    val rowPadding =
                        rowStride -
                            pixelStride *
                            imageWidth

                    val bitmapWidth =
                        imageWidth +
                            rowPadding /
                            pixelStride

                    val bitmap =
                        Bitmap.createBitmap(
                            bitmapWidth,
                            imageHeight,
                            Bitmap.Config.ARGB_8888
                        )

                    buffer.rewind()

                    bitmap.copyPixelsFromBuffer(
                        buffer
                    )

                    processFrame(bitmap)

                } catch (_: Exception) {

                    sendDiagnostic(
                        "ERROR processing screen frame"
                    )

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

        if (virtualDisplay != null) {

            sendDiagnostic(
                "Screen capture active"
            )

        } else {

            sendDiagnostic(
                "ERROR: VirtualDisplay failed"
            )
        }
    }

    private fun processFrame(
        bitmap: Bitmap
    ) {

        val cropWidth =
            (bitmap.width * 0.80f)
                .toInt()

        val cropHeight =
            (bitmap.height * 0.60f)
                .toInt()

        val cropLeft =
            ((bitmap.width - cropWidth) / 2)
                .coerceAtLeast(0)

        val cropTop =
            ((bitmap.height - cropHeight) / 2)
                .coerceAtLeast(0)

        val safeWidth =
            cropWidth.coerceAtMost(
                bitmap.width - cropLeft
            )

        val safeHeight =
            cropHeight.coerceAtMost(
                bitmap.height - cropTop
            )

        if (
            safeWidth <= 0 ||
            safeHeight <= 0
        ) {

            bitmap.recycle()

            sendDiagnostic(
                "ERROR: invalid OCR crop"
            )

            return
        }

        val croppedBitmap =
            try {

                Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    safeWidth,
                    safeHeight
                )

            } catch (_: Exception) {

                bitmap.recycle()

                sendDiagnostic(
                    "ERROR: bitmap crop failed"
                )

                return
            }

        if (
            croppedBitmap !== bitmap &&
            !bitmap.isRecycled
        ) {

            bitmap.recycle()
        }

        ocrExecutor.execute {

            val inputImage =
                try {

                    InputImage.fromBitmap(
                        croppedBitmap,
                        0
                    )

                } catch (_: Exception) {

                    if (
                        !croppedBitmap.isRecycled
                    ) {
                        croppedBitmap.recycle()
                    }

                    return@execute
                }

            recognizer.process(
                inputImage
            )
                .addOnSuccessListener { result ->

                    try {

                        processOcrResult(
                            result,
                            croppedBitmap
                        )

                    } catch (_: Exception) {

                        sendDiagnostic(
                            "ERROR processing OCR result"
                        )

                    } finally {

                        if (
                            !croppedBitmap.isRecycled
                        ) {

                            croppedBitmap.recycle()
                        }
                    }
                }
                .addOnFailureListener {

                    sendDiagnostic(
                        "OCR processing failed"
                    )

                    if (
                        !croppedBitmap.isRecycled
                    ) {

                        croppedBitmap.recycle()
                    }
                }
        }
    }

    private fun processOcrResult(
        result: Text,
        bitmap: Bitmap
    ) {

        val fullText =
            result.text.trim()

        val now =
            System.currentTimeMillis()

        if (
            now - lastDiagnosticTime >
            3000L
        ) {

            lastDiagnosticTime = now

            if (fullText.isEmpty()) {

                sendDiagnostic(
                    "Frames received: $frameCount | OCR text: NONE"
                )

            } else {

                val preview =
                    fullText
                        .replace(
                            "\n",
                            " "
                        )
                        .take(80)

                sendDiagnostic(
                    "OCR text: $preview"
                )
            }
        }

        val pattern =
            Pattern.compile(
                "(\\d+(?:[\\.,]\\d+)?)\\s*[×xX]?"
            )

        var detectedValue:
                Double? = null

        var detectedIsRed =
            false

        for (
            block in result.textBlocks
        ) {

            for (
                line in block.lines
            ) {

                for (
                    element in line.elements
                ) {

                    val rawText =
                        element.text.trim()

                    val normalizedText =
                        rawText.replace(
                            ',',
                            '.'
                        )

                    val matcher =
                        pattern.matcher(
                            normalizedText
                        )

                    if (
                        !matcher.find()
                    ) {
                        continue
                    }

                    val value =
                        matcher
                            .group(1)
                            ?.toDoubleOrNull()
                            ?: continue

                    if (
                        value < 1.0 ||
                        value > 1000000.0
                    ) {
                        continue
                    }

                    detectedValue =
                        value

                    val box =
                        element.boundingBox

                    if (
                        box != null
                    ) {

                        detectedIsRed =
                            containsRedPixels(
                                bitmap,
                                box.left,
                                box.top,
                                box.right,
                                box.bottom
                            )
                    }

                    break
                }

                if (
                    detectedValue != null
                ) {
                    break
                }
            }

            if (
                detectedValue != null
            ) {
                break
            }
        }

        val value =
            detectedValue
                ?: return

        lastLiveMultiplier =
            value

        if (!detectedIsRed) {

            whiteMultiplierSeen =
                true

            sendLiveMultiplier(
                value
            )

            return
        }

        if (!whiteMultiplierSeen) {
            return
        }

        if (
            lastRecordedMultiplier != value
        ) {

            lastRecordedMultiplier =
                value

            recordCompletedRound(
                value
            )
        }

        whiteMultiplierSeen =
            false
    }

    private fun containsRedPixels(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {

        val safeLeft =
            left.coerceIn(
                0,
                bitmap.width - 1
            )

        val safeTop =
            top.coerceIn(
                0,
                bitmap.height - 1
            )

        val safeRight =
            right.coerceIn(
                safeLeft + 1,
                bitmap.width
            )

        val safeBottom =
            bottom.coerceIn(
                safeTop + 1,
                bitmap.height
            )

        var redPixels =
            0

        var sampledPixels =
            0

        val step =
            2

        var y =
            safeTop

        while (
            y < safeBottom
        ) {

            var x =
                safeLeft

            while (
                x < safeRight
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val red =
                    Color.red(pixel)

                val green =
                    Color.green(pixel)

                val blue =
                    Color.blue(pixel)

                if (
                    red > 150 &&
                    red > green * 1.4 &&
                    red > blue * 1.4
                ) {

                    redPixels++
                }

                sampledPixels++

                x += step
            }

            y += step
        }

        if (
            sampledPixels == 0
        ) {

            return false
        }

        return (
            redPixels.toDouble() /
                sampledPixels.toDouble()
            ) >= 0.08
    }

    private fun sendLiveMultiplier(
        value: Double
    ) {

        val updateIntent =
            Intent(
                LIVE_MULTIPLIER_ACTION
            ).apply {

                putExtra(
                    "multiplier",
                    value
                )
            }

        sendBroadcast(
            updateIntent
        )

        android.util.Log.d(
            "AviatorAI",
            String.format(
                Locale.US,
                "LIVE MULTIPLIER: %.2f×",
                value
            )
        )
    }

    private fun recordCompletedRound(
        value: Double
    ) {

        val updateIntent =
            Intent(
                ROUND_COMPLETED_ACTION
            ).apply {

                putExtra(
                    "multiplier",
                    value
                )
            }

        sendBroadcast(
            updateIntent
        )

        android.util.Log.d(
            "AviatorAI",
            String.format(
                Locale.US,
                "COMPLETED ROUND: %.2f×",
                value
            )
        )
    }

    private fun sendDiagnostic(
        message: String
    ) {

        val diagnosticIntent =
            Intent(
                DIAGNOSTIC_ACTION
            ).apply {

                putExtra(
                    "message",
                    message
                )
            }

        sendBroadcast(
            diagnosticIntent
        )

        android.util.Log.d(
            "AviatorAI",
            message
        )
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

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
```
