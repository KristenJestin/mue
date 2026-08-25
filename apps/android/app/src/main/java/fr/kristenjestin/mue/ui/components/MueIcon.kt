package fr.kristenjestin.mue.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.R
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.theme.MueTheme

/** What the prototypes draw an inline icon at; the Lucide grid still reads down to 16 dp. */
val MueIconSize: Dp = 20.dp

/**
 * A Lucide vector, drawn in one colour.
 *
 * The drawables carry no tint of their own, so the caller decides the colour and the same icon
 * reads on the canvas, on an amber card and in a quiet row.
 *
 * [contentDescription] defaults to null because most icons in this app sit beside the very text
 * that names them, and describing them again makes TalkBack say it twice. Pass a label only
 * when the icon is alone inside a control (PRD_ACTIVITIES 15).
 */
@Composable
fun MueIcon(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MueTheme.contentColor,
    size: Dp = MueIconSize,
) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** The same icon addressed by the name `ActivityIcons` publishes rather than by its resource. */
@Composable
fun MueIcon(
    iconName: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MueTheme.contentColor,
    size: Dp = MueIconSize,
) {
    MueIcon(
        iconRes = MueIcons.resource(iconName),
        modifier = modifier,
        contentDescription = contentDescription,
        tint = tint,
        size = size,
    )
}

/**
 * The bridge between icon names and the vectors actually imported into the app
 * (PRD_ACTIVITIES 14.1), plus the names the two tables of that section do not cover.
 *
 * The lookup is an explicit table rather than `Resources.getIdentifier`, which would return `0`
 * for a name nobody imported and would hide every drawable from lint and from the resource
 * shrinker. Here an icon that does not exist cannot be named on the right-hand side, and a name
 * that resolves to nothing fails loudly the first time it is drawn.
 */
object MueIcons {

    /*
     * Beyond PRD 14.1: chrome, list affordances and destructive actions, all of them Lucide
     * names the prototypes already use. `chevron-up` and `chevron-down` are the exercise
     * reordering controls that replaced drag and drop, which no prototype drew.
     */

    const val ARROW_LEFT: String = "ic_arrow_left"
    const val CHEVRON_RIGHT: String = "ic_chevron_right"
    const val CHEVRON_UP: String = "ic_chevron_up"
    const val CHEVRON_DOWN: String = "ic_chevron_down"
    const val CLOSE: String = "ic_x"
    const val CHECK: String = "ic_check"
    const val TRASH: String = "ic_trash_2"
    const val CALENDAR_DAYS: String = "ic_calendar_days"
    const val CLOCK: String = "ic_clock_3"
    const val LIST_PLUS: String = "ic_list_plus"
    const val ZAP: String = "ic_zap"

    /*
     * The Activity Timer (PRD_ACTIVITY_TIMER 6). That document carries no icon table of its own,
     * so these are the names its two prototypes draw, kept as Lucide names because
     * PRD_ACTIVITIES 14.1 makes Lucide the family and its own table the tie-breaker — which is
     * why the preset cards still answer `route` and `footprints` rather than the prototype's
     * `person-standing` and `trees`. The transport glyphs stay stroked like every other vector
     * here; the prototypes fill them with `currentColor`, which nothing in this app does.
     */

    const val PLAY: String = "ic_play"
    const val PAUSE: String = "ic_pause"

    /** Lucide draws the transport stop as `square`. Here it is the `Finish` action, nothing else. */
    const val STOP: String = "ic_square"

    /** The timer screen's overflow, which holds `Discard timer` alone (PRD_ACTIVITY_TIMER 6.3). */
    const val MORE_HORIZONTAL: String = "ic_more_horizontal"

    /** A still bell explains the notification before it is asked for, a ringing one once it is live. */
    const val BELL: String = "ic_bell"
    const val BELL_RING: String = "ic_bell_ring"

    /** The `Active` / `Paused` dot, which is never the only carrier of that state (PRD 11). */
    const val CIRCLE_DOT: String = "ic_circle_dot"

    /** `Log past activity` and `Start again`, the dashboard actions of PRD_ACTIVITY_TIMER 6.1. */
    const val HISTORY: String = "ic_history"
    const val ROTATE_CW: String = "ic_rotate_cw"

