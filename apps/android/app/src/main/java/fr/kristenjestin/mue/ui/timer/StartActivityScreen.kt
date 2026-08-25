package fr.kristenjestin.mue.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.timer.rememberTimerNotificationPermission
import fr.kristenjestin.mue.ui.activity.ActivityFormat
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.activity.ActivityTestTags
import fr.kristenjestin.mue.ui.activity.CatalogEntry
import fr.kristenjestin.mue.ui.activity.CatalogPickerSheet
import fr.kristenjestin.mue.ui.activity.CatalogPickerState
import fr.kristenjestin.mue.ui.activity.CatalogTarget
import fr.kristenjestin.mue.ui.activity.LabelWithIcon
import fr.kristenjestin.mue.ui.activity.LogActivityFormat
import fr.kristenjestin.mue.ui.activity.LogActivityMessages
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueChipRow
import fr.kristenjestin.mue.ui.components.MueDashedAction
import fr.kristenjestin.mue.ui.components.MueFieldContainer
import fr.kristenjestin.mue.ui.components.MueHaptic
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueRemovableChip
import fr.kristenjestin.mue.ui.components.MueChoiceRow
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSegmentedChoice
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueValueChip
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.util.Locale

/** Three tiles a row, as on the form: six presets with no hidden horizontal gesture (PRD 6.2). */
private const val PRESETS_PER_ROW = 3

private val BackIconSize: Dp = 18.dp

/** The accent glyph a card header carries, sized to the caption beside it. */
private val NoteIconSize: Dp = 16.dp

/**
 * Choosing what to start (PRD_ACTIVITY_TIMER 6.2).
 *
 * The six presets of the log form, the same `Other` builder and the same catalogue sheet — a
 * timer is not a second way of describing an activity. PRD 6.2 collects nothing beyond what
 * names the session: no distance, no speed, no energy, no effort and no note.
 *
 * [prefill] is `Start again` (PRD 6.1, contract decision 4): the whole of the last timed
 * request, copied into the builder. It is applied once, as the initial value of a state that
 * then survives on its own — a process death restores what the user has since chosen rather
 * than resetting to the copy.
 */
@Composable
fun StartActivityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    prefill: StartTimerRequest? = null,
) {
    val viewModel = timerViewModel()
    val state = rememberStartActivityState(prefill)
    val haptics = rememberTimerHaptics()
    val permission = rememberTimerNotificationPermission()
    val today = remember { LocalDate.now() }

    StartActivityContent(
        state = state,
        today = today,
        showNotificationRationale = !permission.isGranted,
        onBack = onBack,
        onStart = {
            // FR-TIMER-001: the confirmation is felt on the press. `Confirm` and not `Tick` —
            // the scale's tick is one beat and this is two, so the hand can tell a timer
            // starting from a graduation going past even on a motor with no amplitude control.
            haptics.perform(MueHaptic.Confirm)
            viewModel.start(state.request)
            /*
             * FR-TIMER-012, and in this order on purpose. The timer is written first, so a
             * refused or crashing permission dialog can never cost the user the start they
             * asked for; the prompt then arrives over the timer screen, and dismissing it
             * resumes that screen — which is what posts the notification (contract 8bis).
             */
            permission.request()
        },
        modifier = modifier,
    )
}

