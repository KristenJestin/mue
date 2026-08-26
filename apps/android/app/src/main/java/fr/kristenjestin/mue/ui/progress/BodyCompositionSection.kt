package fr.kristenjestin.mue.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import fr.kristenjestin.mue.domain.logic.BodyCompositionFormula
import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.ui.components.MueAnimatedNumber
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueHeaderChip
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

/*
 * La section de composition corporelle de l'écran `Progress` (PRD_SCALE FR-BODY-003, FR-BODY-005,
 * FR-BODY-006, 18.4).
 *
 * Ce qu'elle ne dessine pas, et pourquoi c'est le point. Pas de catégorie, pas de seuil, pas de
 * code couleur de normalité, et surtout AUCUNE barre de référence à repère — y compris sans
 * libellé de zone. FR-BODY-003 est explicite : une barre place une valeur sur une échelle de
 * normalité, c'est une catégorie déguisée, et elle tombe sous la même interdiction. La voisine
 * immédiate de ces cartes, `MueBmiCard`, en dessine une ; c'est exactement pourquoi la remarque
 * mérite d'être écrite ici plutôt que supposée. Le style de cette section lui est emprunté, sa
 * barre non.
 *
 * La raison n'est pas esthétique. L'erreur type publiée de l'équation de masse maigre est de
 * 3,17 kg (`BodyCompositionFormula.STANDARD_ERROR_KG`) : deux personnes ayant la même lecture
 * peuvent réellement différer d'autant. Un repère sur une échelle prétendrait situer la personne
 * à une précision que le nombre n'a pas. Un écart, lui, partage l'erreur systématique des deux
 * mesures et en annule une partie — c'est pourquoi FR-BODY-003 l'autorise, seul.
 *
 * Lisible sans couleur (PRD_SCALE 20). L'écart porte son signe en toutes lettres, `+` ou `−`, et
 * se dessine dans la même encre qu'il soit positif ou négatif. Rien dans cette section n'est
 * porté par la seule couleur.
 */

/**
 * Le chapeau de la section : le titre, la prudence courte, la date de la valeur affichée et
 * l'accès au texte détaillé.
 *
 * **La date est ici et non répétée sur les quatre cartes.** Elle vaut pour les quatre — elles
 * viennent de la même composition — et l'écrire quatre fois ferait du bruit là où FR-BODY-005 ne
 * demande qu'une chose : qu'elle reste visible, pour que la valeur ne passe pas pour la dernière
 * pesée de poids. Chaque carte la reprend malgré tout dans son annonce d'accessibilité, parce
 * qu'une carte lue seule par un lecteur d'écran perdrait l'en-tête (PRD_SCALE 20).
 */
