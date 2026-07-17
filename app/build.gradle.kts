plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nader.screenfinder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nader.screenfinder"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("rel") {
            val ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "screenfinder"
                keyAlias = "sf"
                keyPassword = "screenfinder"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.8")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
