package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Le calcul de PRD_SCALE 13.2, sur les vecteurs versionnés puis à la main.
 *
 * Les deux moitiés de ce fichier ne se doublonnent pas. La première rejoue
 * `src/test/resources/bodycomposition/mue-foot-to-foot-v1.json` **tel quel** : c'est le contrat
 * inter-langages de PRD_SCALE 13.2, le même fichier que l'implémentation TypeScript rejouera, et
 * il est volontairement illisible en tant que test — on n'y voit que des entiers. La seconde
 * moitié est écrite à la main pour que le lecteur voie ce que ces entiers signifient : d'où vient
 * chaque nombre, ce que le sexe change, quel âge est employé, et ce qui n'est jamais écrit.
 */
class BodyCompositionCalculatorTest {

    private val date: LocalDate = LocalDate.of(2026, 8, 26)

    // ------------------------------------------------------------------ vecteurs versionnés

    @Test
    fun `les vecteurs versionnés déclarent la formule et l'arithmétique de cette implémentation`() {
        val root = vectors()

        assertEquals(BodyCompositionFormula.ID, root["formulaId"]?.asText())
        assertEquals(BodyCompositionFormula.VERSION, root["formulaVersion"]?.asInt())
        assertEquals(BodyCompositionFormula.WORKING_SCALE, root["workingScale"]?.asInt())
        assertEquals(BodyCompositionFormula.ROUNDING.name, root["rounding"]?.asText())
        assertNotNull(
            root["_readme"],
            "le fichier est consommé par une autre équipe et un autre langage : son format se " +
                "documente dans le fichier, pas ailleurs",
        )
    }

