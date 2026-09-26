package com.example.aviatorai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {

        private const val CHANNEL_ID =
            "aviator_monitor"

        private const val NOTIFICATION_ID =
            1001

        private const val ROUND_COMPLETED_ACTION =
            "com.example.aviatorai.ROUND_COMPLETED"

        private const val LIVE_MULTIPLIER_ACTION =
            "com.example.aviatorai.MULTIPLIER_LIVE"

        private const val DIAGNOSTIC_ACTION =
            "com.example.aviatorai.DIAGNOSTIC"
    }

    private var mediaProjection: MediaProjection? =
        null

    private var virtualDisplay: VirtualDisplay? =
        null

    private var imageReader: ImageReader? =
        null

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val ocrExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val textRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private var lastLiveMultiplier =
        -1.0

    private var lastCompletedMultiplier =
        -1.0

    private var lastOcrTime =
        0L

    private val multiplierPattern =
        Pattern.compile(
            "(\\d+(?:[\\.,]\\d+)?)\\s*[×xX]?"
        )

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        sendDiagnostic(
            "Screen capture service created"
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

        if (intent == null) {

            sendDiagnostic(
                "ERROR: service received NULL intent"
            )

            return START_NOT_STICKY
        }

        if (!intent.hasExtra("resultCode")) {

            sendDiagnostic(
                "ERROR: resultCode extra is missing"
            )

            return START_NOT_STICKY
        }

        val resultCode =
            intent.getIntExtra(
                "resultCode",
                Int.MIN_VALUE
            )

        val data =
            if (intent.hasExtra("data")) {

                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(
                    "data"
                )

            } else {
                null
            }

        sendDiagnostic(
            "Received resultCode=$resultCode | data=${data != null}"
        )

        if (
            resultCode !=
            android.app.Activity.RESULT_OK
        ) {

            sendDiagnostic(
                "ERROR: screen capture permission was not approved. resultCode=$resultCode"
            )

            return START_NOT_STICKY
        }

        if (data == null) {

            sendDiagnostic(
                "ERROR: capture permission data is NULL"
            )

            return START_NOT_STICKY
        }

        sendDiagnostic(
            "Valid screen capture permission received"
        )

        startScreenCapture(
            resultCode,
            data
        )

        return START_STICKY
    }

    private fun startScreenCapture(
        resultCode: Int,
        data: Intent
    ) {

        try {

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    data
                )

            if (mediaProjection == null) {

                sendDiagnostic(
                    "ERROR: MediaProjection is NULL"
                )

                return
            }

            sendDiagnostic(
                "MediaProjection created successfully"
            )

            val metrics =
                DisplayMetrics()

            val windowManager =
                getSystemService(
                    WINDOW_SERVICE
                ) as android.view.WindowManager

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(
                metrics
            )

            val width =
                metrics.widthPixels

            val height =
                metrics.heightPixels

            val density =
                metrics.densityDpi

            sendDiagnostic(
                "Screen: ${width}x${height}"
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
                    processImage(reader)
                },
                mainHandler
            )

            virtualDisplay =
                mediaProjection?.createVirtualDisplay(
                    "AviatorScreenMonitor",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    mainHandler
                )

            if (virtualDisplay == null) {

                sendDiagnostic(
                    "ERROR: VirtualDisplay is NULL"
                )

                return
            }

            sendDiagnostic(
                "Screen capture active"
            )

        } catch (e: Exception) {

            sendDiagnostic(
                "ERROR starting capture: ${e.message}"
            )
        }
    }

    private fun processImage(
        reader: ImageReader
    ) {

        val now =
            System.currentTimeMillis()

        if (
            now - lastOcrTime <
            250L
        ) {
            return
        }

        lastOcrTime =
            now

        val image =
            try {

                reader.acquireLatestImage()

            } catch (
                e: Exception
            ) {

                null
            }

        if (image == null) {
            return
        }

        try {

            val width =
                image.width

            val height =
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
                    pixelStride * width

            val bitmapWidth =
                width +
                    rowPadding /
                    pixelStride

            val bitmap =
                Bitmap.createBitmap(
                    bitmapWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )

            bitmap.copyPixelsFromBuffer(
                buffer
            )

            /*
             * Crop the central portion of the screen.
             * This is where the Aviator multiplier
             * is normally visible.
             */

            val cropWidth =
                (bitmap.width * 0.80f)
                    .toInt()

            val cropHeight =
                (bitmap.height * 0.60f)
                    .toInt()

            val left =
                (bitmap.width -
                    cropWidth) / 2

            val top =
                (bitmap.height -
                    cropHeight) / 2

            val safeWidth =
                cropWidth.coerceAtMost(
                    bitmap.width - left
                )

            val safeHeight =
                cropHeight.coerceAtMost(
                    bitmap.height - top
                )

            if (
                safeWidth <= 0 ||
                safeHeight <= 0
            ) {

                bitmap.recycle()

                return
            }

            val crop =
                Bitmap.createBitmap(
                    bitmap,
                    left,
                    top,
                    safeWidth,
                    safeHeight
                )

            bitmap.recycle()

            runOcr(
                crop
            )

        } catch (e: Exception) {

            sendDiagnostic(
                "ERROR processing screen: ${e.message}"
            )

        } finally {

            image.close()
        }
    }

    /*
     * OCR TEST VERSION
     *
     * This version deliberately displays
     * the raw OCR text so we can see exactly
     * what ML Kit is reading from the screen.
     */

    private fun runOcr(
        bitmap: Bitmap
    ) {

        val inputImage =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        textRecognizer
            .process(inputImage)
            .addOnSuccessListener { result ->

                val text =
                    result.text

                if (text.isBlank()) {

                    sendDiagnostic(
                        "OCR running: no text recognized"
                    )

                    bitmap.recycle()

                    return@addOnSuccessListener
                }

                /*
                 * Show the actual OCR result.
                 * New lines are converted to separators
                 * so the Android status field can display it.
                 */

                val diagnosticText =
                    text
                        .replace(
                            "\n",
                            " | "
                        )
                        .replace(
                            "\r",
                            " "
                        )
                        .trim()
                        .take(300)

                sendDiagnostic(
                    "OCR RAW: $diagnosticText"
                )

                val multiplier =
                    extractMultiplier(
                        text
                    )

                if (
                    multiplier != null &&
                    multiplier >= 1.0
                ) {

                    handleMultiplier(
                        multiplier,
                        text
                    )
                }

                /*
                 * IMPORTANT:
                 * Do NOT display "no valid multiplier"
                 * here. That message would immediately
                 * replace the useful raw OCR result.
                 */

                bitmap.recycle()
            }
            .addOnFailureListener { error ->

                sendDiagnostic(
                    "OCR ERROR: ${error.message}"
                )

                bitmap.recycle()
            }
    }

    private fun extractMultiplier(
        text: String
    ): Double? {

        val normalized =
            text
                .replace(
                    ',',
                    '.'
                )
                .replace(
                    '×',
                    'x'
                )

        val matcher =
            multiplierPattern.matcher(
                normalized
            )

        var best:
            Double? = null

        while (
            matcher.find()
        ) {

            val raw =
                matcher.group(1)
                    ?: continue

            val value =
                raw.toDoubleOrNull()
                    ?: continue

            if (
                value >= 1.0 &&
                value <= 1000000.0
            ) {

                if (
                    best == null ||
                    value > best!!
                ) {

                    best =
                        value
                }
            }
        }

        return best
    }

    private fun handleMultiplier(
        multiplier: Double,
        ocrText: String
    ) {

        val rounded =
            String.format(
                Locale.US,
                "%.2f",
                multiplier
            )

        if (
            lastLiveMultiplier < 0.0 ||
            abs(
                multiplier -
                    lastLiveMultiplier
            ) >= 0.01
        ) {

            lastLiveMultiplier =
                multiplier

            sendLiveMultiplier(
                multiplier
            )
        }

        sendDiagnostic(
            "OCR detected ${rounded}×"
        )
    }

    private fun sendLiveMultiplier(
        multiplier: Double
    ) {

        val intent =
            Intent(
                LIVE_MULTIPLIER_ACTION
            ).apply {

                putExtra(
                    "multiplier",
                    multiplier
                )
            }

        sendBroadcast(
            intent
        )
    }

    private fun sendCompletedMultiplier(
        multiplier: Double
    ) {

        val intent =
            Intent(
                ROUND_COMPLETED_ACTION
            ).apply {

                putExtra(
                    "multiplier",
                    multiplier
                )
            }

        sendBroadcast(
            intent
        )
    }

    private fun sendDiagnostic(
        message: String
    ) {

        val intent =
            Intent(
                DIAGNOSTIC_ACTION
            ).apply {

                putExtra(
                    "message",
                    message
                )
            }

        sendBroadcast(
            intent
        )
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Aviator screen monitoring",
                    NotificationManager.IMPORTANCE_LOW
                )

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
                    "Reading the Aviator screen"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .build()

        } else {

            @Suppress("DEPRECATION")
            Notification.Builder(
                this
            )
                .setContentTitle(
                    "Aviator AI"
                )
                .setContentText(
                    "Reading the Aviator screen"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {

        sendDiagnostic(
            "Screen monitor stopped"
        )

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay =
            null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader =
            null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection =
            null

        try {
            textRecognizer.close()
        } catch (_: Exception) {
        }

        ocrExecutor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
