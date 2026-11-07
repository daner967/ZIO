plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bajar.app"
    compileSdk = 35
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    defaultConfig {
        applicationId = "com.zio.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            // Misma firma que antes, así se instala encima. Sin R8: yt-dlp usa reflexión y se rompería
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += listOf("mp3") }
}

kotlin { jvmToolchain(17) }

dependencies {
    val ydl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ydl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ydl")
}
