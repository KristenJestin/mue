package fr.kristenjestin.mue.ui.scale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.Instant
import java.util.Locale

/** Sépare le dernier contact de l'état de présence sur une seule ligne. */
private const val STATUS_SEPARATOR = " · "

/** Manque à `ScaleMessages` : ce que fait l'ouverture d'une ligne, pour l'accessibilité. */
private const val OPEN_SCALE_LABEL = "Open this scale"

/** Manque à `ScaleMessages` : le titre de la liste, sous le nom de l'écran. */
private const val PAIRED_SCALES_TITLE = "Your scales"

/**
 * `Profile > Scales`, câblé (FR-SCALE-010, 013, PRD_SCALE 18.1).
 *
 * Le repérage des balances à portée ne tourne que pendant que l'écran est ouvert (FR-SCALE-013) et
 * **seulement si le scan est possible** : lire `rememberScalePermissions()` est passif, et rien ici
 * n'ouvre de dialogue système. Sans permission, sans radio ou sans localisation système, la liste
 * s'affiche entière et chaque balance se lit `Not in range` — ce qui est l'état normal d'une
 * balance endormie et n'est jamais présenté comme une anomalie (PRD_SCALE 18.2). La permission se
 * demande au premier appairage, un écran plus loin (FR-SCALE-025).
 */
@Composable
internal fun ScalesScreen(
    onBack: () -> Unit,
    onAddScale: () -> Unit,
    onOpenScale: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = scalesViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions = rememberScalePermissions()

    val canScan = permissions.canScan
    DisposableEffect(viewModel, canScan) {
        if (canScan) viewModel.onScreenVisible()
        onDispose { if (canScan) viewModel.onScreenHidden() }
    }

    ScalesContent(
        state = state,
        onBack = onBack,
        onAddScale = onAddScale,
        onOpenScale = onOpenScale,
        modifier = modifier,
    )
}

/**
 * La liste des balances, ou l'invitation à en associer une (PRD_SCALE 18.1).
 *
 * L'état vide explique **ce qu'une balance apporte** avant de proposer `Add a scale`. Il n'annonce
 * rien de manquant : personne n'a tort de peser à la main, et la dernière phrase le dit.
 *
 * Renommer, oublier et consulter le diagnostic vivent sur la fiche d'une balance, pas ici : la
 * liste répond à « laquelle ? », la fiche à « qu'en faire ? ». C'est aussi ce qui évite deux
 * confirmations d'oubli composées en même temps pendant une transition.
 */
@Composable
internal fun ScalesContent(
    state: ScalesUiState,
    onBack: () -> Unit,
    onAddScale: () -> Unit,
    onOpenScale: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    MueSubScreenScaffold(
        title = ScaleMessages.SCALES,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Spacer(Modifier.height(spacing.md))

            when {
                // Rien tant qu'on ne sait pas : proposer `Add a scale` à quelqu'un qui en a trois,
                // le temps d'une image, est une invitation adressée au mauvais lecteur.
                state.loading -> Unit

                state.isEmpty -> ScalesEmptyState(onAddScale = onAddScale)

                else -> {
                    MueText(PAIRED_SCALES_TITLE, MueTheme.typography.sectionTitle)
                    Column(
                        modifier = Modifier.testTag(ScaleTestTags.LIST),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        state.scales.forEach { scale ->
                            PairedScaleRow(scale = scale, onClick = { onOpenScale(scale.id) })
                        }
                    }
                    MueSecondaryButton(
                        label = ScaleMessages.ADD_A_SCALE,
                        onClick = onAddScale,
                        modifier = Modifier.testTag(ScaleTestTags.ADD_SCALE),
                    )
                }
            }

            Spacer(Modifier.height(spacing.xxxl))
        }
    }
}

/**
 * PRD_SCALE 18.1 : ce qu'une balance apporte, et l'action qui en associe une.
 *
 * Le second paragraphe est le plus important des deux — « peser à la main fonctionne exactement
 * comme aujourd'hui » — parce qu'un écran qui vante un accessoire à quelqu'un qui ne l'a pas doit
 * d'abord lui dire qu'il ne lui manque rien (PRD_SCALE 7.1).
 */
