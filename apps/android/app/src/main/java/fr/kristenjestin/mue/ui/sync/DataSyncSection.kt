package fr.kristenjestin.mue.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueValueChip
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.ZoneId
import java.util.Locale

/**
 * Sync PRD 9.1's `Data & sync`, drawn inside `Profile`.
 *
 * Everything the section names is here and nothing else is anywhere: the state, the server, the
 * last successful synchronisation, the count of local changes when it is not zero, `Sync now` and
 * `Server settings`. PRD 9.1 ends with "L'absence de serveur associé n'affiche aucune alerte sur
 * les écrans principaux", so no other screen in the app gained a badge, a banner or a dot — the
 * one place a missing server is mentioned is this card, and only because it is the card you open
 * to ask.
 *
 * ## The status line is one sentence, not three words
 *
 * `Synced · mue.home.arpa · Last synced 26 August at 21:04` is a single fact. Read as three
 * fragments it separates the state from the server it is about, so the row publishes a content
 * description and hides its children — which is also why [SyncTestTags.STATUS_LINE] exists and
 * why no test may query for `Synced` inside it.
 *
 * ## The layout
 *
 * [MueSplitRow] rather than `Row` + `weight(1f)`: the chip on the right doubles with the font
 * scale, and a plain row measures the unweighted child first and hands the label whatever is
 * left — which at scale 2.0 is a ribbon, and the server name comes out broken mid-word. The split
 * row measures both and drops the chip onto its own line when they no longer fit.
 */