    @Test
    fun `chaque vecteur versionné produit exactement les entiers attendus`() {
        val cases = vectors()["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty(), "aucun vecteur : le fichier de référence est vide ou illisible")

        // Toutes les divergences d'un coup : corriger un portage une erreur à la fois est ce qui
        // fait abandonner les contrats inter-langages.
        val failures = cases.mapNotNull { case -> failureOf(case) }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n", prefix = "\n"))
    }

    @Test
    fun `les vecteurs versionnés couvrent les six issues possibles`() {
        val outcomes = vectors()["cases"]!!.jsonArray
            .map { it.jsonObject["expect"]!!.jsonObject["outcome"]!!.asText() }
            .toSet()

        val expected = setOf(
            BodyCompositionResult.OUTCOME_CALCULATED,
            BodyCompositionResult.OUTCOME_MISSING_PROFILE_INPUT,
            BodyCompositionResult.OUTCOME_AGE_OUT_OF_DOMAIN,
            BodyCompositionResult.OUTCOME_BMI_OUT_OF_DOMAIN,
            BodyCompositionResult.OUTCOME_IMPEDANCE_UNUSABLE,
            BodyCompositionResult.OUTCOME_PHYSICALLY_IMPLAUSIBLE,
        )
        assertEquals(expected, outcomes, "un motif de refus sans vecteur est un motif non porté")
    }

    @Test
    fun `chaque identifiant de vecteur est unique`() {
        val ids = vectors()["cases"]!!.jsonArray.map { it.jsonObject["id"]!!.asText() }

        assertEquals(ids.size, ids.toSet().size, "identifiants de vecteurs en double : $ids")
    }

    // ------------------------------------------------------------------ le relevé matériel

    @Test
    fun `le relevé du 26 août 2026 donne la composition publiée dans les vecteurs`() {
        // 85,75 kg et 545 Ω relevés sur la balance de référence, pour 178 cm et 34 ans.
        val result = calculated(sex = Sex.MALE)

        assertEquals(336, result.bodyFatDeciPercent, "33,6 % de masse grasse")
        assertEquals(5695, result.fatFreeMassCg, "56,95 kg de masse maigre")
        assertEquals(486, result.bodyWaterDeciPercent, "48,6 % d'eau corporelle")
        assertEquals(1805, result.restingEnergyKcal, "1805 kcal au repos")
    }

    @Test
    fun `le sexe ne décale la dépense au repos que de ses deux constantes`() {
        // Mifflin–St Jeor : −161 pour Female, +5 pour Male, soit 166 kcal d'écart à entrées égales.
        val female = calculated(sex = Sex.FEMALE)
        val male = calculated(sex = Sex.MALE)

        assertEquals(166, male.restingEnergyKcal - female.restingEnergyKcal)
    }

    @Test
    fun `le sexe change la masse maigre, donc la masse grasse et l'eau`() {
        val female = calculated(sex = Sex.FEMALE)
        val male = calculated(sex = Sex.MALE)

        // +8,125 kg de masse maigre pour un homme, à impédance, poids, taille et âge identiques.
        assertEquals(812, male.fatFreeMassCg - female.fatFreeMassCg)
        assertTrue(male.bodyFatDeciPercent < female.bodyFatDeciPercent)
        assertTrue(male.bodyWaterDeciPercent > female.bodyWaterDeciPercent)
    }

    // ------------------------------------------------------------------ arrondi

    @Test
    fun `une mi-chemin exacte s'arrondit en s'éloignant de zéro sur les quatre sorties`() {
        // Masse maigre exactement 52,125 kg → 5212,5 centièmes → 5213.
        assertEquals(
            5213,
            calculate(weightCg = 6000, heightCm = 165, ageYears = 20, sex = Sex.FEMALE, impedanceOhm = 363)
                .composition().fatFreeMassCg,
        )
        // Masse grasse exactement 32,65 % → 326,5 dixièmes → 327.
        assertEquals(
            327,
            calculate(weightCg = 8000, heightCm = 165, ageYears = 20, sex = Sex.FEMALE, impedanceOhm = 394)
                .composition().bodyFatDeciPercent,
        )
        // Eau exactement 35,55 % → 355,5 dixièmes → 356.
        assertEquals(
            356,
            calculate(weightCg = 9150, heightCm = 165, ageYears = 26, sex = Sex.FEMALE, impedanceOhm = 660)
                .composition().bodyWaterDeciPercent,
        )
        // Dépense au repos exactement 1804,5 kcal → 1805.
        assertEquals(
            1805,
            calculate(weightCg = 8570, heightCm = 178, ageYears = 34, sex = Sex.MALE, impedanceOhm = 545)
                .composition().restingEnergyKcal,
        )
    }

    @Test
    fun `le calcul est déterministe`() {
        val first = calculate(impedanceOhm = 545).composition()
        val second = calculate(impedanceOhm = 545).composition()

        assertEquals(first, second)
    }

    // ------------------------------------------------------------------ instantané des entrées

    @Test
    fun `la composition porte l'instantané exact de ses entrées`() {
        val composition = calculate(
            weightCg = 8575,
            heightCm = 178,
            ageYears = 34,
            sex = Sex.MALE,
            impedanceOhm = 545,
        ).composition()

        // BR-SCALE-014 : la version de formule et les quatre entrées voyagent avec le résultat,
        // faute de quoi ni le recalcul d'historique de FR-BODY-004 ni la reproductibilité exigée
        // par PRD_SCALE 23 ne seraient possibles.
        assertEquals(date, composition.date)
        assertEquals("mue-foot-to-foot-v1", composition.formulaId)
        assertEquals(1, composition.formulaVersion)
        assertEquals(8575, composition.inputWeightCg)
        assertEquals(178, composition.inputHeightCm)
        assertEquals(34, composition.inputAgeYears)
        assertEquals(Sex.MALE, composition.inputSex)
    }

    /**
     * PRD_SCALE 23 : « les valeurs dérivées sont reproductibles depuis l'impédance, l'instantané
     * des entrées et la version de formule stockés. »
     *
     * Le test précédent vérifie que l'instantané est *écrit* ; celui-ci vérifie qu'il **suffit**,
     * ce qui n'est pas la même affirmation et est la seule des deux que la case demande. Une
     * entrée oubliée de l'instantané — le sexe, l'impédance rangée sur la composition plutôt que
     * sur la mesure (FR-BODY-004) — laisserait le premier vert et rendrait le recalcul de
     * FR-BODY-004 impossible le jour où une version 2 de la formule doit rejouer l'historique.
     *
     * L'impédance vient de la mesure et non de la composition, exactement comme elle viendrait de
     * la base : c'est là qu'elle est stockée, et c'est ce qui rend ce test représentatif.
     */
    @Test
    fun `l'instantané stocké suffit à retrouver les quatre entiers`() {
        val measurement = Measurement(
            date = LocalDate.of(2025, 1, 20),
            weight = Weight.ofHundredthsOrNull(8575)!!,
            source = MeasurementSource.SCALE,
            impedanceOhm = 545,
        )
        val profile = UserProfile(heightCm = 178, birthDate = LocalDate.of(1992, 6, 15), sex = Sex.MALE)
        val stored = BodyCompositionCalculator.calculate(measurement, profile).composition()

        // Aucun profil n'entre ici : les seules entrées sont celles que la base porte. Une
        // grandeur que l'équation lirait ailleurs — un champ de profil ajouté sans être copié
        // dans l'instantané — ferait diverger les deux résultats, et c'est le seul défaut que ce
        // test existe pour attraper.
        val replayed = BodyCompositionCalculator.calculate(
            date = stored.date,
            weightCg = stored.inputWeightCg,
            heightCm = stored.inputHeightCm,
            ageYears = stored.inputAgeYears,
            sex = stored.inputSex,
            impedanceOhm = measurement.impedanceOhm,
        ).composition()

        assertEquals(stored, replayed)
        assertEquals(BodyCompositionFormula.ID, stored.formulaId)
        assertEquals(BodyCompositionFormula.VERSION, stored.formulaVersion)
    }

    // ------------------------------------------------------------------ âge à la date de la mesure

    @Test
    fun `l'âge employé est celui de la date de la mesure, pas celui du jour du calcul`() {
        val profile = UserProfile(
            heightCm = 178,
            birthDate = LocalDate.of(1992, 6, 15),
            sex = Sex.MALE,
        )
        val weight = Weight.ofHundredthsOrNull(8575)!!

        // FR-BODY-006 : le recalcul rétroactif rejoue des dates passées. Deux mesures séparées par
        // un anniversaire doivent porter deux âges différents, quelle que soit la date du calcul.
        val before = BodyCompositionCalculator
            .calculate(LocalDate.of(2026, 6, 14), weight, profile, impedanceOhm = 545)
            .composition()
        val after = BodyCompositionCalculator
            .calculate(LocalDate.of(2026, 6, 15), weight, profile, impedanceOhm = 545)
            .composition()

        assertEquals(33, before.inputAgeYears)
        assertEquals(34, after.inputAgeYears)
        assertTrue(
            before.fatFreeMassCg > after.fatFreeMassCg,
            "une année de plus retire 0,136 kg de masse maigre",
        )
    }

    @Test
    fun `une mesure et un profil suffisent à recalculer une composition ancienne`() {
        val profile = UserProfile(heightCm = 178, birthDate = LocalDate.of(1992, 6, 15), sex = Sex.MALE)
        val measurement = Measurement(
            date = LocalDate.of(2025, 1, 20),
            weight = Weight.ofHundredthsOrNull(8575)!!,
            source = MeasurementSource.SCALE,
            impedanceOhm = 545,
        )

        val composition = BodyCompositionCalculator.calculate(measurement, profile).composition()

        assertEquals(LocalDate.of(2025, 1, 20), composition.date)
        assertEquals(32, composition.inputAgeYears)
        assertEquals(measurement.weight.hundredthsKg, composition.inputWeightCg)
    }

    @Test
    fun `une mesure sans impédance ne produit aucune composition`() {
        val profile = UserProfile(heightCm = 178, birthDate = LocalDate.of(1992, 6, 15), sex = Sex.MALE)
        val manual = Measurement(date = date, weight = Weight.ofHundredthsOrNull(8575)!!)

        val result = BodyCompositionCalculator.calculate(manual, profile)

        assertIs<BodyCompositionResult.ImpedanceUnusable>(result)
        assertNull(result.compositionOrNull)
    }

    // ------------------------------------------------------------------ outils

    private fun failureOf(case: JsonObject): String? {
        val id = case["id"]!!.asText()
        val input = case["input"]!!.jsonObject
        val expected = case["expect"]!!.jsonObject

        val result = BodyCompositionCalculator.calculate(
            date = date,
            weightCg = input["weightCg"]!!.asInt(),
            heightCm = input["heightCm"]?.asIntOrNull(),
            ageYears = input["ageYears"]?.asIntOrNull(),
            sex = input["sex"]?.asTextOrNull()?.let { Sex.fromWire(it) ?: return "$id : sexe illisible" },
            impedanceOhm = input["impedanceOhm"]?.asIntOrNull(),
        )

        val outcome = expected["outcome"]!!.asText()
        if (result.wireOutcome != outcome) {
            return "$id : attendu $outcome, obtenu ${result.wireOutcome}"
        }

        return when (result) {
            is BodyCompositionResult.Calculated -> {
                val c = result.composition
                val mismatches = listOfNotNull(
                    diff("bodyFatDeciPercent", expected, c.bodyFatDeciPercent),
                    diff("fatFreeMassCg", expected, c.fatFreeMassCg),
                    diff("bodyWaterDeciPercent", expected, c.bodyWaterDeciPercent),
                    diff("restingEnergyKcal", expected, c.restingEnergyKcal),
                )
                if (mismatches.isEmpty()) null else "$id : ${mismatches.joinToString()}"
            }

            is BodyCompositionResult.MissingProfileInput -> {
                val wanted = expected["missing"]!!.jsonArray.map { it.asText() }
                val got = result.missing.map { it.wireValue }
                if (wanted == got) null else "$id : entrées manquantes attendues $wanted, obtenues $got"
            }

            is BodyCompositionResult.PhysicallyImplausible -> {
                val wanted = expected["check"]!!.asText()
                if (wanted == result.check.wireValue) {
                    null
                } else {
                    "$id : contrôle attendu $wanted, obtenu ${result.check.wireValue}"
                }
            }

            else -> null
        }
    }

    private fun diff(field: String, expected: JsonObject, actual: Int): String? {
        val wanted = expected[field]!!.asInt()
        return if (wanted == actual) null else "$field attendu $wanted, obtenu $actual"
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

    private fun calculated(sex: Sex) = calculate(sex = sex).composition()

    private fun BodyCompositionResult.composition() =
        compositionOrNull ?: fail("composition attendue, obtenu $this")

    private fun vectors(): JsonObject {
        val stream = BodyCompositionCalculatorTest::class.java.classLoader
            ?.getResourceAsStream(VECTORS)
        assertNotNull(stream, "vecteurs de référence absents : $VECTORS")
        val text = stream.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    private companion object {
        const val VECTORS = "bodycomposition/mue-foot-to-foot-v1.json"

        /**
         * Lecture du JSON sans dépendance nouvelle ni classe `@Serializable` : le fichier de
         * vecteurs est un contrat de format, pas un modèle Kotlin, et lui donner un miroir typé
         * ferait passer pour une donnée absente tout champ que le portage TypeScript ajouterait.
         */
        fun JsonElement.asText(): String = (this as JsonPrimitive).content

        fun JsonElement.asTextOrNull(): String? = (this as JsonPrimitive).takeIf { it.isString }?.content

        fun JsonElement.asInt(): Int = (this as JsonPrimitive).content.toInt()

        fun JsonElement.asIntOrNull(): Int? = (this as JsonPrimitive).intOrNull
    }
}
