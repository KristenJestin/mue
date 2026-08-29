package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.logic.BodyCompositionCalculator
import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.domain.logic.compositionOrNull
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD_SCALE FR-BODY-003, FR-BODY-005 et 18.4, en JVM pure : quelles deux compositions la section
 * retient, ce qu'elle affiche quand il n'y en a aucune, et quand elle décide d'exister.
 *
 * Tout ce qui est vérifié ici est décidé par une fonction pure ; l'écran, lui, n'a qu'à rendre. Ce
 * découpage est délibéré — les règles de FR-BODY-005 sont des règles de sélection, et les vérifier
 * à travers un `LazyColumn` reviendrait à tester le défilement en croyant tester le PRD.
 */
class BodyCompositionUiStateTest {

    /** PRD BR-010 : les nombres suivent la langue du téléphone, donc les tests la fixent. */
    @get:Rule
    val locale = LocaleRule(Locale.UK)

    // region le choix des deux compositions

    /** FR-BODY-005 : la valeur principale est la composition la plus récente de la période. */
    @Test
    fun `la valeur principale est la composition la plus récente de la période`() {
        val state = stateOf(
            inPeriod = listOf(
                composed(daysAgo(20), 76.0, 520),
                composed(daysAgo(3), 74.5, 500),
                composed(daysAgo(10), 75.2, 510),
            ),
        )

        assertEquals(daysAgo(3), assertNotNull(state.latest).date)
        assertEquals(daysAgo(10), assertNotNull(state.previous).date)
    }

    /** FR-BODY-005 : l'écart se prend sur la composition immédiatement précédente. */
    @Test
    fun `sans seconde composition dans la période l'écart est un tiret`() {
        val state = stateOf(inPeriod = listOf(composed(daysAgo(3), 74.5, 500)))

        assertNotNull(state.latest)
        assertNull(state.previous)
        BodyCompositionMetric.entries.forEach { metric ->
            assertEquals(
                ScaleMessages.NO_VALUE,
                metric.change(state.latest, state.previous),
                metric.name,
            )
        }
    }

    /**
     * FR-BODY-005 : une période sans composition affiche `—` sur les quatre cartes et n'emprunte
     * **jamais** une valeur hors période. C'est la même règle que celle qui gouverne l'IMC de cet
     * écran (PRD FR-PROGRESS-003).
     */
    @Test
    fun `une période sans composition n'emprunte jamais une valeur hors période`() {
        val outside = composed(daysAgo(90), 78.0, 540)
        val state = stateOf(all = listOf(outside), inPeriod = emptyList())

        assertNull(state.latest)
        assertNull(state.previous)
        assertTrue(state.hasHistory)
        assertTrue(state.showCards)
        BodyCompositionMetric.entries.forEach { metric ->
            assertEquals(ScaleMessages.NO_VALUE, metric.value(state.latest), metric.name)
            assertEquals(
                ScaleMessages.NO_VALUE,
                metric.change(state.latest, state.previous),
                metric.name,
            )
        }
    }

    /**
     * FR-BODY-005 : les pesées sans impédance sont ignorées pour choisir les deux compositions.
     * Une pesée manuelle d'aujourd'hui ne doit donc pas effacer les cartes — elle n'entre
     * simplement pas dans le choix.
     */
    @Test
    fun `une pesée sans impédance n'efface pas les cartes`() {
        val state = stateOf(
            inPeriod = listOf(
                composed(daysAgo(10), 75.2, 510),
                composed(daysAgo(3), 74.5, 500),
                manual(daysAgo(0), 74.4),
            ),
        )

        assertEquals(daysAgo(3), assertNotNull(state.latest).date)
        assertEquals(daysAgo(10), assertNotNull(state.previous).date)
    }

    /**
     * BR-SCALE-005 : une pesée qui porte une impédance mais dont le calcul a été refusé n'a pas
     * de composition, et ne compte donc pas davantage.
     */
    @Test
    fun `une pesée dont le calcul a été refusé ne compte pas comme composition`() {
        val refused = Measurement(
            date = daysAgo(1),
            weight = kilograms(74.4),
            source = MeasurementSource.SCALE,
            impedanceOhm = 505,
        )

        val state = stateOf(inPeriod = listOf(composed(daysAgo(3), 74.5, 500), refused))

        assertEquals(daysAgo(3), assertNotNull(state.latest).date)
    }

    // endregion

    // region quand la section existe

    /**
     * PRD_SCALE 18.1 et BR-SCALE-010 : oublier une balance ne masque jamais des compositions déjà
     * enregistrées. La section ne consulte donc l'existence d'une balance que pour les messages de
     * profil, jamais pour décider si les cartes existent.
     */
    @Test
    fun `la section reste visible quand la balance a été oubliée`() {
        val state = stateOf(
            inPeriod = listOf(composed(daysAgo(3), 74.5, 500)),
            hasPairedScale = false,
        )

        assertTrue(state.isVisible)
        assertTrue(state.showCards)
    }

