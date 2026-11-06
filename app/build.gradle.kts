plugins {
    id("com.android.application")
}

android {
    namespace = "ai.freeapp.app"
    compileSdk = 35

    defaultConfig {
        // Keep Aviator AI's existing application ID so this build updates the same app.
        applicationId = "com.example.aviatorai"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.10.0")
}
