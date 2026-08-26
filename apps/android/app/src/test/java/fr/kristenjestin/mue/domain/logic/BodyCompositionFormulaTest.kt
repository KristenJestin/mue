package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le domaine de validité de `mue-foot-to-foot-v1` (FR-BODY-001) et les six issues de
 * [BodyCompositionResult].
 *
 * Le calcul lui-même est vérifié par [BodyCompositionCalculatorTest] contre les vecteurs
 * versionnés ; ici, on ne regarde que les portes : qui entre, qui n'entre pas, et avec quel motif.
 * Un motif juste compte autant qu'un chiffre juste — c'est lui qui décide du message de
 * PRD_SCALE 18.4 que l'écran `Progress` affichera.
 */
class BodyCompositionFormulaTest {

    private val date: LocalDate = LocalDate.of(2026, 8, 26)

    // ------------------------------------------------------------------ identité

    @Test
    fun `la formule porte l'identifiant et la version de la spécification`() {
        assertEquals("mue-foot-to-foot-v1", BodyCompositionFormula.ID)
        assertEquals(1, BodyCompositionFormula.VERSION)
    }

    @Test
    fun `l'arithmétique est décimale, explicite, et s'éloigne de zéro à mi-chemin`() {
        // PRD_SCALE 13.2 : les mêmes entiers stockés en Kotlin et en TypeScript. Ces deux
        // constantes sont ce que le portage doit copier ; les changer casse le contrat.
        assertEquals(12, BodyCompositionFormula.WORKING_SCALE)
        assertEquals(RoundingMode.HALF_UP, BodyCompositionFormula.ROUNDING)
    }

    @Test
    fun `les coefficients sont ceux des publications citées`() {
        assertEquals(BigDecimal("13.055"), BodyCompositionFormula.INTERCEPT)
        assertEquals(BigDecimal("0.204"), BodyCompositionFormula.WEIGHT_COEFFICIENT)
        assertEquals(BigDecimal("0.394"), BodyCompositionFormula.IMPEDANCE_INDEX_COEFFICIENT)
        assertEquals(BigDecimal("0.136"), BodyCompositionFormula.AGE_COEFFICIENT)
        assertEquals(BigDecimal("8.125"), BodyCompositionFormula.SEX_COEFFICIENT)
        assertEquals(BigDecimal("0.732"), BodyCompositionFormula.FAT_FREE_MASS_HYDRATION)
        assertEquals(3.17, BodyCompositionFormula.STANDARD_ERROR_KG)
        assertEquals(554, BodyCompositionFormula.VALIDATION_SAMPLE_SIZE)
        assertEquals(16..75, BodyCompositionFormula.VALIDATION_AGE_RANGE_YEARS)
    }

    @Test
    fun `le terme de sexe vaut zéro pour Female et un pour Male`() {
        assertEquals(0, BodyCompositionFormula.sexCoefficient(Sex.FEMALE))
        assertEquals(1, BodyCompositionFormula.sexCoefficient(Sex.MALE))
        assertEquals(BigDecimal("-161"), BodyCompositionFormula.restingEnergyOffset(Sex.FEMALE))
        assertEquals(BigDecimal("5"), BodyCompositionFormula.restingEnergyOffset(Sex.MALE))
    }

    // ------------------------------------------------------------------ domaine d'âge

    @Test
    fun `le domaine d'âge va de vingt à soixante-quinze ans inclus`() {
        assertFalse(BodyCompositionFormula.isAgeInDomain(19))
        assertTrue(BodyCompositionFormula.isAgeInDomain(20))
        assertTrue(BodyCompositionFormula.isAgeInDomain(75))
        assertFalse(BodyCompositionFormula.isAgeInDomain(76))
    }

    @Test
    fun `le domaine produit est plus étroit que la population de validation`() {
        // La publication couvre 16 à 75 ans ; FR-BODY-001 s'arrête volontairement à 20, l'âge
        // auquel PRD FR-BMI-002 accepte déjà de nommer une catégorie.
        assertTrue(BodyCompositionFormula.VALIDATION_AGE_RANGE_YEARS.first < BodyCompositionFormula.AGE_RANGE_YEARS.first)
        assertEquals(
            BodyCompositionFormula.VALIDATION_AGE_RANGE_YEARS.last,
            BodyCompositionFormula.AGE_RANGE_YEARS.last,
        )
    }

    // ------------------------------------------------------------------ domaine d'IMC

    @Test
    fun `le domaine d'IMC va de 15,8 à 43,1 inclus`() {
        assertTrue(BodyCompositionFormula.isBmiInDomain(BigDecimal("15.8")))
        assertTrue(BodyCompositionFormula.isBmiInDomain(BigDecimal("43.1")))
        assertFalse(BodyCompositionFormula.isBmiInDomain(BigDecimal("15.7999")))
        assertFalse(BodyCompositionFormula.isBmiInDomain(BigDecimal("43.1001")))
    }

