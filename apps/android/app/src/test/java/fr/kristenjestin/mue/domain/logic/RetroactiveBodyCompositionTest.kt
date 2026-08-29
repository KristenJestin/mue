package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRD_SCALE FR-BODY-006 : ce qu'un profil devenu complet permet de compléter dans le passé, et
 * surtout ce qu'il ne permet pas.
 *
 * Les trois clauses les plus faciles à perdre du calcul rétroactif sont vérifiées ici parce
 * qu'aucune ne se voit à l'écran : l'âge employé est celui de **chaque** date, une composition
 * déjà enregistrée n'est **jamais** écrasée, et le compte annoncé à l'utilisateur vaut exactement
 * le nombre de lignes que l'opération écrira.
 */
class RetroactiveBodyCompositionTest {

    // region ce qui est complété

    @Test
    fun `une pesée qui porte une impédance exploitable est complétée`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, impedanceOhm = 500)),
            PROFILE,
        )

        assertEquals(1, plan.size)
        val composition = assertNotNull(plan.single().bodyComposition)
        assertEquals(DAY, composition.date)
        assertEquals(BodyCompositionFormula.ID, composition.formulaId)
        assertEquals(BodyCompositionFormula.VERSION, composition.formulaVersion)
    }

    @Test
    fun `une pesée sans impédance n'est jamais complétée`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, impedanceOhm = null)),
            PROFILE,
        )

        assertTrue(plan.isEmpty())
    }

    /**
     * BR-SCALE-005 : le pilote convertit son marqueur d'absence en `null` bien avant d'arriver
     * ici, mais une impédance nulle ou négative venue d'ailleurs ne doit pas produire de donnée
     * de santé pour autant.
     */
    @Test
    fun `une impédance nulle ou négative n'est jamais complétée`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, impedanceOhm = 0), weighIn(DAY.minusDays(1), 74.5, -12)),
            PROFILE,
        )

        assertTrue(plan.isEmpty())
    }

    /** FR-BODY-006 : une composition déjà enregistrée n'est jamais écrasée. */
    @Test
    fun `une composition déjà enregistrée n'est jamais écrasée`() {
        val existing = frozenComposition(DAY)
        val measurements =
            listOf(weighIn(DAY, 74.5, impedanceOhm = 500).copy(bodyComposition = existing))

        assertTrue(RetroactiveBodyComposition.plan(measurements, PROFILE).isEmpty())
        assertEquals(0, RetroactiveBodyComposition.count(measurements, PROFILE))
    }

    /**
     * Le corollaire du précédent : accepter deux fois la proposition ne réécrit rien, parce que
     * la première acceptation a vidé la liste.
     */
    @Test
    fun `rejouer le plan sur son propre résultat ne propose plus rien`() {
        val measurements = listOf(
            weighIn(DAY, 74.5, impedanceOhm = 500),
            weighIn(DAY.minusDays(7), 75.0, impedanceOhm = 520),
        )

        val first = RetroactiveBodyComposition.plan(measurements, PROFILE)
        assertEquals(2, first.size)

        assertTrue(RetroactiveBodyComposition.plan(first, PROFILE).isEmpty())
    }

    // endregion

    // region l'instantané de chaque date

    /**
     * FR-BODY-006, la clause la plus silencieuse : chaque composition porte l'âge que la personne
     * avait **à la date de sa mesure**, jamais celui du jour du calcul. Employer le second
     * décalerait la masse maigre de `0,136 kg` par année d'écart, sur des dizaines de lignes d'un
     * coup, sans que rien ne l'indique.
     */
    @Test
    fun `chaque composition porte l'âge de la date de sa mesure`() {
        val old = LocalDate.of(2020, 6, 15)
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, 500), weighIn(old, 74.5, 500)),
            PROFILE,
        )

        val byDate = plan.associateBy { it.date }
        assertEquals(36, assertNotNull(byDate[DAY]?.bodyComposition).inputAgeYears)
        assertEquals(30, assertNotNull(byDate[old]?.bodyComposition).inputAgeYears)
    }

    /**
     * Deux âges différents sur le même poids et la même impédance donnent deux masses maigres
     * différentes : la preuve que l'âge reconstitué est réellement entré dans l'équation, et pas
     * seulement recopié dans l'instantané.
     */
    @Test
    fun `deux dates éloignées ne produisent pas la même masse maigre`() {
        val old = LocalDate.of(2020, 6, 15)
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, 500), weighIn(old, 74.5, 500)),
            PROFILE,
        )

        val byDate = plan.associateBy { it.date }
        val recent = assertNotNull(byDate[DAY]?.bodyComposition).fatFreeMassCg
        val older = assertNotNull(byDate[old]?.bodyComposition).fatFreeMassCg
        assertTrue(older > recent, "à 30 ans la masse maigre estimée dépasse celle de 36 ans")
    }

    /**
     * FR-BODY-006 : faute d'historique de profil, la taille et le sexe employés sont ceux du jour
     * du calcul. L'approximation est assumée — et `ScaleMessages.RETROACTIVE_EXPLANATION` la rend
     * visible dans la proposition.
     */
    @Test
    fun `la taille et le sexe employés sont ceux du profil courant`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(LocalDate.of(2020, 6, 15), 74.5, 500)),
            PROFILE,
        )

        val composition = assertNotNull(plan.single().bodyComposition)
        assertEquals(178, composition.inputHeightCm)
        assertEquals(Sex.MALE, composition.inputSex)
    }

    /** BR-SCALE-015 : le poids de l'instantané est celui de la mesure parente, au centième. */
    @Test
    fun `le poids de l'instantané est celui de la mesure`() {
        val plan = RetroactiveBodyComposition.plan(listOf(weighIn(DAY, 74.5, 500)), PROFILE)

        assertEquals(7_450, assertNotNull(plan.single().bodyComposition).inputWeightCg)
    }

    // endregion

    // region ce que la mesure conserve

    /**
     * BR-SCALE-008 et BR-SCALE-013 : compléter une composition ne touche ni le poids, ni la
     * provenance, ni la balance émettrice, ni l'impédance. Le résultat est directement écrivable.
     */
    @Test
    fun `la mesure complétée garde son poids sa provenance et son impédance`() {
        val original = Measurement(
            date = DAY,
            weight = kilograms(74.5),
            source = MeasurementSource.SCALE,
            sourceScaleId = "scale-1",
            impedanceOhm = 500,
        )

        val completed = RetroactiveBodyComposition.plan(listOf(original), PROFILE).single()

        assertEquals(original.weight, completed.weight)
        assertEquals(MeasurementSource.SCALE, completed.source)
        assertEquals("scale-1", completed.sourceScaleId)
        assertEquals(500, completed.impedanceOhm)
        assertEquals(original, completed.copy(bodyComposition = null))
    }

    @Test
    fun `le plan est rendu dans l'ordre des dates`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(
                weighIn(DAY, 74.5, 500),
                weighIn(DAY.minusDays(30), 75.0, 505),
                weighIn(DAY.minusDays(10), 74.8, 510),
            ),
            PROFILE,
        )

        assertEquals(
            listOf(DAY.minusDays(30), DAY.minusDays(10), DAY),
            plan.map { it.date },
        )
    }

    // endregion

    // region les portes de FR-BODY-001

    @Test
    fun `un profil sans sexe ne complète rien`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, 500)),
            PROFILE.copy(sex = null),
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `un profil sans taille ni date de naissance ne complète rien`() {
        val measurements = listOf(weighIn(DAY, 74.5, 500))

        assertTrue(RetroactiveBodyComposition.plan(measurements, PROFILE.copy(heightCm = null)).isEmpty())
        assertTrue(RetroactiveBodyComposition.plan(measurements, PROFILE.copy(birthDate = null)).isEmpty())
    }

    /** FR-BODY-001 : le domaine d'IMC de l'équation, apprécié mesure par mesure. */
    @Test
    fun `une pesée hors du domaine d'IMC n'est pas complétée alors que ses voisines le sont`() {
        val plan = RetroactiveBodyComposition.plan(
            listOf(
                weighIn(DAY, 74.5, 500),
                // 30 kg pour 178 cm : un IMC de 9,5, très en dessous de la borne 15,8.
                weighIn(DAY.minusDays(1), 30.0, 500),
            ),
            PROFILE,
        )

        assertEquals(listOf(DAY), plan.map { it.date })
    }

    /** FR-BODY-001 : l'âge est apprécié à la date de la mesure, donc la porte l'est aussi. */
    @Test
    fun `une pesée antérieure aux vingt ans de la personne n'est pas complétée`() {
        val teenager = LocalDate.of(2008, 6, 15)
        val plan = RetroactiveBodyComposition.plan(
            listOf(weighIn(DAY, 74.5, 500), weighIn(teenager, 74.5, 500)),
            PROFILE,
        )

        assertEquals(listOf(DAY), plan.map { it.date })
    }

    // endregion

    // region le compte annoncé

    /**
     * PRD_SCALE 18.4 promet un nombre de pesées à l'utilisateur. Le compte n'est donc pas une
     * heuristique séparée mais la taille exacte du plan : une proposition annonçant `4` avant
     * d'en écrire `3` aurait menti sur une donnée de santé.
     */
    @Test
    fun `le compte vaut exactement le nombre de compositions que le plan écrirait`() {
        val measurements = listOf(
            weighIn(DAY, 74.5, 500),
            weighIn(DAY.minusDays(1), 74.6, null),
            weighIn(DAY.minusDays(2), 74.7, 505),
            weighIn(DAY.minusDays(3), 30.0, 505),
            weighIn(DAY.minusDays(4), 74.8, 510).copy(bodyComposition = frozenComposition(DAY.minusDays(4))),
        )

        assertEquals(2, RetroactiveBodyComposition.count(measurements, PROFILE))
        assertEquals(
            RetroactiveBodyComposition.plan(measurements, PROFILE).size,
            RetroactiveBodyComposition.count(measurements, PROFILE),
        )
    }

    @Test
    fun `un historique vide ne propose rien`() {
        assertEquals(0, RetroactiveBodyComposition.count(emptyList(), PROFILE))
    }

    // endregion
}

