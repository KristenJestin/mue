package fr.kristenjestin.mue.data.local.database

import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val DATE: LocalDate = LocalDate.of(2026, 8, 27)

private fun composition(
    date: LocalDate = DATE,
    inputWeightCg: Int = 7_845,
): BodyComposition = BodyComposition(
    date = date,
    formulaId = "mue-foot-to-foot-v1",
    formulaVersion = 1,
    inputWeightCg = inputWeightCg,
    inputHeightCm = 178,
    inputAgeYears = 36,
    inputSex = Sex.MALE,
    bodyFatDeciPercent = 183,
    fatFreeMassCg = 6_409,
    bodyWaterDeciPercent = 552,
    restingEnergyKcal = 1_742,
)

private fun measurement(
    weightCg: Int = 7_845,
    bodyComposition: BodyComposition? = composition(),
): Measurement = Measurement(
    date = DATE,
    weight = Weight.ofHundredthsClamped(weightCg),
    source = MeasurementSource.SCALE,
    sourceScaleId = "scale-1",
    impedanceOhm = 545,
    bodyComposition = bodyComposition,
)

/**
 * Les deux champs que [Measurement.toCompositionEntity] reprend de la mesure parente.
 *
 * BR-SCALE-006 (« la composition est un enfant de sa mesure ») et BR-SCALE-015
 * (« `inputWeightCg` est toujours égal au poids de sa mesure parente ») ne se prouvent pas de la
 * même façon. La première est une contrainte SQL — clé primaire et clé étrangère sur la même
 * colonne — et se teste contre SQLite. La seconde ne peut être tenue par aucune contrainte, parce
 * que le poids vit dans l'autre table : la seule forme qu'elle puisse prendre est celle d'une
 * conversion qui n'offre pas à l'appelant l'occasion de la violer. C'est ce que ce fichier
 * vérifie, et c'est pour cela qu'il part d'un domaine délibérément incohérent.
 */
class MeasurementEntityMappingTest {

    /**
     * BR-SCALE-015 rendue structurelle.
     *
     * Une composition calculée à partir d'un autre poids que celui de sa mesure est un instantané
     * d'entrée mensonger (FR-BODY-004, BR-SCALE-014) : l'écran montrerait une masse grasse dérivée
     * d'une valeur que la ligne parente ne porte pas, sans que rien ne signale l'écart. La
     * conversion ne la refuse pas — elle la rend inécrivable, en reprenant le poids du parent.
     */
    @Test
    fun `un poids d'entrée divergent est remplacé par celui de la mesure parente`() {
        val diverging = measurement(weightCg = 7_845, bodyComposition = composition(inputWeightCg = 6_000))

        val entity = assertNotNull(diverging.toCompositionEntity())

        assertEquals(7_845, entity.inputWeightCg)
    }

    /** L'invariant déjà tenu pour la date, vérifié par la même construction. */
    @Test
    fun `une date divergente est remplacée par celle de la mesure parente`() {
        val diverging = measurement(
            bodyComposition = composition(date = LocalDate.of(2020, 1, 1)),
        )

        val entity = assertNotNull(diverging.toCompositionEntity())

        assertEquals("2026-08-27", entity.date)
    }

    /**
     * Les deux reprises ne débordent pas : tout le reste de l'instantané traverse la conversion
     * inchangé, sans quoi corriger l'incohérence en fabriquerait une autre.
     */
    @Test
    fun `le reste de l'instantané traverse la conversion inchangé`() {
        val entity = assertNotNull(measurement().toCompositionEntity())

        assertEquals("mue-foot-to-foot-v1", entity.formulaId)
        assertEquals(1, entity.formulaVersion)
        assertEquals(178, entity.inputHeightCm)
        assertEquals(36, entity.inputAgeYears)
        assertEquals(Sex.MALE.wireValue, entity.inputSex)
        assertEquals(183, entity.bodyFatDeciPercent)
        assertEquals(6_409, entity.fatFreeMassCg)
        assertEquals(552, entity.bodyWaterDeciPercent)
        assertEquals(1_742, entity.restingEnergyKcal)
    }

    /**
     * Le poids corrigé est celui que le domaine relira : l'aller-retour ne laisse aucune trace de
     * la valeur divergente qui a été fournie.
     */
    @Test
    fun `l'aller-retour rend le poids de la mesure parente et pas celui fourni`() {
        val diverging = measurement(weightCg = 7_845, bodyComposition = composition(inputWeightCg = 6_000))

        val read = assertNotNull(assertNotNull(diverging.toCompositionEntity()).toDomainOrNull())

        assertEquals(7_845, read.inputWeightCg)
        assertEquals(DATE, read.date)
    }

    /**
     * L'absence de composition reste une absence — c'est elle que
     * [MeasurementDao.upsertAggregate] lit comme l'ordre de retirer l'ancienne (BR-SCALE-007), et
     * elle ne doit pas devenir une ligne vide.
     */
    @Test
    fun `une mesure sans composition n'a aucune ligne enfant à écrire`() {
        assertNull(measurement(bodyComposition = null).toCompositionEntity())
    }
}
