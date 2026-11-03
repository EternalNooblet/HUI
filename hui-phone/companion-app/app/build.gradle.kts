plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// --- Deterministic release versioning (see GITHUB_RELEASE_PIPELINE_PLAN.md) ---
// CI sets VERSION_NAME (git tag, e.g. v0.1.10) and VERSION_CODE (monotonic int).
// Locally: fall back to `git describe --tags --abbrev=0`, then "1.0.0-dev", and
// epoch-seconds for versionCode (monotonic until 2038, fits Int).
fun gitTagOrNull(): String? = try {
    val p = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(project.rootProject.projectDir.parentFile)
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0 && out.isNotEmpty()) out else null
} catch (e: Exception) { null }

val propOrEnv: (String) -> String? = { key ->
    (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
}

val releaseVersionName: String = (
    propOrEnv("VERSION_NAME")
        ?: gitTagOrNull()
        ?: "1.0.0-dev"
).removePrefix("v")

val releaseVersionCode: Int = (
    propOrEnv("VERSION_CODE")
        ?: (System.currentTimeMillis() / 1000).toString()
).toIntOrNull() ?: 1

val buildCodename: String = propOrEnv("BUILD_CODENAME") ?: "hui"
val buildNumber: Long = System.currentTimeMillis() / 1000

// --- Release signing (CI injects these; local builds fall back to debug) ---
val ksFile: String? = propOrEnv("android.injected.signing.store.file")
    ?: propOrEnv("KEYSTORE_FILE") ?: propOrEnv("ANDROID_KEYSTORE_FILE")
val ksPassword: String? = propOrEnv("android.injected.signing.store.password")
    ?: propOrEnv("KEYSTORE_PASSWORD") ?: propOrEnv("ANDROID_KEYSTORE_PASSWORD")
val ksAlias: String? = propOrEnv("android.injected.signing.key.alias")
    ?: propOrEnv("KEY_ALIAS") ?: propOrEnv("ANDROID_KEY_ALIAS")
val ksKeyPassword: String? = propOrEnv("android.injected.signing.key.password")
    ?: propOrEnv("KEY_PASSWORD") ?: propOrEnv("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.hui.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hui.companion"
        minSdk = 27  // Android 8.1
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("String", "BUILD_CODENAME", "\"$buildCodename\"")
        buildConfigField("Long", "BUILD_NUMBER", "${buildNumber}L")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildNumber\"")

        buildConfigField("String", "SERVER_URL", "\"wss://10.0.0.126:8081\"")
        buildConfigField("String", "DEVICE_ID", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // TensorFlow Lite model files - don't compress
    aaptOptions {
        noCompress += listOf("tflite", "lite")
    }

    // Packaging options for native libs
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room Database
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:${room_version}")
    implementation("androidx.room:room-ktx:${room_version}")
    kapt("androidx.room:room-compiler:${room_version}")

    // CameraX
    val camerax_version = "1.3.3"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

    // OkHttp + WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // EncryptedSharedPreferences for token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha05")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Image loading for ImageMessageView (prompt-kit Image component)
    implementation("io.coil-kt:coil:2.5.0")

    // QR authentication (ML Kit + CameraX)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
    }
}