    /** PRD_SCALE 18.1 : sans historique de composition, la section est absente. */
    @Test
    fun `sans historique de composition et sans balance la section est absente`() {
        val state = stateOf(
            all = listOf(manual(daysAgo(2), 74.4)),
            inPeriod = listOf(manual(daysAgo(2), 74.4)),
            profile = UserProfile.EMPTY,
            hasPairedScale = false,
        )

        assertFalse(state.isVisible)
        assertFalse(state.showCards)
        assertFalse(state.showIncompleteProfile)
        assertFalse(state.showUnavailableForProfile)
    }

    /**
     * PRD_SCALE 18.4 : l'exception écrite noir sur blanc par FR-BODY-005 — une balance associée et
     * un profil incomplet justifient d'expliquer ce qui manque, même sans aucune composition.
     */
    @Test
    fun `une balance associée et un profil incomplet expliquent ce qui manque`() {
        val state = stateOf(
            all = listOf(withImpedance(daysAgo(2), 74.4, 505)),
            inPeriod = listOf(withImpedance(daysAgo(2), 74.4, 505)),
            profile = PROFILE.copy(sex = null),
            hasPairedScale = true,
        )

        assertTrue(state.isVisible)
        assertFalse(state.showCards)
        assertTrue(state.showIncompleteProfile)
        assertEquals(setOf(BodyCompositionResult.ProfileInput.SEX), state.missingProfileInputs)
    }

    /**
     * PRD_SCALE 18.4 conditionne l'explication à une balance associée : sans balance, réclamer une
     * taille pour une estimation que rien ne peut produire serait une demande gratuite.
     */
    @Test
    fun `sans balance associée le profil incomplet n'est pas commenté`() {
        val state = stateOf(
            all = listOf(withImpedance(daysAgo(2), 74.4, 505)),
            inPeriod = listOf(withImpedance(daysAgo(2), 74.4, 505)),
            profile = PROFILE.copy(sex = null),
            hasPairedScale = false,
        )

        assertFalse(state.showIncompleteProfile)
        assertFalse(state.isVisible)
    }

    /**
     * FR-BODY-004 : effacer une entrée de profil n'efface aucune composition enregistrée. Les
     * cartes continuent donc de lire l'instantané.
     */
    @Test
    fun `les compositions historiques restent affichées quand le profil redevient incomplet`() {
        val state = stateOf(
            inPeriod = listOf(composed(daysAgo(3), 74.5, 500)),
            profile = PROFILE.copy(sex = null),
            hasPairedScale = true,
        )

        assertTrue(state.showCards)
        assertNotNull(state.latest)
        assertTrue(state.showIncompleteProfile)
    }

    // endregion

    // region hors domaine

    /**
     * FR-BODY-001 et PRD_SCALE 18.4 : profil complet, mais hors du domaine d'âge de l'équation.
     * L'état le dit ; l'écran, lui, n'affichera **ni l'IMC ni l'âge**.
     */
    @Test
    fun `un profil complet hors du domaine d'âge n'a pas d'estimations disponibles`() {
        val state = stateOf(
            inPeriod = listOf(composed(daysAgo(3), 74.5, 500)),
            profile = PROFILE.copy(birthDate = LocalDate.of(1930, 1, 1)),
        )

        assertTrue(state.isOutOfDomain)
        assertTrue(state.showUnavailableForProfile)
        // Et les compositions déjà enregistrées restent affichées (FR-BODY-004).
        assertTrue(state.showCards)
    }

    /** FR-BODY-001 : la même porte, côté IMC, sur le dernier poids enregistré. */
    @Test
    fun `un profil complet hors du domaine d'IMC n'a pas d'estimations disponibles`() {
        val heavy = manual(daysAgo(0), 160.0)
        val state = stateOf(
            all = listOf(composed(daysAgo(30), 74.5, 500), heavy),
            inPeriod = listOf(heavy),
        )

        assertTrue(state.isOutOfDomain)
        assertTrue(state.showUnavailableForProfile)
    }

    /**
     * Un profil **incomplet** n'est jamais dit « hors domaine » : les deux messages de
     * PRD_SCALE 18.4 sont exclusifs, et dire à quelqu'un qui n'a pas renseigné son sexe que son
     * profil ne convient pas serait un contresens.
     */
    @Test
    fun `un profil incomplet n'est jamais dit hors domaine`() {
        val state = stateOf(
            inPeriod = listOf(composed(daysAgo(3), 74.5, 500)),
            profile = PROFILE.copy(sex = null, birthDate = LocalDate.of(1930, 1, 1)),
            hasPairedScale = true,
        )

        assertFalse(state.isOutOfDomain)
        assertFalse(state.showUnavailableForProfile)
        assertTrue(state.showIncompleteProfile)
    }

