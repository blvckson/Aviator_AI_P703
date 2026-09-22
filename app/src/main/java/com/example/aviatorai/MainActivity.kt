package     override fun onPause() {
        unregisterReceiver(multiplierReceiver)
        super.onPause()
    }

    private fun requestScreenCapture() {
        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

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
