package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.time.LocalTime

/** The prototype's `h-12 w-12` glyph tile, which also clears the 48 dp touch minimum. */
private val IconTileSize: Dp = 48.dp

/** The prototype's `h-1 w-1` bullet between two facts. */
private val FactBulletSize: Dp = 4.dp

private const val EDIT_CLICK_LABEL = "Edit this activity"

/**
 * One session, as it reads on the dashboard and in the history — the very same card in both
 * (PRD FR-ACTIVITY-002 and 012), so the two lists can never drift apart.
 *
 * The label comes from [ActivitySummary.label], which the storage already derived through the
 * five rules of PRD 11.1. Deriving it a second time here would be a second implementation of
 * those rules, and the card does not even hold the equipment they need.
 */
@Composable
fun ActivitySessionCard(
    summary: ActivitySummary,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = modifier.testTag(ActivityTestTags.sessionCard(summary.id.value)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
        onClick = onClick,
        onClickLabel = EDIT_CLICK_LABEL,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(IconTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the label right beside it already names the activity.
                MueIcon(iconName = iconOf(summary.movement), tint = colors.onAccentSoft)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MueText(
                        text = summary.label,
                        style = type.bodyStrong,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MueText(
                        text = ActivityFormat.dayAndTime(
                            date = summary.startedOn,
                            time = summary.startedAtTime,
                            today = today,
                        ),
                        style = type.micro,
                        color = colors.textTertiary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = spacing.sm),
                    )
                }

                FactRow(
                    facts = listOf(ActivityFormat.duration(summary.duration)) +
                        ActivityFormat.facts(summary),
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            MueIcon(
                iconName = MueIcons.CHEVRON_RIGHT,
                tint = colors.textQuiet,
                size = 16.dp,
            )
        }
    }
}

/** The duration and the secondary facts, separated by the prototype's small bullets. */
@Composable
private fun FactRow(facts: List<String>, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = MueTheme.spacing.sm)
                        .size(FactBulletSize)
                        .clip(MueTheme.shapes.pill)
                        .background(colors.textQuiet),
                )
            }
            MueText(fact, MueTheme.typography.micro, color = colors.textTertiary, maxLines = 1)
        }
    }
}

/**
 * The glyph of PRD 14.1, chosen through the preset the card would reopen the session in.
 *
 * A summary carries no equipment, so `ActivityPreset.of` reads a treadmill walk as an outdoor
 * one — which changes nothing here, since both walks share `footprints` by that very table.
 * Going through the preset keeps one movement-to-icon rule in the module instead of two.
 */
private fun iconOf(movement: Movement): String =
    ActivityIcons.forPreset(ActivityPreset.of(movement, equipment = emptyList()))

// region Previews

private val PreviewToday: LocalDate = LocalDate.of(2026, 8, 23)

internal fun previewSummary(
    label: String = "Treadmill walk",
    movement: Movement = Movement.WALKING,
    daysAgo: Long = 0,
    minutes: Int = 45,
    startedAtTime: LocalTime? = null,
    distanceMetres: Int? = 4_200,
    validSetCount: Int? = null,
    estimatedEnergyKcal: Int? = 280,
    id: String = "preview-$label-$daysAgo",
): ActivitySummary = ActivitySummary(
    id = ActivityId(id),
    label = label,
    movement = movement,
    startedOn = PreviewToday.minusDays(daysAgo),
    startedAtTime = startedAtTime,
    duration = requireNotNull(ActivityDuration.ofHoursAndMinutesOrNull(0, minutes)),
    distanceMetres = distanceMetres,
    validSetCount = validSetCount,
    estimatedEnergyKcal = estimatedEnergyKcal,
)

@Preview(name = "Session cards", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun ActivitySessionCardPreview() {
    MuePreviewHost(padding = 28) {
        ActivitySessionCard(previewSummary(), PreviewToday, onClick = {})
        ActivitySessionCard(
            summary = previewSummary(
                label = "Strength training",
                movement = Movement.STRENGTH_TRAINING,
                daysAgo = 2,
                minutes = 55,
                startedAtTime = LocalTime.of(18, 30),
                distanceMetres = null,
                validSetCount = 12,
                estimatedEnergyKcal = 320,
            ),
            today = PreviewToday,
            onClick = {},
        )
        ActivitySessionCard(
            summary = previewSummary(
                label = "Padel",
                movement = Movement.OTHER,
                daysAgo = 12,
                minutes = 90,
                distanceMetres = null,
                estimatedEnergyKcal = null,
            ),
            today = PreviewToday,
            onClick = {},
        )
    }
}

// endregion
