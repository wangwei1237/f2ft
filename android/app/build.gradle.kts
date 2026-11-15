plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds pass -PappVersion=X.Y.Z; local builds keep the defaults.
val appVersion: String? = providers.gradleProperty("appVersion").orNull
val appVersionCode: Int = appVersion?.let { v ->
    val parts = v.split(".").map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it >= 0 } && parts[1]!! <= 99 && parts[2]!! <= 99) {
        "appVersion must be X.Y.Z with Y and Z between 0 and 99, got '$v'"
    }
    parts[0]!! * 10000 + parts[1]!! * 100 + parts[2]!!
} ?: 1

// Release signing comes from environment variables set by CI; without them the release build is unsigned.
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull

android {
    namespace = "com.fy.facetranslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fy.facetranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion ?: "1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // One APK per CPU architecture plus a universal APK containing all of them.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
