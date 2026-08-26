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
import androidx.compose.ui.platform.LocalContext
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

/**
 * Sépare le dernier contact de l'état de présence sur une seule ligne.
 *
 * **Volontairement pas dans `ScaleMessages`** : ce n'est pas une phrase adressée à quelqu'un, c'est
 * la mise en page d'une ligne qui en porte deux. Il vit donc à côté de [statusLine], la seule
 * fonction qui l'emploie. Le point médian de PRD_SCALE 18.5, lui, fait partie de trois citations
 * littérales et reste écrit dans les constantes qui le citent.
 */
private const val STATUS_SEPARATOR = " · "

/**
 * `Profile > Scales`, câblé (FR-SCALE-010, 013, PRD_SCALE 18.1, 18.5, FR-SCALE-025).
 *
 * Le repérage des balances à portée ne tourne que pendant que l'écran est ouvert (FR-SCALE-013) et
 * **seulement si le scan est possible**. Lire `rememberScalePermissions()` est entièrement passif :
 * rien ici n'ouvre de dialogue système, et les quatre gestes qui en ouvrent un sont passés à
 * [ScalesContent] sous forme de rappels que seul un appui déclenche (FR-SCALE-025, dernière phrase).
 *
 * La même condition sert deux fois, et c'est délibéré : `gate == ScanGate.READY` décide à la fois si
 * le scan de présence démarre et laquelle des phrases de PRD_SCALE 18.5 l'écran doit. Les lire
 * séparément — `canScan` d'un côté, [ScalePermissionsState.toScanGate] de l'autre — permettrait à un
 * écran de chercher en affirmant qu'il ne peut pas, ou l'inverse.
 *
 * Le **premier** appairage reste le seul endroit où Mue demande la permission (FR-SCALE-025) : on
 * n'arrive ici avec des balances enregistrées qu'après y être passé, donc la carte que cet écran
 * peut montrer est celle d'une permission *révoquée*, et son bouton ne fait que rouvrir une
 * question déjà posée une fois. Rien n'y est spontané : la carte explique, l'utilisateur touche, et
 * c'est seulement alors qu'un écran du système paraît.
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
    val context = LocalContext.current

    val gate = permissions.toScanGate()
    DisposableEffect(viewModel, gate) {
        if (gate == ScanGate.READY) viewModel.onScreenVisible()
        onDispose { if (gate == ScanGate.READY) viewModel.onScreenHidden() }
    }

    ScalesContent(
        state = state,
        gate = gate,
        onBack = onBack,
        onAddScale = onAddScale,
        onOpenScale = onOpenScale,
        onRequestPermission = permissions::request,
        onOpenSettings = { context.startActivity(permissions.appSettingsIntent) },
        onEnableBluetooth = { context.startActivity(permissions.enableBluetoothIntent) },
        onOpenLocationSettings = {
            context.startActivity(permissions.systemLocationSettingsIntent)
        },
        modifier = modifier,
    )
}

/**
 * La liste des balances, ou l'invitation à en associer une (PRD_SCALE 18.1, 18.5).
 *
 * L'état vide explique **ce qu'une balance apporte** avant de proposer `Add a scale`. Il n'annonce
 * rien de manquant : personne n'a tort de peser à la main, et la dernière phrase le dit.
 *
 * Renommer, oublier et consulter le diagnostic vivent sur la fiche d'une balance, pas ici : la
 * liste répond à « laquelle ? », la fiche à « qu'en faire ? ». C'est aussi ce qui évite deux
 * confirmations d'oubli composées en même temps pendant une transition.
 *
 * [gate] porte les deux lignes que PRD_SCALE 18.5 adresse nommément à cet écran — « Bluetooth
 * désactivé : `Scales` propose de l'activer », « permission refusée ou révoquée : `Scales` explique
 * la permission manquante ». La carte se pose **au-dessus** de la liste et ne la remplace jamais :
 * une radio éteinte n'efface pas les balances enregistrées, et le nom, le modèle et le dernier
 * contact de chacune se lisent exactement comme avant (FR-SCALE-013). Ce que la carte remplace est
 * l'état à portée, qui n'a plus de sens sans scan — voir [PairedScaleRow].
 *
 * Elle ne s'affiche pas sur l'état vide, et ce n'est pas un oubli : les trois phrases parlent de
 * « votre balance » et présupposent qu'il y en ait une, alors que PRD_SCALE 18.1 veut de cet écran,
 * sans balance, une invitation et rien d'autre — surtout pas le rapport d'une panne qui ne concerne
 * personne. Quelqu'un qui touche `Add a scale` rencontre la même carte un écran plus loin, à
 * l'endroit exact où FR-SCALE-025 met la demande.
 */
