package fr.kristenjestin.mue.ui.activity

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityMetric
import fr.kristenjestin.mue.domain.model.ActivityMetrics
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.MetricSource
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.effortOf
import fr.kristenjestin.mue.domain.model.loadOf
import fr.kristenjestin.mue.domain.model.minutesOf
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.ui.profile.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 24)
private val EDITED = ActivityId("7b6a2f1e-0000-4000-8000-000000000001")

/**
 * PRD FR-ACTIVITY-004 to 011 on the one object that owns the draft.
 *
 * The screen is never involved: everything asserted here is about what the form keeps, what it
 * refuses, and what reaches the repository.
 */
class LogActivityViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    // A dot-decimal locale keeps the expected strings readable; the comma has its own tests.
    @get:Rule
    val locale = LocaleRule(Locale.UK)

    // region FR-ACTIVITY-004 — the preset keeps everything

    @Test
    fun `a new form opens on the preselected preset`() = logTest { model, _ ->
        assertEquals(ActivityPreset.TREADMILL_WALK, model.uiState.value.preset)
        assertFalse(model.uiState.value.isEditing)
        assertEquals(LogActivityMessages.SAVE_ACTIVITY, model.uiState.value.saveLabel)
    }

    @Test
    fun `switching presets keeps the common fields`() = logTest { model, _ ->
        model.onHoursChange("1")
        model.onMinutesChange("5")
        model.onEffortChange(7)
        model.onNotesChange("Felt good")
        model.onStartHoursChange("18")
        model.onStartMinutesChange("30")

        model.onPresetSelected(ActivityPreset.RUN)

        val state = model.uiState.value
        assertEquals("1", state.hours)
        assertEquals("5", state.minutes)
        assertEquals(7, state.perceivedEffort)
        assertEquals("Felt good", state.notes)
        assertEquals("18", state.startHours)
        assertEquals("30", state.startMinutes)
    }

    @Test
    fun `returning to a preset finds the measurements it was left with`() = logTest { model, _ ->
        model.onMetricChange(MetricKind.INCLINE, "2.5")
        model.onMetricChange(MetricKind.DISTANCE, "4.2")

        model.onPresetSelected(ActivityPreset.RUN)
        assertEquals("", model.input(MetricKind.DISTANCE))

        model.onPresetSelected(ActivityPreset.TREADMILL_WALK)
        assertEquals("2.5", model.input(MetricKind.INCLINE))
        assertEquals("4.2", model.input(MetricKind.DISTANCE))
    }

    @Test
    fun `only the active preset's measurements are written`() = logTest { model, repository ->
        model.onMetricChange(MetricKind.INCLINE, "2.5")
        model.onPresetSelected(ActivityPreset.RUN)
        model.onMinutesChange("45")
        model.onMetricChange(MetricKind.DISTANCE, "5")
        model.save()

        val saved = repository.saved.single()
        assertEquals(5_000, saved.metrics.valueOf(MetricKind.DISTANCE))
        assertNull(saved.metrics.valueOf(MetricKind.INCLINE))
        assertEquals(Movement.RUNNING, saved.session.movement)
        assertEquals(ActivityEnvironment.OUTDOOR, saved.session.environment)
    }

    // endregion

    // region FR-ACTIVITY-005 and 006 — what a session is made of

    @Test
    fun `a treadmill walk is written with its movement, place and machine`() =
        logTest { model, repository ->
            model.onMinutesChange("45")
            model.onMetricChange(MetricKind.DISTANCE, "4.2")
            model.onMetricChange(MetricKind.ESTIMATED_ENERGY, "280")
            model.save()

            val saved = repository.saved.single()
            assertEquals(Movement.WALKING, saved.session.movement)
            assertEquals(ActivityEnvironment.INDOOR, saved.session.environment)
            assertEquals(minutesOf(45), saved.session.duration)
            assertEquals(4_200, saved.metrics.valueOf(MetricKind.DISTANCE))
            assertEquals(
                listOf(EquipmentType.TREADMILL),
                saved.equipment.map { it.equipmentType },
            )
        }

    /** PRD 11.3: the machine's calorie readout is not a hand-entered number. */
    @Test
    fun `the treadmill's estimated energy keeps the machine's provenance`() =
        logTest { model, repository ->
            model.onMinutesChange("30")
            model.onMetricChange(MetricKind.ESTIMATED_ENERGY, "280")
            model.save()

            val energy = repository.saved.single().metrics[MetricKind.ESTIMATED_ENERGY]
            assertEquals(MetricSource.EQUIPMENT, assertNotNull(energy).source)
        }

    @Test
    fun `an outdoor walk's energy is hand-entered`() = logTest { model, repository ->
        model.onPresetSelected(ActivityPreset.OUTDOOR_WALK)
        model.onMinutesChange("30")
        model.onMetricChange(MetricKind.ESTIMATED_ENERGY, "180")
        model.save()

        val energy = repository.saved.single().metrics[MetricKind.ESTIMATED_ENERGY]
        assertEquals(MetricSource.MANUAL, assertNotNull(energy).source)
    }

    @Test
    fun `a pace typed as minutes and seconds is stored per kilometre`() =
        logTest { model, repository ->
            model.onPresetSelected(ActivityPreset.RUN)
            model.onMinutesChange("30")
            model.onPaceChange("7", null)
            model.onPaceChange(null, "10")
            model.save()

            assertEquals(430, repository.saved.single().metrics.valueOf(MetricKind.AVERAGE_PACE))
        }

    @Test
    fun `an hour and a start time reach the session`() = logTest { model, repository ->
        model.onHoursChange("1")
        model.onMinutesChange("5")
        model.onStartHoursChange("18")
        model.onStartMinutesChange("30")
        model.save()

        val session = repository.saved.single().session
        assertEquals(ActivityDuration.SECONDS_PER_HOUR + 300, session.duration.seconds)
        assertEquals(LocalTime.of(18, 30), session.startedAtTime)
    }

    // endregion

    // region PRD 12 — validation

    @Test
    fun `a missing duration refuses the save with its message`() = logTest { model, repository ->
        model.save()

        assertEquals(ActivityValidation.DURATION_ERROR, model.uiState.value.durationError)
        assertEquals(ActivityValidation.DURATION_ERROR, model.uiState.value.formError)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `sixty minutes is not a duration`() = logTest { model, repository ->
        model.onMinutesChange("60")
        model.save()

        assertEquals(ActivityValidation.DURATION_ERROR, model.uiState.value.durationError)
        assertTrue(repository.saved.isEmpty())
    }

    /** The ceiling is enforced at the keystroke: two digits a box, so 100 h cannot be typed. */
    @Test
    fun `99 h 59 min is the longest session and nothing longer can be typed`() =
        logTest { model, repository ->
            model.onHoursChange("99")
            model.onMinutesChange("59")
            model.save()
            assertEquals(
                ActivityDuration.SESSION_MAX_SECONDS,
                repository.saved.single().session.duration.seconds,
            )

            model.onHoursChange("100")
            assertEquals("10", model.uiState.value.hours)
        }

    @Test
    fun `a clock box takes digits and nothing else`() = logTest { model, _ ->
        model.onMinutesChange("4a5")
        assertEquals("45", model.uiState.value.minutes)

        model.onMetricChange(MetricKind.DISTANCE, "4.2 km")
        assertEquals("4.2", model.input(MetricKind.DISTANCE))
    }

    @Test
    fun `a session cannot be in the future`() = logTest { model, repository ->
        model.onMinutesChange("30")
        model.onDateSelected(TODAY.plusDays(1))
        model.save()

        assertEquals(ActivityValidation.DATE_ERROR, model.uiState.value.dateError)
        assertEquals(ActivityValidation.DATE_ERROR, model.uiState.value.formError)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `today is allowed`() = logTest { model, repository ->
        model.onMinutesChange("30")
        model.onDateSelected(TODAY)
        model.save()

        assertNull(model.uiState.value.dateError)
        assertEquals(TODAY, repository.saved.single().session.startedOn)
    }

    @Test
    fun `an unreadable measurement refuses the save with its message`() =
        logTest { model, repository ->
            model.onMinutesChange("30")
            model.onMetricChange(MetricKind.DISTANCE, "..")
            model.save()

            assertEquals(
                ActivityValidation.NUMBER_ERROR,
                model.metric(MetricKind.DISTANCE).error,
            )
            assertEquals(ActivityValidation.NUMBER_ERROR, model.uiState.value.formError)
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun `an impossible pace refuses the save with its message`() = logTest { model, repository ->
        model.onPresetSelected(ActivityPreset.RUN)
        model.onMinutesChange("30")
        model.onPaceChange("7", "99")
        model.save()

        assertEquals(
            ActivityValidation.PACE_ERROR,
            model.metric(MetricKind.AVERAGE_PACE).error,
        )
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `an impossible start time refuses the save with its message`() =
        logTest { model, repository ->
            model.onMinutesChange("30")
            model.onStartHoursChange("25")
            model.save()

            assertEquals(
                LogActivityMessages.START_TIME_ERROR,
                model.uiState.value.startTimeError,
            )
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun `the builder needs an activity before anything is written`() =
        logTest { model, repository ->
            model.onPresetSelected(ActivityPreset.OTHER)
            model.onMinutesChange("30")
            model.save()

            assertEquals(LogActivityMessages.MOVEMENT_REQUIRED, model.uiState.value.movementError)
            assertEquals(LogActivityMessages.MOVEMENT_REQUIRED, model.uiState.value.formError)
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun `an over-long custom activity name refuses the save with its message`() =
        logTest { model, repository ->
            model.onPresetSelected(ActivityPreset.OTHER)
            model.onMinutesChange("30")
            model.onOpenMovementPicker()
            model.onPickerQueryChange("x".repeat(ActivitySession.MAX_CUSTOM_MOVEMENT_NAME_LENGTH + 1))
            model.onCreateFromSearch()

            assertEquals(
                ActivityValidation.MOVEMENT_NAME_ERROR,
                assertNotNull(model.uiState.value.picker).notice,
            )
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun `an error goes as soon as the field it belongs to changes`() = logTest { model, _ ->
        model.save()
        assertNotNull(model.uiState.value.durationError)

        model.onMinutesChange("30")

        assertNull(model.uiState.value.durationError)
        assertNull(model.uiState.value.formError)
    }

    /** PRD 12: an empty optional is null, and is never turned into a zero. */
    @Test
    fun `an empty optional field is never stored as zero`() = logTest { model, repository ->
        model.onMinutesChange("30")
        model.save()

        val saved = repository.saved.single()
        assertTrue(saved.metrics.isEmpty)
        assertNull(saved.session.perceivedEffort)
        assertNull(saved.session.notes)
        assertNull(saved.session.startedAtTime)
    }

    @Test
    fun `a blank note is no note at all`() = logTest { model, repository ->
        model.onMinutesChange("30")
        model.onNotesChange("   ")
        model.save()

        assertNull(repository.saved.single().session.notes)
    }

    // endregion

    // region PRD 12 — both separators in, the phone's language out

    @Test
    fun `a comma is read as a decimal separator`() = logTest { model, repository ->
        model.onMinutesChange("30")
        model.onMetricChange(MetricKind.DISTANCE, "4,2")
        model.save()

        assertEquals(4_200, repository.saved.single().metrics.valueOf(MetricKind.DISTANCE))
    }

    @Test
    fun `a stored distance comes back in the phone's language`() {
        assertEquals("4.2", LogActivityFormat.metricInput(MetricKind.DISTANCE, 4_200, Locale.UK))
        assertEquals(
            "4,2",
            LogActivityFormat.metricInput(MetricKind.DISTANCE, 4_200, Locale.FRANCE),
        )
        assertEquals(
            "7:10",
            LogActivityFormat.metricInput(MetricKind.AVERAGE_PACE, 430, Locale.FRANCE),
        )
    }

    // endregion

    // region FR-ACTIVITY-008 — the builder

    @Test
    fun `a catalogue activity is stored by its own movement, never as a free name`() =
        logTest { model, repository ->
            model.onPresetSelected(ActivityPreset.OTHER)
            model.onMinutesChange("30")
            model.onOpenMovementPicker()
            model.onCatalogEntrySelected(Movement.YOGA.id)
            model.save()

            val session = repository.saved.single().session
            assertEquals(Movement.YOGA, session.movement)
            assertNull(session.customMovementName)
            assertNull(model.uiState.value.picker)
        }

    @Test
    fun `creating an activity is the one path to a free name`() = logTest { model, repository ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onMinutesChange("30")
        model.onOpenMovementPicker()
        model.onPickerQueryChange("  Kayaking  ")
        model.onCreateFromSearch()
        model.save()

        val session = repository.saved.single().session
        assertEquals(Movement.OTHER, session.movement)
        assertEquals("Kayaking", session.customMovementName)
    }

    @Test
    fun `the catalogue offers every movement no preset already covers`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenMovementPicker()

        val names = assertNotNull(model.uiState.value.picker).results.map { it.name }
        assertEquals(ActivityPreset.OTHER_CATALOGUE.map { it.displayName }, names)
        assertFalse(names.contains(Movement.WALKING.displayName))
        assertFalse(names.contains(Movement.OTHER.displayName))
    }

    @Test
    fun `the catalogue is searched whatever the case`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenMovementPicker()
        model.onPickerQueryChange("yOg")

        assertEquals(
            listOf(Movement.YOGA.displayName),
            assertNotNull(model.uiState.value.picker).results.map { it.name },
        )
    }

    @Test
    fun `the same equipment is never added twice`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenEquipmentPicker()
        model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)

        model.onOpenEquipmentPicker()
        model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)

        assertEquals(listOf("Yoga mat"), model.uiState.value.equipment.map { it.label })
        assertEquals(
            LogActivityMessages.ALREADY_ADDED,
            assertNotNull(model.uiState.value.picker).notice,
        )
    }

    @Test
    fun `a custom equipment folded the same way is never added twice`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenEquipmentPicker()
        model.onPickerQueryChange("Garden rower")
        model.onCreateFromSearch()

        model.onOpenEquipmentPicker()
        model.onPickerQueryChange("  GARDEN ROWER ")
        model.onCreateFromSearch()

        assertEquals(listOf("Garden rower"), model.uiState.value.equipment.map { it.label })
    }

    @Test
    fun `an over-long custom equipment name is refused with its message`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenEquipmentPicker()
        model.onPickerQueryChange("x".repeat(ActivityValidation.MAX_EQUIPMENT_NAME_LENGTH + 1))
        model.onCreateFromSearch()

        assertEquals(
            ActivityValidation.EQUIPMENT_NAME_ERROR,
            assertNotNull(model.uiState.value.picker).notice,
        )
        assertTrue(model.uiState.value.equipment.isEmpty())
    }

    @Test
    fun `equipment tags are written in order and a removed one is gone`() =
        logTest { model, repository ->
            model.onPresetSelected(ActivityPreset.OTHER)
            model.onMinutesChange("30")
            model.onOpenMovementPicker()
            model.onCatalogEntrySelected(Movement.YOGA.id)
            model.onOpenEquipmentPicker()
            model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)
            model.onOpenEquipmentPicker()
            model.onPickerQueryChange("Garden rower")
            model.onCreateFromSearch()
            model.onEquipmentRemoved(0)
            model.save()

            val equipment = repository.saved.single().equipment
            assertEquals(listOf(EquipmentType.OTHER), equipment.map { it.equipmentType })
            assertEquals("Garden rower", equipment.single().customName)
        }

    @Test
    fun `the builder's place is unknown until it is chosen`() = logTest { model, repository ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onMinutesChange("30")
        model.onOpenMovementPicker()
        model.onCatalogEntrySelected(Movement.YOGA.id)
        assertEquals(ActivityEnvironment.UNKNOWN, model.uiState.value.environment)

        model.onEnvironmentSelected(ActivityEnvironment.INDOOR)
        model.save()

        assertEquals(ActivityEnvironment.INDOOR, repository.saved.single().session.environment)
    }

    // endregion

    // region PRD 9.1 — quick and detailed

    @Test
    fun `a new strength session starts quick and the toggle asks nothing`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.STRENGTH_TRAINING)
        assertFalse(model.uiState.value.detailed)

        model.onDetailedLogSelected()
        assertTrue(model.uiState.value.detailed)

        model.onQuickLogSelected()
        assertFalse(model.uiState.value.detailed)
        assertFalse(model.uiState.value.quickLogConfirmationVisible)
    }

    @Test
    fun `the exercises typed under detailed come back intact after a trip through quick`() =
        logTest(savedState = handleWith(draftWithOneValidSet())) { model, _ ->
            assertEquals(1, model.uiState.value.exerciseCount)

            model.onQuickLogSelected()
            model.onDetailedLogSelected()

            assertEquals(1, model.uiState.value.exerciseCount)
            assertEquals("10", model.draft.value.exercises.single().sets.single().reps)
        }

    @Test
    fun `a quick log writes no exercise however many the draft holds`() =
        logTest(savedState = handleWith(draftWithOneValidSet())) { model, repository ->
            model.onQuickLogSelected()
            model.save()

            assertTrue(repository.saved.single().exercises.isEmpty())
        }

    @Test
    fun `dropping a stored detailed session to quick asks first`() = logTest(
        detail = detailWithExercises(3),
    ) { model, _ ->
        model.start(EDITED)
        assertTrue(model.uiState.value.detailed)
        assertEquals(3, model.uiState.value.storedExerciseCount)

        model.onQuickLogSelected()
        assertTrue(model.uiState.value.quickLogConfirmationVisible)
        assertTrue(model.uiState.value.detailed)

        model.onCancelQuickLog()
        assertFalse(model.uiState.value.quickLogConfirmationVisible)
        assertTrue(model.uiState.value.detailed)

        model.onQuickLogSelected()
        model.onConfirmQuickLog()
        assertFalse(model.uiState.value.detailed)
        assertFalse(model.uiState.value.quickLogConfirmationVisible)
    }

    @Test
    fun `the confirmation names how many exercises are at stake`() {
        assertEquals("Your 3 exercises will be removed.", quickLogConfirmationBody(3))
        assertEquals("Your 1 exercise will be removed.", quickLogConfirmationBody(1))
    }

    @Test
    fun `a detailed session needs one complete set`() =
        logTest(savedState = handleWith(draftWithOneEmptySet())) { model, repository ->
            model.save()

            assertEquals(LogActivityMessages.NO_VALID_SET, model.uiState.value.formError)
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun `a detailed session writes its exercises and drops the empty ones`() =
        logTest(savedState = handleWith(draftWithOneValidSet(plusEmptyExercise = true))) {
            model, repository ->
            model.save()

            val exercises = repository.saved.single().exercises
            assertEquals(1, exercises.size)
            assertEquals("Bench press", exercises.single().definition.name)
            assertEquals(10, exercises.single().sets.single().repetitions)
            assertEquals(loadOf(60.0), exercises.single().sets.single().load)
        }

    // endregion

    // region PRD 7 — editing an existing session

    @Test
    fun `an existing session reopens on the form that fits it`() =
        logTest(detail = treadmillDetail()) { model, _ ->
            model.start(EDITED)

            val state = model.uiState.value
            assertTrue(state.isEditing)
            assertEquals(LogActivityMessages.SAVE_CHANGES, state.saveLabel)
            assertEquals(ActivityPreset.TREADMILL_WALK, state.preset)
            assertEquals("1", state.hours)
            assertEquals("5", state.minutes)
            assertEquals("18", state.startHours)
            assertEquals("30", state.startMinutes)
            assertEquals(7, state.perceivedEffort)
            assertEquals("Felt good", state.notes)
            assertEquals("4.2", model.input(MetricKind.DISTANCE))
            assertEquals("280", model.input(MetricKind.ESTIMATED_ENERGY))
            assertEquals(LocalDate.of(2026, 8, 19), state.date)
        }

    @Test
    fun `saving an edited session keeps its identifier`() =
        logTest(detail = treadmillDetail()) { model, repository ->
            model.start(EDITED)
            model.onMinutesChange("10")
            model.save()

            assertEquals(EDITED, repository.saved.single().session.id)
        }

    @Test
    fun `a custom session reopens on the builder with its free name`() =
        logTest(detail = customDetail()) { model, _ ->
            model.start(EDITED)

            val state = model.uiState.value
            assertEquals(ActivityPreset.OTHER, state.preset)
            assertEquals("Kayaking", state.mainActivityLabel)
            assertEquals(ActivityEnvironment.OUTDOOR, state.environment)
            assertEquals(listOf("Sea kayak"), state.equipment.map { it.label })
        }

    @Test
    fun `reopening the same session twice does not restart the draft`() =
        logTest(detail = treadmillDetail()) { model, _ ->
            model.start(EDITED)
            model.onMinutesChange("42")
            model.start(EDITED)

            assertEquals("42", model.uiState.value.minutes)
        }

    // endregion

    // region FR-ACTIVITY-010 and 011 — saving, failing and deleting

    @Test
    fun `a save is confirmed on the button and the form only resets afterwards`() =
        logTest { model, repository ->
            model.onMinutesChange("30")
            model.save()

            assertTrue(model.uiState.value.justSaved)
            assertEquals(1, repository.saved.size)

            model.onSaveConfirmationFinished()
            assertFalse(model.uiState.value.justSaved)

            model.start(null)
            assertEquals("", model.uiState.value.minutes)
        }

    /** PRD 13.4: nothing is confirmed, nothing is lost, and the action can be tried again. */
    @Test
    fun `a failed write keeps the draft and offers another try`() = logTest { model, repository ->
        repository.failSave = true
        model.onMinutesChange("30")
        model.onMetricChange(MetricKind.DISTANCE, "4.2")
        model.save()

        assertEquals(LogActivityMessages.SAVE_FAILED, model.uiState.value.saveError)
        assertFalse(model.uiState.value.justSaved)
        assertEquals("4.2", model.input(MetricKind.DISTANCE))

        repository.failSave = false
        model.save()

        assertNull(model.uiState.value.saveError)
        assertTrue(model.uiState.value.justSaved)
        assertEquals(4_200, repository.saved.single().metrics.valueOf(MetricKind.DISTANCE))
    }

    @Test
    fun `deleting asks first and only then cascades`() = logTest(detail = treadmillDetail()) {
        model, repository ->
        model.start(EDITED)
        model.onRequestDelete()
        assertTrue(model.uiState.value.deleteConfirmationVisible)

        model.onCancelDelete()
        assertFalse(model.uiState.value.deleteConfirmationVisible)
        assertTrue(repository.deleted.isEmpty())

        model.onRequestDelete()
        model.onConfirmDelete()

        assertEquals(listOf(EDITED), repository.deleted)
        assertTrue(model.uiState.value.justDeleted)
    }

    @Test
    fun `a new session has nothing to delete`() = logTest { model, repository ->
        model.onConfirmDelete()
        assertTrue(repository.deleted.isEmpty())
        assertFalse(model.uiState.value.isEditing)
    }

    // endregion

    // region PRD 16.4 — the draft outlives the process

    @Test
    fun `a half-typed draft comes back after a process death`() = runTest {
        val handle = SavedStateHandle()
        val repository = FakeActivityRepository()
        val first = viewModel(repository, handle)
        collect(first)
        first.start(null)
        first.onMetricChange(MetricKind.DISTANCE, "7,")
        first.onMinutesChange("4")
        first.onNotesChange("Half a thought")
        first.onPresetSelected(ActivityPreset.RUN)
        advanceUntilIdle()

        val restored = viewModel(repository, handle.copy())
        collect(restored)
        restored.start(null)
        advanceUntilIdle()

        val state = restored.uiState.value
        assertEquals(ActivityPreset.RUN, state.preset)
        assertEquals("4", state.minutes)
        assertEquals("Half a thought", state.notes)
        restored.onPresetSelected(ActivityPreset.TREADMILL_WALK)
        assertEquals("7,", restored.input(MetricKind.DISTANCE))
    }

    @Test
    fun `an edited session comes back as an edit after a process death`() = runTest {
        val handle = SavedStateHandle()
        val repository = FakeActivityRepository(detail = treadmillDetail())
        val first = viewModel(repository, handle)
        collect(first)
        first.start(EDITED)
        first.onMinutesChange("42")
        advanceUntilIdle()

        val restored = viewModel(repository, handle.copy())
        collect(restored)
        restored.start(EDITED)
        advanceUntilIdle()

        assertTrue(restored.uiState.value.isEditing)
        assertEquals("42", restored.uiState.value.minutes)
    }

    // endregion

    // region harness

    private fun LogActivityViewModel.metric(kind: MetricKind): MetricFieldState =
        uiState.value.metrics.single { it.kind == kind }

    private fun LogActivityViewModel.input(kind: MetricKind): String = metric(kind).input

    private fun TestScope.collect(model: LogActivityViewModel) {
        backgroundScope.launch { model.uiState.collect {} }
    }

    private fun viewModel(
        repository: FakeActivityRepository,
        savedState: SavedStateHandle,
    ): LogActivityViewModel = LogActivityViewModel(
        activities = repository,
        catalog = FakeExerciseCatalogRepository(),
        preferences = FakeUserPreferencesRepository(),
        savedState = savedState,
        today = { TODAY },
        locale = { Locale.UK },
    )

    private fun logTest(
        detail: ActivitySessionDetail? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(LogActivityViewModel, FakeActivityRepository) -> Unit,
    ) = runTest {
        val repository = FakeActivityRepository(detail = detail)
        val model = viewModel(repository, savedState)
        collect(model)
        advanceUntilIdle()
        body(model, repository)
    }

    // endregion
}

