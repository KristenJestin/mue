package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueChoiceCardDefaults
import fr.kristenjestin.mue.ui.components.MueChoiceRow
import fr.kristenjestin.mue.ui.components.MueDashedAction
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePickerEmpty
import fr.kristenjestin.mue.ui.components.MuePickerList
import fr.kristenjestin.mue.ui.components.MuePickerRow
import fr.kristenjestin.mue.ui.components.MuePickerSectionHeader
import fr.kristenjestin.mue.ui.components.MuePickerSheet
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.util.Locale

/** The one list FR-ACTIVITY-009 asks for, and what it is called before anything is typed. */
internal const val EXERCISE_SECTION_DEFAULT = "Recent & common"
internal const val EXERCISE_SECTION_RESULTS = "Results"
internal const val EXERCISE_SEARCH_PLACEHOLDER = "Search squat, row, plank…"
internal const val EXERCISE_PICKER_TITLE = "Choose an exercise"
internal const val EXERCISE_PICKER_EYEBROW = "Build your session"
internal const val EXERCISE_PICKER_EMPTY = "No matching exercise yet."
internal const val CREATE_YOUR_OWN = "Create your own"
internal const val CREATE_YOUR_OWN_HINT =
    "Use the search text as its name, then choose how each set is tracked."
internal const val CREATE_PROMPT = "Name your exercise above"

/**
 * The create action of the sheet, which `ActivityTestTags` does not name: that object was
 * written before this sheet existed and belongs to another chunk.
 */
internal const val EXERCISE_CREATE_TAG = "activity:createExercise"

/** The mode chosen for a brand-new definition. PRD 9.2 keeps the four ids of the V1. */
private val CustomModeDefault = TrackingMode.WEIGHT_AND_REPS

/**
 * The exercise catalogue, rising from the bottom (PRD FR-ACTIVITY-009).
 *
 * One list, searched, most recently used first — the repository already orders it that way, so
 * nothing here sorts. Below it, the prototype's `Create your own` block, which turns the text
 * that found nothing into a definition.
 *
 * The picker never decides that a name is new: [onCreate] hands the typed text back and
 * `StrengthDraftEditor.definitionFor` folds it against the catalogue, so `  bench press ` can
 * never become a second `Bench press` (PRD 9.2). What the sheet does do is show that reuse
 * honestly — the create button says `Use “Bench press”` when the fold already matches.
 */
@Composable
internal fun ExercisePickerSheet(
    visible: Boolean,
    catalogue: List<ExerciseDefinition>,
    onDismissRequest: () -> Unit,
    onPick: (ExerciseDefinition) -> Unit,
    onCreate: (name: String, mode: TrackingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable(visible) { mutableStateOf("") }
    var customMode by rememberSaveable(visible) { mutableStateOf(CustomModeDefault) }

    val typed = query.trim()
    val results = remember(catalogue, typed) { catalogue.matching(typed) }
    val existing = remember(catalogue, typed) { catalogue.foldedMatch(typed) }

    MuePickerSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = EXERCISE_PICKER_TITLE,
        eyebrow = EXERCISE_PICKER_EYEBROW,
        query = query,
        onQueryChange = { query = it },
        modifier = modifier.testTag(ActivityTestTags.EXERCISE_PICKER),
        searchPlaceholder = EXERCISE_SEARCH_PLACEHOLDER,
        searchLabel = "Search the exercise catalogue",
        searchIcon = { MueIcon(ActivityIcons.SEARCH, tint = MueTheme.colors.textTertiary, size = 18.dp) },
        closeContentDescription = "Close the exercise picker",
        footer = {
            CreateYourOwn(
                typed = typed,
                existing = existing,
                mode = customMode,
                onModeChange = { customMode = it },
                onCreate = { onCreate(typed, customMode) },
                modifier = Modifier.padding(top = MueTheme.spacing.lg),
            )
        },
    ) {
        MuePickerSectionHeader(
            title = if (typed.isEmpty()) EXERCISE_SECTION_DEFAULT else EXERCISE_SECTION_RESULTS,
            trailing = "${results.size} exercises",
            modifier = Modifier.padding(top = MueTheme.spacing.md),
        )
        if (results.isEmpty()) {
            MuePickerEmpty(EXERCISE_PICKER_EMPTY, modifier = Modifier.padding(top = MueTheme.spacing.sm))
        } else {
            MuePickerList(modifier = Modifier.padding(top = MueTheme.spacing.sm)) {
                results.forEachIndexed { index, definition ->
                    MuePickerRow(
                        name = definition.name,
                        meta = definition.meta(),
                        showDivider = index > 0,
                        onClick = { onPick(definition) },
                        modifier = Modifier.testTag(exercisePickerRowTag(definition)),
                    )
                }
            }
        }
    }
}