@Composable
internal fun StartActivityContent(
    state: StartActivityState,
    today: LocalDate,
    showNotificationRationale: Boolean,
    onBack: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var actionHeight by remember { mutableStateOf(0.dp) }

    // The pinned action sits outside the scaffold, as it does on the log form: it is chrome over
    // the whole window, so its edge runs the full width instead of stopping at the gutter.
    Box(modifier = modifier.fillMaxSize().testTag(TimerTestTags.START_SCREEN)) {
        MueSubScreenScaffold(
            title = TimerMessages.CHOICE_TITLE,
            onNavigateBack = onBack,
            navigationIcon = {
                MueIcon(
                    iconName = MueIcons.ARROW_LEFT,
                    tint = colors.textSecondary,
                    size = BackIconSize,
                )
            },
            navigationContentDescription = TimerMessages.BACK_TO_ACTIVITY,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The same split as the log form: the viewport ends above the solid block,
                    // and the ramp is left in so content dissolves under it instead of being
                    // cut across.
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .verticalScroll(scroll)
                    .padding(bottom = MueStickyActionRamp),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                MueScreenTitle(
                    title = TimerMessages.CHOICE_QUESTION,
                    eyebrow = TimerMessages.CHOICE_EYEBROW,
                    modifier = Modifier.padding(horizontal = spacing.sm),
                )

                PresetTiles(state)
                ReadyCard(state, today)

                // PRD 6.2: the builder, its catalogue, its place and its equipment — and
                // nothing else before the timer runs.
                if (state.preset == ActivityPreset.OTHER) StartBuilder(state)

                if (showNotificationRationale) NotificationNote()
            }
        }

        MueStickyBottomAction(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    actionHeight = with(density) { size.height.toDp() }
                },
            coversContent = scroll.canScrollForward,
        ) {
            MuePrimaryButton(
                label = TimerMessages.START_TIMER,
                onClick = { if (state.validate()) onStart() },
                modifier = Modifier.testTag(TimerTestTags.START_TIMER),
            )
        }
    }

    CatalogPickerSheet(
        picker = state.picker,
        onQueryChange = state::onPickerQueryChange,
        onSelect = state::onCatalogEntrySelected,
        onCreate = state::onCreateFromSearch,
        onDismiss = state::dismissPicker,
    )
}

// region Sections

@Composable
private fun PresetTiles(state: StartActivityState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TimerTestTags.PRESET_ROW),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        ActivityPreset.entries.chunked(PRESETS_PER_ROW).forEach { row ->
            MueChoiceRow {
                row.forEach { preset ->
                    val selected = preset == state.preset
                    MueChoiceCard(
                        label = preset.label,
                        selected = selected,
                        onClick = { state.selectPreset(preset) },
                        icon = {
                            MueIcon(
                                iconName = ActivityIcons.forPreset(preset),
                                tint = if (selected) {
                                    MueTheme.colors.onAccentSoft
                                } else {
                                    MueTheme.colors.textTertiary
                                },
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TimerTestTags.preset(preset.id)),
                    )
                }
            }
        }
    }
}

/**
 * The prototype's summary card, kept by contract decision 3.
 *
 * PRD 6.2 forbids *recalling* the previous session — no `Last timed activity`, no `Use again` —
 * and this recalls nothing: it names what the next tap is about to start, which is the one
 * thing the six tiles above do not say in full.
 */
