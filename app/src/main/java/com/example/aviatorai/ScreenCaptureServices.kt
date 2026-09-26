
package com.example.aviatorai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.Handler
import android.os.IBinder
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

        private const val CHANNEL_ID = "aviator_screen_monitor"
        private const val NOTIFICATION_ID = 1001

        private const val ROUND_COMPLETED_ACTION =
            "com.example.aviatorai.ROUND_COMPLETED"

        private const val LIVE_MULTIPLIER_ACTION =
            "com.example.aviatorai.MULTIPLIER_LIVE"

        private const val DIAGNOSTIC_ACTION =
            "com.example.aviatorai.DIAGNOSTIC"
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null

    private var detectedTextView: TextView? = null
    private var predictedTextView: TextView? = null
    private var statusTextView: TextView? = null

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private val history =
        mutableListOf<Double>()

    private var lastDetected =
        Double.NaN

    private var lastRoundValue =
        Double.NaN

    private var lastDiagnosticTime =
        0L

    /*
     * Detection validation state.
     */
    private var pendingMultiplier =
        Double.NaN

    private var pendingCount =
        0

    private var lastAcceptedTime =
        0L

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(
                Context.WINDOW_SERVICE
            ) as WindowManager

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        createFloatingDisplay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {

            sendDiagnostic(
                "ERROR: Service intent is null"
            )

            return START_NOT_STICKY
        }

        val resultCode =
            intent.getIntExtra(
                "resultCode",
                -1
            )

        /*
         * Android 8.1 compatibility:
         *
         * MainActivity places the MediaProjection
         * permission Intent into the "data" extra.
         *
         * This deprecated API is intentionally used here
         * because the minimum Android version includes
         * Android 8.1.
         */
        @Suppress("DEPRECATION")
        val data =
            intent.getParcelableExtra<Intent>(
                "data"
            )

        if (
            resultCode == -1 ||
            data == null
        ) {

            sendDiagnostic(
                "ERROR: Screen capture permission data missing"
            )

            return START_NOT_STICKY
        }

        startCapture(
            resultCode,
            data
        )

        return START_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {

        try {

            stopCapture()

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    data
                )

            if (mediaProjection == null) {

                sendDiagnostic(
                    "ERROR: MediaProjection unavailable"
                )

                return
            }

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
                mediaProjection?.createVirtualDisplay(
                    "AviatorScreenMonitor",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    handler
                )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    processImage(reader)
                },
                handler
            )

            updateStatus(
                "MONITORING"
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

    private fun processImage(
        reader: ImageReader
    ) {

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
                    pixelStride * image.width

            val bitmapWidth =
                image.width +
                    rowPadding / pixelStride

            val bitmap =
                Bitmap.createBitmap(
                    bitmapWidth,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

            bitmap.copyPixelsFromBuffer(
                buffer
            )

            runOcr(bitmap)

        } catch (e: Exception) {

            sendDiagnosticThrottled(
                "Image ERROR: ${e.message}"
            )

        } finally {

            image.close()
        }
    }

    private fun runOcr(
        bitmap: Bitmap
    ) {

        val input =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        recognizer
            .process(input)
            .addOnSuccessListener { result ->

                val text =
                    result.text

                sendDiagnosticThrottled(
                    "OCR RAW: $text"
                )

                val multiplier =
                    extractMultiplier(text)

                if (multiplier != null) {

                    validateMultiplier(
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

        val normalized =
            text
                .replace('×', 'x')
                .replace('X', 'x')
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('L', '1')
                .replace(',', '.')

        val pattern =
            Regex(
                """(?<![\d.])(\d{1,5}(?:\.\d{1,4})?)\s*x\b"""
            )

        val candidates =
            mutableListOf<Double>()

        for (match in pattern.findAll(normalized)) {

            val raw =
                match.groupValues[1]

            val value =
                raw.toDoubleOrNull()

            if (value != null) {

                if (
                    value >= 1.00 &&
                    value <= 10000.0
                ) {

                    candidates.add(
                        value
                    )
                }
            }
        }

        return candidates.lastOrNull()
    }

    private fun validateMultiplier(
        multiplier: Double
    ) {

        if (
            multiplier < 1.00 ||
            multiplier > 10000.0
        ) {
            return
        }

        if (!multiplier.isFinite()) {
            return
        }

        if (
            !pendingMultiplier.isNaN() &&
            abs(
                multiplier -
                    pendingMultiplier
            ) <= 0.01
        ) {

            pendingCount++

        } else {

            pendingMultiplier =
                multiplier

            pendingCount = 1
        }

        if (pendingCount >= 2) {

            val now =
                System.currentTimeMillis()

            if (
                now -
                    lastAcceptedTime >=
                100L
            ) {

                lastAcceptedTime =
                    now

                handleMultiplier(
                    pendingMultiplier
                )

                pendingCount = 0
            }
        }
    }

    private fun handleMultiplier(
        multiplier: Double
    ) {

        if (
            !lastDetected.isNaN() &&
            abs(
                multiplier - lastDetected
            ) < 0.001
        ) {
            return
        }

        lastDetected =
            multiplier

        updateDetected(
            multiplier
        )

        sendLiveMultiplier(
            multiplier
        )

        addToHistory(
            multiplier
        )

        val prediction =
            calculatePrediction()

        if (prediction != null) {

            updatePrediction(
                prediction
            )
        }

        if (
            multiplier <= 1.05 &&
            !lastRoundValue.isNaN() &&
            lastRoundValue > 1.05
        ) {

            sendRoundCompleted(
                lastRoundValue
            )
        }

        if (multiplier > 1.05) {

            lastRoundValue =
                multiplier
        }
    }

    private fun addToHistory(
        multiplier: Double
    ) {

        if (history.isEmpty()) {

            history.add(
                multiplier
            )

            return
        }

        val previous =
            history.last()

        if (
            abs(
                previous - multiplier
            ) >= 0.001
        ) {

            history.add(
                multiplier
            )
        }

        if (history.size > 5000) {

            history.removeAt(0)
        }
    }

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

        val middle =
            sorted.size / 2

        val median =
            if (sorted.size % 2 == 0) {

                (
                    sorted[middle - 1] +
                        sorted[middle]
                    ) / 2.0

            } else {

                sorted[middle]
            }

        return median.coerceIn(
            1.01,
            100.0
        )
    }

    private fun createFloatingDisplay() {

        val container =
            LinearLayout(this)

        container.orientation =
            LinearLayout.VERTICAL

        container.setPadding(
            16,
            12,
            16,
            12
        )

        container.setBackgroundColor(
            Color.argb(
                225,
                20,
                20,
                20
            )
        )

        statusTextView =
            TextView(this)

        detectedTextView =
            TextView(this)

        predictedTextView =
            TextView(this)

        statusTextView?.text =
            "AVIATOR MONITOR"

        detectedTextView?.text =
            "Detected: --"

        predictedTextView?.text =
            "Predicted: --"

        statusTextView?.setTextColor(
            Color.WHITE
        )

        detectedTextView?.setTextColor(
            Color.WHITE
        )

        predictedTextView?.setTextColor(
            Color.WHITE
        )

        container.addView(
            statusTextView
        )

        container.addView(
            detectedTextView
        )

        container.addView(
            predictedTextView
        )

        val windowType =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        params.x = 20
        params.y = 120

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        container.setOnTouchListener {
                _: View,
                event: MotionEvent ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    startX =
                        params.x

                    startY =
                        params.y

                    touchX =
                        event.rawX

                    touchY =
                        event.rawY

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    params.x =
                        startX +
                            (
                                event.rawX -
                                    touchX
                                ).toInt()

                    params.y =
                        startY +
                            (
                                event.rawY -
                                    touchY
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

    private fun updateDetected(
        multiplier: Double
    ) {

        handler.post {

            detectedTextView?.text =
                String.format(
                    Locale.US,
                    "Detected: %.2f×",
                    multiplier
                )
        }
    }

    private fun updatePrediction(
        prediction: Double
    ) {

        handler.post {

            predictedTextView?.text =
                String.format(
                    Locale.US,
                    "Predicted: %.2f×",
                    prediction
                )
        }
    }

    private fun updateStatus(
        status: String
    ) {

        handler.post {

            statusTextView?.text =
                status
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
                )
            }

        sendBroadcast(
            intent
        )
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

    private fun sendDiagnosticThrottled(
        message: String
    ) {

        val now =
            System.currentTimeMillis()

        if (
            now - lastDiagnosticTime <
            1000L
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

    private fun stopCapture() {

        try {
            imageReader?.setOnImageAvailableListener(
                null,
                null
            )
        } catch (_: Exception) {
        }

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        imageReader = null
        virtualDisplay = null
        mediaProjection = null
    }

    override fun onDestroy() {

        stopCapture()

        try {
            recognizer.close()
        } catch (_: Exception) {
        }

        try {

            overlayView?.let { view ->

                windowManager.removeView(
                    view
                )
            }

        } catch (_: Exception) {
        }

        overlayView = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}