    @DrawableRes
    fun resource(name: String): Int = when (name) {
        ActivityIcons.TAB_ENTRY -> R.drawable.ic_scale
        ActivityIcons.TAB_PROGRESS -> R.drawable.ic_chart_no_axes_combined
        ActivityIcons.TAB_ACTIVITY -> R.drawable.ic_activity
        ActivityIcons.TAB_PROFILE -> R.drawable.ic_user_round

        ActivityIcons.FOOTPRINTS -> R.drawable.ic_footprints
        ActivityIcons.ROUTE -> R.drawable.ic_route
        ActivityIcons.BIKE -> R.drawable.ic_bike
        ActivityIcons.DUMBBELL -> R.drawable.ic_dumbbell
        ActivityIcons.SHAPES -> R.drawable.ic_shapes
        ActivityIcons.TIMER -> R.drawable.ic_timer
        ActivityIcons.GAUGE -> R.drawable.ic_gauge
        ActivityIcons.FLAME -> R.drawable.ic_flame
        ActivityIcons.TRENDING_UP -> R.drawable.ic_trending_up
        ActivityIcons.MAP_PIN -> R.drawable.ic_map_pin
        ActivityIcons.TREES -> R.drawable.ic_trees
        ActivityIcons.WRENCH -> R.drawable.ic_wrench
        ActivityIcons.NOTEBOOK_PEN -> R.drawable.ic_notebook_pen
        ActivityIcons.PLUS -> R.drawable.ic_plus
        ActivityIcons.PLUS_CIRCLE -> R.drawable.ic_plus_circle
        ActivityIcons.COPY_PLUS -> R.drawable.ic_copy_plus
        ActivityIcons.SEARCH -> R.drawable.ic_search
        ActivityIcons.SPARKLES -> R.drawable.ic_sparkles

        ActivityIcons.WAVES -> R.drawable.ic_waves
        ActivityIcons.SAILBOAT -> R.drawable.ic_sailboat
        ActivityIcons.ORBIT -> R.drawable.ic_orbit
        ActivityIcons.MOUNTAIN -> R.drawable.ic_mountain
        ActivityIcons.MOUNTAIN_SNOW -> R.drawable.ic_mountain_snow
        ActivityIcons.FLOWER -> R.drawable.ic_flower
        ActivityIcons.MUSIC -> R.drawable.ic_music
        ActivityIcons.PERSON_STANDING -> R.drawable.ic_person_standing
        ActivityIcons.MOVE -> R.drawable.ic_move
        ActivityIcons.VOLLEYBALL -> R.drawable.ic_volleyball

        ARROW_LEFT -> R.drawable.ic_arrow_left
        CHEVRON_RIGHT -> R.drawable.ic_chevron_right
        CHEVRON_UP -> R.drawable.ic_chevron_up
        CHEVRON_DOWN -> R.drawable.ic_chevron_down
        CLOSE -> R.drawable.ic_x
        CHECK -> R.drawable.ic_check
        TRASH -> R.drawable.ic_trash_2
        CALENDAR_DAYS -> R.drawable.ic_calendar_days
        CLOCK -> R.drawable.ic_clock_3
        LIST_PLUS -> R.drawable.ic_list_plus
        ZAP -> R.drawable.ic_zap

        PLAY -> R.drawable.ic_play
        PAUSE -> R.drawable.ic_pause
        STOP -> R.drawable.ic_square
        MORE_HORIZONTAL -> R.drawable.ic_more_horizontal
        BELL -> R.drawable.ic_bell
        BELL_RING -> R.drawable.ic_bell_ring
        CIRCLE_DOT -> R.drawable.ic_circle_dot
        HISTORY -> R.drawable.ic_history
        ROTATE_CW -> R.drawable.ic_rotate_cw

        else -> error("No Lucide vector was imported for the icon `$name`")
    }

    /**
     * The glyphs the Activity Timer introduces, named as a group so its own test can walk them
     * without restating the list — and so [names] cannot be the place one of them is forgotten.
     */
    val timerNames: List<String> = listOf(
        PLAY,
        PAUSE,
        STOP,
        MORE_HORIZONTAL,
        BELL,
        BELL_RING,
        CIRCLE_DOT,
        HISTORY,
        ROTATE_CW,
    )

    /** Every name this app can draw. A test walks it so an unimported icon cannot ship. */
    val names: List<String> = listOf(
        ActivityIcons.TAB_ENTRY,
        ActivityIcons.TAB_PROGRESS,
        ActivityIcons.TAB_ACTIVITY,
        ActivityIcons.TAB_PROFILE,
        ActivityIcons.FOOTPRINTS,
        ActivityIcons.ROUTE,
        ActivityIcons.BIKE,
        ActivityIcons.DUMBBELL,
        ActivityIcons.SHAPES,
        ActivityIcons.TIMER,
        ActivityIcons.GAUGE,
        ActivityIcons.FLAME,
        ActivityIcons.TRENDING_UP,
        ActivityIcons.MAP_PIN,
        ActivityIcons.TREES,
        ActivityIcons.WRENCH,
        ActivityIcons.NOTEBOOK_PEN,
        ActivityIcons.PLUS,
        ActivityIcons.PLUS_CIRCLE,
        ActivityIcons.COPY_PLUS,
        ActivityIcons.SEARCH,
        ActivityIcons.SPARKLES,
        ActivityIcons.WAVES,
        ActivityIcons.SAILBOAT,
        ActivityIcons.ORBIT,
        ActivityIcons.MOUNTAIN,
        ActivityIcons.MOUNTAIN_SNOW,
        ActivityIcons.FLOWER,
        ActivityIcons.MUSIC,
        ActivityIcons.PERSON_STANDING,
        ActivityIcons.MOVE,
        ActivityIcons.VOLLEYBALL,
        ARROW_LEFT,
        CHEVRON_RIGHT,
        CHEVRON_UP,
        CHEVRON_DOWN,
        CLOSE,
        CHECK,
        TRASH,
        CALENDAR_DAYS,
        CLOCK,
        LIST_PLUS,
        ZAP,
    ) + timerNames
}

@Preview(name = "Icons", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueIconPreview() {
    MuePreviewHost {
        MueIcons.names.chunked(9).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { name ->
                    MueIcon(name, tint = MueTheme.colors.textPrimary, size = 24.dp)
                }
            }
        }
    }
}