@Composable
private fun ReadyCard(state: StartActivityState, today: LocalDate) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    MueSurfaceCard(modifier = Modifier.testTag(TimerTestTags.READY_CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MueIcon(
                    iconName = MueIcons.CIRCLE_DOT,
                    tint = colors.accent,
                    size = NoteIconSize,
                )
                MueText(
                    text = TimerMessages.READY_TO_START,
                    style = type.label,
                    color = colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
            MueText(
                // The session begins at the moment this is read, so the day is enough and the
                // clock is `now` (PRD 6.2 asks for nothing else before the start).
                text = ActivityFormat.dayLabel(today, today) +
                    TimerFormat.SEPARATOR +
                    TimerMessages.NOW,
                style = type.micro,
                color = colors.textQuiet,
                maxLines = 1,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(state.activityLabel, type.bodyStrong, maxLines = 1)
                MueText(
                    text = state.contextLabel,
                    style = type.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }
            MueValueChip(
                text = TimerMessages.NO_METRICS_YET,
                modifier = Modifier.padding(start = spacing.md),
            )
        }
    }
}

/** FR-ACTIVITY-008's builder, reached from `Other` — the log form's, not a second one. */
@Composable
private fun StartBuilder(state: StartActivityState) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(
                    text = LogActivityMessages.BUILDER_TITLE,
                    style = MueTheme.typography.sectionTitle,
                )
                MueText(
                    text = LogActivityMessages.BUILDER_SUBTITLE,
                    style = MueTheme.typography.caption,
                    color = colors.textTertiary,
                )
            }
            MueIcon(iconName = ActivityIcons.SPARKLES, tint = colors.accent)
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            MueFieldContainer(
                label = LogActivityMessages.MAIN_ACTIVITY_LABEL,
                modifier = Modifier.testTag(ActivityTestTags.MOVEMENT_PICKER),
                isError = state.movementError != null,
                onClick = state::openMovementPicker,
                onClickLabel = LogActivityMessages.ACTIVITY_PICKER_TITLE,
                trailing = {
                    MueIcon(iconName = MueIcons.CHEVRON_RIGHT, tint = colors.textTertiary)
                },
            ) {
                MueText(
                    text = state.mainActivityLabel,
                    style = MueTheme.typography.bodyStrong,
                    color = if (state.hasMainActivity) {
                        colors.textPrimary
                    } else {
                        colors.textTertiary
                    },
                    maxLines = 1,
                )
            }
            state.movementError?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = colors.error,
                    modifier = Modifier
                        .padding(horizontal = spacing.xs)
                        .semantics { error(message) },
                )
            }
        }

        MueSurfaceCard(shape = MueTheme.shapes.field) {
            LabelWithIcon(LogActivityMessages.ENVIRONMENT_LABEL, ActivityIcons.MAP_PIN)
            MueSegmentedChoice(
                options = ENVIRONMENTS,
                selected = state.environment,
                onSelect = state::selectEnvironment,
                label = { it.displayName },
                modifier = Modifier
                    .padding(top = spacing.md)
                    .testTag(ActivityTestTags.ENVIRONMENT_PICKER),
            )
        }

        MueSurfaceCard(shape = MueTheme.shapes.field) {
            LabelWithIcon(LogActivityMessages.EQUIPMENT_LABEL, ActivityIcons.WRENCH)
            if (state.equipment.isNotEmpty()) {
                MueChipRow(modifier = Modifier.padding(top = spacing.md)) {
                    state.equipment.forEachIndexed { index, chip ->
                        MueRemovableChip(
                            label = chip.displayName,
                            onRemove = { state.removeEquipment(index) },
                            modifier = Modifier.testTag(ActivityTestTags.equipmentChip(index)),
                        )
                    }
                }
            }
            MueDashedAction(
                label = if (state.equipment.isEmpty()) {
                    LogActivityMessages.CHOOSE_EQUIPMENT
                } else {
                    LogActivityMessages.ADD_ANOTHER_EQUIPMENT
                },
                onClick = state::openEquipmentPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md)
                    .testTag(ActivityTestTags.EQUIPMENT_PICKER),
                icon = { MueIcon(iconName = ActivityIcons.PLUS, size = NoteIconSize) },
            )
        }
    }
}

/**
 * FR-TIMER-012's short contextual explanation, shown before the permission is ever asked for —
 * and kept afterwards while it is still missing, since it is also the reason the notification
 * is absent.
 */
@Composable
private fun NotificationNote() {
    val colors = MueTheme.colors
    MueSurfaceCard(
        modifier = Modifier.testTag(TimerTestTags.NOTIFICATION_RATIONALE),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            MueIcon(
                iconName = MueIcons.BELL,
                tint = colors.textTertiary,
                size = NoteIconSize,
            )
            MueText(
                text = TimerMessages.NOTIFICATION_RATIONALE,
                style = MueTheme.typography.micro,
                color = colors.textQuiet,
                modifier = Modifier.padding(start = MueTheme.spacing.md),
            )
        }
    }
}

// endregion

// region State

/** `Not set` is last, as in the form, and is what an unstated place means. */
private val ENVIRONMENTS: List<ActivityEnvironment> = listOf(
    ActivityEnvironment.INDOOR,
    ActivityEnvironment.OUTDOOR,
    ActivityEnvironment.UNKNOWN,
)

/**
 * What the start screen is holding while it is open (PRD 6.2).
 *
 * A state holder rather than a `ViewModel`: nothing here outlives the screen, nothing here is
 * read by another surface, and the one thing that must survive a process death — the choice
 * itself — is four enums and a list of equipment, which `rememberSaveable` carries as text.
 * [TimerViewModel] owns the timer; this owns the question that has not been asked yet.
 */
