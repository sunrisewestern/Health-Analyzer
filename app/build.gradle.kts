plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.healthanalyzer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.healthanalyzer.app"
        minSdk = 29 // Samsung Health Data SDK requires Android 10+
        targetSdk = 36
        versionCode = 6
        versionName = "1.4.0"
    }

    // Release signing: the keystore and passwords live in ~/.gradle/gradle.properties
    // (HEALTHANALYZER_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD), never in this project.
    val storeFilePath = providers.gradleProperty("HEALTHANALYZER_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath.replaceFirst("~", System.getProperty("user.home")))
                storePassword = providers.gradleProperty("HEALTHANALYZER_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("HEALTHANALYZER_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("HEALTHANALYZER_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // Own package (.dev) so debug installs next to the release app (separate data and signature).
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            // Without the properties (e.g. another machine) the release build is left unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // The Samsung Health Data SDK AAR ships data-binding classes.
        dataBinding = true
    }
}

// The Samsung Health Data SDK isn't redistributed in this repository – see README "Building".
val samsungSdk = file("libs/samsung-health-data-api-1.1.0.aar")
if (!samsungSdk.exists()) {
    throw GradleException(
        "Missing ${samsungSdk.relativeTo(rootDir)}. Download Samsung Health Data SDK 1.1.0 from " +
            "https://developer.samsung.com/health/data and copy libs/samsung-health-data-api-1.1.0.aar into app/libs/.",
    )
}

dependencies {
    // Samsung Health Data SDK (downloaded manually from developer.samsung.com)
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0") // required by the SDK
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.work:work-runtime-ktx:2.9.1") // scheduled automatic backups

    testImplementation("junit:junit:4.13.2")

    // AI providers: official Anthropic SDK + OkHttp for OpenAI-compatible endpoints
    implementation("com.anthropic:anthropic-java:3.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.garmin:fit:21.221.0") // Garmin FIT SDK (reads .fit activity files)
}

kotlin {
    jvmToolchain(17)
}