    @Test
    fun `l'IMC de la porte est exact, là où l'IMC affiché est arrondi`() {
        // 172,45 kg pour 200 cm : 43,1125 exactement.
        val exact = BodyCompositionFormula.bmiOrNull(weightCg = 17245, heightCm = 200)

        assertEquals(0, exact!!.compareTo(BigDecimal("43.1125")))
        assertFalse(
            BodyCompositionFormula.isBmiInDomain(exact),
            "43,1125 sort du domaine publié même s'il s'affiche 43,1",
        )
        // Et pourtant l'écran, lui, montre bien 43,1 : les deux règles sont différentes exprès.
        assertEquals(
            43.1,
            BmiCalculator.calculate(
                weight = Weight.ofHundredthsOrNull(17245),
                heightCm = 200,
                birthDate = LocalDate.of(1986, 1, 1),
                today = date,
            ).valueOrNull,
        )
    }

    @Test
    fun `une taille nulle ne produit aucun IMC`() {
        assertNull(BodyCompositionFormula.bmiOrNull(weightCg = 8575, heightCm = 0))
    }

    // ------------------------------------------------------------------ entrées utilisables

    @Test
    fun `une taille hors du domaine de saisie du profil n'est pas utilisable`() {
        assertFalse(BodyCompositionFormula.isHeightUsable(null))
        assertFalse(BodyCompositionFormula.isHeightUsable(UserProfile.HEIGHT_RANGE_CM.first - 1))
        assertTrue(BodyCompositionFormula.isHeightUsable(UserProfile.HEIGHT_RANGE_CM.first))
        assertTrue(BodyCompositionFormula.isHeightUsable(UserProfile.HEIGHT_RANGE_CM.last))
        assertFalse(BodyCompositionFormula.isHeightUsable(UserProfile.HEIGHT_RANGE_CM.last + 1))
    }

    @Test
    fun `une impédance absente, nulle ou négative n'est pas exploitable`() {
        assertFalse(BodyCompositionFormula.isImpedanceUsable(null))
        assertFalse(BodyCompositionFormula.isImpedanceUsable(0))
        assertFalse(BodyCompositionFormula.isImpedanceUsable(-1))
        assertTrue(BodyCompositionFormula.isImpedanceUsable(1))
        assertTrue(BodyCompositionFormula.isImpedanceUsable(545))
    }

    // ------------------------------------------------------------------ les six issues

    @Test
    fun `une entrée complète et dans le domaine produit une composition`() {
        val result = calculate()

        assertIs<BodyCompositionResult.Calculated>(result)
        assertEquals(BodyCompositionResult.OUTCOME_CALCULATED, result.wireOutcome)
    }

    @Test
    fun `sans impédance exploitable, aucune composition et un motif d'impédance`() {
        val result = calculate(impedanceOhm = null)

        assertIs<BodyCompositionResult.ImpedanceUnusable>(result)
        assertNull(result.impedanceOhm)
        assertNull(result.compositionOrNull)
    }

    @Test
    fun `l'impédance est examinée avant le profil, pour ne pas faire passer une saisie manuelle pour un profil incomplet`() {
        // Une pesée manuelle d'un profil sans sexe : deux causes possibles, une seule réponse
        // utile. Si le profil passait en premier, l'appelant de FR-BODY-006 compterait cette
        // mesure parmi celles qu'un profil complété débloquerait, alors qu'elle n'a pas
        // d'impédance et ne sera jamais complétable.
        val result = calculate(sex = null, impedanceOhm = null)

        assertIs<BodyCompositionResult.ImpedanceUnusable>(result)
    }

    @Test
    fun `chaque entrée de profil manquante est nommée`() {
        assertEquals(
            listOf(BodyCompositionResult.ProfileInput.SEX),
            missingOf(calculate(sex = null)),
        )
        assertEquals(
            listOf(BodyCompositionResult.ProfileInput.HEIGHT),
            missingOf(calculate(heightCm = null)),
        )
        assertEquals(
            listOf(BodyCompositionResult.ProfileInput.BIRTH_DATE),
            missingOf(calculate(ageYears = null)),
        )
    }

    @Test
    fun `plusieurs entrées manquantes sont toutes nommées, dans un ordre stable`() {
        val missing = missingOf(calculate(heightCm = null, ageYears = null, sex = null))

        assertEquals(
            listOf(
                BodyCompositionResult.ProfileInput.HEIGHT,
                BodyCompositionResult.ProfileInput.BIRTH_DATE,
                BodyCompositionResult.ProfileInput.SEX,
            ),
            missing,
        )
    }

    @Test
    fun `une taille aberrante est traitée comme une taille absente`() {
        assertEquals(
            listOf(BodyCompositionResult.ProfileInput.HEIGHT),
            missingOf(calculate(heightCm = 3)),
        )
    }