@Stable
internal class StartActivityState internal constructor(
    preset: ActivityPreset = ActivityPreset.DEFAULT,
    movement: Movement? = null,
    customMovementName: String = "",
    environment: ActivityEnvironment = ActivityEnvironment.UNKNOWN,
    equipment: List<SessionEquipment> = emptyList(),
) {
    var preset: ActivityPreset by mutableStateOf(preset)
        private set

    var movement: Movement? by mutableStateOf(movement)
        private set

    var customMovementName: String by mutableStateOf(customMovementName)
        private set

    var environment: ActivityEnvironment by mutableStateOf(environment)
        private set

    var equipment: List<SessionEquipment> by mutableStateOf(equipment)
        private set

    /** The catalogue sheet, which is transient and is deliberately not saved. */
    var picker: CatalogPickerState? by mutableStateOf(null)
        private set

    /** FR-ACTIVITY-008: raised by `Start timer`, never before the user has tried. */
    var movementError: String? by mutableStateOf(null)
        private set

    /**
     * What `Start timer` sends (FR-TIMER-001).
     *
     * A preset carries its own axes — a treadmill walk is indoors, on a treadmill — so the
     * builder's values are ignored unless the builder is what is on screen. That is what makes
     * the label the timer shows agree with the one the review form will rebuild, since
     * `ActivityPreset.of` reads those very axes back.
     */
    val request: StartTimerRequest
        get() = if (preset == ActivityPreset.OTHER) {
            StartTimerRequest(
                movement = movement ?: Movement.OTHER,
                customMovementName = customMovementName.trim().takeIf { it.isNotEmpty() },
                environment = environment,
                equipment = equipment,
            )
        } else {
            presetRequest(preset)
        }

    val activityLabel: String
        get() = request.let {
            TimerFormat.activityLabel(it.movement, it.customMovementName, it.equipment)
        }

    val contextLabel: String
        get() = request.let { TimerFormat.context(it.environment, it.equipment) }

    /** The free name wins, exactly as it does on a stored session (FR-ACTIVITY-008). */
    val mainActivityLabel: String
        get() = when {
            movement == Movement.OTHER && customMovementName.isNotBlank() ->
                customMovementName.trim()

            movement != null && movement != Movement.OTHER -> requireNotNull(movement).displayName
            else -> LogActivityMessages.CHOOSE_FROM_CATALOGUE
        }

    val hasMainActivity: Boolean
        get() = movement != null &&
            (movement != Movement.OTHER || customMovementName.isNotBlank())

    /** Only the builder can be incomplete; the five other presets already know what they are. */
    val canStart: Boolean get() = preset != ActivityPreset.OTHER || hasMainActivity

    /** Answers whether the start may proceed, and says why on screen when it may not. */
    fun validate(): Boolean {
        movementError = if (canStart) null else LogActivityMessages.MOVEMENT_REQUIRED
        return canStart
    }

    fun selectPreset(preset: ActivityPreset) {
        this.preset = preset
        // The builder's own values are kept: leaving `Other` and coming back to it should find
        // the activity that was already chosen rather than an empty field.
        if (preset != ActivityPreset.OTHER) movementError = null
    }

    fun selectEnvironment(environment: ActivityEnvironment) {
        this.environment = environment
    }

    fun removeEquipment(index: Int) {
        equipment = equipment
            .filterIndexed { position, _ -> position != index }
            .mapIndexed { position, item -> item.copy(position = position) }
    }

    // --- The catalogue sheet ----------------------------------------------------------

    fun openMovementPicker() {
        picker = pickerFor(CatalogTarget.MOVEMENT, query = "")
    }

    fun openEquipmentPicker() {
        picker = pickerFor(CatalogTarget.EQUIPMENT, query = "")
    }

    fun dismissPicker() {
        picker = null
    }

    fun onPickerQueryChange(query: String) {
        picker = picker?.let { pickerFor(it.target, query) }
    }

    /**
     * The two catalogues close differently, exactly as they do on the log form: a movement is
     * one choice and the sheet leaves with it, equipment is `Select one or more` and a row
     * toggles while the sheet stays put.
     */
    fun onCatalogEntrySelected(id: String) {
        when (picker?.target ?: return) {
            CatalogTarget.MOVEMENT -> {
                movement = Movement.fromId(id)
                customMovementName = ""
                movementError = null
                picker = null
            }

            CatalogTarget.EQUIPMENT -> {
                toggleEquipment(SessionEquipment(EquipmentType.fromId(id)))
                refreshPicker()
            }
        }
    }

    /** FR-ACTIVITY-008: the last resort, and the only path to `movement = other`. */
    fun onCreateFromSearch() {
        val current = picker ?: return
        val name = current.trimmedQuery
        when (current.target) {
            CatalogTarget.MOVEMENT ->
                when (val validated = ActivityValidation.validateCustomMovementName(name)) {
                    is Validated.Valid -> {
                        movement = Movement.OTHER
                        customMovementName = validated.value
                        movementError = null
                        picker = null
                    }

                    is Validated.Invalid -> picker = current.copy(notice = validated.message)
                }

            CatalogTarget.EQUIPMENT ->
                when (val validated = ActivityValidation.validateCustomEquipmentName(name)) {
                    is Validated.Valid -> createEquipment(validated.value)
                    is Validated.Invalid -> picker = current.copy(notice = validated.message)
                }
        }
    }

    /**
     * Asking to create a name the activity already carries is a mistake to point out, not an
     * instruction to remove it — which is why this is not [toggleEquipment].
     */
    private fun createEquipment(name: String) {
        if (equipment.any { it.displayName.folded() == name.folded() }) {
            picker = picker?.copy(notice = LogActivityMessages.ALREADY_ADDED)
            return
        }
        equipment = equipment + SessionEquipment(
            equipmentType = EquipmentType.OTHER,
            customName = name,
            position = equipment.size,
        )
        onPickerQueryChange("")
    }

    private fun toggleEquipment(item: SessionEquipment) {
        val folded = item.displayName.folded()
        val next = if (equipment.any { it.displayName.folded() == folded }) {
            equipment.filterNot { it.displayName.folded() == folded }
        } else {
            equipment + item
        }
        equipment = next.mapIndexed { position, entry -> entry.copy(position = position) }
    }

    private fun refreshPicker() {
        picker = picker?.let { pickerFor(it.target, it.query) }
    }

    /**
     * The catalogue, filtered here rather than in composition.
     *
     * The same two lists the log form offers, restated because that one filters
     * `EquipmentDraft`s held in a serialised draft and this one holds real
     * [SessionEquipment]; sharing the code would mean sharing that draft as well.
     */
    private fun pickerFor(target: CatalogTarget, query: String): CatalogPickerState {
        val needle = query.folded()
        val results = when (target) {
            CatalogTarget.MOVEMENT -> ActivityPreset.OTHER_CATALOGUE
                .filter { needle.isEmpty() || it.displayName.folded().contains(needle) }
                .map { entry ->
                    CatalogEntry(
                        id = entry.id,
                        name = entry.displayName,
                        meta = LogActivityFormat.meta(entry),
                        selected = movement == entry,
                    )
                }

            CatalogTarget.EQUIPMENT -> EquipmentType.entries
                .filterNot { it == EquipmentType.OTHER }
                .filter { needle.isEmpty() || it.displayName.folded().contains(needle) }
                .map { type ->
                    CatalogEntry(
                        id = type.id,
                        name = type.displayName,
                        meta = LogActivityFormat.meta(type),
                        selected = equipment.any {
                            it.displayName.folded() == type.displayName.folded()
                        },
                    )
                }
        }
        return CatalogPickerState(target = target, query = query, results = results)
    }

    companion object {
        /**
         * `Start again` (contract decision 4): the whole request, copied.
         *
         * A preset is only chosen for it when that preset reproduces the request exactly.
         * Anything a preset would quietly change — a walk recorded outdoors on a treadmill —
         * opens in the builder instead, so nothing that was measured once is lost on the way
         * to measuring it again.
         */
        fun of(prefill: StartTimerRequest?): StartActivityState {
            if (prefill == null) return StartActivityState()
            val candidate = ActivityPreset.of(prefill.movement, prefill.equipment)
            val exact = candidate != ActivityPreset.OTHER &&
                prefill.customMovementName.isNullOrBlank() &&
                presetRequest(candidate).sameAxesAs(prefill)
            return StartActivityState(
                preset = if (exact) candidate else ActivityPreset.OTHER,
                movement = prefill.movement,
                customMovementName = prefill.customMovementName.orEmpty(),
                environment = prefill.environment,
                equipment = prefill.equipment,
            )
        }
    }
}

