plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bajar.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bajar.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
    val ydl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ydl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ydl")
}
