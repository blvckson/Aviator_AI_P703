```kotlin
package com.example.aviatorai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.Looper
import android.os.IBinder
import android.util.DisplayMetrics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

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

    private var mediaProjection:
        MediaProjection? = null

    private var virtualDisplay:
        VirtualDisplay? = null

    private var imageReader:
        ImageReader? = null

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val ocrExecutor:
        ExecutorService =
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

    /*
     * Explicit multiplier:
     *
     * Examples:
     * 1.25x
     * 2.50x
     * 10.30×
     */
    private val explicitMultiplierPattern =
        Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*[xX]"
        )

    /*
     * Decimal fallback:
     *
     * Used when OCR sees the number but misses
     * the × or x character.
     */
    private val decimalPattern =
        Pattern.compile(
            "(?<!\\d)(\\d{1,7}\\.\\d{1,4})(?!\\d)"
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

        /*
         * RESULT_OK is -1.
         *
         * We must check whether the extra exists
         * before reading it.
         */
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

        /*
         * Android RESULT_OK = -1.
         */
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

                    processImage(
                        reader
                    )
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

        /*
         * OCR approximately four times per second.
         */
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

            } catch (_: Exception) {

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
                    rowPadding / pixelStride

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
             * Expanded OCR region.
             *
             * The previous version used:
             * 80% width × 60% height.
             *
             * This version uses:
             * 90% width × 80% height.
             *
             * This gives ML Kit more of the actual
             * Aviator game screen to work with while
             * still avoiding some outer UI elements.
             */
            val cropWidth =
                (
                    bitmap.width * 0.90f
                ).toInt()

            val cropHeight =
                (
                    bitmap.height * 0.80f
                ).toInt()

            val left =
                (
                    bitmap.width -
                        cropWidth
                ) / 2

            val top =
                (
                    bitmap.height -
                        cropHeight
                ) / 2

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

    private fun runOcr(
        bitmap: Bitmap
    ) {

        val inputImage =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        textRecognizer
            .process(
                inputImage
            )
            .addOnSuccessListener { result ->

                val text =
                    result.text

                /*
                 * IMPORTANT DIAGNOSTIC:
                 *
                 * Tell us exactly what ML Kit
                 * thinks is on the screen.
                 */
                if (text.isBlank()) {

                    sendDiagnostic(
                        "OCR running: no text recognized"
                    )

                    bitmap.recycle()

                    return@addOnSuccessListener
                }

                /*
                 * Collapse line breaks so the diagnostic
                 * fits on the status line.
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
                        .take(180)

                sendDiagnostic(
                    "OCR text: $diagnosticText"
                )

                val multiplier =
                    extractMultiplier(
                        text
                    )

                if (
                    multiplier != null &&
                    multiplier >= 1.0 &&
                    multiplier <= 1000000.0
                ) {

                    handleMultiplier(
                        multiplier,
                        text
                    )

                } else {

                    sendDiagnostic(
                        "OCR text found, but no valid multiplier"
                    )
                }

                bitmap.recycle()
            }
            .addOnFailureListener { error ->

                sendDiagnostic(
                    "OCR error: ${error.message}"
                )

                bitmap.recycle()
            }
    }

    private fun extractMultiplier(
        text: String
    ): Double? {

        /*
         * Normalize common OCR mistakes.
         *
         * Examples:
         *
         * 1,O5  -> 1.05
         * I.25  -> 1.25
         * l.50  -> 1.50
         * 2,35  -> 2.35
         */
        val normalized =
            text
                .replace(
                    '×',
                    'x'
                )
                .replace(
                    ',',
                    '.'
                )
                .replace(
                    'O',
                    '0'
                )
                .replace(
                    'o',
                    '0'
                )
                .replace(
                    'I',
                    '1'
                )
                .replace(
                    'l',
                    '1'
                )
                .replace(
                    'S',
                    '5'
                )
                .replace(
                    's',
                    '5'
                )

        /*
         * STEP 1:
         *
         * Look specifically for a multiplier followed
         * by x.
         */
        val explicitMatcher =
            explicitMultiplierPattern.matcher(
                normalized
            )

        while (
            explicitMatcher.find()
        ) {

            val raw =
                explicitMatcher.group(1)
                    ?: continue

            val value =
                raw.toDoubleOrNull()
                    ?: continue

            if (
                value >= 1.0 &&
                value <= 1000000.0
            ) {

                return value
            }
        }

        /*
         * STEP 2:
         *
         * OCR sometimes recognizes:
         *
         * 1.25
         *
         * but completely misses the ×.
         *
         * Therefore search decimal values too.
         */
        val decimalMatcher =
            decimalPattern.matcher(
                normalized
            )

        var best:
            Double? =
            null

        while (
            decimalMatcher.find()
        ) {

            val raw =
                decimalMatcher.group(1)
                    ?: continue

            val value =
                raw.toDoubleOrNull()
                    ?: continue

            if (
                value >= 1.0 &&
                value <= 1000000.0
            ) {

                /*
                 * At this diagnostic stage we retain
                 * the largest valid decimal candidate.
                 *
                 * Later validation will use screen
                 * position, round state and historical
                 * continuity so unrelated numbers are
                 * rejected.
                 */
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

        /*
         * A live multiplier changes continuously.
         *
         * Only broadcast when the value changes
         * sufficiently to avoid flooding the UI.
         */
        if (
            lastLiveMultiplier < 0.0 ||
            kotlin.math.abs(
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

```
