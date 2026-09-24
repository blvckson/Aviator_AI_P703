
package com.example.aviatorai

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
    }

    private lateinit var statusText: TextView
    private lateinit var detectedText: TextView

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
            requestScreenCapture()
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
        }
    }

    override fun onResume() {

        super.onResume()

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
            }

        registerReceiver(
            multiplierReceiver,
            filter
        )
    }

    override fun onPause() {

        unregisterReceiver(
            multiplierReceiver
        )

        super.onPause()
    }

    private fun requestScreenCapture() {

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val intent =
            manager.createScreenCaptureIntent()

        startActivityForResult(
            intent,
            SCREEN_CAPTURE_REQUEST
        )
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

if (requestCode != SCREEN_CAPTURE_REQUEST) {
    return
}

if (resultCode != RESULT_OK) {
    statusText.text =
        "ERROR: Android returned resultCode = $resultCode"
    return
}

if (data == null) {
    statusText.text =
        "ERROR: Android returned NULL capture data"
    return
}
            val serviceIntent =
                Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {

                    putExtra(
                        "resultCode",
                        resultCode
                    )

                    putExtra(
                        "data",
                        data
                    )
                }

            startService(
                serviceIntent
            )

            statusText.text =
                "Screen monitor running"
        }
    }
}
