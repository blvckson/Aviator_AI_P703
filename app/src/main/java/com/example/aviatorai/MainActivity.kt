            statusText.textpackage com.example.aviatorai

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
        private const val MULTIPLIER_ACTION =
            "com.example.aviatorai.MULTIPLIER_DETECTED"
    }

    private lateinit var statusText: TextView
    private lateinit var detectedText: TextView

    private val multiplierReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action == MULTIPLIER_ACTION) {

                val multiplier =
                    intent.getDoubleExtra("multiplier", -1.0)

                if (multiplier >= 1.0) {

                    detectedText.text =
                        String.format(
                            Locale.US,
                            "Detected multiplier: %.2fx",
                            multiplier
                        )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detectedText = findViewById(R.id.detectedText)

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

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

            statusText.text = "Monitor stopped"
        }
    }

    override fun onResume() {
        super.onResume()

        val filter =
            IntentFilter(MULTIPLIER_ACTION)

        registerReceiver(
            multiplierReceiver,
            filter
        )
    }

    override fun onPause() {
        unregisterReceiver(multiplierReceiver)
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

    @Deprecated("Deprecated in Android API 29")
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
            requestCode == SCREEN_CAPTURE_REQUEST &&
            resultCode == RESULT_OK &&
            data != null
        ) {

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

            startService(serviceIntent)

            statusText.text =
                "Screen monitor running"
        }
    }
}