/** A preset's own axes, which are what a session started from it carries. */
private fun presetRequest(preset: ActivityPreset): StartTimerRequest = StartTimerRequest(
    movement = preset.movement ?: Movement.OTHER,
    customMovementName = null,
    environment = preset.environment,
    equipment = listOfNotNull(preset.equipment?.let { SessionEquipment(it) }),
)

/** Equal as far as a timer is concerned: the same activity, in the same place, on the same gear. */
private fun StartTimerRequest.sameAxesAs(other: StartTimerRequest): Boolean =
    movement == other.movement &&
        environment == other.environment &&
        equipment.map { it.displayName.folded() } == other.equipment.map { it.displayName.folded() }

/** `"I".lowercase()` is `"ı"` on a Turkish phone, so every fold names its locale. */
private fun String.folded(): String = trim().lowercase(Locale.ROOT)

/** Four enums and a list of equipment, all of them as text so a `Bundle` can carry them. */
private val StartActivityStateSaver: Saver<StartActivityState, Any> =
    listSaver<StartActivityState, String>(
        save = { state ->
            listOf(
                state.preset.id,
                state.movement?.id.orEmpty(),
                state.customMovementName,
                state.environment.id,
            ) + state.equipment.flatMap { listOf(it.equipmentType.id, it.customName.orEmpty()) }
        },
        restore = { saved ->
            // Every `fromId` below is total, so a stack written by another build degrades to a
            // sensible choice rather than throwing on the first frame after an update.
            StartActivityState(
                preset = ActivityPreset.fromId(saved.getOrElse(0) { "" }),
                movement = saved.getOrElse(1) { "" }.takeIf { it.isNotEmpty() }
                    ?.let(Movement::fromId),
                customMovementName = saved.getOrElse(2) { "" },
                environment = ActivityEnvironment.fromId(saved.getOrElse(3) { "" }),
                equipment = saved.drop(SAVED_HEADER)
                    .chunked(2)
                    .filter { it.size == 2 }
                    .mapIndexed { position, pair ->
                        SessionEquipment(
                            equipmentType = EquipmentType.fromId(pair[0]),
                            customName = pair[1].takeIf { it.isNotEmpty() },
                            position = position,
                        )
                    },
            )
        },
    )

