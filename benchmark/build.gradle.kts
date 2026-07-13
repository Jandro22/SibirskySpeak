plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sibirskyspeak.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The local AVD is useful for smoke validation but is not a real
        // performance device; keep macrobenchmark execution usable there.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,NOT-SELF-INSTRUMENTING"
    }

    targetProjectPath = ":app"

    buildTypes {
        create("benchmark") {
            // The tested application is non-debuggable and shrinker-enabled;
            // mirror that shape so R8 mapping/obfuscation is exercised here too.
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            // The target app is the shrinker subject; keeping the tiny test APK
            // unminified avoids R8 interpreting JDK-only test annotations as app
            // runtime classes.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// The app's benchmark variant is minified and non-debuggable. This task is only
// an additional consistency check for a minified *test* APK, which is not useful
// for the benchmark signal and would reject harmless test-only annotations.
tasks.matching { it.name == "checkTestedAppObfuscationBenchmark" }.configureEach {
    enabled = false
}

dependencies {
    // Keep the runner/core pair aligned. Macrobenchmark launches the runner
    // inside the tested process, so mixing generations can omit Kotlin
    // synthetic classes at instrumentation bootstrap.
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:core:1.6.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.arch.core:core-common:2.2.0")
    implementation("androidx.arch.core:core-runtime:2.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.tracing:tracing:1.2.0")
    implementation("junit:junit:4.13.2")
    implementation("com.google.errorprone:error_prone_annotations:2.36.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
}
