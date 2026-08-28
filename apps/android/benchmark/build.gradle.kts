plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

/*
 * The module that measures, and the module that records.
 *
 * It is a `com.android.test` project, so it builds an instrumentation APK with **no application
 * of its own** and targets `:app` instead. That separation is the whole point: a frame timing
 * taken from inside the process being timed is a frame timing of a process carrying a test
 * runner, and the numbers this exists to defend would include the instrument. Macrobenchmark
 * drives the installed APK through UiAutomator and reads the frames back out of the system's own
 * trace, so what it reports is what the phone actually drew.
 *
 * Nothing here ships. It is not a dependency of `:app`; `:app` names it the other way round, as
 * the producer of a baseline profile, which is a text file that gets packaged and not code.
 */
android {
    namespace = "fr.kristenjestin.mue.benchmark"
    compileSdk = 36

    defaultConfig {
        // 28 is the floor for macrobenchmark: below it the platform has no `profileable` shell
        // and no `Trace` sections to read, and the app's own floor of 26 cannot be honoured here.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /*
     * No `buildTypes` block, deliberately.
     *
     * A `com.android.test` project declares only `debug`, and the two variants this module is
     * actually built in are written by the `androidx.baselineprofile` plugin on both sides of the
     * pair: `nonMinifiedRelease` — R8 off, so the profile records unobfuscated names — and
     * `benchmarkRelease` — R8 on, which is the shape the owner installs and therefore the only
     * shape worth timing. Declaring a `release` here would fail configuration and shadow them.
     */
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    /*
     * The generation run is driven by hand, pinned to one emulator serial, and never by a build.
     *
     * `automaticGenerationDuringBuild = true` would make every `assembleRelease` install and
     * instrument on **every attached device**, and one of the attached devices here is the
     * owner's phone. That is the exact mechanism that destroyed his weight history once already.
     */
    useConnectedDevices = true
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