    @Test
    fun `un profil dans le domaine ne dit rien du tout`() {
        val state = stateOf(inPeriod = listOf(composed(daysAgo(3), 74.5, 500)))

        assertFalse(state.isOutOfDomain)
        assertFalse(state.showUnavailableForProfile)
        assertFalse(state.showIncompleteProfile)
    }

    // endregion

    // region la proposition rétroactive

    /**
     * FR-BODY-006 : le cas nominal du calcul rétroactif est celui d'un historique **sans** aucune
     * composition — des semaines de pesées faites avant que le sexe soit renseigné. La proposition
     * doit donc pouvoir rendre la section visible à elle seule, sans quoi elle serait
     * inatteignable au moment exact où elle a un sens.
     */
    @Test
    fun `la proposition rétroactive rend la section visible sans aucun historique`() {
        val history = listOf(
            withImpedance(daysAgo(20), 75.0, 520),
            withImpedance(daysAgo(10), 74.8, 510),
        )
        val state = stateOf(all = history, inPeriod = history)

        assertFalse(state.hasHistory)
        assertFalse(state.showCards)
        assertTrue(state.showRetroactiveProposal)
        assertEquals(2, state.retroactiveCount)
        assertTrue(state.isVisible)
    }

    /** PRD_SCALE 18.4 : sans aucune pesée à compléter, la proposition ne s'affiche pas. */
    @Test
    fun `sans pesée à compléter la proposition ne s'affiche pas`() {
        val state = stateOf(inPeriod = listOf(composed(daysAgo(3), 74.5, 500)))

        assertEquals(0, state.retroactiveCount)
        assertFalse(state.showRetroactiveProposal)
    }

    /**
     * FR-BODY-006 : le compte porte sur tout l'historique, pas sur la période sélectionnée.
     * Remplir le passé n'est pas une opération de fenêtre.
     */
    @Test
    fun `le compte rétroactif porte sur tout l'historique et non sur la période`() {
        val state = stateOf(
            all = listOf(withImpedance(daysAgo(200), 75.0, 520), withImpedance(daysAgo(2), 74.8, 510)),
            inPeriod = listOf(withImpedance(daysAgo(2), 74.8, 510)),
        )

        assertEquals(2, state.retroactiveCount)
    }

    // endregion

    // region ce qui s'affiche

    /** FR-BODY-003 : une décimale pour les pourcentages et les masses. */
    @Test
    fun `les pourcentages et les masses portent une décimale`() {
        val composition = frozen(
            daysAgo(0),
            bodyFatDeciPercent = 242,
            fatFreeMassCg = 5_640,
            bodyWaterDeciPercent = 555,
        )

        assertEquals("24.2", BodyCompositionMetric.BODY_FAT.value(composition))
        assertEquals("56.4", BodyCompositionMetric.FAT_FREE_MASS.value(composition))
        assertEquals("55.5", BodyCompositionMetric.BODY_WATER.value(composition))
    }

    /** FR-BODY-003 : un entier pour la dépense énergétique au repos, jamais une décimale. */
    @Test
    fun `la dépense énergétique au repos est un entier`() {
        val computed = compositionOf(daysAgo(0), 74.5, 500)

        // 10×74,5 + 6,25×178 − 5×36 + 5 = 1682,5, arrondi une seule fois vers l'entier.
        assertEquals(1_683, computed.restingEnergyKcal)
        assertEquals("1683", BodyCompositionMetric.RESTING_ENERGY.value(computed))
    }

    /** Un calcul réel traverse le formatage sans y perdre sa décimale. */
    @Test
    fun `une composition réellement calculée s'affiche à la décimale`() {
        val computed = compositionOf(daysAgo(0), 74.5, 500)

        assertEquals(242, computed.bodyFatDeciPercent)
        assertEquals("24.2", BodyCompositionMetric.BODY_FAT.value(computed))
    }

    /** FR-BODY-003 : l'écart est la seule mise en perspective, et il porte toujours son signe. */
    @Test
    fun `l'écart porte toujours son signe`() {
        val minus = ProgressFormat.signedEstimate(-1.0).first()
        val latest = frozen(daysAgo(0), bodyFatDeciPercent = 242, restingEnergyKcal = 1_683)
        val previous = frozen(daysAgo(7), bodyFatDeciPercent = 204, restingEnergyKcal = 1_700)

        BodyCompositionMetric.entries.forEach { metric ->
            val change = metric.change(latest, previous)
            assertTrue(change.first() == '+' || change.first() == minus, "$metric: $change")
        }
    }

