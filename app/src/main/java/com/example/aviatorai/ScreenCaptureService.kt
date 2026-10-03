package com.example.aviatorai

import android.app.Activity
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.ArrayDeque

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "aviator_screen_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val ROUND_COMPLETED_ACTION = "com.example.aviatorai.ROUND_COMPLETED"
        private const val LIVE_MULTIPLIER_ACTION = "com.example.aviatorai.MULTIPLIER_LIVE"
        private const val DIAGNOSTIC_ACTION = "com.example.aviatorai.DIAGNOSTIC"
        private const val PREDICTION_ACTION = "com.example.aviatorai.PREDICTION"

        private var projectionResultCode: Int = -1
        private var projectionData: Intent? = null

        fun setProjectionData(resultCode: Int, data: Intent) {
            projectionResultCode = resultCode
            projectionData = data
        }

        fun clearProjectionData() {
            projectionResultCode = -1
            projectionData = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var detectedTextView: TextView? = null
    private var predictedTextView: TextView? = null
    private var confidenceTextView: TextView? = null
    private var statusTextView: TextView? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val history = mutableListOf<Double>()
    private lateinit var preferences: SharedPreferences
    private val predictionModel = PredictionModel()
    // Keep statistical calculation off the capture/OCR thread.
    private val predictionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Recovery scanner: runs only after a completed round and never concurrently
    // with the live OCR recognizer. It does not alter the live detector state.
    private var historyScanPending = false
    private var historyScanBusy = false
    private var lastHistoryScanTime = 0L
    private var pendingHistoryBitmap: Bitmap? = null
    private val historyScanMinIntervalMs = 1200L

    private var currentRoundPeak = Double.NaN
    private var lowAfterPeakCount = 0
    private var lastDetected = Double.NaN
    private var lastRoundValue = Double.NaN
    private var lastDiagnosticTime = 0L
    private var ocrBusy = false
    // OCR is asynchronous and can take longer than the screen frame rate.
    // Keep a short rolling frame queue instead of silently dropping every
    // frame that arrives while OCR is busy.
    private val pendingOcrFrames = ArrayDeque<Bitmap>()
    private val maxPendingOcrFrames = 1
    private var pendingMultiplier = Double.NaN
    private var pendingCount = 0
    private var lastAcceptedTime = 0L
    private var serviceStopping = false

    // Aviator live-round state: the multiplier rises while the plane is flying.
    // A round is finalized by the "flew away" state and its final multiplier.
    private var roundActive = false
    private var roundFinalized = false
    private var lastLiveMultiplier = Double.NaN
    private var lastFinalMultiplier = Double.NaN

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences("aviator_history", Context.MODE_PRIVATE)
        loadHistory()
        createNotificationChannel()

        // Keep this service alive until the user explicitly presses Stop.
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (serviceStopping) return START_NOT_STICKY

        // Android can call START_STICKY services again with a null intent.
        // Never interpret that normal lifecycle event as a permission failure.
        if (mediaProjection != null && virtualDisplay != null && imageReader != null) {
            updateStatus("MONITORING")
            return START_STICKY
        }

        val resultCode =
            intent?.getIntExtra("projection_result_code", Activity.RESULT_OK)
                ?: projectionResultCode

        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("projection_data", Intent::class.java)
                ?: projectionData
        } else {
            @Suppress("DEPRECATION")
            (intent?.getParcelableExtra("projection_data") ?: projectionData)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            // Only report this when there is no existing capture session.
            sendDiagnostic("ERROR: Screen capture permission data missing")
            return START_STICKY
        }

        // Keep a local copy for the whole lifetime of this service.
        projectionResultCode = resultCode
        projectionData = data

        startCapture(resultCode, data)

        // Do NOT clear the permission data here. A sticky service may receive
        // another lifecycle start command and needs the same session data.
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            if (mediaProjection != null || virtualDisplay != null || imageReader != null) {
                stopCapture()
            }

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                sendDiagnostic("ERROR: MediaProjection unavailable")
                return
            }

            if (!Settings.canDrawOverlays(this)) {
                sendDiagnostic("ERROR: Display-over-other-apps permission is not granted")
                return
            }

            if (overlayView == null) createFloatingDisplay()

            val metrics = resources.displayMetrics
            val width = (metrics.widthPixels * 0.75f).toInt().coerceAtLeast(480)
            val height = (metrics.heightPixels * 0.75f).toInt().coerceAtLeast(800)
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AviatorScreenMonitor",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                processImage(reader)
            }, handler)

            updateStatus("MONITORING")
            sendDiagnostic("Screen capture started")
        } catch (e: Exception) {
            sendDiagnostic("Capture ERROR: ${e.message}")
            // Keep the foreground service alive so the user can retry without
            // the overlay disappearing unexpectedly.
            updateStatus("MONITOR ERROR — press Start to retry")
        }
    }

    private fun processImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmapWidth = image.width + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(
                bitmapWidth, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            prepareHistoryScan(bitmap)

            // Use the central multiplier region first. This reduces memory and
            // OCR load substantially and helps the monitor remain alive.
            val cropLeft = (bitmap.width * 0.08f).toInt().coerceAtLeast(0)
            val cropTop = (bitmap.height * 0.08f).toInt().coerceAtLeast(0)
            val cropRight = (bitmap.width * 0.92f).toInt().coerceAtMost(bitmap.width)
            val cropBottom = (bitmap.height * 0.72f).toInt().coerceAtMost(bitmap.height)

            if (cropRight <= cropLeft || cropBottom <= cropTop) {
                bitmap.recycle()
                finishOcr()
                return
            }

            val crop = Bitmap.createBitmap(
                bitmap, cropLeft, cropTop,
                cropRight - cropLeft, cropBottom - cropTop
            )
            bitmap.recycle()

            val enlarged = Bitmap.createScaledBitmap(
                crop,
                (crop.width * 1.5f).toInt().coerceAtLeast(1),
                (crop.height * 1.5f).toInt().coerceAtLeast(1),
                true
            )
            crop.recycle()

            enqueueOcrFrame(enlarged)
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            sendDiagnosticThrottled("Image ERROR: ${e.message}")
            finishOcr()
        }
    }

    private fun runOcr(bitmap: Bitmap, allowBareDecimal: Boolean) {
        val input = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(input)
            .addOnSuccessListener { result ->
                try {
                    val text = result.text
                    if (text.isBlank()) {
                        sendDiagnosticThrottled("No valid multiplier detected")
                        return@addOnSuccessListener
                    }

                    // Use the richer ML Kit hierarchy as well as the flat OCR text.
                    // This catches the live number even when punctuation/spaces are
                    // split across OCR lines or elements.
                    val ocrParts = buildString {
                        append(text)
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                append('\n').append(line.text)
                                for (element in line.elements) {
                                    append(' ').append(element.text)
                                }
                            }
                        }
                    }

                    sendDiagnosticThrottled("OCR RAW: $text")
                    val finalState = containsFlewAway(ocrParts)
                    val multiplier = extractMultiplier(ocrParts, allowBareDecimal)

                    if (finalState) {
                        // The final multiplier and the "flew away" label can be
                        // split across OCR frames. Preserve the latest validated
                        // live value if the final OCR frame has no number.
                        val finalMultiplier = multiplier
                            ?: lastLiveMultiplier.takeUnless { it.isNaN() }
                            ?: currentRoundPeak.takeUnless { it.isNaN() }
                        if (finalMultiplier != null) {
                            finalizeDetectedRound(finalMultiplier)
                        }
                    } else if (multiplier != null) {
                        validateMultiplier(multiplier)
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    finishOcr()
                }
            }
            .addOnFailureListener { error ->
                if (!bitmap.isRecycled) bitmap.recycle()
                sendDiagnosticThrottled("OCR ERROR: ${error.message}")
                finishOcr()
            }
    }

    private fun containsFlewAway(text: String): Boolean {
        val normalized = text
            .lowercase(Locale.US)
            .replace('0', 'o')
            .replace('1', 'l')
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.contains("flew away") ||
            normalized.contains("flewaway") ||
            normalized.contains("flew  away") ||
            normalized.contains("flew awa")
    }

    private fun extractMultiplier(text: String, allowBareDecimal: Boolean): Double? {
        if (text.isBlank()) return null

        val source = text
            .replace('×', 'x')
            .replace('X', 'x')
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('L', '1')
            .replace(Regex("[^0-9xX.,\\s]"), " ")
            .replace(Regex("[ \\t]+"), " ")

        // Primary form: 1.25x, 1 . 25 x, 1,25x and large grouped values.
        val explicitDecimal = Regex(
            """(?<![\\d.])(\\d{1,12})\\s*[.,]\\s*(\\d{1,3})\\s*[xX]\\b"""
        )
        for (m in explicitDecimal.findAll(source)) {
            val value = (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()
            if (value != null && value.isFinite() && value >= 1.0) return value
        }

        val withX = Regex(
            """(?<![\\d.])((?:\\d{1,3}(?:[,\\s]\\d{3})+|\\d+)(?:[.]\\d{1,100})?)\\s*[xX]\\b"""
        )
        for (match in withX.findAll(source)) {
            val value = normalizeNumericToken(match.groupValues[1]).toDoubleOrNull()
            if (value != null && value.isFinite() && value >= 1.0 && value <= Double.MAX_VALUE) {
                return value
            }
        }

        // Some OCR engines split the decimal into separate tokens and lose the
        // punctuation. Rejoin only when the raw OCR still contains an x marker.
        val tokenJoin = Regex("""(?<![\\d.])(\\d{1,3})\\s+(\\d{1,3})\\s*[xX]\\b""")
        for (m in tokenJoin.findAll(source)) {
            val left = m.groupValues[1]
            val right = m.groupValues[2]
            if (right.length <= 2) {
                val value = (left + "." + right).toDoubleOrNull()
                if (value != null && value.isFinite() && value >= 1.0) return value
            }
        }

        if (allowBareDecimal) {
            val bare = Regex("""(?<![\\d.])(\\d+\\s*[.]\\s*\\d+)(?![\\d.])""")
            val values = bare.findAll(source)
                .mapNotNull { it.groupValues[1].replace(" ", "").let { v -> v.toDoubleOrNull() } }
                .filter { it.isFinite() && it >= 1.0 && it <= Double.MAX_VALUE }
                .toList()
            if (values.size == 1) return values[0]
        }
        return null
    }

    private fun normalizeNumericToken(raw: String): String {
        val token = raw.trim()

        // Comma/space groups such as 1,000,000 or 1 000 000.
        if (Regex("""^\\d{1,3}(?:[,\\s]\\d{3})+$""").matches(token)) {
            return token.replace(",", "").replace(" ", "")
        }

        // OCR sometimes uses a comma as the decimal separator.
        if (token.count { it == ',' } == 1 && !token.contains(" ")) {
            return token.replace(',', '.')
        }

        return token.replace(" ", "")
    }

    private fun enqueueOcrFrame(bitmap: Bitmap) {
        handler.post {
            if (serviceStopping) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@post
            }

            // Preserve the newest frames. Old frames are less useful than the
            // frames closest to a round transition.
            while (pendingOcrFrames.isNotEmpty()) {
                val old = pendingOcrFrames.removeFirst()
                if (!old.isRecycled) old.recycle()
            }
            pendingOcrFrames.addLast(bitmap)
            drainOcrQueue()
        }
    }

    private fun drainOcrQueue() {
        if (serviceStopping || ocrBusy || pendingOcrFrames.isEmpty()) return

        ocrBusy = true
        val next = pendingOcrFrames.removeFirst()
        runOcr(next, true)
    }

    private fun finishOcr() {
        handler.post {
            ocrBusy = false
            if (serviceStopping) return@post
            val recovery = pendingHistoryBitmap
            pendingHistoryBitmap = null
            if (recovery != null && !historyScanBusy &&
                System.currentTimeMillis() - lastHistoryScanTime >= historyScanMinIntervalMs) {
                runHistoryScan(recovery)
            } else {
                recovery?.let { if (!it.isRecycled) it.recycle() }
                drainOcrQueue()
            }
        }
    }

    private fun prepareHistoryScan(fullBitmap: Bitmap) {
        if (!historyScanPending || historyScanBusy || pendingHistoryBitmap != null) return
        val now = System.currentTimeMillis()
        if (now - lastHistoryScanTime < historyScanMinIntervalMs) return
        historyScanPending = false
        historyScanBusy = true
        lastHistoryScanTime = now
        try {
            // Aviator layouts commonly place the recent-round strip along the top.
            // Scan that strip only; the live multiplier crop is untouched.
            val h = (fullBitmap.height * 0.22f).toInt().coerceAtLeast(1)
            pendingHistoryBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, h)
        } catch (_: Exception) {
            historyScanBusy = false
        }
    }

    private fun runHistoryScan(bitmap: Bitmap) {
        ocrBusy = true
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                try {
                    val text = buildString {
                        append(result.text)
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                append('\\n').append(line.text)
                            }
                        }
                    }
                    val values = extractHistoryMultipliers(text)
                    if (values.size >= 2) reconcileHistory(values)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    historyScanBusy = false
                    ocrBusy = false
                    drainOcrQueue()
                }
            }
            .addOnFailureListener {
                if (!bitmap.isRecycled) bitmap.recycle()
                historyScanBusy = false
                ocrBusy = false
                drainOcrQueue()
            }
    }

    private fun extractHistoryMultipliers(text: String): List<Double> {
        if (text.isBlank()) return emptyList()
        val source = text
            .replace('×', 'x').replace('X', 'x')
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1').replace('L', '1')
        val patterns = listOf(
            Regex("""(?<![\\d.])(\\d{1,12})\\s*[.,]\\s*(\\d{1,3})\\s*[xX]\\b"""),
            Regex("""(?<![\\d.])((?:\\d{1,3}(?:[,\\s]\\d{3})+|\\d+)(?:[.]\\d{1,100})?)\\s*[xX]\\b""")
        )
        val out = ArrayList<Double>(24)
        for (pattern in patterns) {
            for (m in pattern.findAll(source)) {
                val raw = if (m.groupValues.size > 2 && m.groupValues[2].isNotEmpty())
                    m.groupValues[1] + "." + m.groupValues[2] else m.groupValues[1]
                val v = normalizeNumericToken(raw).toDoubleOrNull()
                if (v != null && v.isFinite() && v >= 1.0) out.add(v)
            }
        }
        return out.distinctBy { String.format(Locale.US, "%.3f", it) }.take(30)
    }

    private fun reconcileHistory(scanned: List<Double>) {
        if (history.size < 2 || scanned.size < 2) return
        val saved = history.takeLast(30)
        var bestCandidate = emptyList<Double>()
        var bestMatches = -1
        var bestSpan = Int.MAX_VALUE

        for (candidate in listOf(scanned, scanned.asReversed())) {
            // Find the strongest ordered chain of matching history points.
            for (si in candidate.indices) {
                for (hi in saved.indices) {
                    if (abs(candidate[si] - saved[hi]) > 0.02) continue
                    var s = si
                    var h = hi
                    var matches = 0
                    while (s < candidate.size && h < saved.size) {
                        if (abs(candidate[s] - saved[h]) <= 0.02) {
                            matches++; s++; h++
                        } else {
                            // One missing/incorrect record is allowed at a time.
                            val nextS = if (s + 1 < candidate.size && abs(candidate[s + 1] - saved[h]) <= 0.02) 1 else 0
                            val nextH = if (h + 1 < saved.size && abs(candidate[s] - saved[h + 1]) <= 0.02) 1 else 0
                            if (nextS > 0) s++ else if (nextH > 0) h++ else break
                        }
                    }
                    val span = h - hi
                    if (matches > bestMatches || (matches == bestMatches && span < bestSpan)) {
                        bestMatches = matches
                        bestSpan = span
                        bestCandidate = candidate
                    }
                }
            }
        }

        // Require two independent anchors before editing stored history.
        if (bestMatches < 2 || bestCandidate.isEmpty()) return

        // Locate the first and last reliable anchors and replace only the region
        // between them. This repairs a wrong value and also inserts skipped rounds.
        val tailStart = maxOf(0, history.size - 30)
        val tail = history.subList(tailStart, history.size).toList()
        var firstSaved = -1
        var firstScan = -1
        outer@ for (si in bestCandidate.indices) {
            for (hi in tail.indices) {
                if (abs(bestCandidate[si] - tail[hi]) <= 0.02) {
                    firstScan = si; firstSaved = hi; break@outer
                }
            }
        }
        if (firstSaved < 0) return

        var lastSaved = -1
        var lastScan = -1
        for (si in bestCandidate.indices.reversed()) {
            for (hi in tail.indices.reversed()) {
                if (hi >= firstSaved && si >= firstScan && abs(bestCandidate[si] - tail[hi]) <= 0.02) {
                    lastScan = si; lastSaved = hi; break
                }
            }
            if (lastSaved >= 0) break
        }
        if (lastSaved < firstSaved || lastScan < firstScan) return

        val replacement = bestCandidate.subList(firstScan, lastScan + 1)
        val existing = tail.subList(firstSaved, lastSaved + 1)
        if (replacement.isEmpty() || replacement.size > existing.size + 5 || existing.size > replacement.size + 5) return

        var changed = replacement.size != existing.size
        val newTail = ArrayList<Double>(tail.size + replacement.size)
        newTail.addAll(tail.subList(0, firstSaved))
        for (i in replacement.indices) {
            val v = replacement[i]
            if (i >= existing.size || abs(existing[i] - v) > 0.02) changed = true
            newTail.add(v)
        }
        if (lastSaved + 1 < tail.size) newTail.addAll(tail.subList(lastSaved + 1, tail.size))
        if (!changed) return

        history.subList(tailStart, history.size).clear()
        history.addAll(newTail)
        if (history.size > 2000) {
            val keep = history.takeLast(2000)
            history.clear(); history.addAll(keep)
        }
        saveHistory()
        handler.post { updateStatus("HISTORY RECOVERED/CORRECTED | history=" + history.size) }
    }

    private fun validateMultiplier(multiplier: Double) {
        if (!multiplier.isFinite() || multiplier < 1.00 || multiplier > Double.MAX_VALUE) return

        // The live multiplier should not be forced to repeat across two OCR
        // frames: it changes rapidly (1.00x, 1.01x, 1.02x ...). Accept a new
        // value immediately when it is consistent with the current rising round.
        if (!roundActive) {
            startLiveRound(multiplier)
            return
        }

        // A genuine round cannot move backwards. A sharp return toward
        // 1.00x, however, is also a useful fallback round-boundary signal when
        // OCR misses the "flew away" text. Use two confirming low readings before
        // finalizing the last validated value, preventing a single OCR glitch
        // from creating a false round.
        if (!lastLiveMultiplier.isNaN() &&
            multiplier + 0.10 < lastLiveMultiplier) {
            if (lastLiveMultiplier >= 1.01 && multiplier <= 1.10) {
                lowAfterPeakCount++
                if (lowAfterPeakCount >= 2) {
                    finalizeDetectedRound(lastLiveMultiplier)
                    return
                }
            }
            sendDiagnosticThrottled(
                String.format(Locale.US, "Backward OCR ignored %.2f×", multiplier)
            )
            return
        }

        val now = System.currentTimeMillis()
        if (!lastLiveMultiplier.isNaN() &&
            abs(multiplier - lastLiveMultiplier) < 0.001) {
            return
        }

        lastAcceptedTime = now
        lastLiveMultiplier = multiplier
        currentRoundPeak = maxOf(
            if (currentRoundPeak.isNaN()) multiplier else currentRoundPeak,
            multiplier
        )
        lowAfterPeakCount = 0
        lastDetected = multiplier

        updateDetected(multiplier)
        sendLiveMultiplier(multiplier)
    }

    private fun startLiveRound(multiplier: Double) {
        roundActive = true
        roundFinalized = false
        lastFinalMultiplier = Double.NaN
        lastLiveMultiplier = multiplier
        currentRoundPeak = multiplier
        lowAfterPeakCount = 0
        lastDetected = multiplier

        updateDetected(multiplier)
        updateStatus("FLYING " + formatMultiplier(multiplier))
        sendLiveMultiplier(multiplier)
    }

    private fun finalizeDetectedRound(multiplier: Double) {
        if (!multiplier.isFinite() || multiplier < 1.0 || multiplier > Double.MAX_VALUE) return

        // Do not save the same final OCR result repeatedly while "flew away"
        // remains on screen.
        if (roundFinalized &&
            !lastFinalMultiplier.isNaN() &&
            abs(lastFinalMultiplier - multiplier) < 0.001) {
            return
        }

        if (!roundActive) {
            // A 1.00x crash can be so fast that the first useful OCR frame is
            // already the final "flew away" screen.
            roundActive = true
            lastLiveMultiplier = multiplier
            currentRoundPeak = multiplier
        }

        roundFinalized = true
        lastFinalMultiplier = multiplier
        lastLiveMultiplier = multiplier
        currentRoundPeak = multiplier
        lastDetected = multiplier

        updateDetected(multiplier)
        updateStatus(
            "FLEW AWAY " + formatMultiplier(multiplier) + " — ROUND COMPLETE"
        )
        completeRound(multiplier)

        roundActive = false
        roundFinalized = true
        currentRoundPeak = Double.NaN
        lowAfterPeakCount = 0
        pendingMultiplier = Double.NaN
        pendingCount = 0
        lastLiveMultiplier = Double.NaN
    }

    private fun completeRound(multiplier: Double) {
        if (!multiplier.isFinite() || multiplier < 1.0 || multiplier > Double.MAX_VALUE) return

        history.add(multiplier)
        if (history.size > 2000) history.removeAt(0)
        saveHistory()
        sendRoundCompleted(multiplier)
        historyScanPending = true

        updateStatus(
            "ROUND SAVED " + formatMultiplier(multiplier) + " | history=" + history.size
        )

        // The round is already displayed/broadcast. Calculate the expensive
        // statistical estimate from a snapshot so OCR and UI never wait for it.
        val snapshot = history.toList()
        predictionExecutor.execute {
            val estimate = predictionModel.estimate(snapshot)
            if (estimate != null && !serviceStopping) {
                handler.post { updatePrediction(estimate) }
            }
        }
    }

    private fun loadHistory() {
        try {
            val raw = preferences.getString("rounds", "[]") ?: "[]"
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optDouble(i, Double.NaN)
                if (value.isFinite() && value >= 1.0) history.add(value)
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
            history.forEach { array.put(it) }
            preferences.edit().putString("rounds", array.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun createFloatingDisplay() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(16, 12, 16, 12)
        container.setBackgroundColor(Color.argb(225, 20, 20, 20))

        statusTextView = TextView(this)
        detectedTextView = TextView(this)
        predictedTextView = TextView(this)
        confidenceTextView = TextView(this)

        statusTextView?.text = "AVIATOR MONITOR"
        detectedTextView?.text = "Detected: --"
        predictedTextView?.text = "Next: —"
        confidenceTextView?.text = "No estimate until 10 completed rounds"

        statusTextView?.setTextColor(Color.WHITE)
        detectedTextView?.setTextColor(Color.WHITE)
        predictedTextView?.setTextColor(Color.WHITE)
        confidenceTextView?.setTextColor(Color.WHITE)

        container.addView(statusTextView)
        container.addView(detectedTextView)
        container.addView(predictedTextView)
        container.addView(confidenceTextView)

        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 120

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            overlayView = container
        } catch (e: Exception) {
            sendDiagnostic("Overlay ERROR: ${e.message}")
        }
    }

    private fun formatMultiplier(multiplier: Double): String =
        if (multiplier.isFinite()) String.format(Locale.US, "%.2f", multiplier) else "overflow"

    private fun updateDetected(multiplier: Double) {
        handler.post {
            detectedTextView?.text = formatMultiplier(multiplier)
        }
    }

    private fun updatePrediction(estimate: PredictionEstimate) {
        handler.post {
            predictedTextView?.text = String.format(Locale.US, "Estimate: %.2f×", estimate.value)
            confidenceTextView?.text = String.format(
                Locale.US,
                "Range %.2f–%.2f× | %.0f%% | n=%d | P≥2× %.0f%%",
                estimate.lower, estimate.upper, estimate.confidence,
                estimate.sampleSize, estimate.probabilityAbove2x
            )
            sendPrediction(estimate)
        }
    }

    private fun sendPrediction(estimate: PredictionEstimate) {
        sendBroadcast(Intent(PREDICTION_ACTION).apply {
            putExtra("value", estimate.value)
            putExtra("lower", estimate.lower)
            putExtra("upper", estimate.upper)
            putExtra("confidence", estimate.confidence)
            putExtra("sampleSize", estimate.sampleSize)
        })
    }

    private fun updateStatus(status: String) {
        handler.post { statusTextView?.text = status }
    }

    private fun sendLiveMultiplier(multiplier: Double) {
        sendBroadcast(Intent(LIVE_MULTIPLIER_ACTION).apply {
            putExtra("multiplier", multiplier)
        })
    }

    private fun sendRoundCompleted(multiplier: Double) {
        sendBroadcast(Intent(ROUND_COMPLETED_ACTION).apply {
            putExtra("multiplier", multiplier)
        })
    }

    private fun sendDiagnostic(message: String) {
        sendBroadcast(Intent(DIAGNOSTIC_ACTION).apply {
            putExtra("message", message)
        })
    }

    private fun sendDiagnosticThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticTime < 1000L) return
        lastDiagnosticTime = now
        sendDiagnostic(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aviator Screen Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aviator Predictor")
            .setContentText("Screen monitoring is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    private fun stopCapture() {
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}

        imageReader = null
        virtualDisplay = null
        mediaProjection = null
        ocrBusy = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the activity away must not stop the monitor.
        // The explicit Stop button is the intended shutdown control.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceStopping = true
        stopCapture()

        try { recognizer.close() } catch (_: Exception) {}
        pendingHistoryBitmap?.let { try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {} }
        pendingHistoryBitmap = null
        while (pendingOcrFrames.isNotEmpty()) {
            val frame = pendingOcrFrames.removeFirst()
            try { if (!frame.isRecycled) frame.recycle() } catch (_: Exception) {}
        }
        try { predictionExecutor.shutdownNow() } catch (_: Exception) {}

        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}

        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
