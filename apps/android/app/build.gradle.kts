plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
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

        /*
         * The build the owner actually carries: `release`'s speed with `debug`'s trust store.
         *
         * A debug build is not what the app feels like. Measured over 24 tab switches on one
         * device: debug spends 13–29 frames past 64 ms with a worst case of 81–200 ms, while the
         * same source under R8 spends 0–1 and never passes 100 ms. The half-second before a tab
         * moves is unoptimised, un-precompiled code, not the layout.
         *
         * But `release` cannot reach his server: the network security config that trusts a
         * user-installed authority lives in the `debug` source set on purpose, so a shipped build
         * only ever trusts the platform store. Judging the speed then costs the synchronisation,
         * and judging the synchronisation costs the speed.
         *
         * `local` initialises from `release` — minified, shrunk, R8'd, baseline profile and all —
         * and adds only the debug source set's `res/xml` and manifest overlay. Production is
         * untouched: `release` is still built from `main` + `release` alone, and this variant is
         * signed with the debug key, so it can never be published.
         */
        create("local") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = null
            signingConfig = signingConfigs.getByName("debug")
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

    /*
     * `local` reads the `debug` source set rather than owning a copy of it.
     *
     * The only thing it needs from there is the manifest overlay contributing
     * `android:networkSecurityConfig` and the `res/xml` it points at — the two files that let a
     * build trust a user-installed authority, and therefore reach a server whose certificate no
     * public CA would ever issue. Copying them would be two files free to drift, and the drift
     * would show up as a phone that pairs on one build and refuses on the other.
     *
     * Pointed, not copied, so `debug` and `local` cannot disagree about what they trust.
     */
    sourceSets.getByName("local") {
        manifest.srcFile("src/debug/AndroidManifest.xml")
        res.srcDirs(files("src/debug/res"))
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

/*
 * How the recorded profile gets into the APK.
 *
 * `saveInSrc` keeps the result under version control at
 * `app/src/release/generated/baselineProfiles/`, which is the difference between a profile and a
 * hope: a checked-in file is one every build packages and one a reviewer can read, where a
 * regenerated-on-demand file is whatever the last machine to run the emulator happened to record.
 *
 * `automaticGenerationDuringBuild` stays **off**, and it is the load-bearing line in this block.
 * Turned on, every `assembleRelease` would install the app and run an instrumentation on every
 * attached device — and the device attached to this machine beside the emulator is the owner's
 * own phone, over wireless debugging. Regeneration is a deliberate act, run against a named
 * serial, and `:benchmark`'s `BaselineProfileGenerator` says how.
 */
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    /*
     * The profile is a *rule*, so R8 keeps and lays out the classes it names even when it can see
     * no other reference to them. Off, R8 is free to move a startup class away from the classes
     * it is loaded beside, and the profile then names methods in pages the loader has to seek to.
     */
    dexLayoutOptimization = true
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
    // `ProcessLifecycleOwner`: whether *the application* is in the foreground, rather than
    // whether some activity is. The live channel of sync PRD 9.4 is scoped to it, and the 700 ms
    // debounce it already carries is what keeps a rotation from closing and reopening a socket.
    implementation(libs.androidx.lifecycle.process)

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

    /*
     * What actually installs the baseline profile on the phone.
     *
     * The profile `:benchmark` records is packaged as `assets/dexopt/baseline.prof`, and on a
     * device that got the APK from Play the Play installer hands it to ART by itself. On every
     * other route — a sideload, an `adb install`, an update pushed to the owner's own phone —
     * nothing does, and the profile sits in the APK doing nothing at all. `ProfileInstaller` is
     * the library that writes it into ART's own store on first run, through the `androidx.startup`
     * initialiser it declares in its own manifest, so there is no call site for it here.
     *
     * It drags in `androidx.startup:startup-runtime` and nothing else; neither touches
     * `kotlinx-serialization`, so the `force` block below is unchanged by it. Checked with
     * `dependencies --configuration releaseRuntimeClasspath`, not assumed.
     */
    implementation(libs.androidx.profileinstaller)

    /*
     * The producer of that profile. This is not a code dependency in either direction: `:app`
     * does not compile against `:benchmark`, and the only artefact that crosses is the text file
     * of class and method names the `androidx.baselineprofile` plugin copies into
     * `src/release/generated/baselineProfiles/`.
     */
    baselineProfile(project(":benchmark"))

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

    /*
     * FR-FOOD-003's scanner: the camera, and the decoder that reads a barcode out of its frames.
     *
     * PRD_FOOD 9.2 fixes both halves of this choice. "Le décodage est **local** … aucune image ne
     * quitte le téléphone" is why the bundled `com.google.mlkit:barcode-scanning` is used rather
     * than `play-services-mlkit-barcode-scanning`: the unbundled variant fetches its model through
     * Google Play services on first use, which is a network round trip about a photograph, on the
     * one path this PRD promises never leaves the device. The bundled artefact ships the model in
     * the APK, so a phone in flight mode decodes exactly as well as one on wifi.
     *
     * CameraX rather than `android.hardware.camera2`, for the reason every release note gives:
     * the lifecycle binding, the rotation handling and the analysis back-pressure are the parts
     * that are wrong on some device somewhere, and they are not parts worth re-deriving for one
     * screen. `camera-lifecycle` is what stops the preview when the sheet closes.
     *
     * **`camera-compose` and not `camera-view`**, and the difference is two whole libraries.
     * `camera-view` exists to give a `PreviewView` to a `View` hierarchy, and it declares
     * `androidx.appcompat` and `androidx.camera:camera-video` to do it — an `AppCompatActivity`
     * theme stack and a video recorder, in an app that has neither a `View` layout nor a
     * `Recorder` anywhere in it. `camera-compose` declares Compose, `camera-core` and
     * `camera-viewfinder`, and `CameraXViewfinder` is a composable that takes the `SurfaceRequest`
     * `Preview` already emits.
     *
     * Nothing added here drags a `kotlinx-serialization` artefact in: the `force` block below
     * still resolves 1.8.1 for all three, checked with
     * `dependencies --configuration debugRuntimeClasspath` after the change, not assumed.
     *
     * What ML Kit *does* bring is named in `AndroidManifest.xml` beside the permission, because
     * one of its transitive dependencies uploads usage telemetry unless it is switched off, and
     * PRD_FOOD 22's "seul le numéro est transmis" is a claim about the whole application.
     */
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.mlkit.barcode.scanning)

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
