/*
 * Throwaway spike module: proves we can pull a weight off the HOMEBUDS HB9027 scale
 * (BLE name `HB BODY FAT`) before any of this is designed into `:app`.
 *
 * It shares the root version catalog and nothing else. `:app` does not depend on it, and it
 * does not depend on `:app`. Deleting the `spike/scale` branch removes it whole.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "fr.kristenjestin.muespike"
    compileSdk = 36

    defaultConfig {
        // A distinct id so the spike and the real app can sit on the phone at the same time.
        applicationId = "fr.kristenjestin.muespike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
        renderScript = false
    }

    // `bun run android:lint` sweeps every module in the build. A spike must never be what
    // makes that script fail, so its findings are reported and not fatal.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