@Composable
private fun ScalesEmptyState(onAddScale: () -> Unit) {
    val spacing = MueTheme.spacing
    MueSurfaceCard(
        modifier = Modifier.testTag(ScaleTestTags.EMPTY_STATE),
        contentPadding = PaddingValues(spacing.cardPadding),
    ) {
        MueText(ScaleMessages.SCALES_EMPTY_TITLE, MueTheme.typography.sectionTitle)
        MueText(
            text = ScaleMessages.SCALES_EMPTY_BODY,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.sm),
        )
        MuePrimaryButton(
            label = ScaleMessages.ADD_A_SCALE,
            onClick = onAddScale,
            modifier = Modifier
                .padding(top = spacing.lg)
                .testTag(ScaleTestTags.ADD_SCALE),
        )
    }
}

/**
 * Une balance dans la liste : le nom donné, le modèle reconnu, le dernier contact et la présence
 * (FR-SCALE-013).
 *
 * La ligne d'état est annoncée poliment plutôt qu'à chaque changement de trame : une balance qui
 * entre à portée pendant qu'on lit l'écran est une information, un commentaire continu ne l'est pas
 * (PRD_SCALE 20). Elle porte les deux faits en toutes lettres, sans couleur ni pastille : aucune
 * information n'est portée par la seule couleur.
 */
@Composable
private fun PairedScaleRow(scale: PairedScale, onClick: () -> Unit) {
    val spacing = MueTheme.spacing
    val locale = rememberMueLocale()
    MueSurfaceCard(
        modifier = Modifier.testTag(ScaleTestTags.row(scale.id)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
        onClick = onClick,
        onClickLabel = OPEN_SCALE_LABEL,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(scale.displayName, MueTheme.typography.bodyStrong)
                MueText(
                    text = scale.modelName,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
                MueText(
                    text = scale.statusLine(locale),
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                    modifier = Modifier
                        .padding(top = spacing.xs)
                        .testTag(ScaleTestTags.rowStatus(scale.id))
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            MueIcon(
                iconName = MueIcons.CHEVRON_RIGHT,
                tint = MueTheme.colors.textTertiary,
                size = 18.dp,
            )
        }
    }
}

/**
 * `Last seen 23 August 2026 · Not in range`, ou `Never connected · In range`.
 *
 * Les deux faits tiennent sur une ligne parce qu'ils répondent à la même question — « est-ce que
 * Mue peut la joindre, et sinon quand l'a-t-elle jointe pour la dernière fois ? ». `Last seen` ne
 * précède jamais `Never connected`, qui est déjà une phrase.
 */
internal fun PairedScale.statusLine(locale: Locale): String {
    val lastSeen = if (lastSeenAt == null) {
        ScaleMessages.NEVER_CONNECTED
    } else {
        "${ScaleMessages.LAST_SEEN_LABEL} ${formatLastSeen(lastSeenAt, locale)}"
    }
    val presence = if (inRange) ScaleMessages.IN_RANGE else ScaleMessages.NOT_IN_RANGE
    return lastSeen + STATUS_SEPARATOR + presence
}

private val PreviewScales: List<PairedScale> = listOf(
    PairedScale(
        id = "a",
        displayName = "Bathroom scale",
        modelName = "Homebuds HB9027",
        driverId = "homebuds-hb9027",
        address = "FF:10:00:1F:52:C3",
        advertisedName = "Health Scale",
        lastSeenAt = Instant.parse("2026-08-25T07:12:00Z"),
        inRange = true,
    ),
    PairedScale(
        id = "b",
        displayName = "Downstairs",
        modelName = "Homebuds HB9027",
        driverId = "homebuds-hb9027",
        address = "FF:10:00:1F:52:C4",
        advertisedName = "Health Scale",
        lastSeenAt = null,
        inRange = false,
    ),
)

@Preview(name = "Scales — list", widthDp = 390, heightDp = 720)
@Composable
private fun ScalesListPreview() {
    MueTheme {
        ScalesContent(
            state = ScalesUiState(loading = false, scales = PreviewScales),
            onBack = {},
            onAddScale = {},
            onOpenScale = {},
        )
    }
}

@Preview(name = "Scales — empty", widthDp = 390, heightDp = 720)
@Composable
private fun ScalesEmptyPreview() {
    MueTheme {
        ScalesContent(
            state = ScalesUiState(loading = false),
            onBack = {},
            onAddScale = {},
            onOpenScale = {},
        )
    }
}