// region fixtures

/** Rebuilds a handle from what the system would have written out and read back. */
private fun SavedStateHandle.copy(): SavedStateHandle =
    SavedStateHandle(keys().associateWith { get<Any?>(it) })

/** A handle already holding a draft, which is how the strength editor's work arrives. */
private fun handleWith(draft: ActivityDraft): SavedStateHandle =
    SavedStateHandle(mapOf("activity.log.draft" to draft.toJson()))

private fun strengthDraft(sets: List<SetDraft>, plusEmptyExercise: Boolean): ActivityDraft =
    ActivityDraft(
        presetId = ActivityPreset.STRENGTH_TRAINING.id,
        minutes = "45",
        detailed = true,
        exercises = buildList {
            add(
                ExerciseDraft(
                    definitionId = "definition-bench",
                    name = "Bench press",
                    trackingModeId = TrackingMode.WEIGHT_AND_REPS.id,
                    sets = sets,
                ),
            )
            if (plusEmptyExercise) {
                add(
                    ExerciseDraft(
                        definitionId = "definition-squat",
                        name = "Squat",
                        trackingModeId = TrackingMode.WEIGHT_AND_REPS.id,
                        sets = listOf(SetDraft()),
                    ),
                )
            }
        },
    )

private fun draftWithOneValidSet(plusEmptyExercise: Boolean = false): ActivityDraft =
    strengthDraft(listOf(SetDraft(reps = "10", loadKg = "60")), plusEmptyExercise)

