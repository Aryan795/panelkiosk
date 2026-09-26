plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "dev.aryan.panelkiosk"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.aryan.panelkiosk"
    minSdk = 23          // Android 6.0; older WebViews are too old to run the dashboard anyway
    targetSdk = 35
    versionCode = 4
    versionName = "1.3"
  }
  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  buildFeatures { buildConfig = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.webkit:webkit:1.12.1")
  implementation("androidx.lifecycle:lifecycle-service:2.6.2") // camera service, see MotionService
  val camerax = "1.4.1"
  implementation("androidx.camera:camera-core:$camerax")
  implementation("androidx.camera:camera-camera2:$camerax")
  implementation("androidx.camera:camera-lifecycle:$camerax")
}
