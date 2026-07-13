import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("jacoco")
}

android {
    namespace = "com.sibirskyspeak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sibirskyspeak"
        minSdk = 26
        targetSdk = 35
        // Formal releases encode SemVer into a unique Android versionCode. CI
        // may override this for the isolated rolling channel only.
        val configuredVersionName = System.getenv("VERSION_NAME") ?: "2.0.0"
        versionName = configuredVersionName
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull())
            ?: semVerVersionCode(configuredVersionName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is deliberately fail-closed. A missing production key must
    // never silently turn a public artifact into a debug-signed APK.
    val releaseSigning = mapOf(
        "KEYSTORE_FILE" to System.getenv("KEYSTORE_FILE"),
        "KEYSTORE_PASSWORD" to System.getenv("KEYSTORE_PASSWORD"),
        "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
        "KEY_PASSWORD" to System.getenv("KEY_PASSWORD")
    )
    val configuredSigning = releaseSigning.values.any { !it.isNullOrBlank() }
    val completeSigning = releaseSigning.values.all { !it.isNullOrBlank() }
    if (configuredSigning && !completeSigning) {
        throw GradleException("Release signing requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD together")
    }
    val releaseTaskRequested = gradle.startParameter.taskNames.any {
        it.substringAfterLast(':').contains("release", ignoreCase = true)
    }
    if (releaseTaskRequested && !completeSigning) {
        throw GradleException("Refusing to configure a release build without production signing credentials")
    }
    val releaseKeystore = releaseSigning.getValue("KEYSTORE_FILE")
    if (completeSigning && !rootProject.file(releaseKeystore!!).isFile) {
        throw GradleException("KEYSTORE_FILE does not point to a readable keystore: $releaseKeystore")
    }
    val rollingChannel = System.getenv("BUILD_CHANNEL") == "rolling"

    signingConfigs {
        // Debug signing is only for local/QA builds. The rolling channel has an
        // isolated application id and can never be mistaken for a public release.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (completeSigning) {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            if (rollingChannel) applicationIdSuffix = ".dev"
        }
        // Instrumentation runners uninstall their target package after a connected
        // test run. Never point them at the learner's real debug install: QA uses an
        // isolated application id and therefore an isolated database/files directory.
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        // Non-debuggable, shrinker-equivalent build used by Macrobenchmark. It
        // uses the local debug key only for repeatable benchmark runs; public
        // release artifacts remain signed by the fail-closed production config.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            proguardFiles("benchmark-rules.pro")
        }
    }

    testBuildType = "qa"

    buildFeatures {
        compose = true
        buildConfig = true
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

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

private fun semVerVersionCode(version: String): Int {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(version)
        ?: throw GradleException("VERSION_NAME must be SemVer (major.minor.patch), got '$version'")
    val (major, minor, patch) = match.destructured
    val code = major.toLong() * 1_000_000L + minor.toLong() * 1_000L + patch.toLong()
    require(code in 1..Int.MAX_VALUE) { "VERSION_NAME is too large for Android versionCode: $version" }
    return code.toInt()
}

// Export the Room schema to version-controlled JSON so future schema changes can
// ship real migrations instead of wiping user data. (Enabled alongside
// exportSchema = true on @Database.)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX Test's runner references this class while launching the
    // non-debuggable benchmark variant. Keep it in the tested APK so the
    // macrobenchmark process can bootstrap on device.
    implementation("androidx.tracing:tracing:1.2.0")
    add("benchmarkImplementation", "androidx.test:monitor:1.7.2")
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
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.xerial:sqlite-jdbc:3.46.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose UI test 1.7.6 otherwise resolves Espresso 3.5.0, whose
    // InputManager reflection fails on Android 16. Espresso 3.7.0 uses the
    // public system-service API and keeps the physical-device gate working.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Instrumentation targets the isolated `qa` build type, not debug. Without
    // this manifest the Compose rule cannot resolve ComponentActivity and every
    // standalone Compose UI test fails before its content is composed.
    add("qaImplementation", "androidx.compose.ui:ui-test-manifest")
}

tasks.register("simCheck") {
    group = "verification"
    description = "Runs the seeded pedagogy learner simulation regression suite."
    dependsOn("testDebugUnitTest")
}

// Unit-test coverage is intentionally report-only while the project establishes a
// measured baseline. The report is split from the Android test task so CI can publish
// it without making instrumentation availability a prerequisite for local development.
tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    group = "verification"
    description = "Generates XML and HTML coverage for the debug JVM test suite."
    dependsOn("testDebugUnitTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    classDirectories.setFrom(
        files(
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
        ).asFileTree.matching {
            exclude(
                "**/R.class", "**/R$*.class", "**/BuildConfig.class", "**/Manifest*.*",
                "**/*_Factory.class", "**/*_MembersInjector.class", "**/*_HiltModules*.class",
                "**/Hilt_*.*", "**/*_Impl.class"
            )
        }
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/debugUnitTest/jacocoDebugUnitTestReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/debugUnitTest/html"))
    }
}

tasks.register("coverageBaseline") {
    group = "verification"
    description = "Generates the measured debug coverage baseline used by CI trend tracking."
    dependsOn("jacocoDebugUnitTestReport")
}