/** A test names a catalogue row by its definition rather than by its place in the list. */
internal fun exercisePickerRowTag(definition: ExerciseDefinition): String =
    "${ActivityTestTags.EXERCISE_PICKER}:${definition.nameFolded}"

/** `Barbell · weight & reps`, as in the prototype: what it needs, and what it records. */
internal fun ExerciseDefinition.meta(): String {
    val mode = trackingMode.label.lowercase(Locale.ROOT)
    return equipment?.let { "${it.displayName} · $mode" } ?: mode
}

/** Plain substring matching, folded: seventeen entries need no index and no fuzziness. */
private fun List<ExerciseDefinition>.matching(query: String): List<ExerciseDefinition> {
    if (query.isEmpty()) return this
    val folded = ExerciseDefinition.fold(query)
    return filter { folded in it.nameFolded }
}

private fun List<ExerciseDefinition>.foldedMatch(query: String): ExerciseDefinition? {
    if (query.isEmpty()) return null
    val folded = ExerciseDefinition.fold(query)
    return firstOrNull { it.nameFolded == folded }
}

/**
 * The prototype's amber footer, with the four tracking modes of PRD 9.2 laid out two by two.
 *
 * A single row of four segments would give each label 66 dp on a 390 dp screen, and
 * `Weight & duration` needs more than that; two rows of two give each tile the width of half
 * the sheet. The mode is disabled while the typed name already exists, because PRD 9.2 is
 * explicit that a reused definition keeps the mode it was created with.
 */
@Composable
private fun CreateYourOwn(
    typed: String,
    existing: ExerciseDefinition?,
    mode: TrackingMode,
    onModeChange: (TrackingMode) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    val named = typed.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = AccentBorderAlpha), shape)
            .padding(MueTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            MueIcon(ActivityIcons.SPARKLES, tint = colors.accent, size = 16.dp)
            MueText(CREATE_YOUR_OWN, MueTheme.typography.bodyStrong, color = colors.onAccentSoft)
        }
        MueText(
            text = if (existing == null) CREATE_YOUR_OWN_HINT else existingHint(existing),
            style = MueTheme.typography.caption,
            color = colors.textSecondary,
        )

        TrackingMode.entries.chunked(2).forEach { pair ->
            MueChoiceRow {
                pair.forEach { option ->
                    MueChoiceCard(
                        label = option.label,
                        selected = option == mode,
                        onClick = { onModeChange(option) },
                        enabled = existing == null,
                        minHeight = ModeTileHeight,
                        contentPadding = MueChoiceCardDefaults.CompactPadding,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        MueDashedAction(
            label = when {
                !named -> CREATE_PROMPT
                existing != null -> "Use “${existing.name}”"
                else -> "Create “$typed”"
            },
            onClick = onCreate,
            enabled = named,
            icon = { MueIcon(ActivityIcons.PLUS, tint = MueTheme.contentColor, size = 14.dp) },
            modifier = Modifier.fillMaxWidth().testTag(EXERCISE_CREATE_TAG),
        )
    }
}

private fun existingHint(existing: ExerciseDefinition): String =
    "“${existing.name}” is already in your catalogue, so this session will reuse it."

/** Two lines of `chip` type plus the tile padding; no icon, unlike the preset tiles. */
private val ModeTileHeight = 64.dp

/** The prototype's `border-[#efb45f]/20` around the amber block. */
private const val AccentBorderAlpha = 0.20f
