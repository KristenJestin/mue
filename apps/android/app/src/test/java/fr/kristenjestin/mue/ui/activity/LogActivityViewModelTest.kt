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
import fr.kristenjestin.mue.domain.model.ActivitySource
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
import fr.kristenjestin.mue.domain.model.StartTimerOutcome
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.effortOf
import fr.kristenjestin.mue.domain.model.loadOf
import fr.kristenjestin.mue.domain.model.minutesOf
import fr.kristenjestin.mue.domain.model.secondsOf
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
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
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 24)
private val EDITED = ActivityId("7b6a2f1e-0000-4000-8000-000000000001")
private val TIMED = TimedDraftId("9c4d3e2a-0000-4000-8000-000000000002")

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
        model.onStartTimeSelected(LocalTime.of(18, 30))

        model.onPresetSelected(ActivityPreset.RUN)

        val state = model.uiState.value
        assertEquals("1", state.hours)
        assertEquals("5", state.minutes)
        assertEquals(7, state.perceivedEffort)
        assertEquals("Felt good", state.notes)
        assertEquals(LocalTime.of(18, 30), state.startTime)
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
        model.onStartTimeSelected(LocalTime.of(18, 30))
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

    /**
     * The picker cannot produce an impossible time, but the draft crosses `SavedStateHandle` as
     * text: a string this app did not write is refused rather than silently dropped, and the
     * field says so instead of standing there empty and saving something else.
     */
    @Test
    fun `a start time the app did not write refuses the save with its message`() =
        logTest(savedState = handleWith(ActivityDraft(minutes = "30", startedAtTime = "25:61"))) {
            model, repository ->
            assertNull(model.uiState.value.startTime)

            model.save()

            assertEquals(
                LogActivityMessages.START_TIME_ERROR,
                model.uiState.value.startTimeError,
            )
            assertTrue(repository.saved.isEmpty())
        }

    /** PRD 8.2: `Clear` takes the time back off, and leaves no midnight behind. */
    @Test
    fun `clearing the start time is not the same as picking midnight`() =
        logTest { model, repository ->
            model.onMinutesChange("30")
            model.onStartTimeSelected(LocalTime.MIDNIGHT)
            model.save()
            assertEquals(LocalTime.MIDNIGHT, repository.saved.single().session.startedAtTime)

            model.onStartTimeSelected(null)
            assertNull(model.uiState.value.startTime)
            model.save()

            assertNull(repository.saved.last().session.startedAtTime)
        }

    @Test
    fun `the start time panel opens and closes on its own`() = logTest { model, _ ->
        assertFalse(model.uiState.value.timePickerVisible)

        model.onOpenTimePicker()
        assertTrue(model.uiState.value.timePickerVisible)

        model.onDismissTimePicker()
        assertFalse(model.uiState.value.timePickerVisible)

        model.onOpenTimePicker()
        model.onStartTimeSelected(LocalTime.of(7, 5))
        assertFalse(model.uiState.value.timePickerVisible)
        assertEquals(LocalTime.of(7, 5), model.uiState.value.startTime)
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

    /**
     * PRD FR-ACTIVITY-008 asks for zero, one or several: the sheet says `Select one or more`,
     * so a row is a switch and the panel stays open around it.
     */
    @Test
    fun `an equipment row adds on the first tap and takes it back off on the second`() =
        logTest { model, _ ->
            model.onPresetSelected(ActivityPreset.OTHER)
            model.onOpenEquipmentPicker()

            model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)
            assertEquals(listOf("Yoga mat"), model.uiState.value.equipment.map { it.label })
            assertTrue(model.pickerRow(EquipmentType.YOGA_MAT).selected)

            model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)
            assertTrue(model.uiState.value.equipment.isEmpty())
            assertFalse(model.pickerRow(EquipmentType.YOGA_MAT).selected)
        }

    @Test
    fun `the equipment sheet stays open while several items are picked`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenEquipmentPicker()
        model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)
        model.onCatalogEntrySelected(EquipmentType.KETTLEBELL.id)
        model.onCatalogEntrySelected(EquipmentType.RESISTANCE_BANDS.id)

        assertNotNull(model.uiState.value.picker)
        assertEquals(
            listOf("Yoga mat", "Kettlebell", "Resistance bands"),
            model.uiState.value.equipment.map { it.label },
        )

        model.onCatalogEntrySelected(EquipmentType.KETTLEBELL.id)

        assertNotNull(model.uiState.value.picker)
        assertEquals(
            listOf("Yoga mat", "Resistance bands"),
            model.uiState.value.equipment.map { it.label },
        )
    }

    /** The movement is a single choice, so its own sheet still leaves on the pick. */
    @Test
    fun `the movement sheet still closes on the row that answers it`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenMovementPicker()
        model.onCatalogEntrySelected(Movement.YOGA.id)

        assertNull(model.uiState.value.picker)
        assertEquals(Movement.YOGA, model.uiState.value.movement)
    }

    @Test
    fun `only a dismissal closes the equipment sheet`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenEquipmentPicker()
        model.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)
        assertNotNull(model.uiState.value.picker)

        model.onDismissPicker()

        assertNull(model.uiState.value.picker)
        assertEquals(listOf("Yoga mat"), model.uiState.value.equipment.map { it.label })
    }

    /**
     * `Create` is not a row and does not toggle: asking to create a name that is already on the
     * session is a mistake to point out, never an instruction to take the item back off.
     */
    @Test
    fun `creating a name the session already carries is refused rather than undone`() =
        logTest { model, _ ->
            model.onPresetSelected(ActivityPreset.OTHER)
            model.onOpenEquipmentPicker()
            model.onPickerQueryChange("Garden rower")
            model.onCreateFromSearch()

            model.onPickerQueryChange("garden rower")
            model.onCreateFromSearch()

            assertEquals(listOf("Garden rower"), model.uiState.value.equipment.map { it.label })
            assertEquals(
                LogActivityMessages.ALREADY_ADDED,
                assertNotNull(model.uiState.value.picker).notice,
            )
        }

    /** A created item clears the search rather than the panel: the next pick starts fresh. */
    @Test
    fun `creating an equipment leaves the sheet open on an empty search`() = logTest { model, _ ->
        model.onPresetSelected(ActivityPreset.OTHER)
        model.onOpenEquipmentPicker()
        model.onPickerQueryChange("Garden rower")
        model.onCreateFromSearch()

        val picker = assertNotNull(model.uiState.value.picker)
        assertEquals("", picker.query)
        assertEquals(listOf("Garden rower"), model.uiState.value.equipment.map { it.label })
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

    /** Contract section 5: the editor speaks in values and this is the only thing holding state. */
    @Test
    fun `an edit from the strength editor lands on the very draft the form shows`() =
        logTest { model, _ ->
            model.onPresetSelected(ActivityPreset.STRENGTH_TRAINING)
            model.onDetailedLogSelected()
            model.onStrengthEdit(StrengthEdit.AddExercise(benchPress()))
            model.onStrengthEdit(StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"))

            assertEquals(1, model.uiState.value.exerciseCount)
            assertEquals("8", model.draft.value.exercises.single().sets.single().reps)
        }

    /**
     * The session trio of the strength screen writes the fields the log form's own inputs write,
     * so the two views cannot drift apart.
     */
    @Test
    fun `the session duration, effort and energy are one value on both screens`() =
        logTest { model, _ ->
            model.onPresetSelected(ActivityPreset.STRENGTH_TRAINING)
            model.onStrengthEdit(StrengthEdit.SetDurationMinutes("45"))
            model.onStrengthEdit(StrengthEdit.SetSessionEffort(7))
            model.onStrengthEdit(StrengthEdit.SetEstimatedEnergy("320"))

            val state = model.uiState.value
            assertEquals("45", state.minutes)
            assertEquals(7, state.perceivedEffort)
            assertEquals("320", model.input(MetricKind.ESTIMATED_ENERGY))
        }

    /**
     * PRD 9.2: the picker mints a definition for a name it does not know, and nothing writes it.
     * The save path resolves it against the catalogue, or the foreign key would have nothing to
     * point at.
     */
    @Test
    fun `an exercise the catalogue has never seen is created before the session references it`() =
        logTest(savedState = handleWith(draftWithACustomExercise())) { model, repository ->
            model.save()

            val definition = repository.saved.single().exercises.single().definition
            assertEquals("Zercher squat", definition.name)
            assertEquals("definition-zercher squat", definition.id.value)
        }

    /** PRD 11.4: a re-opened session must not quote itself back as its own last performance. */
    @Test
    fun `the last performance of a drafted exercise leaves the edited session out`() =
        logTest(detail = detailWithExercises(1)) { model, repository ->
            model.start(EDITED)

            assertEquals(EDITED, repository.performanceExclusions.single())
        }

    /** What `Add exercise` lists comes from the catalogue, not from the draft (PRD 9.2). */
    @Test
    fun `the exercise catalogue reaches the strength editor`() = logTest { model, _ ->
        assertEquals(
            listOf("Bench press", "Squat"),
            model.catalogue.value.map { it.name },
        )
    }

    // endregion

    // region PRD 9.1 — the quick log's equipment

    @Test
    fun `the quick strength log collects equipment too`() = logTest { model, repository ->
        model.onPresetSelected(ActivityPreset.STRENGTH_TRAINING)
        assertTrue(model.uiState.value.showsEquipment)

        model.onOpenEquipmentPicker()
        model.onCatalogEntrySelected(EquipmentType.BARBELL.id)
        model.onMinutesChange("45")
        model.save()

        assertEquals(
            listOf(EquipmentType.BARBELL),
            repository.saved.single().equipment.map { it.equipmentType },
        )
    }

    @Test
    fun `a preset with a machine of its own asks for no equipment`() = logTest { model, _ ->
        assertFalse(model.uiState.value.showsEquipment)
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
            assertEquals(LocalTime.of(18, 30), state.startTime)
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

    /**
     * The screen calls `start` on every entry, including the one a rotation causes.
     *
     * The save marker is dropped on the write rather than on the discharge that follows it, so
     * without a guard a rotation inside that second reads as a new visit: the form empties, the
     * confirmation is lost and the return to the dashboard never happens.
     */
    @Test
    fun `rotating while the save confirms neither empties the form nor loses the return`() =
        logTest { model, repository ->
            model.onMinutesChange("30")
            model.save()

            model.start(null)

            assertTrue(model.uiState.value.justSaved)
            assertEquals("30", model.uiState.value.minutes)
            assertEquals(1, repository.saved.size)
        }

    /** The same for a delete, whose beat is read on the screen rather than on the button. */
    @Test
    fun `rotating while the delete confirms keeps the acknowledgement on screen`() =
        logTest(detail = treadmillDetail()) { model, _ ->
            model.start(EDITED)
            model.onRequestDelete()
            model.onConfirmDelete()

            model.start(EDITED)

            assertTrue(model.uiState.value.justDeleted)
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
        val repository = FakeLogActivityRepository()
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
        val repository = FakeLogActivityRepository(detail = treadmillDetail())
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

    // region FR-TIMER-005 — the prefilled review form

    @Test
    fun `finishing a timer opens the form on what was measured`() = reviewTest { model, _ ->
        val state = model.uiState.value
        assertTrue(state.isTimedSession)
        assertFalse(state.isEditing)
        assertEquals(ActivityPreset.TREADMILL_WALK, state.preset)
        assertEquals(Movement.WALKING, state.movement)
        assertEquals(ActivityEnvironment.INDOOR, state.environment)
        assertEquals(LocalDate.of(2026, 8, 24), state.date)
        assertEquals(LogActivityMessages.SAVE_ACTIVITY, state.saveLabel)
    }

    /** FR-TIMER-006: the seconds are the whole point, and they arrive with the form. */
    @Test
    fun `the prefilled duration keeps its seconds`() = reviewTest { model, _ ->
        val state = model.uiState.value
        assertEquals("0", state.hours)
        assertEquals("42", state.minutes)
        assertEquals("18", state.seconds)
    }

    /** FR-TIMER-005: `18:32:47` is stored as `18:32`, and never rounded up to `18:33`. */
    @Test
    fun `the prefilled start time is truncated to the minute`() = reviewTest { model, drafts ->
        assertEquals(LocalTime.of(18, 32), model.uiState.value.startTime)

        model.save()
        val saved = drafts.committed.single().second.session
        assertEquals(LocalTime.of(18, 32), saved.startedAtTime)
        assertEquals(secondsOf(42 * 60 + 18), saved.duration)
    }

    /** FR-TIMER-005: nothing Mue did not observe is filled in for the person. */
    @Test
    fun `no measurement is prefilled`() = reviewTest { model, _ ->
        assertTrue(model.uiState.value.metrics.all { it.input.isEmpty() })
        assertNull(model.uiState.value.perceivedEffort)
        assertEquals("", model.uiState.value.notes)
    }

    @Test
    fun `a free-named draft opens the builder already filled`() {
        val timed = timedDraft(
            movement = Movement.OTHER,
            customMovementName = "Kayaking",
            environment = ActivityEnvironment.OUTDOOR,
            equipment = listOf(SessionEquipment(EquipmentType.OTHER, customName = "Sea kayak")),
        )
        reviewTest(timed) { model, _ ->
            val state = model.uiState.value
            assertEquals(ActivityPreset.OTHER, state.preset)
            assertEquals("Kayaking", state.mainActivityLabel)
            assertEquals(ActivityEnvironment.OUTDOOR, state.environment)
            assertEquals(listOf("Sea kayak"), state.equipment.map { it.label })
        }
    }

    /** A draft saved or discarded from elsewhere leaves nothing to review, not an empty review. */
    @Test
    fun `a draft that is gone opens a blank form`() = reviewTest(timed = null) { model, drafts ->
        assertFalse(model.uiState.value.isTimedSession)
        assertEquals("", model.uiState.value.minutes)
        assertTrue(drafts.formStates.isEmpty())
    }

    // endregion

    // region FR-TIMER-006 — correcting the measured duration

    @Test
    fun `the correction panel opens, writes the three fields and closes`() =
        reviewTest { model, _ ->
            model.onOpenDurationPicker()
            assertTrue(model.uiState.value.durationPickerVisible)

            model.onTimedDurationSelected(1, 5, 9)

            val state = model.uiState.value
            assertFalse(state.durationPickerVisible)
            assertEquals("1", state.hours)
            assertEquals("5", state.minutes)
            assertEquals("9", state.seconds)
        }

    @Test
    fun `a correction abandoned leaves the measured duration alone`() = reviewTest { model, _ ->
        model.onOpenDurationPicker()
        model.onDismissDurationPicker()

        assertFalse(model.uiState.value.durationPickerVisible)
        assertEquals("42", model.uiState.value.minutes)
        assertEquals("18", model.uiState.value.seconds)
    }

    @Test
    fun `a corrected duration is what gets written`() = reviewTest { model, drafts ->
        model.onTimedDurationSelected(1, 5, 9)
        model.save()

        assertEquals(
            secondsOf(1 * 3_600 + 5 * 60 + 9),
            drafts.committed.single().second.session.duration,
        )
    }

    /** FR-TIMER-006: a session of a few seconds is real, and the manual floor does not apply. */
    @Test
    fun `a timed session shorter than a minute is saved`() {
        reviewTest(timedDraft(duration = secondsOf(40))) { model, drafts ->
            assertEquals("40", model.uiState.value.seconds)
            model.save()

            assertEquals(secondsOf(40), drafts.committed.single().second.session.duration)
            assertNull(model.uiState.value.durationError)
        }
    }

    @Test
    fun `a corrected duration of zero is refused with the timed message`() =
        reviewTest { model, drafts ->
            model.onTimedDurationSelected(0, 0, 0)
            model.save()

            assertEquals(
                ActivityValidation.TIMED_DURATION_ERROR,
                model.uiState.value.durationError,
            )
            assertEquals(ActivityValidation.TIMED_DURATION_ERROR, model.uiState.value.formError)
            assertTrue(drafts.committed.isEmpty())
        }

    /** The ceiling is common to both modes of entry: 99 h 59 min 30 sec is past it. */
    @Test
    fun `a corrected duration past the ceiling is refused with the timed message`() =
        reviewTest { model, drafts ->
            model.onTimedDurationSelected(99, 59, 30)
            model.save()

            assertEquals(
                ActivityValidation.TIMED_DURATION_ERROR,
                model.uiState.value.durationError,
            )
            assertTrue(drafts.committed.isEmpty())
        }

    /** PRD 17: the one-minute floor is still the manual form's, and still says so. */
    @Test
    fun `a hand-typed session keeps the minute floor and its own message`() =
        logTest { model, repository ->
            model.onMinutesChange("0")
            model.save()

            assertEquals(ActivityValidation.DURATION_ERROR, model.uiState.value.durationError)
            assertFalse(model.uiState.value.isTimedSession)
            assertEquals("", model.uiState.value.seconds)
            assertTrue(repository.saved.isEmpty())
        }

    // endregion

    // region PRD 8.2 — the review form's own persistence

    @Test
    fun `the form state is written at every significant change`() = reviewTest { model, drafts ->
        val afterOpening = drafts.formStates.size
        model.onEffortChange(7)
        model.onNotesChange("Legs heavy")
        model.onMetricChange(MetricKind.DISTANCE, "4.2")

        assertEquals(afterOpening + 3, drafts.formStates.size)
        val (state, version) = drafts.formStates.last()
        val stored = ActivityDraft.fromJson(state)
        assertEquals(ActivityDraft.SCHEMA_VERSION, version)
        assertEquals(7, stored.perceivedEffort)
        assertEquals("Legs heavy", stored.notes)
        assertEquals("4.2", stored.presetDraft().metricInput(MetricKind.DISTANCE))
    }

    /** The version written is the version compared against, or nothing would ever decode. */
    @Test
    fun `a form state written by this build is read back with its typing`() = runTest {
        val drafts = FakeTimedActivityRepository(timedDraft())
        val first = viewModel(FakeLogActivityRepository(), SavedStateHandle(), drafts)
        collect(first)
        first.start(sessionId = null, draftId = TIMED)
        first.onNotesChange("Legs heavy")
        first.onMetricChange(MetricKind.DISTANCE, "4.2")
        first.onTimedDurationSelected(0, 43, 5)
        advanceUntilIdle()

        // A fresh handle: this is the app closed and opened again, not a rotation.
        val reopened = viewModel(FakeLogActivityRepository(), SavedStateHandle(), drafts)
        collect(reopened)
        reopened.start(sessionId = null, draftId = TIMED)
        advanceUntilIdle()

        val state = reopened.uiState.value
        assertTrue(state.isTimedSession)
        assertEquals("Legs heavy", state.notes)
        assertEquals("4.2", reopened.input(MetricKind.DISTANCE))
        assertEquals("43", state.minutes)
        assertEquals("5", state.seconds)
    }

    /** PRD 8.2: never blocking. The typing goes, the measured duration never does. */
    @Test
    fun `an unreadable form state rebuilds the form from the typed columns`() {
        val timed = timedDraft(
            reviewFormState = "{not json at all",
            reviewFormSchemaVersion = ActivityDraft.SCHEMA_VERSION,
        )
        reviewTest(timed) { model, _ ->
            val state = model.uiState.value
            assertTrue(state.isTimedSession)
            assertEquals(ActivityPreset.TREADMILL_WALK, state.preset)
            assertEquals("42", state.minutes)
            assertEquals("18", state.seconds)
            assertEquals(LocalTime.of(18, 32), state.startTime)
            assertEquals("", state.notes)
        }
    }

    /**
     * A blob from another schema is never decoded, not decoded and repaired: this build cannot
     * know what its fields mean any more.
     */
    @Test
    fun `a form state from an unknown version rebuilds the form from the typed columns`() {
        val fromAnotherBuild = ActivityDraft(
            timedDraftId = TIMED.value,
            presetId = ActivityPreset.RUN.id,
            minutes = "7",
            notes = "Written by another build",
        )
        val timed = timedDraft(
            reviewFormState = fromAnotherBuild.toJson(),
            reviewFormSchemaVersion = ActivityDraft.SCHEMA_VERSION + 1,
        )
        reviewTest(timed) { model, _ ->
            val state = model.uiState.value
            assertTrue(state.isTimedSession)
            assertEquals(ActivityPreset.TREADMILL_WALK, state.preset)
            assertEquals("42", state.minutes)
            assertEquals("18", state.seconds)
            assertEquals("", state.notes)
        }
    }

    // endregion

    // region FR-TIMER-007 and 008 — saving, and walking away

    @Test
    fun `saving a review goes through the atomic hand-off`() = reviewTest { model, drafts ->
        model.onMetricChange(MetricKind.DISTANCE, "4.2")
        model.onEffortChange(6)
        model.save()

        val (id, detail) = drafts.committed.single()
        assertEquals(TIMED, id)
        assertEquals(ActivitySource.TIMER, detail.session.source)
        assertEquals(Movement.WALKING, detail.session.movement)
        assertEquals(4_200, detail.metrics.valueOf(MetricKind.DISTANCE))
        assertEquals(
            listOf(EquipmentType.TREADMILL),
            detail.equipment.map { it.equipmentType },
        )
        assertTrue(model.uiState.value.justSaved)
    }

    /**
     * FR-TIMER-006 and 007, on the *stored* session rather than on the review.
     *
     * Reopening a measured session to fix a note used to cost it both of the things the module
     * exists to protect: the seconds were never put into the draft, so the save rebuilt the
     * duration from hours and minutes alone, and `source` was decided from a draft id that a
     * saved session no longer has. A no-op `Save changes` turned `6 min 25 sec` from the timer
     * into `6 min` typed by hand — and took it out of `Start again`'s reach with it.
     */
    @Test
    fun `re-saving a stored timed session keeps its seconds and its source`() = logTest(
        detail = timedTreadmillDetail(),
    ) { model, repository ->
        model.start(EDITED)
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()

        val saved = repository.saved.single()
        assertEquals(secondsOf(6 * 60 + 25), saved.session.duration)
        assertEquals(ActivitySource.TIMER, saved.session.source)
    }

    /** And the form offers the third span, which is what makes the correction possible at all. */
    @Test
    fun `a stored timed session is edited to the second`() = logTest(
        detail = timedTreadmillDetail(),
    ) { model, _ ->
        model.start(EDITED)
        advanceUntilIdle()

        val state = model.uiState.value
        assertTrue(state.isTimedSession)
        assertEquals("25", state.seconds)
    }

    /** A hand-typed session is untouched by all of that: no seconds, and still manual. */
    @Test
    fun `re-saving a stored manual session leaves it manual`() = logTest(
        detail = treadmillDetail(),
    ) { model, repository ->
        model.start(EDITED)
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()

        val saved = repository.saved.single()
        assertEquals(minutesOf(65), saved.session.duration)
        assertEquals(ActivitySource.MANUAL, saved.session.source)
        assertFalse(model.uiState.value.isTimedSession)
    }

    /** The two paths never cross: a review is never written as a second, manual session. */
    @Test
    fun `saving a review writes nothing through the manual path`() = runTest {
        val drafts = FakeTimedActivityRepository(timedDraft())
        val activities = FakeLogActivityRepository()
        val model = viewModel(activities, SavedStateHandle(), drafts)
        collect(model)
        model.start(sessionId = null, draftId = TIMED)
        model.save()
        advanceUntilIdle()

        assertTrue(activities.saved.isEmpty())
        assertEquals(1, drafts.committed.size)
    }

    /** PRD 12 and 13.4: a failed hand-off keeps the draft and says so. */
    @Test
    fun `a failed hand-off keeps the draft and the message`() = reviewTest { model, drafts ->
        drafts.failCommit = true
        model.save()

        assertEquals(LogActivityMessages.SAVE_FAILED, model.uiState.value.saveError)
        assertFalse(model.uiState.value.justSaved)
        assertNotNull(drafts.findDraft(TIMED))
    }

    /** FR-TIMER-008: nothing is lost by walking away, and the draft is still there to reopen. */
    @Test
    fun `leaving the form keeps the draft waiting`() = reviewTest { model, drafts ->
        model.onNotesChange("Half a thought")

        assertTrue(drafts.committed.isEmpty())
        val waiting = assertNotNull(drafts.findDraft(TIMED))
        assertEquals(TimedDraftStatus.PENDING_REVIEW, waiting.status)
        assertEquals(secondsOf(42 * 60 + 18), waiting.accumulatedActive)
        assertEquals("Half a thought", ActivityDraft.fromJson(waiting.reviewFormState).notes)
    }

    // endregion

    // region harness

    private fun LogActivityViewModel.metric(kind: MetricKind): MetricFieldState =
        uiState.value.metrics.single { it.kind == kind }

    private fun LogActivityViewModel.input(kind: MetricKind): String = metric(kind).input

    /** The catalogue row the sheet is drawing for [type], selected state and all. */
    private fun LogActivityViewModel.pickerRow(type: EquipmentType): CatalogEntry =
        assertNotNull(uiState.value.picker).results.single { it.id == type.id }

    /**
     * Every flow the screens read. All three are `WhileSubscribed`, so nothing behind them runs
     * until something is collecting — including the queries the strength editor's two feeds make.
     */
    private fun TestScope.collect(model: LogActivityViewModel) {
        backgroundScope.launch { model.uiState.collect {} }
        backgroundScope.launch { model.catalogue.collect {} }
        backgroundScope.launch { model.lastPerformances.collect {} }
    }

    private fun viewModel(
        repository: FakeLogActivityRepository,
        savedState: SavedStateHandle,
        drafts: FakeTimedActivityRepository = FakeTimedActivityRepository(),
    ): LogActivityViewModel = LogActivityViewModel(
        activities = repository,
        drafts = drafts,
        catalog = FakeExerciseCatalogRepository(),
        preferences = FakeUserPreferencesRepository(),
        savedState = savedState,
        today = { TODAY },
        locale = { Locale.UK },
    )

    private fun logTest(
        detail: ActivitySessionDetail? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(LogActivityViewModel, FakeLogActivityRepository) -> Unit,
    ) = runTest {
        val repository = FakeLogActivityRepository(detail = detail)
        val model = viewModel(repository, savedState)
        collect(model)
        advanceUntilIdle()
        body(model, repository)
    }

    /**
     * The same harness opened on a finished timer (FR-TIMER-005): the form is started on the
     * draft, not on a blank session, and the timed repository is the one being asserted.
     */
    private fun reviewTest(
        timed: TimedActivityDraft? = timedDraft(),
        savedState: SavedStateHandle = SavedStateHandle(),
        start: Boolean = true,
        body: suspend TestScope.(LogActivityViewModel, FakeTimedActivityRepository) -> Unit,
    ) = runTest {
        val drafts = FakeTimedActivityRepository(timed)
        val model = viewModel(FakeLogActivityRepository(), savedState, drafts)
        collect(model)
        if (start) model.start(sessionId = null, draftId = TIMED)
        advanceUntilIdle()
        body(model, drafts)
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

/** What the picker leaves behind for a name nobody has stored yet (PRD 9.2). */
private fun draftWithACustomExercise(): ActivityDraft = ActivityDraft(
    presetId = ActivityPreset.STRENGTH_TRAINING.id,
    minutes = "45",
    detailed = true,
    exercises = listOf(
        ExerciseDraft(
            definitionId = "never-written-down",
            name = "Zercher squat",
            trackingModeId = TrackingMode.WEIGHT_AND_REPS.id,
            isCustom = true,
            sets = listOf(SetDraft(reps = "5", loadKg = "80")),
        ),
    ),
)

/**
 * A finished timer waiting to be reviewed: a treadmill walk of `42 min 18 sec` started at
 * `18:32:47`, which is FR-TIMER-005's own example of a start time that loses its seconds while
 * the duration keeps them.
 */
private fun timedDraft(
    movement: Movement = Movement.WALKING,
    equipment: List<SessionEquipment> = listOf(SessionEquipment(EquipmentType.TREADMILL)),
    environment: ActivityEnvironment = ActivityEnvironment.INDOOR,
    customMovementName: String? = null,
    duration: ActivityDuration = secondsOf(42 * 60 + 18),
    reviewFormState: String? = null,
    reviewFormSchemaVersion: Int = 0,
): TimedActivityDraft = TimedActivityDraft(
    id = TIMED,
    status = TimedDraftStatus.PENDING_REVIEW,
    movement = movement,
    startedAtMillis = 0L,
    startedOn = LocalDate.of(2026, 8, 24),
    startedAtLocalTime = LocalTime.of(18, 32, 47),
    accumulatedActive = duration,
    customMovementName = customMovementName,
    environment = environment,
    equipment = equipment,
    reviewFormState = reviewFormState,
    reviewFormSchemaVersion = reviewFormSchemaVersion,
)

private fun benchPress(): ExerciseDefinition = ExerciseDefinition(
    id = ExerciseDefinitionId("definition-bench"),
    name = "Bench press",
    trackingMode = TrackingMode.WEIGHT_AND_REPS,
)

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

/** The same walk, but measured: `source = timer` and a duration that is not a round minute. */
private fun timedTreadmillDetail(): ActivitySessionDetail = ActivitySessionDetail(
    session = ActivitySession(
        id = EDITED,
        movement = Movement.WALKING,
        startedOn = LocalDate.of(2026, 8, 19),
        duration = secondsOf(6 * 60 + 25),
        environment = ActivityEnvironment.INDOOR,
        startedAtTime = LocalTime.of(18, 32),
        source = ActivitySource.TIMER,
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

private class FakeLogActivityRepository(
    private val detail: ActivitySessionDetail? = null,
) : ActivityRepository {

    val saved = mutableListOf<ActivitySessionDetail>()
    val deleted = mutableListOf<ActivityId>()

    /** Which session each last-performance lookup was told to skip (PRD 11.4). */
    val performanceExclusions = mutableListOf<ActivityId?>()
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
    ): LastPerformance? {
        performanceExclusions += excludingSession
        return null
    }
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

/**
 * The timed side of PRD 8.2 and FR-TIMER-007.
 *
 * The form state is stored exactly as the repository stores it — an opaque string beside a
 * version number, never read — so a test asserting what came back is asserting the ViewModel's
 * own encoding and nothing else.
 */
private class FakeTimedActivityRepository(
    private var draft: TimedActivityDraft? = null,
) : TimedActivityRepository {

    val formStates = mutableListOf<Pair<String?, Int>>()
    val committed = mutableListOf<Pair<TimedDraftId, ActivitySessionDetail>>()
    var failCommit: Boolean = false

    override fun observeLiveDraft(): Flow<TimedActivityDraft?> = flowOf(null)

    override suspend fun findLiveDraft(): TimedActivityDraft? = null

    override fun observeDraftsToReview(): Flow<List<TimedActivityDraft>> =
        flowOf(listOfNotNull(draft))

    override suspend fun findDraft(id: TimedDraftId): TimedActivityDraft? =
        draft?.takeIf { it.id == id }

    override fun observeLastTimedStart(): Flow<StartTimerRequest?> = flowOf(null)

    override suspend fun start(
        request: StartTimerRequest,
        now: TimerInstant,
        zone: ZoneId,
    ): StartTimerOutcome = error("the review form never starts a timer")

    override suspend fun pause(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? = null

    override suspend fun resume(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? = null

    override suspend fun finish(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? = null

    override suspend fun discard(id: TimedDraftId) {
        draft = null
    }

    override suspend fun saveReviewFormState(id: TimedDraftId, state: String?, schemaVersion: Int) {
        formStates += state to schemaVersion
        draft = draft?.takeIf { it.id == id }
            ?.copy(reviewFormState = state, reviewFormSchemaVersion = schemaVersion)
            ?: draft
    }

    override suspend fun commitToSession(id: TimedDraftId, detail: ActivitySessionDetail) {
        if (failCommit) error("the disk said no")
        committed += id to detail
        draft = null
    }
}

private class FakeUserPreferencesRepository : UserPreferencesRepository {

    override val preferences: Flow<UserPreferences> = flowOf(UserPreferences.DEFAULT)

    override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
}

// endregion