@Composable
internal fun BodyCompositionHeader(
    state: BodyCompositionUiState,
    onShowDetailedCaution: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    Column(modifier = modifier.fillMaxWidth().testTag(ScaleTestTags.COMPOSITION_SECTION)) {
        MueText(ScaleMessages.BODY_COMPOSITION, type.sectionTitle)

        MueText(
            text = ScaleMessages.ESTIMATES_CAUTION,
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )

        state.latest?.let { composition ->
            MueText(
                text = BodyCompositionMessages.measuredOn(ProgressFormat.date(composition.date)),
                style = type.micro,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        // Le texte détaillé de PRD_SCALE 13.3, à un geste. Un lien plutôt qu'un bouton pleine
        // largeur : la prudence accompagne la lecture, elle ne la précède pas.
        MueText(
            text = BodyCompositionMessages.HOW_ESTIMATED,
            style = type.caption,
            color = colors.accent,
            modifier = Modifier
                .padding(top = spacing.xs)
                .heightIn(min = MueMinTouchTarget)
                .clickable(role = Role.Button, onClick = onShowDetailedCaution)
                .testTag(ProgressTestTags.COMPOSITION_CAUTION),
        )
    }
}

/**
 * Une des quatre cartes de FR-BODY-003 : le libellé, la mention d'estimation, la valeur avec son
 * unité, et l'écart signé avec la composition précédente de la période.
 *
 * Toutes les quatre partagent ce gabarit ; ce qui les distingue vit dans [BodyCompositionMetric].
 * Une carte écrite par grandeur finirait par en voir une recevoir un jour ce que les autres n'ont
 * pas.
 *
 * `MueSurfaceCard` et non `MueAccentCard` : l'ambre est déjà pris par l'IMC juste au-dessus, et
 * quatre cartes ambre en enfilade en feraient l'élément dominant d'un écran dont le sujet reste le
 * poids (PRD_SCALE 19).
 */
@Composable
internal fun BodyCompositionCard(
    metric: BodyCompositionMetric,
    state: BodyCompositionUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    val value = metric.value(state.latest)
    val change = metric.change(state.latest, state.previous)

    MueSurfaceCard(modifier = modifier.testTag(metric.testTag)) {
        MueSplitRow(
            modifier = Modifier.fillMaxWidth(),
            gap = spacing.md,
            verticalAlignment = Alignment.Top,
            start = { MueText(metric.label, type.label, color = colors.textTertiary) },
            // FR-BODY-003 : toute grandeur dérivée dit qu'elle est une estimation. Le mot est
            // dans la carte, pas seulement dans le texte de prudence du chapeau, parce qu'une
            // carte se lit isolément.
            end = { MueHeaderChip(ScaleMessages.ESTIMATE) },
        )

        MueAnimatedNumber(
            text = value,
            style = type.metricLarge,
            suffix = metric.unit,
            suffixStyle = type.caption,
            durationMillis = MueMotion.PeriodChangeMillis,
            contentDescription = valueDescription(metric, value, state.latest),
            modifier = Modifier.padding(top = spacing.sm),
        )

        MueDivider(modifier = Modifier.padding(top = spacing.md))

        MueSplitRow(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
            gap = spacing.md,
            start = {
                MueText(
                    text = state.previous
                        ?.let { BodyCompositionMessages.changeSince(ProgressFormat.date(it.date)) }
                        ?: BodyCompositionMessages.CHANGE_LABEL,
                    style = type.label,
                    color = colors.textTertiary,
                )
            },
            end = {
                MueText(
                    text = change,
                    style = type.bodyStrong,
                    // La même encre quel que soit le signe : PRD_SCALE 20 interdit qu'une
                    // information soit portée par la seule couleur, et un écart teinté en
                    // vert ou en rouge serait aussi un jugement, que FR-BODY-003 refuse.
                    color = colors.textPrimary,
                    modifier = Modifier.semantics {
                        contentDescription = changeDescription(metric, change, state.previous)
                    },
                )
            },
        )
    }
}

/**
 * FR-BODY-001 et PRD_SCALE 18.4 : le profil est complet mais sort du domaine de l'équation.
 *
 * Une phrase, reprise mot pour mot du PRD, et rien d'autre : **ni l'IMC, ni l'âge**, qui
 * transformeraient une limite de validité en verdict, et **aucune suggestion de modifier les
 * données**, qui reviendrait à demander de mentir pour obtenir un chiffre.
 */
@Composable
internal fun EstimatesUnavailableCard(modifier: Modifier = Modifier) {
    MueSurfaceCard(modifier = modifier.testTag(ProgressTestTags.COMPOSITION_UNAVAILABLE)) {
        MueText(
            text = ScaleMessages.ESTIMATES_UNAVAILABLE,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
        )
    }
}

/**
 * PRD_SCALE 18.4 : ce qui manque au profil, nommément, et l'accès à `Profile`.
 *
 * Le message ne parle pas que de l'avenir. L'impédance déjà mesurée a été conservée
 * (FR-BODY-004), donc compléter son profil rouvre aussi le passé — c'est la promesse que
 * FR-BODY-006 tiendra ensuite, et la taire ferait passer la demande pour une formalité sans
 * bénéfice immédiat.
 *
 * Aucun blocage, aucune saisie imposée : un bouton qui ouvre un écran, que l'utilisateur peut
 * ignorer indéfiniment sans que rien d'autre ne change.
 */
@Composable
internal fun IncompleteProfileCard(
    missing: Set<BodyCompositionResult.ProfileInput>,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    MueSurfaceCard(modifier = modifier.testTag(ScaleTestTags.INCOMPLETE_PROFILE)) {
        MueText(ScaleMessages.PROFILE_INCOMPLETE_TITLE, MueTheme.typography.sectionTitle)
        MueText(
            text = BodyCompositionMessages.profileIncompleteBody(missing),
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.sm),
        )
        MueSecondaryButton(
            label = ScaleMessages.OPEN_PROFILE,
            onClick = onOpenProfile,
            modifier = Modifier.padding(top = spacing.lg),
        )
    }
}

/**
 * FR-BODY-006 : la proposition de compléter les pesées passées.
 *
 * **Proposée, jamais silencieuse.** Ce bouton crée des données de santé pour des dates passées ;
 * l'utilisateur doit savoir d'où elles viennent avant de l'appuyer, pas après. D'où le compte
 * exact — PRD_SCALE 18.4 exige de dire *combien* — et l'explication qui rend visible
 * l'approximation assumée : la taille et le sexe employés sont ceux d'aujourd'hui, faute
 * d'historique de profil, tandis que l'âge vient de la date de chaque pesée.
 *
 * Sans aucune pesée à compléter, cette carte n'est pas composée du tout (voir
 * [BodyCompositionUiState.showRetroactiveProposal]) : une proposition annonçant zéro serait une
 * sollicitation sans objet.
 */
@Composable
internal fun RetroactiveProposalCard(
    count: Int,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    MueSurfaceCard(modifier = modifier.testTag(ScaleTestTags.RETROACTIVE_PROPOSAL)) {
        MueText(
            text = ScaleMessages.pastWeighInsToComplete(count),
            style = MueTheme.typography.sectionTitle,
        )
        MueText(
            text = ScaleMessages.RETROACTIVE_EXPLANATION,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.sm),
        )
        MueSecondaryButton(
            label = ScaleMessages.COMPLETE_PAST_WEIGH_INS,
            onClick = onComplete,
            modifier = Modifier
                .padding(top = spacing.lg)
                .testTag(ScaleTestTags.RETROACTIVE_CONFIRM),
        )
    }
}