    /**
     * L'écart se calcule sur les **entiers stockés**, avant toute division d'affichage : passer
     * par les `Double` dérivés arrondirait deux fois.
     */
    @Test
    fun `l'écart se calcule sur les entiers stockés`() {
        val previous = frozen(daysAgo(7), bodyFatDeciPercent = 204, restingEnergyKcal = 1_700)
        val latest = frozen(daysAgo(0), bodyFatDeciPercent = 201, restingEnergyKcal = 1_688)

        assertEquals(
            ProgressFormat.signedEstimate(-0.3),
            BodyCompositionMetric.BODY_FAT.change(latest, previous),
        )
        assertEquals(
            ProgressFormat.signedEnergy(-12),
            BodyCompositionMetric.RESTING_ENERGY.change(latest, previous),
        )
        // Et le résultat reste bien lisible : un signe, un chiffre, une décimale.
        assertEquals(4, BodyCompositionMetric.BODY_FAT.change(latest, previous).length)
    }

    /** Le tiret de `ScaleMessages` et celui de `ProgressFormat` sont le même caractère. */
    @Test
    fun `le tiret d'absence est le même partout sur cet écran`() {
        assertEquals(ProgressFormat.UNAVAILABLE, ScaleMessages.NO_VALUE)
    }

    // endregion

    // region ce qui manque au profil (FR-BODY-001, PRD_SCALE 18.4)

    /**
     * La formulation elle-même a rejoint `ScaleMessages`, où vit désormais toute la langue du
     * module, et `ScaleMessagesTest` la verrouille — y compris le fait que la phrase des trois
     * entrées manquantes et `PROFILE_INCOMPLETE_BODY` sont une seule et même chose. Ce qui reste
     * ici est ce que cet état sait vraiment : lesquelles des trois entrées manquent.
     *
     * FR-BODY-001 : une taille hors du domaine de saisie se traite comme une taille absente.
     */
    @Test
    fun `une taille hors domaine compte comme une taille manquante`() {
        val missing = BodyCompositionUiState.missingProfileInputsOf(PROFILE.copy(heightCm = 12))

        assertEquals(setOf(BodyCompositionResult.ProfileInput.HEIGHT), missing)
    }

    // endregion
}

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

private val PROFILE = UserProfile(
    heightCm = 178,
    birthDate = LocalDate.of(1990, 1, 1),
    sex = Sex.MALE,
)

private fun daysAgo(days: Long): LocalDate = TODAY.minusDays(days)

private fun kilograms(value: Double): Weight =
    requireNotNull(Weight.ofKilogramsOrNull(value)) { "$value kg est hors domaine" }

private fun manual(date: LocalDate, value: Double): Measurement =
    Measurement(date = date, weight = kilograms(value))

private fun withImpedance(date: LocalDate, value: Double, impedanceOhm: Int): Measurement =
    Measurement(
        date = date,
        weight = kilograms(value),
        source = MeasurementSource.SCALE,
        impedanceOhm = impedanceOhm,
    )

/** Une composition réelle, produite par le calcul du domaine plutôt qu'inventée. */
private fun compositionOf(date: LocalDate, value: Double, impedanceOhm: Int): BodyComposition =
    requireNotNull(
        BodyCompositionCalculator
            .calculate(withImpedance(date, value, impedanceOhm), PROFILE)
            .compositionOrNull,
    ) { "le fixture doit être dans le domaine de FR-BODY-001" }

/** La mesure et sa composition, telles que la balance et le calcul les auraient écrites. */
private fun composed(date: LocalDate, value: Double, impedanceOhm: Int): Measurement =
    withImpedance(date, value, impedanceOhm)
        .copy(bodyComposition = compositionOf(date, value, impedanceOhm))

/** Une composition dont seuls les entiers comptent, pour vérifier un écart au dixième près. */
private fun frozen(
    date: LocalDate,
    bodyFatDeciPercent: Int = 240,
    fatFreeMassCg: Int = 5_600,
    bodyWaterDeciPercent: Int = 550,
    restingEnergyKcal: Int = 1_700,
): BodyComposition = BodyComposition(
    date = date,
    formulaId = "fixture",
    formulaVersion = 1,
    inputWeightCg = 7_450,
    inputHeightCm = 178,
    inputAgeYears = 36,
    inputSex = Sex.MALE,
    bodyFatDeciPercent = bodyFatDeciPercent,
    fatFreeMassCg = fatFreeMassCg,
    bodyWaterDeciPercent = bodyWaterDeciPercent,
    restingEnergyKcal = restingEnergyKcal,
)

private fun stateOf(
    inPeriod: List<Measurement>,
    all: List<Measurement> = inPeriod,
    profile: UserProfile = PROFILE,
    hasPairedScale: Boolean = true,
): BodyCompositionUiState = BodyCompositionUiState.from(
    allMeasurements = all,
    inPeriod = inPeriod,
    profile = profile,
    today = TODAY,
    hasPairedScale = hasPairedScale,
)
