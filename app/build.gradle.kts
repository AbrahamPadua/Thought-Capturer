plugins {
    id("com.android.application")
}

android {
    namespace = "app.thoughtcapturer"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.thoughtcapturer"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only has org.json stubs, so local unit tests need the real one
    testImplementation("org.json:json:20240303")
}