@Composable
internal fun ScalesContent(
    state: ScalesUiState,
    gate: ScanGate,
    onBack: () -> Unit,
    onAddScale: () -> Unit,
    onOpenScale: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
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
                    // PRD_SCALE 18.5 : ce qui empêche Mue de joindre ces balances, et le geste qui
                    // le lève. Au-dessus de la liste, jamais à sa place.
                    ScaleGateCard(
                        gate = gate,
                        onRequestPermission = onRequestPermission,
                        onOpenSettings = onOpenSettings,
                        onEnableBluetooth = onEnableBluetooth,
                        onOpenLocationSettings = onOpenLocationSettings,
                    )

                    MueText(ScaleMessages.YOUR_SCALES, MueTheme.typography.sectionTitle)
                    Column(
                        modifier = Modifier.testTag(ScaleTestTags.LIST),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        state.scales.forEach { scale ->
                            PairedScaleRow(
                                scale = scale,
                                presenceKnown = gate == ScanGate.READY,
                                onClick = { onOpenScale(scale.id) },
                            )
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
 *
 * @param presenceKnown Un scan tourne, donc « à portée » et « hors de portée » veulent dire quelque
 *   chose. À `false` — radio éteinte, permission absente, localisation système coupée — la ligne
 *   s'arrête au dernier contact. FR-SCALE-013 ne demande l'état de présence que « lorsque l'écran
 *   est ouvert », c'est-à-dire lorsqu'il est observé ; l'écrire `Not in range` sans rien observer
 *   ferait passer une radio éteinte pour une balance absente, ce qui est faux et, pire, oriente vers
 *   le mauvais geste.
 */
@Composable
private fun PairedScaleRow(scale: PairedScale, presenceKnown: Boolean, onClick: () -> Unit) {
    val spacing = MueTheme.spacing
    val locale = rememberMueLocale()
    MueSurfaceCard(
        modifier = Modifier.testTag(ScaleTestTags.row(scale.id)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
        onClick = onClick,
        onClickLabel = ScaleMessages.OPEN_THIS_SCALE,
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
                    text = scale.statusLine(locale, presenceKnown),
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
 *
 * @param presenceKnown Faux quand rien ne cherche : la ligne se réduit alors au dernier contact,
 *   qui reste vrai, plutôt que d'affirmer une absence que personne n'a constatée (PRD_SCALE 18.5).
 *   La carte posée au-dessus de la liste dit, elle, pourquoi il n'y a rien à constater.
 */
internal fun PairedScale.statusLine(locale: Locale, presenceKnown: Boolean = true): String {
    val lastSeen = if (lastSeenAt == null) {
        ScaleMessages.NEVER_CONNECTED
    } else {
        "${ScaleMessages.LAST_SEEN_LABEL} ${formatLastSeen(lastSeenAt, locale)}"
    }
    if (!presenceKnown) return lastSeen
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

@Composable
private fun ScalesPreview(state: ScalesUiState, gate: ScanGate) {
    MueTheme {
        ScalesContent(
            state = state,
            gate = gate,
            onBack = {},
            onAddScale = {},
            onOpenScale = {},
            onRequestPermission = {},
            onOpenSettings = {},
            onEnableBluetooth = {},
            onOpenLocationSettings = {},
        )
    }
}

@Preview(name = "Scales — list", widthDp = 390, heightDp = 720)
@Composable
private fun ScalesListPreview() {
    ScalesPreview(ScalesUiState(loading = false, scales = PreviewScales), ScanGate.READY)
}

@Preview(name = "Scales — empty", widthDp = 390, heightDp = 720)
@Composable
private fun ScalesEmptyPreview() {
    ScalesPreview(ScalesUiState(loading = false), ScanGate.READY)
}

/** PRD_SCALE 18.5 : la liste reste entière et lisible, et rien n'y prétend à une portée. */
@Preview(name = "Scales — Bluetooth off", widthDp = 390, heightDp = 720)
@Composable
private fun ScalesBluetoothOffPreview() {
    ScalesPreview(
        ScalesUiState(loading = false, scales = PreviewScales),
        ScanGate.BLUETOOTH_OFF,
    )
}