    @Test
    fun `un âge hors domaine refuse le calcul en nommant l'âge`() {
        val result = calculate(ageYears = 19)

        assertIs<BodyCompositionResult.AgeOutOfDomain>(result)
        assertEquals(19, result.ageYears)
        assertNull(result.compositionOrNull)
    }

    @Test
    fun `un IMC hors domaine refuse le calcul en portant l'IMC exact`() {
        val result = calculate(weightCg = 17245, heightCm = 200, ageYears = 40)

        assertIs<BodyCompositionResult.BmiOutOfDomain>(result)
        assertEquals(0, result.bmi!!.compareTo(BigDecimal("43.1125")))
        assertNull(result.compositionOrNull)
    }

    @Test
    fun `un résultat physiquement incohérent est refusé et jamais ramené dans les bornes`() {
        // 150 Ω pour 175 cm : l'indice d'impédance explose et la masse maigre sort à 109,78 kg
        // pour 60 kg de poids. L'âge et l'IMC, eux, sont parfaitement valides.
        val result = calculate(weightCg = 6000, heightCm = 175, ageYears = 30, impedanceOhm = 150)

        assertIs<BodyCompositionResult.PhysicallyImplausible>(result)
        assertEquals(
            BodyCompositionResult.PlausibilityCheck.FAT_FREE_MASS_ABOVE_WEIGHT,
            result.check,
        )
        assertNull(
            result.compositionOrNull,
            "aucune composition n'est produite : PRD_SCALE 13.2 refuse de ramener un résultat dans les bornes",
        )
    }

    @Test
    fun `chaque issue porte un nom stable, celui des vecteurs versionnés`() {
        assertEquals("calculated", calculate().wireOutcome)
        assertEquals("impedance-unusable", calculate(impedanceOhm = 0).wireOutcome)
        assertEquals("missing-profile-input", calculate(sex = null).wireOutcome)
        assertEquals("age-out-of-domain", calculate(ageYears = 90).wireOutcome)
        assertEquals("bmi-out-of-domain", calculate(weightCg = 3000).wireOutcome)
        assertEquals(
            "physically-implausible",
            calculate(weightCg = 6000, heightCm = 175, ageYears = 30, impedanceOhm = 150).wireOutcome,
        )
    }

    // ------------------------------------------------------------------ textes de l'écran Progress

    @Test
    fun `le texte d'indisponibilité est celui de FR-BODY-001, au caractère près`() {
        assertEquals(
            "Body composition estimates are not available for this profile",
            BodyCompositionFormula.UNAVAILABLE_FOR_PROFILE,
        )
    }

    @Test
    fun `le texte de prudence détaillé nomme la provenance, l'incertitude et le facteur d'hydratation`() {
        val caution = BodyCompositionFormula.DETAILED_CAUTION

        assertTrue(caution.contains("554"), "la population de validation doit être visible")
        assertTrue(caution.contains("3.17 kg"), "l'erreur type publiée doit être visible")
        assertTrue(caution.contains("0.732"), "le facteur d'hydratation fixe doit être visible")
        assertTrue(caution.contains("mue-foot-to-foot-v1"), "la version de formule doit être visible")
        assertTrue(caution.contains("DXA"), "la méthode de référence doit être visible")
        assertTrue(caution.contains("Mifflin-St Jeor"))
    }

    @Test
    fun `le texte de prudence ne compare l'utilisateur à personne`() {
        val caution = BodyCompositionFormula.DETAILED_CAUTION.lowercase()

        // FR-BODY-003 : ni catégorie, ni seuil, ni code couleur de normalité. Un texte qui
        // qualifierait une valeur de « normale » ou « saine » rétablirait par la prose la
        // catégorie que l'écran s'interdit.
        for (forbidden in listOf("normal", "healthy range", "ideal", "should be", "too high", "too low")) {
            assertFalse(caution.contains(forbidden), "le texte de prudence ne doit pas dire « $forbidden »")
        }
    }

    // ------------------------------------------------------------------ outils

    private fun missingOf(result: BodyCompositionResult): List<BodyCompositionResult.ProfileInput> {
        assertIs<BodyCompositionResult.MissingProfileInput>(result)
        assertNull(result.compositionOrNull)
        return result.missing.toList()
    }

    private fun calculate(
        weightCg: Int = 8575,
        heightCm: Int? = 178,
        ageYears: Int? = 34,
        sex: Sex? = Sex.MALE,
        impedanceOhm: Int? = 545,
    ): BodyCompositionResult = BodyCompositionCalculator.calculate(
        date = date,
        weightCg = weightCg,
        heightCm = heightCm,
        ageYears = ageYears,
        sex = sex,
        impedanceOhm = impedanceOhm,
    )
}
