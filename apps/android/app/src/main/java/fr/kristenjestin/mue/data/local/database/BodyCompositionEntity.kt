package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Sex
import java.time.LocalDate

/**
 * L'estimation de composition corporelle d'une pesée (PRD_SCALE 12.3, 21.1).
 *
 * **[date] est à la fois la clé primaire et la clé étrangère** vers `measurements(date)`. Ce n'est
 * pas une économie de colonne : c'est la seule formulation en SQL de BR-SCALE-006, « une
 * composition est un enfant facultatif de `Measurement` et ne peut exister seule ». Une
 * composition orpheline devient impossible à écrire, et « au plus une composition par jour »
 * devient une contrainte plutôt qu'une convention d'interface.
 *
 * `ON DELETE CASCADE` réalise la seconde moitié de BR-SCALE-007 — supprimer une mesure supprime
 * atomiquement sa composition — et `ON UPDATE CASCADE` la fait suivre lorsque la date d'une mesure
 * est déplacée. La première moitié, « un payload complet sans composition retire l'ancienne », ne
 * peut pas s'exprimer par une contrainte : c'est [MeasurementDao.upsertAggregate] qui la tient.
 *
 * Aucun index n'est déclaré pour la clé étrangère : ses colonnes enfant sont exactement la clé
 * primaire de la table, que SQLite indexe déjà. Un second index sur `date` serait une copie de
 * celui-là, écrite à chaque pesée pour n'accélérer aucune lecture.
 *
 * **Tout est entier** (PRD_SCALE 21.1) : la précision décimale est conservée pendant le calcul et
 * l'arrondi n'intervient qu'une fois, à l'écriture. Deux implémentations — Kotlin et TypeScript
 * (PRD_SCALE 13.2) — doivent produire les mêmes entiers pour le même payload, ce qu'un `REAL`
 * sérialisé ne garantirait pas.
 *
 * **Ce qui n'est pas ici : l'impédance.** Elle est portée par `measurements.impedance_ohm`
 * (FR-BODY-004, BR-SCALE-008), afin qu'une impédance mesurée avant que le profil ne soit complet
 * survive à l'absence de composition et reste disponible pour le calcul rétroactif de
 * FR-BODY-006.
 *
 * @property inputSex Forme stockée de [Sex] — `female` ou `male` — décodée par [Sex.fromWire] et
 *   jamais par un `@TypeConverter` : la colonne reste un `TEXT` nu et le schéma exporté se lit
 *   comme la table qu'il décrit.
 */
@Entity(
    tableName = BodyCompositionEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = MeasurementEntity::class,
            parentColumns = ["date"],
            childColumns = ["date"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class BodyCompositionEntity(
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = "formula_id")
    val formulaId: String,

    @ColumnInfo(name = "formula_version")
    val formulaVersion: Int,

    @ColumnInfo(name = "input_weight_cg")
    val inputWeightCg: Int,

    @ColumnInfo(name = "input_height_cm")
    val inputHeightCm: Int,

    @ColumnInfo(name = "input_age_years")
    val inputAgeYears: Int,

    @ColumnInfo(name = "input_sex")
    val inputSex: String,

    @ColumnInfo(name = "body_fat_deci_percent")
    val bodyFatDeciPercent: Int,

    @ColumnInfo(name = "fat_free_mass_cg")
    val fatFreeMassCg: Int,

    @ColumnInfo(name = "body_water_deci_percent")
    val bodyWaterDeciPercent: Int,

    @ColumnInfo(name = "resting_energy_kcal")
    val restingEnergyKcal: Int,
) {
    companion object {
        const val TABLE_NAME = "body_composition"
    }
}

/**
 * `null` lorsque [BodyCompositionEntity.inputSex] est illisible.
 *
 * [BodyComposition.inputSex] est non nul par construction : PRD_SCALE FR-BODY-004 veut que
 * l'instantané reflète une entrée **réellement utilisée** par la formule, donc inventer un repli
 * fabriquerait une estimation que personne n'a calculée. Une ligne indéchiffrable est traitée
 * comme l'absence de composition, ce qui est le cas nominal d'un profil incomplet
 * (FR-BODY-001) : le poids se lit normalement, la composition est simplement absente.
 */
internal fun BodyCompositionEntity.toDomainOrNull(): BodyComposition? {
    val sex = Sex.fromWire(inputSex) ?: return null
    return BodyComposition(
        date = LocalDate.parse(date),
        formulaId = formulaId,
        formulaVersion = formulaVersion,
        inputWeightCg = inputWeightCg,
        inputHeightCm = inputHeightCm,
        inputAgeYears = inputAgeYears,
        inputSex = sex,
        bodyFatDeciPercent = bodyFatDeciPercent,
        fatFreeMassCg = fatFreeMassCg,
        bodyWaterDeciPercent = bodyWaterDeciPercent,
        restingEnergyKcal = restingEnergyKcal,
    )
}

internal fun BodyComposition.toEntity(): BodyCompositionEntity = BodyCompositionEntity(
    date = date.toString(),
    formulaId = formulaId,
    formulaVersion = formulaVersion,
    inputWeightCg = inputWeightCg,
    inputHeightCm = inputHeightCm,
    inputAgeYears = inputAgeYears,
    inputSex = inputSex.wireValue,
    bodyFatDeciPercent = bodyFatDeciPercent,
    fatFreeMassCg = fatFreeMassCg,
    bodyWaterDeciPercent = bodyWaterDeciPercent,
    restingEnergyKcal = restingEnergyKcal,
)
