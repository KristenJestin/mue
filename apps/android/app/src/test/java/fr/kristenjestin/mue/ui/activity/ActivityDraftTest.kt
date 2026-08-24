package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDraftTest {

    @Test
    fun `a new draft opens on the preset PRD FR-ACTIVITY-004 preselects`() {
        assertEquals(ActivityPreset.TREADMILL_WALK, ActivityDraft().preset)
        assertNull(ActivityDraft().editingSessionId)
    }

    @Test
    fun `returning to a preset restores the fields it was left with`() {
        val draft = ActivityDraft()
            .withPresetDraft { it.withMetric(MetricKind.INCLINE, "2.5") }
            .copy(presetId = ActivityPreset.RUN.id)
            .withPresetDraft { it.withMetric(MetricKind.AVERAGE_PACE, "5:30") }

        assertEquals("5:30", draft.presetDraft().metricInput(MetricKind.AVERAGE_PACE))
        assertEquals(
            "2.5",
            draft.presetDraft(ActivityPreset.TREADMILL_WALK).metricInput(MetricKind.INCLINE),
        )
    }

    @Test
    fun `only what the active preset shows is read back on save`() {
        val draft = ActivityDraft()
            .withPresetDraft { it.withMetric(MetricKind.INCLINE, "2.5") }
            .copy(presetId = ActivityPreset.RUN.id)

        assertEquals(ActivityPreset.RUN.metrics.toSet(), draft.activeMetricInputs().keys)
        assertTrue(MetricKind.INCLINE !in draft.activeMetricInputs())
    }

    @Test
    fun `a whole draft survives the trip through its saved string`() {
        val draft = ActivityDraft(
            editingSessionId = "session-1",
            presetId = ActivityPreset.STRENGTH_TRAINING.id,
            startedOn = "2026-08-24",
            startedAtTime = "18:45",
            hours = "1",
            minutes = "05",
            perceivedEffort = 7,
            notes = "Felt good",
            detailed = true,
            byPreset = mapOf(
                ActivityPreset.STRENGTH_TRAINING.id to PresetDraft(
                    metrics = mapOf(MetricKind.ESTIMATED_ENERGY.id to "320"),
                    equipment = listOf(EquipmentDraft("other", "Garden rower")),
                ),
            ),
            exercises = listOf(
                ExerciseDraft(
                    definitionId = "definition-1",
                    name = "Barbell squat",
                    trackingModeId = "weight_and_reps",
                    sets = listOf(SetDraft(reps = "8", loadKg = "60")),
                ),
            ),
        )
        assertEquals(draft, ActivityDraft.fromJson(draft.toJson()))
    }

    @Test
    fun `a half-typed number comes back exactly as it was left`() {
        val draft = ActivityDraft().withPresetDraft { it.withMetric(MetricKind.DISTANCE, "7,") }
        assertEquals("7,", ActivityDraft.fromJson(draft.toJson()).presetDraft().metricInput(MetricKind.DISTANCE))
    }

    @Test
    fun `an unreadable saved string is a draft that was never there`() {
        assertEquals(ActivityDraft(), ActivityDraft.fromJson(null))
        assertEquals(ActivityDraft(), ActivityDraft.fromJson(""))
        assertEquals(ActivityDraft(), ActivityDraft.fromJson("{"))
        assertEquals(ActivityDraft(), ActivityDraft.fromJson("not json at all"))
    }

    @Test
    fun `a draft written by a newer build is read for the part this one understands`() {
        val restored = ActivityDraft.fromJson("""{"presetId":"run","notes":"Hi","future":42}""")
        assertEquals(ActivityPreset.RUN, restored.preset)
        assertEquals("Hi", restored.notes)
    }

    // region PRD_ACTIVITY_TIMER 8.2 — the review form's serialised column

    @Test
    fun `a hand-typed draft is not a review and shows no seconds`() {
        assertFalse(ActivityDraft().isTimedReview)
        assertEquals("", ActivityDraft().seconds)
    }

    @Test
    fun `a review draft survives the trip through its stored blob`() {
        val draft = ActivityDraft(
            timedDraftId = "9c4d3e2a-0000-4000-8000-000000000002",
            presetId = ActivityPreset.TREADMILL_WALK.id,
            startedOn = "2026-08-24",
            startedAtTime = "18:32",
            hours = "0",
            minutes = "42",
            seconds = "18",
            notes = "Legs heavy",
        )
        val restored = ActivityDraft.fromJson(draft.toJson())

        assertEquals(draft, restored)
        assertTrue(restored.isTimedReview)
        assertEquals("18", restored.seconds)
    }

    /**
     * The rule that lets [ActivityDraft.SCHEMA_VERSION] stay where it is: a field merely added
     * still decodes under the version that predates it, so nothing is bumped for an addition.
     */
    @Test
    fun `a blob written before the timed fields existed still decodes`() {
        val restored = ActivityDraft.fromJson(
            """{"presetId":"run","minutes":"30","notes":"Before the timer"}""",
        )

        assertEquals(ActivityPreset.RUN, restored.preset)
        assertEquals("30", restored.minutes)
        assertEquals("", restored.seconds)
        assertNull(restored.timedDraftId)
    }

    /**
     * PRD 8.2 tells an unreadable blob from an empty draft, which a blank one cannot: rebuilding
     * the form from the typed columns and opening it blank are opposite outcomes.
     */
    @Test
    fun `an unreadable blob answers null rather than an empty draft`() {
        assertNull(ActivityDraft.fromJsonOrNull(null))
        assertNull(ActivityDraft.fromJsonOrNull(""))
        assertNull(ActivityDraft.fromJsonOrNull("{"))
        assertNull(ActivityDraft.fromJsonOrNull("not json at all"))
        assertEquals(ActivityDraft(), ActivityDraft.fromJsonOrNull(ActivityDraft().toJson()))
    }

    // endregion

    @Test
    fun `a new set is seeded empty, never with a plausible-looking default`() {
        val set = SetDraft()
        assertEquals("", set.reps)
        assertEquals("", set.loadKg)
        assertEquals("", set.durationSeconds)
        assertNull(set.perceivedEffort)
    }
}
