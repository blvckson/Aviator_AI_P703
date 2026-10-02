package com.example.aviatorai

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {


companion object {

    private const val SCREEN_CAPTURE_REQUEST = 1001

    private const val ROUND_COMPLETED_ACTION =
        "com.example.aviatorai.ROUND_COMPLETED"

    private const val LIVE_MULTIPLIER_ACTION =
        "com.example.aviatorai.MULTIPLIER_LIVE"

    private const val DIAGNOSTIC_ACTION =
        "com.example.aviatorai.DIAGNOSTIC"

    private const val PREDICTION_ACTION =
        "com.example.aviatorai.PREDICTION"
}

private lateinit var statusText: TextView
private lateinit var detectedText: TextView
private lateinit var estimateText: TextView
private lateinit var confidenceText: TextView

private val multiplierReceiver =
    object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {

            when (intent?.action) {

                LIVE_MULTIPLIER_ACTION -> {

                    val multiplier =
                        intent.getDoubleExtra(
                            "multiplier",
                            -1.0
                        )

                    if (multiplier >= 1.0) {

                        detectedText.text =
                            String.format(
                                Locale.US,
                                "Live multiplier: %.2f×",
                                multiplier
                            )
                    }
                }

                ROUND_COMPLETED_ACTION -> {

                    val multiplier =
                        intent.getDoubleExtra(
                            "multiplier",
                            -1.0
                        )

                    if (multiplier >= 1.0) {

                        detectedText.text =
                            String.format(
                                Locale.US,
                                "Completed round: %.2f×",
                                multiplier
                            )
                    }
                }

                PREDICTION_ACTION -> {
                    val value = intent.getDoubleExtra("value", Double.NaN)
                    val lower = intent.getDoubleExtra("lower", Double.NaN)
                    val upper = intent.getDoubleExtra("upper", Double.NaN)
                    val confidence = intent.getDoubleExtra("confidence", 0.0)
                    val sampleSize = intent.getIntExtra("sampleSize", 0)

                    if (value.isFinite()) {
                        estimateText.text = String.format(
                            Locale.US,
                            "Statistical estimate: %.2f×",
                            value
                        )
                        confidenceText.text = String.format(
                            Locale.US,
                            "Uncertainty: %.2f–%.2f× | Confidence: %.0f%% | n=%d",
                            lower,
                            upper,
                            confidence,
                            sampleSize
                        )
                    }
                }

                DIAGNOSTIC_ACTION -> {

                    val message =
                        intent.getStringExtra(
                            "message"
                        )

                    if (!message.isNullOrEmpty()) {

                        statusText.text = message
                    }
                }
            }
        }
    }

override fun onCreate(
    savedInstanceState: Bundle?
) {
    super.onCreate(savedInstanceState)

    setContentView(
        R.layout.activity_main
    )

    statusText =
        findViewById(
            R.id.statusText
        )

    estimateText =
        findViewById(R.id.estimateText)

    confidenceText =
        findViewById(R.id.confidenceText)

    detectedText =
        findViewById(
            R.id.detectedText
        )

    val startButton =
        findViewById<Button>(
            R.id.startButton
        )

    val stopButton =
        findViewById<Button>(
            R.id.stopButton
        )

    startButton.setOnClickListener {
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "First allow Display over other apps, then press Start again."
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        } else {
            requestScreenCapture()
        }
    }

    stopButton.setOnClickListener {

        stopService(
            Intent(
                this,
                ScreenCaptureService::class.java
            )
        )

        statusText.text =
            "Monitor stopped"

        detectedText.text =
            "Detected: --"
        estimateText.text = "Statistical estimate: —"
        confidenceText.text = "Uncertainty: —"
    }
}

override fun onResume() {

    super.onResume()

    // Return from the overlay permission screen and show the correct state.
    if (::statusText.isInitialized && Settings.canDrawOverlays(this)) {
        if (statusText.text.toString().startsWith("First allow")) {
            statusText.text = "Overlay permission granted. Press Start Monitor."
        }
    }

    val filter =
        IntentFilter().apply {

            addAction(
                LIVE_MULTIPLIER_ACTION
            )

            addAction(
                ROUND_COMPLETED_ACTION
            )

            addAction(
                DIAGNOSTIC_ACTION
            )

            addAction(
                PREDICTION_ACTION
            )
        }

    registerReceiver(
        multiplierReceiver,
        filter
    )
}

override fun onPause() {

    try {

        unregisterReceiver(
            multiplierReceiver
        )

    } catch (_: IllegalArgumentException) {
    }

    super.onPause()
}

private fun requestScreenCapture() {

    try {

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val captureIntent =
            manager.createScreenCaptureIntent()

        startActivityForResult(
            captureIntent,
            SCREEN_CAPTURE_REQUEST
        )

    } catch (e: Exception) {

        statusText.text =
            "ERROR requesting screen capture: ${e.message}"
    }
}

@Deprecated(
    "Deprecated in Android API 29"
)
override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
) {

    super.onActivityResult(
        requestCode,
        resultCode,
        data
    )

    if (
        requestCode !=
        SCREEN_CAPTURE_REQUEST
    ) {
        return
    }

    if (
        resultCode !=
        RESULT_OK
    ) {

        statusText.text =
            "ERROR: Screen capture permission denied"

        return
    }

    if (data == null) {

        statusText.text =
            "ERROR: Android returned NULL capture data"

        return
    }

    /*
     * Store the MediaProjection permission data
     * directly inside ScreenCaptureService.
     *
     * We do NOT place the permission Intent
     * inside another Intent.
     */
    val serviceIntent =
        Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            putExtra("projection_result_code", resultCode)
            putExtra("projection_data", data)
        }

    try {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(
                serviceIntent
            )

        } else {

            startService(
                serviceIntent
            )
        }

        statusText.text =
            "Screen monitor running"

    } catch (e: Exception) {

        statusText.text =
            "ERROR starting monitor: ${e.message}"
    }
}


}