/** The four scalars written before the equipment pairs begin. */
private const val SAVED_HEADER = 4

@Composable
internal fun rememberStartActivityState(
    prefill: StartTimerRequest? = null,
): StartActivityState = rememberSaveable(saver = StartActivityStateSaver) {
    StartActivityState.of(prefill)
}

// endregion

// region Previews

@Preview(name = "Start activity", showBackground = true, heightDp = 844, widthDp = 390)
@Composable
private fun StartActivityPreview() {
    MueTheme {
        StartActivityContent(
            state = StartActivityState(),
            today = LocalDate.of(2026, 8, 24),
            showNotificationRationale = true,
            onBack = {},
            onStart = {},
        )
    }
}

@Preview(name = "Start activity — builder", showBackground = true, heightDp = 980, widthDp = 390)
@Composable
private fun StartActivityBuilderPreview() {
    MueTheme {
        StartActivityContent(
            state = StartActivityState.of(
                StartTimerRequest(
                    movement = Movement.YOGA,
                    environment = ActivityEnvironment.INDOOR,
                    equipment = listOf(SessionEquipment(EquipmentType.YOGA_MAT)),
                ),
            ),
            today = LocalDate.of(2026, 8, 24),
            showNotificationRationale = false,
            onBack = {},
            onStart = {},
        )
    }
}

// endregion
