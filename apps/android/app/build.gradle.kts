plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fr.kristenjestin.mue"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.kristenjestin.mue"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // An unsigned APK cannot be installed, so the minified build could never be
            // exercised on a device. `-PmueDebugSigning` signs it with the local debug key
            // for that purpose only; the store key is supplied out of band and no keystore
            // or credential belongs in this repository.
            if (providers.gradleProperty("mueDebugSigning").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // `MigrationTestHelper` reads the exported schemas off the test APK, so the folder KSP
    // writes them to has to ship as an androidTest asset.
    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"))
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

// Room stores its generated schemas here so migrations can be verified in tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // No navigation library: the shell is four sibling tabs with no back stack to model, so
    // `MueNavigationHost` is a saved integer, and the one tab that does have a stack --
    // Activity -- models it as a saved list of routes. Both are Compose's own AnimatedContent.

    // Storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // An activity draft is a nested structure, so `SavedStateHandle` holds it as one JSON
    // string rather than as a flat set of Bundle keys. A sync payload is stored the same way.
    implementation(libs.kotlinx.serialization.json)

    // Server synchronisation (sync PRD 19). The client, its engine and the deferred worker are
    // declared together so one Ktor version answers for all of them; see the note on the force
    // block below for why that version is not simply the newest.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.androidx.work.runtime.ktx)

    // Local unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    // Ktor's own in-memory engine, so the sync client's path, bearer and error mapping are
    // asserted on the JVM without a socket. Test-only, on the same version as the client above,
    // so it adds nothing to the APK and nothing to the `force` block's reasoning below.
    testImplementation(libs.ktor.client.mock)

    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/*
 * Realigns kotlinx-serialization on the version Room is built against.
 *
 * `androidx.room:room-migration` — what `MigrationTestHelper` parses the exported schema with —
 * asks for 1.8.1, while `androidx.savedstate` brings the 1.7.3 BOM, which pins the whole family
 * *strictly*. Two strict versions cannot be reconciled by ordering, so `force` is the only way
 * out; left alone, the helper dies inside its own generated serializers with an
 * `AbstractMethodError` on `GeneratedSerializer.typeParametersSerializers`.
 *
 * The force covers the app as well as the test APK on purpose: an instrumentation APK loads
 * shared classes from the app's classloader first, so pinning the test side alone would change
 * nothing. The app now serializes its activity draft with the very same version it declares
 * above, so the force only moves a library every party here already expects to share.
 *
 * Ktor is the third party to this pin, and it is why the client is on 3.2.4 rather than on the
 * newest release: 3.2.4 is the last version whose `ktor-serialization-kotlinx` asks for exactly
 * 1.8.1, so nothing here has to be re-derived. From 3.3.0 onward Ktor asks for 1.9.0, which
 * would force the pin up and put `MigrationTestHelper` back on a version of the runtime it is
 * not built against. Bumping Ktor past 3.2.x therefore means re-deriving this force and
 * re-running the instrumented suite, not editing one number.
 *
 * `kotlinx-serialization-json-io` is listed for completeness: Ktor pulls it as a third artefact
 * of the same family, and a pin covering two of three holds only until something moves the
 * third. It resolves to the same version today, so the line is a no-op, which is the point of
 * writing it now rather than after a skew has produced an `AbstractMethodError` nobody can place.
 */
configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.kotlinxSerialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinxSerialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-io:${libs.versions.kotlinxSerialization.get()}",
    )
}
