plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sibirskyspeak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sibirskyspeak"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE (the run number) so each published build has a
        // higher code and installs cleanly over the previous one. Local builds
        // default to 1.
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    // A real release keystore is supplied via environment (e.g. CI secrets). When
    // it's absent (local dev), release builds fall back to the debug keystore so
    // `assembleRelease` still works without leaking or requiring secrets.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")

    signingConfigs {
        // Reproducible debug signing: both local and CI builds sign with the
        // committed debug keystore (public default credentials), so an APK built
        // by GitHub Actions installs as an update over a locally-installed one.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Export the Room schema to version-controlled JSON so future schema changes can
// ship real migrations instead of wiping user data. (Enabled alongside
// exportSchema = true on @Database.)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val roomVersion = "2.6.1"
    val lifecycleVersion = "2.8.7"
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
