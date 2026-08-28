package fr.kristenjestin.mue.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import fr.kristenjestin.mue.benchmark.MueJourney.awaitShell
import fr.kristenjestin.mue.benchmark.MueJourney.lapTheFoodViews
import fr.kristenjestin.mue.benchmark.MueJourney.lapTheTabs
import org.junit.Rule
import org.junit.Test

/**
 * Records which methods and classes ART should compile ahead of time, by walking the app.
 *
 * A baseline profile is not a guess about hot code: it is a *recording* of the code that ran while
 * this test drove the app, written to `app/src/release/generated/baselineProfiles/` and packaged
 * into the APK. On the owner's phone ART compiles that list at install time instead of
 * interpreting it and JIT-ing it on the way past — which is what a first tab switch after an
 * update is paying for today.
 *
 * ## What is recorded, and why exactly this
 *
 * The measurements this module was written for are unambiguous about where the cost sits: the
 * over-budget frames are **cold-code frames**, they cost 13–29 frames over 64 ms per 24 switches
 * in a debug build against 0–1 in a release build with R8, and the first two release runs after an
 * install were far worse than the third. That last fact is the signature of the ART warm-up curve
 * and of nothing else, and a baseline profile is the one mechanism that flattens it.
 *
 * So the journey is the complaint: the launch, a lap of the five tabs, and the Food module's own
 * three views. It is deliberately *not* the whole app. A profile that lists everything compiles
 * everything, which costs install time and dex layout for code the first minute never reaches;
 * the point of the list is that it is short and true.
 *
 * ## Regenerating it
 *
 * Never through `generateBaselineProfile`, and never through any `connected…AndroidTest` task.
 * Those install on **every attached device**, and the machine this is developed on has the owner's
 * own phone attached over wireless debugging beside the emulator; an unpinned run of that shape is
 * what destroyed his weight history once. Pin a serial and drive the instrumentation directly:
 *
 * ```
 * ./gradlew :app:assembleNonMinifiedRelease :benchmark:assembleNonMinifiedRelease -PmueDebugSigning
 * adb -s <emulator> install -r app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk
 * adb -s <emulator> install -r benchmark/build/outputs/apk/nonMinifiedRelease/benchmark-nonMinifiedRelease.apk
 * adb -s <emulator> shell am instrument -w \
 *   -e class fr.kristenjestin.mue.benchmark.BaselineProfileGenerator \
 *   -e androidx.benchmark.suppressErrors EMULATOR,UNLOCKED,DEBUGGABLE,LOW-BATTERY,NOT-PROFILEABLE \
 *   -e additionalTestOutputDir /sdcard/Android/media/fr.kristenjestin.mue.benchmark/additional_test_output \
 *   fr.kristenjestin.mue.benchmark/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Then copy the two files it names out of that directory into
 * `app/src/release/generated/baselineProfiles/` as `baseline-prof.txt` and `startup-prof.txt`,
 * sorted and de-duplicated so the diff of a regeneration shows what actually moved. The next
 * `assembleRelease` packages them as `assets/dexopt/baseline.prof`, which is worth checking with
 * `unzip -l` rather than assuming.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * The launch alone, recorded as a **startup** profile.
     *
     * A startup profile is not a second baseline profile: it is the list R8 lays the dex file out
     * by, so the classes the first frame needs sit on the same pages instead of being seeked to
     * one at a time. It has to be the launch and nothing else — a startup profile listing a lap of
     * five tabs would ask R8 to pack half the app next to the entry point and defeat itself.
     *
     * Without this test `dexLayoutOptimization` in `:app` would be a switch with nothing behind it.
     */
    @Test
    fun startup() = rule.collect(
        packageName = MUE_PACKAGE,
        includeInStartupProfile = true,
        maxIterations = 3,
        stableIterations = 2,
    ) {
        pressHome()
        startActivityAndWait()
        device.awaitShell()
    }

    @Test
    fun generate() = rule.collect(
        packageName = MUE_PACKAGE,
        /*
         * Two passes over the same path.
         *
         * The first pass runs against a cold process and records the launch; the second runs
         * against classes already loaded and records what a *re-entry* touches, which is not the
         * same set — `AnimatedContent` on a tab that has been visited resolves a saved state
         * holder slot that a first visit creates. Both are journeys the owner makes.
         */
        maxIterations = 3,
        stableIterations = 2,
    ) {
        pressHome()
        startActivityAndWait()
        device.awaitShell()
        device.lapTheTabs()
        device.lapTheFoodViews()
        device.lapTheTabs()
    }
}
