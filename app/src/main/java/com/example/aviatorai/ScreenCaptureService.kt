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
import android.content.SharedPreferences
import org.json.JSONArray
import android.os.Looper
import android.provider.Settings
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

        private const val CHANNEL_ID =
            "aviator_screen_monitor"

        private const val NOTIFICATION_ID =
            1001

        private const val ROUND_COMPLETED_ACTION =
            "com.example.aviatorai.ROUND_COMPLETED"

        private const val LIVE_MULTIPLIER_ACTION =
            "com.example.aviatorai.MULTIPLIER_LIVE"

        private const val DIAGNOSTIC_ACTION =
            "com.example.aviatorai.DIAGNOSTIC"

        private const val PREDICTION_ACTION =
            "com.example.aviatorai.PREDICTION"

        /*
         * MediaProjection permission data is supplied by
         * MainActivity before the service is started.
         *
         * This fixes:
         *
         * ERROR: Screen capture permission data missing
         */
        private var projectionResultCode: Int =
            -1

        private var projectionData: Intent? =
            null

        fun setProjectionData(
            resultCode: Int,
            data: Intent
        ) {
            projectionResultCode =
                resultCode

            projectionData =
                data
        }

        fun clearProjectionData() {

            projectionResultCode =
                -1

            projectionData =
                null
        }
    }

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var mediaProjection:
            MediaProjection? = null

    private var virtualDisplay:
            VirtualDisplay? = null

    private var imageReader:
            ImageReader? = null

    private lateinit var windowManager:
            WindowManager

    private var overlayView:
            View? = null

    private var detectedTextView:
            TextView? = null

    private var predictedTextView:
            TextView? = null

    private var confidenceTextView:
            TextView? = null

    private var statusTextView:
            TextView? = null

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private val history =
        mutableListOf<Double>()

    private lateinit var preferences:
            SharedPreferences

    private val predictionModel = PredictionModel()

    // Live OCR values are never inserted directly into the historical model.
    // Only the completed round value is persisted.
    private var currentRoundPeak =
        Double.NaN

    private var lowAfterPeakCount =
        0

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

        preferences = getSharedPreferences(
            "aviator_history",
            Context.MODE_PRIVATE
        )
        loadHistory()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        /*
         * IMPORTANT:
         *
         * Do not read MediaProjection permission
         * data from the service Intent.
         *
         * MainActivity stores it using
         * setProjectionData() before starting
         * this service.
         */
        val resultCode = intent?.getIntExtra("projection_result_code", -1) ?: -1

        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("projection_data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("projection_data")
        }

        if (resultCode == -1 || data == null) {

            sendDiagnostic(
                "ERROR: Screen capture permission data missing"
            )

            return START_NOT_STICKY
        }

        startCapture(
            resultCode,
            data
        )

        /*
         * The service now has its own local reference
         * to the permission Intent through startCapture().
         */
        clearProjectionData()

        return START_NOT_STICKY
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

            if (
                mediaProjection == null
            ) {

                sendDiagnostic(
                    "ERROR: MediaProjection unavailable"
                )

                return
            }

            // The overlay must be created only after screen-capture permission
            // is active. Creating it in onCreate() can fail before Android has
            // granted the projection/overlay app-op.
            if (!Settings.canDrawOverlays(this)) {
                sendDiagnostic(
                    "ERROR: Display-over-other-apps permission is not granted"
                )
                return
            }

            if (overlayView == null) {
                createFloatingDisplay()
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

        if (
            image == null
        ) {
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

            // OCR the full frame and a large center crop. The multiplier
            // is usually central and becomes much easier to read when enlarged.
            runOcr(bitmap, false)

            val cropLeft = (bitmap.width * 0.08f).toInt().coerceAtLeast(0)
            val cropTop = (bitmap.height * 0.08f).toInt().coerceAtLeast(0)
            val cropRight = (bitmap.width * 0.92f).toInt().coerceAtMost(bitmap.width)
            val cropBottom = (bitmap.height * 0.72f).toInt().coerceAtMost(bitmap.height)
            if (cropRight > cropLeft && cropBottom > cropTop) {
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                val enlarged = Bitmap.createScaledBitmap(
                    crop,
                    (crop.width * 1.8f).toInt().coerceAtLeast(1),
                    (crop.height * 1.8f).toInt().coerceAtLeast(1),
                    true
                )
                runOcr(enlarged, true)
            }

        } catch (e: Exception) {

            sendDiagnosticThrottled(
                "Image ERROR: ${e.message}"
            )

        } finally {

            image.close()
        }
    }

    private fun runOcr(
        bitmap: Bitmap,
        allowBareDecimal: Boolean
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

                if (text.isBlank()) {
                    sendDiagnosticThrottled(
                        "No valid multiplier detected"
                    )
                    return@addOnSuccessListener
                }

                sendDiagnosticThrottled(
                    "OCR RAW: $text"
                )

                val multiplier =
                    extractMultiplier(
                        text,
                        allowBareDecimal
                    )

                if (
                    multiplier != null
                ) {

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
        text: String,
        allowBareDecimal: Boolean
    ): Double? {

        if (
            text.isBlank()
        ) {
            return null
        }

        val normalized = text
            .replace('×', 'x')
            .replace('X', 'x')
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('L', '1')
            .replace(',', '.')

        // Primary form: Aviator normally renders values such as 1.25x.
        val withX = Regex(
            """(?<![\\d.])(\\d{1,5}(?:\\.\\d{1,4})?)\\s*[xX]\\b"""
        )

        for (match in withX.findAll(normalized)) {
            val value = match.groupValues[1].toDoubleOrNull()
            if (value != null && value.isFinite() && value in 1.0..10000.0) {
                return value
            }
        }

        // Some OCR engines drop the trailing x. Only accept a decimal with
        // 1–4 fractional digits when the OCR text is short (typical of the
        // cropped multiplier region), avoiding most UI-number false positives.
        if (allowBareDecimal && normalized.length <= 120) {
            val bare = Regex(
                """(?<![\\d.])(\\d{1,5}\\.\\d{1,4})(?![\\d.])"""
            )
            val values = bare.findAll(normalized)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .filter { it.isFinite() && it in 1.0..10000.0 }
                .toList()
            if (values.size == 1) return values[0]
        }

        return null
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

        if (
            !multiplier.isFinite()
        ) {
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

            pendingCount =
                1
        }

        if (
            pendingCount >= 2
        ) {

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

                pendingCount =
                    0
            }
        }
    }

    private fun handleMultiplier(
        multiplier: Double
    ) {
        if (!lastDetected.isNaN() &&
            abs(multiplier - lastDetected) < 0.001) {
            return
        }

        lastDetected = multiplier
        updateDetected(multiplier)
        sendLiveMultiplier(multiplier)

        /*
         * Round tracking:
         * The animated live multiplier is telemetry only.
         * A round enters the historical dataset only when a new low/start
         * is validated after a rising peak. This prevents hundreds of OCR
         * frames from becoming hundreds of fake "historical rounds".
         */
        if (currentRoundPeak.isNaN()) {
            currentRoundPeak = multiplier
            lowAfterPeakCount = 0
        } else if (multiplier >= currentRoundPeak - 0.01) {
            currentRoundPeak = maxOf(currentRoundPeak, multiplier)
            lowAfterPeakCount = 0
        } else {
            val largeDrop =
                multiplier <= 1.10 ||
                multiplier <= currentRoundPeak * 0.82

            if (largeDrop && currentRoundPeak >= 1.05) {
                lowAfterPeakCount++
            } else {
                lowAfterPeakCount = 0
            }

            if (lowAfterPeakCount >= 2) {
                val completed = currentRoundPeak
                completeRound(completed)

                currentRoundPeak = multiplier
                lowAfterPeakCount = 0
            }
        }

        /*
         * A prediction is based only on completed, validated rounds.
         * No history means no estimate.
         */
        val prediction = calculatePrediction()
        if (prediction != null) {
            updatePrediction(prediction)
        }
    }

    private fun completeRound(
        multiplier: Double
    ) {
        if (!multiplier.isFinite() ||
            multiplier < 1.0 ||
            multiplier > 10000.0) {
            return
        }

        // Reject duplicate completed values created by repeated OCR frames.
        if (history.isNotEmpty() &&
            abs(history.last() - multiplier) < 0.001) {
            return
        }

        history.add(multiplier)
        if (history.size > 2000) {
            history.removeAt(0)
        }

        saveHistory()

        sendRoundCompleted(multiplier)

        updateStatus(
            String.format(
                Locale.US,
                "ROUND SAVED %.2f× | history=%d",
                multiplier,
                history.size
            )
        )
    }

    private fun calculatePrediction(): PredictionEstimate? {
        return predictionModel.estimate(history)
    }

    private fun loadHistory() {
        try {
            val raw = preferences.getString(
                "rounds",
                "[]"
            ) ?: "[]"
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optDouble(i, Double.NaN)
                if (value.isFinite() && value in 1.0..10000.0) {
                    history.add(value)
                }
            }
            if (history.size > 2000) {
                val trimmed = history.takeLast(2000)
                history.clear()
                history.addAll(trimmed)
            }
        } catch (_: Exception) {
            history.clear()
        }
    }

    private fun saveHistory() {
        try {
            val array = JSONArray()
            for (value in history) {
                array.put(value)
            }
            preferences.edit()
                .putString("rounds", array.toString())
                .apply()
        } catch (_: Exception) {
        }
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

        confidenceTextView =
            TextView(this)

        statusTextView?.text =
            "AVIATOR MONITOR"

        detectedTextView?.text =
            "Detected: --"

        predictedTextView?.text =
            "Next: —"

        confidenceTextView?.text =
            "No estimate until 10 completed rounds"

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

        container.addView(
            confidenceTextView
        )

        val windowType =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")
                WindowManager.LayoutParams
                    .TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or
                Gravity.START

        params.x =
            20

        params.y =
            120

        var startX =
            0

        var startY =
            0

        var touchX =
            0f

        var touchY =
            0f

        container.setOnTouchListener {
                _: View,
                event: MotionEvent ->

            when (
                event.action
            ) {

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

                        windowManager
                            .updateViewLayout(
                                container,
                                params
                            )

                    } catch (
                        _: Exception
                    ) {
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

        } catch (
            e: Exception
        ) {

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
        estimate: PredictionEstimate
    ) {

        handler.post {

            predictedTextView?.text =
                String.format(
                    Locale.US,
                    "Estimate: %.2f×",
                    estimate.value
                )

            confidenceTextView?.text =
                String.format(
                    Locale.US,
                    "Range %.2f–%.2f× | %.0f%% | n=%d | P≥2× %.0f%%",
                    estimate.lower,
                    estimate.upper,
                    estimate.confidence,
                    estimate.sampleSize,
                    estimate.probabilityAbove2x
                )

            sendPrediction(estimate)
        }
    }

    private fun sendPrediction(
        estimate: PredictionEstimate
    ) {
        sendBroadcast(
            Intent(PREDICTION_ACTION).apply {
                putExtra("value", estimate.value)
                putExtra("lower", estimate.lower)
                putExtra("upper", estimate.upper)
                putExtra("confidence", estimate.confidence)
                putExtra("sampleSize", estimate.sampleSize)
            }
        )
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
            now -
                lastDiagnosticTime <
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
                    NotificationManager
                        .IMPORTANCE_LOW
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
                android.R.drawable
                    .ic_menu_view
            )
            .setOngoing(
                true
            )
            .build()
    }

    private fun stopCapture() {

        try {

            imageReader
                ?.setOnImageAvailableListener(
                    null,
                    null
                )

        } catch (
            _: Exception
        ) {
        }

        try {

            imageReader?.close()

        } catch (
            _: Exception
        ) {
        }

        try {

            virtualDisplay?.release()

        } catch (
            _: Exception
        ) {
        }

        try {

            mediaProjection?.stop()

        } catch (
            _: Exception
        ) {
        }

        imageReader =
            null

        virtualDisplay =
            null

        mediaProjection =
            null
    }

    override fun onDestroy() {

        stopCapture()

        try {

            recognizer.close()

        } catch (
            _: Exception
        ) {
        }

        try {

            overlayView?.let { view ->

                windowManager.removeView(
                    view
                )
            }

        } catch (
            _: Exception
        ) {
        }

        overlayView =
            null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