private fun draftWithOneEmptySet(): ActivityDraft =
    strengthDraft(listOf(SetDraft()), plusEmptyExercise = false)

private fun treadmillDetail(): ActivitySessionDetail = ActivitySessionDetail(
    session = ActivitySession(
        id = EDITED,
        movement = Movement.WALKING,
        startedOn = LocalDate.of(2026, 8, 19),
        duration = minutesOf(65),
        environment = ActivityEnvironment.INDOOR,
        startedAtTime = LocalTime.of(18, 30),
        perceivedEffort = effortOf(7),
        notes = "Felt good",
    ),
    metrics = ActivityMetrics.of(
        ActivityMetric(MetricKind.DISTANCE, 4_200),
        ActivityMetric(MetricKind.ESTIMATED_ENERGY, 280, MetricSource.EQUIPMENT),
    ),
    equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
)

private fun customDetail(): ActivitySessionDetail = ActivitySessionDetail(
    session = ActivitySession(
        id = EDITED,
        movement = Movement.OTHER,
        startedOn = LocalDate.of(2026, 8, 19),
        duration = minutesOf(50),
        customMovementName = "Kayaking",
        environment = ActivityEnvironment.OUTDOOR,
    ),
    equipment = listOf(SessionEquipment(EquipmentType.OTHER, customName = "Sea kayak")),
)

