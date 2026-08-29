package fr.kristenjestin.mue.benchmark

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The package the profile is recorded for and the frames are counted in.
 *
 * `.debug`, and not `fr.kristenjestin.mue`, because that is what the APK this module drives is
 * called. `:app` gives every `nonMinified…` and `benchmark…` build type the same
 * `applicationIdSuffix` the `debug` build type carries — the block in `app/build.gradle.kts`
 * argues it — so a macrobenchmark run can no longer install over the application the owner
 * carries, whichever serial the command happened to reach.
 *
 * It does not change what is recorded. A baseline profile lists class and method descriptors, and
 * those are built from `namespace`, which is still `fr.kristenjestin.mue`; the file this produces
 * is byte-identical to the one produced before the suffix existed and is still the file
 * `assembleRelease` packages.
 */
const val MUE_PACKAGE: String = "fr.kristenjestin.mue.debug"

/** Long enough for a cold, uncompiled first frame on a loaded emulator; not a pacing device. */
private const val AppearTimeoutMs: Long = 15_000

/**
 * One `MueMotion.TabChangeMillis` (220 ms) and a margin, which is how long a switch takes to stop
 * moving. Written here rather than read from the app because this module does not depend on it.
 */
private const val SettleMs: Long = 400

/** A handle going stale is the screen having moved on, so looking again is the whole remedy. */
private const val TapAttempts: Int = 5

/**
 * The one journey, written once and used twice.
 *
 * A baseline profile is only worth its module if it covers the code the owner's complaint is
 * about, and the only way to be sure of that is for the generator and the benchmark to walk the
 * *same* path. So this file is the path, and neither of its two callers gets to drift from it:
 * [BaselineProfileGenerator] records it, [TabSwitchBenchmark] times it.
 *
 * What it walks is what he described — "ça lag de malade sur les transitions de tab" — plus the
 * one sub-navigation inside a tab that has the same shape, the Food module's view switcher.
 *
 * ## Why the labels and not test tags
 *
 * UiAutomator sees Compose through the semantics tree, and this app publishes no
 * `testTagsAsResourceId`, so a tag is invisible from out here. Turning that on would be an
 * app-code change made for the benefit of the measuring instrument, which is the wrong way round.
 * Every control this journey touches already carries the text it is found by, in the tab bar and
 * in the switcher alike.
 */
object MueJourney {

    /** The five tabs of `MueDestination`, in bar order — the order a lap must follow. */
    val TABS: List<String> = listOf("Entry", "Progress", "Activity", "Food", "Profile")

    /** The three views `FoodRoute.SWITCHABLE` publishes, in switcher order. */
    val FOOD_VIEWS: List<String> = listOf("Day", "Recipes", "Foods")

    /** The app is up and the shell has drawn its bar. */
    fun UiDevice.awaitShell() {
        check(wait(Until.hasObject(By.text("Entry")), AppearTimeoutMs)) {
            "the tab bar never appeared — the app did not reach its first frame"
        }
    }

    /**
     * One lap of the five tabs, ending back where it started.
     *
     * Six switches and not five: returning to `Entry` exercises the backward direction of
     * `MueMotion.tabTransition`, which is a different `ContentTransform` and therefore different
     * code, and it leaves the app on the tab it started on so iterations are comparable.
     */
    fun UiDevice.lapTheTabs() {
        (TABS.drop(1) + TABS.first()).forEach { tab ->
            selectTab(tab)
        }
    }

    /** The Food module's own switcher, which is a tab change by another name. */
    fun UiDevice.lapTheFoodViews() {
        selectTab("Food")
        (FOOD_VIEWS.drop(1) + FOOD_VIEWS.first()).forEach { view ->
            selectFoodView(view)
        }
    }

    /**
     * A tab in the bar, which is the **lowest** node carrying that word.
     *
     * `Food` is a tab and also a word that a catalogue row may well contain; `Day` is a switcher
     * segment and also the day journal's own heading. Picking by position rather than by
     * uniqueness is what keeps this journey walking the chassis instead of whatever the screen
     * happens to be showing, and the bar is always the bottom-most of the candidates.
     */
    private fun UiDevice.selectTab(label: String) = tap(label) { it.maxByOrNull(::centreY) }

    /** A switcher segment, which is the **highest** node carrying that word, for the same reason. */
    private fun UiDevice.selectFoodView(label: String) = tap(label) { it.minByOrNull(::centreY) }

    /**
     * Find the node, tap it, and let the transition finish before anything else is asked of the
     * screen.
     *
     * Two hazards, both met on the first run of this journey and both handled here rather than
     * papered over with a longer timeout.
     *
     * **Staleness.** A `UiObject2` is a handle onto an accessibility node, and a node that Compose
     * has recomposed underneath it throws `StaleObjectException` on the next call — including on
     * `getVisibleBounds`, which is what this has to read to tell the bar from the switcher. Since
     * the whole point of the journey is to click *while things are moving*, the find, the measure
     * and the click all sit inside one retry: a stale handle means the screen moved on, and the
     * answer is to look again, not to fail.
     *
     * **Settling.** `waitForIdle` waits for the accessibility event stream to go quiet, which a
     * Compose animation does not reliably disturb — a slide that only moves a layer can finish
     * without a single event. So each tap is followed by a wait one transition long plus a margin.
     * That is idle time, and idle time draws no frames, so it costs the frame metric nothing while
     * guaranteeing that the frames of one switch are never attributed to the next.
     */
    private fun UiDevice.tap(label: String, pick: (List<UiObject2>) -> UiObject2?) {
        var failure: Throwable? = null
        repeat(TapAttempts) {
            try {
                wait(Until.hasObject(By.text(label)), AppearTimeoutMs)
                val target = pick(findObjects(By.text(label)))
                    ?: error("no node reading `$label` is on screen")
                target.click()
                waitForIdle()
                SystemClock.sleep(SettleMs)
                return
            } catch (stale: StaleObjectException) {
                failure = stale
                waitForIdle()
                SystemClock.sleep(SettleMs)
            }
        }
        throw IllegalStateException("`$label` would not stay still long enough to tap", failure)
    }

    private fun centreY(node: UiObject2): Int = node.visibleBounds.centerY()
}