private val DAY: LocalDate = LocalDate.of(2026, 8, 23)

/** 36 ans à [DAY], 178 cm : un profil complet, bien à l'intérieur du domaine de FR-BODY-001. */
private val PROFILE = UserProfile(
    heightCm = 178,
    birthDate = LocalDate.of(1990, 1, 1),
    sex = Sex.MALE,
)

private fun kilograms(value: Double): Weight =
    requireNotNull(Weight.ofKilogramsOrNull(value)) { "$value kg est hors domaine" }

private fun weighIn(date: LocalDate, value: Double, impedanceOhm: Int?): Measurement =
    Measurement(
        date = date,
        weight = kilograms(value),
        source = MeasurementSource.SCALE,
        impedanceOhm = impedanceOhm,
    )

/**
 * Une composition dont les valeurs n'ont aucune importance : elle n'est là que pour occuper la
 * place et vérifier qu'on ne l'écrase pas. Ses entrées sont volontairement fausses, de sorte
 * qu'un recalcul silencieux se verrait immédiatement.
 */
private fun frozenComposition(date: LocalDate): BodyComposition = BodyComposition(
    date = date,
    formulaId = "already-there",
    formulaVersion = 99,
    inputWeightCg = 1,
    inputHeightCm = 1,
    inputAgeYears = 1,
    inputSex = Sex.FEMALE,
    bodyFatDeciPercent = 1,
    fatFreeMassCg = 1,
    bodyWaterDeciPercent = 1,
    restingEnergyKcal = 1,
)
