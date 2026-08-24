package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.testing.LocaleRule
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The five renderings PRD 11.4 tabulates, and the two things around them: nothing at all for an
 * exercise never practised, and numbers that follow the phone's language while the words do not.
 */
class LastPerformanceFormatTest {

    /** English is not the default everywhere; pinning it proves the words never move. */
    @get:Rule
    val locale = LocaleRule(Locale.FRANCE)

    private fun set(
        repetitions: Int? = null,
        loadKilograms: Double? = null,
        seconds: Int? = null,
    ) = StrengthSet(
        id = StrengthSetId("set"),
        position = 0,
        repetitions = repetitions,
        load = loadKilograms?.let { Load.ofKilogramsOrNull(it) },
        duration = seconds?.let { ActivityDuration.ofSecondsOrNull(it) },
    )

    private fun summary(mode: TrackingMode, set: StrengthSet, locale: Locale = Locale.ENGLISH) =
        LastPerformanceFormat.summary(mode, set, locale)

    // region The table of PRD 11.4

    @Test
    fun `weight and reps with a load reads as a load times a count`() {
        assertEquals(
            "60 kg × 8",
            summary(TrackingMode.WEIGHT_AND_REPS, set(repetitions = 8, loadKilograms = 60.0)),
        )
    }

    @Test
    fun `weight and reps without a load falls back on the repetitions alone`() {
        assertEquals(
            "8 reps",
            summary(TrackingMode.WEIGHT_AND_REPS, set(repetitions = 8)),
        )
    }

    @Test
    fun `reps only reads as a count of repetitions`() {
        assertEquals("12 reps", summary(TrackingMode.REPS_ONLY, set(repetitions = 12)))
    }

    @Test
    fun `duration reads as minutes and seconds`() {
        assertEquals("1:30", summary(TrackingMode.DURATION, set(seconds = 90)))
    }

    @Test
    fun `weight and duration reads as a load beside a hold`() {
        assertEquals(
            "20 kg · 1:30",
            summary(
                TrackingMode.WEIGHT_AND_DURATION,
                set(seconds = 90, loadKilograms = 20.0),
            ),
        )
    }

    @Test
    fun `weight and duration without a load keeps the hold alone`() {
        assertEquals("1:30", summary(TrackingMode.WEIGHT_AND_DURATION, set(seconds = 90)))
    }

    // endregion

    // region Loads and durations

    @Test
    fun `a round load shows no decimal and a half plate shows one`() {
        assertEquals("60 kg", LastPerformanceFormat.kilograms(load(60.0), Locale.ENGLISH))
        assertEquals("62.5 kg", LastPerformanceFormat.kilograms(load(62.5), Locale.ENGLISH))
        assertEquals("1.25 kg", LastPerformanceFormat.kilograms(load(1.25), Locale.ENGLISH))
    }

    /** PRD 12: the words stay English, the number follows the phone. */
    @Test
    fun `a load reads with the separator of the phone's language`() {
        assertEquals("62,5 kg", LastPerformanceFormat.kilograms(load(62.5), Locale.FRANCE))
        assertEquals(
            "Last time · 62,5 kg × 8",
            LastPerformanceFormat.format(
                LastPerformance(
                    performedOn = LocalDate.of(2026, 8, 20),
                    trackingMode = TrackingMode.WEIGHT_AND_REPS,
                    set = set(repetitions = 8, loadKilograms = 62.5),
                ),
                Locale.FRANCE,
            ),
        )
    }

    @Test
    fun `a hold under a minute reads in seconds alone`() {
        assertEquals("45s", LastPerformanceFormat.clock(duration(45), Locale.ENGLISH))
        assertEquals("1:00", LastPerformanceFormat.clock(duration(60), Locale.ENGLISH))
        assertEquals("2:05", LastPerformanceFormat.clock(duration(125), Locale.ENGLISH))
    }

    @Test
    fun `a single repetition is not called reps`() {
        assertEquals("1 rep", summary(TrackingMode.REPS_ONLY, set(repetitions = 1)))
    }

    // endregion

    // region Absences

    @Test
    fun `an exercise never practised shows nothing`() {
        assertNull(LastPerformanceFormat.format(null, Locale.ENGLISH))
    }

    /** The rule is `StrengthRules.isValid`, restated nowhere: a set without its measure says nothing. */
    @Test
    fun `a set that does not carry its primary measure shows nothing`() {
        assertNull(summary(TrackingMode.WEIGHT_AND_REPS, set(loadKilograms = 60.0)))
        assertNull(summary(TrackingMode.DURATION, set(repetitions = 8)))
    }

    @Test
    fun `the whole line carries the prefix PRD 11 4 gives it`() {
        assertEquals(
            "Last time · 60 kg × 8",
            LastPerformanceFormat.format(
                LastPerformance(
                    performedOn = LocalDate.of(2026, 8, 20),
                    trackingMode = TrackingMode.WEIGHT_AND_REPS,
                    set = set(repetitions = 8, loadKilograms = 60.0),
                ),
                Locale.ENGLISH,
            ),
        )
    }

    // endregion

    private fun load(kilograms: Double): Load = requireNotNull(Load.ofKilogramsOrNull(kilograms))

    private fun duration(seconds: Int): ActivityDuration =
        requireNotNull(ActivityDuration.ofSecondsOrNull(seconds))
}
