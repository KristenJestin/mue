package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.theme.mueAmberGlow

/** Length of the ramp a scrolling screen dissolves into under the header. */
val MueContentTopFade: Dp = 24.dp

/**
 * Handles on the parts of the shell that are the same on every screen, so a test can measure that
 * they really are.
 *
 * Declared beside the component rather than in a screen's own tag file, for the reason
 * `ProgressTestTags` sits inside `ProgressScreen`: the tag belongs to whoever draws the node, and
 * every one of the five tabs draws this one.
 */
internal object MueScaffoldTestTags {

    /**
     * The top edge of everything below the header — the number that has to match across the tabs.
     *
     * It is the column itself and not the first thing in it, because the first thing in it is a
     * different composable on each of the five screens and several of them are inside a lazy list
     * that has not composed yet when the tab arrives.
     */
    const val CONTENT: String = "mue:scaffoldContent"

    /** The wordmark, which is the landmark the eye anchors on and therefore the one that must not move. */
    const val WORDMARK: String = "mue:wordmark"

    /**
     * The same edge on a screen reached *from* a tab, so the seam between the two can be measured.
     *
     * A distinct name rather than [CONTENT] on both scaffolds: the two are on screen together for
     * the length of a push, and a matcher that could not tell them apart would fail on the
     * ambiguity rather than on the geometry it was written for.
     */
    const val SUB_SCREEN_CONTENT: String = "mue:subScreenContent"
}

/**
 * Shared shell of the three screens: canvas, amber glow bleeding from the top edge, the
 * `MUE` wordmark with a contextual [trailing] slot, then the content column already gutted
 * to the screen padding.
 *
 * A screen whose content scrolls passes [topFade] so that content leaves under the header
 * through a short ramp; without it the first pixels below the wordmark are cut straight
 * across and the screen reads as truncated rather than scrolled. Entry does not scroll and
 * leaves it at zero.
 *
 * [header] is a band between the wordmark and the content, drawn **edge to edge** and outside
 * [topFade]: a control that belongs to the whole screen rather than to what it scrolls. The Food
 * tab's view switcher is what asked for it — a rail that has to be able to scroll past the gutter,
 * and that must not be the thing dissolving under the ramp meant for the content below it. It is
 * null on every other screen, which is why nothing else in the app moved when it landed.
 *
 * The bottom tab bar is intentionally *not* part of this scaffold: it lives above the
 * navigation host so it never moves during a tab transition (PRD 8).
 */
@Composable
fun MueScreenScaffold(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
    trailing: @Composable (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    topFade: Dp = 0.dp,
    header: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MueTheme.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MueTheme.colors.canvas)
            .mueAmberGlow(),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = spacing.screenTop,
                        bottom = spacing.sm,
                    )
                    /*
                     * The touch minimum, and not the 40 dp the wordmark alone needs.
                     *
                     * ## Why it was raised
                     *
                     * This row is the app's one fixed landmark: `MUE` is drawn at the same place
                     * on all five tabs, so the eye anchors on it and reads any movement of it as
                     * the page lurching. At 40 dp that only held while every [trailing] control
                     * was a chip. The Food catalogue's settings control was a button, so it was
                     * `MueMinTouchTarget` tall as PRD 15 requires of anything tappable, and a
                     * 48 dp child in a 40 dp row grows the row: the wordmark dropped 4 dp and
                     * everything under it dropped 8, on that screen and no other — "le bouton
                     * settings dans food pousse toujours tout le truc, et du coup c'est pas beau
                     * au switch de tab parce que tout le tab se déplace".
                     *
                     * ## Why it stays now that the button is gone
                     *
                     * That control has since moved to `Profile`, so no trailing slot in the app
                     * is taller than a chip today and the five tabs would agree at 40 dp again.
                     * The floor is **not** reverted, for a reason that never depended on it and
                     * that the raise fixed by accident:
                     * [MueSubScreenScaffold]'s header row is a 48 dp back control between the
                     * same paddings, so at 40 dp a tab and a screen reached *from* a tab opened
                     * their content 8 dp apart — and every push out of Food, Activity or Profile
                     * stepped the page in exactly the way the complaint was about, only in the
                     * other direction. At the touch minimum the two are the same height and the
                     * seam is invisible.
                     *
                     * It is also what keeps the next trailing control from pushing anything: the
                     * tallest one this row may legally hold is the height it already is. The four
                     * tabs carrying a chip pay 8 dp for that, once, in a place where 8 dp of air
                     * above the title is not a defect.
                     *
                     * `MueTabHeaderAlignmentTest` measures the five tabs and fails if they part;
                     * `aSubScreenOpensItsContentWhereATabDoes` measures the seam and is the one
                     * that fails if this line goes back to 40.
                     */
                    .heightIn(min = MueMinTouchTarget),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MueText(
                    text = "MUE",
                    style = MueTheme.typography.wordmark,
                    color = MueTheme.colors.textPrimary,
                    // Without this TalkBack spells the three letters out.
                    modifier = Modifier
                        .testTag(MueScaffoldTestTags.WORDMARK)
                        .semantics { contentDescription = "Mue" },
                )
                trailing?.invoke()
            }

            header?.invoke()

            Column(
                modifier = Modifier
                    /*
                     * Before the padding and before the fade, so the tag reports where the
                     * content column *begins* rather than where its gutter does. The one thing
                     * `MueTabHeaderAlignmentTest` needs is a handle on this edge, and it cannot
                     * come from a string: what sits at the top of the column differs on every
                     * tab, and a control faded to nothing is still in the semantics tree with
                     * all of its text.
                     */
                    .testTag(MueScaffoldTestTags.CONTENT)
                    .fillMaxWidth()
                    .weight(1f)
                    .topFade(topFade)
                    .padding(horizontal = horizontalPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }
}

/**
 * Dissolves the top [height] of the content instead of cutting it.
 *
 * The alpha of the content itself is masked rather than a scrim being painted over it, so
 * the amber glow behind the screen keeps showing through the ramp at full strength.
 */
private fun Modifier.topFade(height: Dp): Modifier {
    if (height <= 0.dp) return this
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = height.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/** Eyebrow plus title block that opens each screen. [eyebrow] is hidden when null. */
@Composable
fun MueScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        eyebrow?.let {
            MueText(it, MueTheme.typography.eyebrow, color = MueTheme.colors.textSecondary)
        }
        MueText(
            text = title,
            style = MueTheme.typography.screenTitle,
            color = MueTheme.colors.textPrimary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Preview(name = "Screen scaffold", showBackground = true, backgroundColor = 0xFF101012, heightDp = 520)
@Composable
private fun MueScreenScaffoldPreview() {
    MueTheme {
        MueScreenScaffold(trailing = { MueHeaderChip("Today") }) {
            MueScreenTitle(
                title = "Where are you today?",
                eyebrow = "Hello Kris,",
                modifier = Modifier.padding(top = MueTheme.spacing.xl),
            )
            MueAnimatedNumber(
                text = "74.5",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MueTheme.spacing.xxl),
                suffix = "kg",
                horizontalArrangement = Arrangement.Center,
            )
            MueText(
                text = "SLIDE TO ADJUST",
                style = MueTheme.typography.hint,
                color = MueTheme.colors.accent,
                modifier = Modifier.fillMaxWidth().padding(top = MueTheme.spacing.md),
            )
        }
    }
}