/**
 * Le texte de prudence détaillé de PRD_SCALE 13.3, tel que [BodyCompositionFormula] l'écrit.
 *
 * Rien n'est reformulé ici : les paragraphes viennent du domaine, à côté des constantes qu'ils
 * commentent, pour qu'une correction d'un coefficient et la phrase qui le décrit ne puissent pas
 * dériver l'une de l'autre.
 */
@Composable
internal fun DetailedCautionSheet(visible: Boolean, onDismissRequest: () -> Unit) {
    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = BodyCompositionMessages.HOW_ESTIMATED,
        bodyScrolls = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProgressTestTags.COMPOSITION_CAUTION_SHEET),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            BodyCompositionFormula.DETAILED_CAUTION_PARAGRAPHS.forEach { paragraph ->
                MueText(
                    text = paragraph,
                    style = MueTheme.typography.body,
                    color = MueTheme.colors.textSecondary,
                )
            }
        }
    }
}

/** PRD_SCALE 20 : ce qu'un lecteur d'écran dit de la valeur principale d'une carte. */
private fun valueDescription(
    metric: BodyCompositionMetric,
    value: String,
    latest: BodyComposition?,
): String = latest
    ?.let { BodyCompositionMessages.valueDescription(metric, value, ProgressFormat.date(it.date)) }
    ?: BodyCompositionMessages.valueUnavailableDescription(metric)

/** PRD_SCALE 20 : et de son écart, qui sans sa date ne dirait pas sur quoi il porte. */
private fun changeDescription(
    metric: BodyCompositionMetric,
    change: String,
    previous: BodyComposition?,
): String = previous
    ?.let { BodyCompositionMessages.changeDescription(metric, change, ProgressFormat.date(it.date)) }
    ?: BodyCompositionMessages.NO_PREVIOUS_DESCRIPTION

// region Previews

private val PreviewDate: LocalDate = LocalDate.of(2026, 8, 20)

private fun previewComposition(
    date: LocalDate,
    bodyFatDeciPercent: Int,
    fatFreeMassCg: Int,
    bodyWaterDeciPercent: Int,
    restingEnergyKcal: Int,
) = BodyComposition(
    date = date,
    formulaId = BodyCompositionFormula.ID,
    formulaVersion = BodyCompositionFormula.VERSION,
    inputWeightCg = 7_450,
    inputHeightCm = 178,
    inputAgeYears = 36,
    inputSex = Sex.MALE,
    bodyFatDeciPercent = bodyFatDeciPercent,
    fatFreeMassCg = fatFreeMassCg,
    bodyWaterDeciPercent = bodyWaterDeciPercent,
    restingEnergyKcal = restingEnergyKcal,
)

private val PreviewState = BodyCompositionUiState(
    latest = previewComposition(PreviewDate, 214, 5_855, 456, 1_688),
    previous = previewComposition(PreviewDate.minusDays(7), 221, 5_812, 452, 1_692),
    hasHistory = true,
    missingProfileInputs = emptySet(),
    isOutOfDomain = false,
    hasPairedScale = true,
    retroactiveCount = 0,
)

@Preview(name = "Body composition", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun BodyCompositionPreview() {
    MueTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MueTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            BodyCompositionHeader(state = PreviewState, onShowDetailedCaution = {})
            BodyCompositionMetric.entries.forEach { metric ->
                BodyCompositionCard(metric = metric, state = PreviewState)
            }
        }
    }
}

@Preview(name = "Body composition — empty period", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun BodyCompositionEmptyPeriodPreview() {
    MueTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MueTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            val empty = PreviewState.copy(latest = null, previous = null)
            BodyCompositionHeader(state = empty, onShowDetailedCaution = {})
            BodyCompositionMetric.entries.forEach { metric ->
                BodyCompositionCard(metric = metric, state = empty)
            }
        }
    }
}

@Preview(name = "Body composition — profile", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun BodyCompositionProfilePreview() {
    MueTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MueTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            IncompleteProfileCard(
                missing = setOf(BodyCompositionResult.ProfileInput.SEX),
                onOpenProfile = {},
            )
            RetroactiveProposalCard(count = 12, onComplete = {})
            EstimatesUnavailableCard()
        }
    }
}

// endregion