private fun detailWithExercises(count: Int): ActivitySessionDetail = ActivitySessionDetail(
    session = ActivitySession(
        id = EDITED,
        movement = Movement.STRENGTH_TRAINING,
        startedOn = LocalDate.of(2026, 8, 19),
        duration = minutesOf(50),
    ),
    exercises = List(count) { index ->
        StrengthExerciseDetail(
            exercise = StrengthExercise(StrengthExerciseId("exercise-$index"), index),
            definition = ExerciseDefinition(
                id = ExerciseDefinitionId("definition-$index"),
                name = "Exercise $index",
                trackingMode = TrackingMode.WEIGHT_AND_REPS,
            ),
            sets = listOf(
                StrengthSet(
                    id = StrengthSetId("set-$index"),
                    position = 0,
                    repetitions = 10,
                    load = loadOf(60.0),
                ),
            ),
        )
    },
)

// endregion

// region doubles

private class FakeActivityRepository(
    private val detail: ActivitySessionDetail? = null,
) : ActivityRepository {

    val saved = mutableListOf<ActivitySessionDetail>()
    val deleted = mutableListOf<ActivityId>()
    var failSave: Boolean = false

    override fun observeRecentSummaries(limit: Int): Flow<List<ActivitySummary>> =
        flowOf(emptyList())

    override fun observeAllSummaries(): Flow<List<ActivitySummary>> = flowOf(emptyList())

    override fun observeSummariesIn(window: DateWindow): Flow<List<ActivitySummary>> =
        flowOf(emptyList())

    override fun observeSessionCount(): Flow<Int> = flowOf(0)

    override suspend fun findDetail(id: ActivityId): ActivitySessionDetail? =
        detail?.takeIf { it.session.id == id }

    override suspend fun save(detail: ActivitySessionDetail) {
        if (failSave) error("the disk said no")
        saved += detail
    }

    override suspend fun delete(id: ActivityId) {
        deleted += id
    }

    override suspend fun findLastPerformance(
        exercise: ExerciseDefinitionId,
        excludingSession: ActivityId?,
    ): LastPerformance? = null
}