@Composable
internal fun DataSyncSection(
    state: DataSyncUiState,
    onSyncNow: () -> Unit,
    onOpenServerSettings: () -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = rememberMueLocale(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors

    MueSurfaceCard(
        modifier = modifier.testTag(SyncTestTags.SECTION),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
    ) {
        SyncStatusLine(state = state, locale = locale, zone = zone)

        if (!state.connected) {
            MueText(
                text = SyncMessages.NOT_CONNECTED_BODY,
                style = MueTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        Column(
            modifier = Modifier.padding(top = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            // PRD 9.1 shows the count only when it is not zero, and every line below is the
            // reason a number is what it is rather than a repeat of it.
            SyncMessages.outstanding(state.outstandingChanges)?.let { line ->
                MueText(line, MueTheme.typography.caption, color = colors.textPrimary)
            }
            SyncMessages.refused(state.refusedChanges)?.let { line ->
                MueText(line, MueTheme.typography.caption, color = colors.error)
            }
            SyncMessages.undeliverable(state.undeliverableChanges)?.let { line ->
                MueText(line, MueTheme.typography.caption, color = colors.textTertiary)
            }
            // FR-SYNC-008: the engine's own last words, shown rather than summarised. A phone
            // that cannot reach its server away from home says so here and nowhere else.
            state.lastErrorMessage
                ?.takeIf { state.status == SyncStatus.SYNC_ISSUE }
                ?.let { message ->
                    MueText(message, MueTheme.typography.caption, color = colors.textSecondary)
                }
        }

        state.syncNote?.let { note ->
            StatusLine(
                message = note.message,
                color = if (note.isProblem) colors.error else colors.accent,
                assertive = note.isProblem,
            )
        }

        MueSecondaryButton(
            label = if (state.syncing) SyncMessages.CONNECTING else SyncMessages.SYNC_NOW,
            onClick = onSyncNow,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(SyncTestTags.SYNC_NOW),
            // `Sync now` with no server would run an engine that returns `NotPaired` and change
            // nothing. It stays on screen because PRD 9.1 lists it, and it is inert because
            // pressing it would be theatre.
            enabled = state.connected && !state.syncing,
        )

        MueSecondaryButton(
            label = SyncMessages.SERVER_SETTINGS,
            onClick = onOpenServerSettings,
            modifier = Modifier
                .padding(top = spacing.sm)
                .testTag(SyncTestTags.SERVER_SETTINGS),
        )
    }
}

/**
 * The state, the server and the last success — one row, one announcement.
 *
 * The chip carries the state word and sits on the right where the eye finds a value; the server
 * and the timestamp read down the left. At a large font scale the chip drops beneath them rather
 * than squeezing the hostname, which is [MueSplitRow]'s whole reason for existing.
 */
@Composable
private fun SyncStatusLine(state: DataSyncUiState, locale: Locale, zone: ZoneId) {
    val colors = MueTheme.colors
    val statusLabel = SyncMessages.label(state.status)
    val serverName = state.serverName ?: SyncMessages.NO_SERVER
    val lastSync = SyncMessages.lastSync(state.lastSuccessAt, locale, zone)

    MueSplitRow(
        modifier = Modifier
            .testTag(SyncTestTags.STATUS_LINE)
            // `clearAndSetSemantics` and not `semantics(mergeDescendants = true)`: the second
            // leaves the children's own text in the tree beside the description, so the row is
            // read twice and a test can match either. This is the app's `announcedAs` idiom —
            // one node, one sentence, no descendants in either tree.
            .clearAndSetSemantics {
                contentDescription = if (state.connected) {
                    "$statusLabel. $serverName. $lastSync."
                } else {
                    "$statusLabel. $serverName."
                }
                // The state is the section's headline, and it changes under the user while they
                // watch a `Sync now`. Polite, never assertive: PRD 9.4 forbids a repeated alarm.
                liveRegion = LiveRegionMode.Polite
            },
        gap = 12.dp,
        stackedGap = 8.dp,
        start = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MueText(
                    text = serverName,
                    style = MueTheme.typography.bodyStrong,
                    color = colors.textPrimary,
                )
                if (state.connected) {
                    MueText(lastSync, MueTheme.typography.caption, color = colors.textSecondary)
                }
            }
        },
        end = {
            MueValueChip(
                text = statusLabel,
                container = statusContainer(state.status),
                contentColor = statusContent(state.status),
            )
        },
    )
}

@Composable
private fun statusContainer(status: SyncStatus): Color = when (status) {
    SyncStatus.SYNCED -> MueTheme.colors.accentSoft
    SyncStatus.CHANGES_PENDING -> MueTheme.colors.surfaceStrong
    // Not a filled red alarm: FR-SYNC-008 makes an unreachable server an ordinary state away
    // from home, so the word is coloured and the badge is not shouted.
    SyncStatus.SYNC_ISSUE -> MueTheme.colors.surfaceStrong
    SyncStatus.NOT_CONNECTED -> MueTheme.colors.surfaceStrong
}

@Composable
private fun statusContent(status: SyncStatus): Color = when (status) {
    SyncStatus.SYNCED -> MueTheme.colors.onAccentSoft
    SyncStatus.CHANGES_PENDING -> MueTheme.colors.textPrimary
    SyncStatus.SYNC_ISSUE -> MueTheme.colors.error
    SyncStatus.NOT_CONNECTED -> MueTheme.colors.textSecondary
}

/** A one-line outcome under an action; announced so it is not missed without sight. */
@Composable
internal fun StatusLine(message: String, color: Color, assertive: Boolean = false) {
    MueText(
        text = message,
        style = MueTheme.typography.caption,
        color = color,
        modifier = Modifier
            .padding(top = MueTheme.spacing.sm, start = 4.dp, end = 4.dp)
            .semantics {
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
    )
}

private const val PreviewInstant = 1_756_240_000_000L

@Composable
private fun SectionPreview(state: DataSyncUiState) {
    MueTheme {
        DataSyncSection(
            state = state,
            onSyncNow = {},
            onOpenServerSettings = {},
            locale = Locale.UK,
            zone = ZoneId.of("Europe/Paris"),
        )
    }
}

@Preview(name = "Data & sync — not connected", widthDp = 390)
@Composable
private fun DataSyncNotConnectedPreview() {
    SectionPreview(DataSyncUiState())
}

@Preview(name = "Data & sync — synced", widthDp = 390)
@Composable
private fun DataSyncSyncedPreview() {
    SectionPreview(
        DataSyncUiState(
            status = SyncStatus.SYNCED,
            serverName = "mue.home.arpa",
            lastSuccessAt = PreviewInstant,
        ),
    )
}

@Preview(name = "Data & sync — changes pending", widthDp = 390)
@Composable
private fun DataSyncPendingPreview() {
    SectionPreview(
        DataSyncUiState(
            status = SyncStatus.CHANGES_PENDING,
            serverName = "mue.home.arpa",
            lastSuccessAt = PreviewInstant,
            outstandingChanges = 4,
            undeliverableChanges = 1,
        ),
    )
}

@Preview(name = "Data & sync — sync issue", widthDp = 390)
@Composable
private fun DataSyncIssuePreview() {
    SectionPreview(
        DataSyncUiState(
            status = SyncStatus.SYNC_ISSUE,
            serverName = "mue.home.arpa",
            lastSuccessAt = PreviewInstant,
            outstandingChanges = 3,
            refusedChanges = 1,
            lastErrorMessage = "The server could not be reached.",
        ),
    )
}
