
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
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {

        private const val CHANNEL_ID = "aviator_monitor"

        private const val ROUND_COMPLETED_ACTION =
            "com.example.aviatorai.ROUND_COMPLETED"

        private const val LIVE_MULTIPLIER_ACTION =
            "com.example.aviatorai.MULTIPLIER_LIVE"

        private const val DIAGNOSTIC_ACTION =
            "com.example.aviatorai.DIAGNOSTIC"

        private const val MIN_MULTIPLIER = 1.00
        private const val MAX_MULTIPLIER = 10000.0
    }

    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var imageReader: ImageReader

    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null
    private var detectedText: TextView? = null
    private var predictedText: TextView? = null
    private var statusText: TextView? = null

    private val handler =
        Handler(Looper.getMainLooper())

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /*
     * Historical observations captured from the screen.
     *
     * This is deliberately kept separate from the UI.
     * Later modelling can consume this validated dataset.
     */
    private val history =
        mutableListOf<Double>()

    private var lastDetectedMultiplier =
        Double.NaN

    private var lastCompletedMultiplier =
        Double.NaN

    private var lastPrediction =
        Double.NaN

    private var lastDiagnosticTime =
        0L

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        startForeground(
            1001,
            createNotification()
        )

        createFloatingOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {
            return START_NOT_STICKY
        }

        val resultCode =
            intent.getIntExtra(
                "resultCode",
                -1
            )

        val data =
            intent.getParcelableExtra<Intent>(
                "data"
            )

        if (resultCode == -1 || data == null) {

            sendDiagnostic(
                "ERROR: Missing screen capture permission data"
            )

            return START_NOT_STICKY
        }

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

            val manager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(
                    resultCode,
                    data
                )

            val metrics =
                resources.displayMetrics

            val width =
                metrics.widthPixels

            val height =
                metrics.heightPixels

            val density =
                metrics.densityDpi

            imageReader =
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
                )

            virtualDisplay =
                mediaProjection.createVirtualDisplay(
                    "AviatorScreenMonitor",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    handler
                )

            imageReader.setOnImageAvailableListener(
                { reader ->

                    processLatestImage(reader)

                },
                handler
            )

            sendDiagnostic(
                "Screen capture started"
            )

        } catch (e: Exception) {

            sendDiagnostic(
                "Capture ERROR: ${e.message}"
            )
        }
    }

    private fun processLatestImage(
        reader: ImageReader
    ) {

        val image = try {

            reader.acquireLatestImage()

        } catch (e: Exception) {

            null
        }

        if (image == null) {
            return
        }

        try {

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
                        image.width

            val bitmapWidth =
                image.width +
                        rowPadding /
                        pixelStride

            val bitmap =
                Bitmap.createBitmap(
                    bitmapWidth,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

            bitmap.copyPixelsFromBuffer(
                buffer
            )

            runOCR(
                bitmap
            )

        } catch (e: Exception) {

            sendDiagnostic(
                "Image ERROR: ${e.message}"
            )

        } finally {

            image.close()
        }
    }

    private fun runOCR(
        bitmap: Bitmap
    ) {

        val inputImage =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        recognizer
            .process(inputImage)
            .addOnSuccessListener { result ->

                val rawText =
                    result.text

                sendDiagnosticThrottled(
                    "OCR RAW: $rawText"
                )

                val multiplier =
                    extractMultiplier(
                        rawText
                    )

                if (multiplier != null) {

                    handleDetectedMultiplier(
                        multiplier
                    )
                }
            }
            .addOnFailureListener { error ->

                sendDiagnosticThrottled(
                    "OCR ERROR: ${error.message}"
                )
            }
    }

    private fun extractMultiplier(
        text: String
    ): Double? {

        if (text.isBlank()) {
            return null
        }

        /*
         * OCR commonly returns forms such as:
         *
         * 1.24x
         * 2.53X
         * 10.00 x
         * 1,87x
         *
         * Only values immediately associated with
         * the multiplier symbol are accepted.
         */

        val regex =
            Regex(
                """(?i)(\d+(?:[.,]\d+)?)\s*[x×]"""
            )

        val matches =
            regex.findAll(text)

        var candidate: Double? = null

        for (match in matches) {

            val value =
                match.groupValues[1]
                    .replace(",", ".")
                    .toDoubleOrNull()

            if (
                value != null &&
                value >= MIN_MULTIPLIER &&
                value <= MAX_MULTIPLIER
            ) {

                candidate = value
            }
        }

        return candidate
    }

    private fun handleDetectedMultiplier(
        multiplier: Double
    ) {

        /*
         * Ignore OCR duplicates.
         */
        if (
            !lastDetectedMultiplier.isNaN() &&
            abs(
                multiplier -
                        lastDetectedMultiplier
            ) < 0.001
        ) {

            return
        }

        lastDetectedMultiplier =
            multiplier

        updateDetectedDisplay(
            multiplier
        )

        sendLiveMultiplier(
            multiplier
        )

        /*
         * A multiplier that has changed represents
         * a new screen observation.
         *
         * We only add validated values to history.
         */
        if (
            history.isEmpty() ||
            abs(
                history.last() -
                        multiplier
            ) >= 0.001
        ) {

            history.add(
                multiplier
            )

            if (history.size > 5000) {

                history.removeAt(0)
            }
        }

        /*
         * The prediction is intentionally separated
         * from OCR extraction.
         */
        val prediction =
            calculatePrediction()

        if (prediction != null) {

            lastPrediction =
                prediction

            updatePredictionDisplay(
                prediction
            )
        }

        /*
         * A completed round is identified when the
         * displayed multiplier returns to approximately
         * 1.x after previously being above it.
         */
        if (
            !lastCompletedMultiplier.isNaN() &&
            multiplier <= 1.05
        ) {

            if (
                lastCompletedMultiplier >
                1.05
            ) {

                sendRoundCompleted(
                    lastCompletedMultiplier
                )
            }
        }

        if (multiplier > 1.05) {

            lastCompletedMultiplier =
                multiplier
        }
    }

    /*
     * Temporary model layer.
     *
     * This is NOT intended to be the final Aviator
     * probability model. It provides a prediction only
     * after enough validated observations exist.
     *
     * The historical dataset is preserved so the
     * advanced statistical model can replace this
     * function without changing the screen-capture layer.
     */
    private fun calculatePrediction(): Double? {

        if (history.size < 10) {
            return null
        }

        val recent =
            history.takeLast(30)

        if (recent.isEmpty()) {
            return null
        }

        val sorted =
            recent.sorted()

        val median =
            if (sorted.size % 2 == 0) {

                (
                    sorted[
                        sorted.size / 2 - 1
                    ] +
                    sorted[
                        sorted.size / 2
                    ]
                ) / 2.0

            } else {

                sorted[
                    sorted.size / 2
                ]
            }

        val prediction =
            median.coerceIn(
                1.01,
                100.0
            )

        return prediction
    }

    private fun createFloatingOverlay() {

        val container =
            LinearLayout(this)

        container.orientation =
            LinearLayout.VERTICAL

        container.setPadding(
            18,
            12,
            18,
            12
        )

        container.setBackgroundColor(
            0xDD111111.toInt()
        )

        statusText =
            TextView(this)

        detectedText =
            TextView(this)

        predictedText =
            TextView(this)

        statusText?.text =
            "AVIATOR MONITOR"

        detectedText?.text =
            "Detected: --"

        predictedText?.text =
            "Predicted: --"

        container.addView(
            statusText
        )

        container.addView(
            detectedText
        )

        container.addView(
            predictedText
        )

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        params.x = 20
        params.y = 120

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    initialX =
                        params.x

                    initialY =
                        params.y

                    initialTouchX =
                        event.rawX

                    initialTouchY =
                        event.rawY

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    params.x =
                        initialX +
                                (
                                    event.rawX -
                                            initialTouchX
                                ).toInt()

                    params.y =
                        initialY +
                                (
                                    event.rawY -
                                            initialTouchY
                                ).toInt()

                    try {

                        windowManager.updateViewLayout(
                            container,
                            params
                        )

                    } catch (_: Exception) {
                    }

                    true
                }

                else -> false
            }
        }

        try {

            windowManager.addView(
                container,
                params
            )

            overlayView =
                container

        } catch (e: Exception) {

            sendDiagnostic(
                "Overlay ERROR: ${e.message}"
            )
        }
    }

    private fun updateDetectedDisplay(
        multiplier: Double
    ) {

        handler.post {

            detectedText?.text =
                String.format(
                    Locale.US,
                    "Detected: %.2f×",
                    multiplier
                )
        }
    }

    private fun updatePredictionDisplay(
        prediction: Double
    ) {

        handler.post {

            predictedText?.text =
                String.format(
                    Locale.US,
                    "Predicted: %.2f×",
                    prediction
                )
        }
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
                }

            }

        sendBroadcast(intent)
    }

    private fun sendRoundCompleted(
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

        sendBroadcast(intent)
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

        sendBroadcast(intent)
    }

    private fun sendDiagnosticThrottled(
        message: String
    ) {

        val now =
            System.currentTimeMillis()

        /*
         * Avoid flooding the Activity with OCR
         * messages on every captured frame.
         */
        if (
            now -
                    lastDiagnosticTime <
                    1000
        ) {

            return
        }

        lastDiagnosticTime =
            now

        sendDiagnostic(
            message
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
                    "Aviator Screen Monitor",
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

    private fun createNotification(): Notification {

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "Aviator Predictor"
            )
            .setContentText(
                "Screen monitoring is running"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        try {
            imageReader.close()
        } catch (_: Exception) {
        }

        try {
            virtualDisplay.release()
        } catch (_: Exception) {
        }

        try {
            mediaProjection.stop()
        } catch (_: Exception) {
        }

        try {
            recognizer.close()
        } catch (_: Exception) {
        }

        try {

            overlayView?.let {

                windowManager.removeView(it)

            }

        } catch (_: Exception) {
        }

        overlayView = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ) = null
}