/** Behaves like the Room catalogue: a known id resolves, a name folds into one definition. */
private class FakeExerciseCatalogRepository : ExerciseCatalogRepository {

    private val definitions = mutableMapOf(
        "definition-bench" to ExerciseDefinition(
            id = ExerciseDefinitionId("definition-bench"),
            name = "Bench press",
            trackingMode = TrackingMode.WEIGHT_AND_REPS,
        ),
        "definition-squat" to ExerciseDefinition(
            id = ExerciseDefinitionId("definition-squat"),
            name = "Squat",
            trackingMode = TrackingMode.WEIGHT_AND_REPS,
        ),
    )

    override fun observeCatalogue(): Flow<List<ExerciseDefinition>> =
        flowOf(definitions.values.toList())

    override suspend fun findById(id: ExerciseDefinitionId): ExerciseDefinition? =
        definitions[id.value]

    override suspend fun findByName(name: String): ExerciseDefinition? =
        definitions.values.firstOrNull { it.nameFolded == ExerciseDefinition.fold(name) }

    override suspend fun findOrCreate(
        name: String,
        trackingMode: TrackingMode,
        equipment: EquipmentType?,
    ): ExerciseDefinition = findByName(name) ?: ExerciseDefinition(
        id = ExerciseDefinitionId("definition-${ExerciseDefinition.fold(name)}"),
        name = name.trim(),
        trackingMode = trackingMode,
        equipment = equipment,
        isCustom = true,
    ).also { definitions[it.id.value] = it }
}

private class FakeUserPreferencesRepository : UserPreferencesRepository {

    override val preferences: Flow<UserPreferences> = flowOf(UserPreferences.DEFAULT)

    override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
}

// endregion
