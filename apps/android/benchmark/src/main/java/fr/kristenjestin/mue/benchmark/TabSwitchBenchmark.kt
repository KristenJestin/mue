package fr.kristenjestin.mue.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import fr.kristenjestin.mue.benchmark.MueJourney.awaitShell
import fr.kristenjestin.mue.benchmark.MueJourney.lapTheFoodViews
import fr.kristenjestin.mue.benchmark.MueJourney.lapTheTabs
import org.junit.Rule
import org.junit.Test

/**
 * What a tab switch costs, in frames the phone actually drew.
 *
 * Three tests, one journey, one APK. The only thing that differs between them is how much of the
 * app ART has compiled when the journey starts, which is the single variable the baseline profile
 * moves — so the difference between the numbers is the profile's worth and nothing else's.
 *
 * - [tabSwitchWithNoCompilation] is a fresh install that ships no profile: everything is
 *   interpreted until the JIT catches up. This is the *before*.
 * - [tabSwitchWithBaselineProfile] is a fresh install that ships one. `Require` fails the run
 *   outright if the APK has no profile in it, so this test is also the assertion that the profile
 *   was packaged rather than merely generated. This is the *after*.
 * - [tabSwitchWithFullCompilation] compiles the entire app ahead of time. Nobody ships this — it
 *   is the ceiling, and it is here so the report can say what *fraction* of the achievable win the
 *   profile actually took rather than only that it took some.
 *
 * ## Reading the output
 *
 * `frameDurationCpuMs` is the work the app did on the UI and RenderThread; `frameOverrunMs` is how
 * late the frame was against its deadline, and a positive P99 there is a dropped frame the owner
 * saw. The P99 is the one that matters here: his complaint is not that every frame is slow, it is
 * that *one* frame at the instant of the switch is enormous, which is exactly what a high P99 over
 * a low median describes.
 */
class TabSwitchBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun tabSwitchWithNoCompilation() = measureTabSwitching(CompilationMode.None())

    @Test
    fun tabSwitchWithBaselineProfile() = measureTabSwitching(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun tabSwitchWithFullCompilation() = measureTabSwitching(CompilationMode.Full())

    /**
     * The launch is measured too, and for the same reason.
     *
     * A profile earns its place at the first frame as much as at the tenth, and separating the two
     * would let a win on one hide a loss on the other. [StartupTimingMetric] costs nothing extra
     * here: the journey has to start the activity regardless.
     */
    private fun measureTabSwitching(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = MUE_PACKAGE,
        metrics = listOf(FrameTimingMetric(), StartupTimingMetric()),
        compilationMode = compilationMode,
        // The switch has to be paid for cold every iteration, which is the frame he is describing.
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.awaitShell()
        device.lapTheTabs()
        device.lapTheFoodViews()
    }

    private companion object {
        /**
         * Enough for a stable P99 on a machine that also has an emulator on it, and few enough
         * that all three modes finish inside one sitting. Macrobenchmark reports the median of the
         * iterations, so this is a sample size and not a warm-up count.
         */
        const val ITERATIONS = 10
    }
}
