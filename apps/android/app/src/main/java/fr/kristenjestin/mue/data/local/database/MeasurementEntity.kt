package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate

/**
 * One row per calendar day (PRD 11.1, 20.3).
 *
 * The date is the primary key, so "one measurement per date" (PRD BR-001) is a
 * SQLite constraint rather than a UI convention, as PRD 16.3 demands. Storing it as
 * ISO text also makes lexicographic order equal chronological order, so every query
 * sorts on the key itself.
 *
 * The weight is an integer count of hundredths of a kilogram: no float ever touches the
 * database, so no rounding can drift. The column carries its unit in its name, so a row read
 * by any tool is unambiguous — and the change of unit in version 2 could not be silent.
 *
 * Version 7 ajoute la provenance et l'impédance (PRD_SCALE 21.1). Aucune des trois colonnes n'est
 * une décoration d'affichage : sans [sourceType] explicite, BR-SCALE-013 — « modifier
 * manuellement un poids reçu retire sa provenance matérielle, son impédance et sa composition » —
 * serait inexprimable.
 *
 * @property sourceType Forme stockée de [MeasurementSource]. `NOT NULL DEFAULT 'manual'` : c'est
 *   la valeur dont tout l'historique antérieur au module balance est rétro-rempli par la
 *   migration additive de PRD_SCALE 21.1, et la seule qu'une saisie manuelle puisse produire.
 * @property sourceScaleId [ScaleEntity.id] de la balance émettrice, en `ON DELETE SET NULL`.
 *   **BR-SCALE-010 est cette contrainte et rien d'autre** : oublier une balance ne supprime aucune
 *   mesure, le poids garde `source_type = 'scale'`, et seul l'identifiant devenu inutilisable est
 *   annulé. Une cascade y perdrait l'historique ; un `RESTRICT` interdirait l'oubli.
 *
 *   **Strictement local** (PRD_SCALE 16.2 et 22) : cet identifiant, l'adresse et le nom de la
 *   balance ne quittent jamais le téléphone, et n'apparaissent donc dans aucun payload de
 *   `SyncOutbox`.
 * @property impedanceOhm Impédance corporelle mesurée en même temps que le poids, **portée par la
 *   mesure et non par la composition** (FR-BODY-004, BR-SCALE-008). Une impédance parfaitement
 *   exploitable est produite dès les premières pesées, alors que le profil est encore incomplet ;
 *   rangée dans la composition, elle disparaîtrait exactement dans ce cas, et des semaines de
 *   mesures seraient perdues le jour où le calcul rétroactif de FR-BODY-006 en aurait besoin.
 *   `null` quand la balance a signalé une mesure impossible (BR-SCALE-005).
 */
@Entity(
    tableName = MeasurementEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = ScaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_scale_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["source_scale_id"])],
)
data class MeasurementEntity(
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = MeasurementEntity.WEIGHT_COLUMN)
    val weightCg: Int,

    @ColumnInfo(name = MeasurementEntity.SOURCE_TYPE_COLUMN, defaultValue = "'manual'")
    val sourceType: String = MeasurementSource.MANUAL.wireValue,

    @ColumnInfo(name = "source_scale_id")
    val sourceScaleId: String? = null,

    @ColumnInfo(name = "impedance_ohm")
    val impedanceOhm: Int? = null,
) {
    companion object {
        const val TABLE_NAME = "measurements"
        const val WEIGHT_COLUMN = "weight_cg"
        const val SOURCE_TYPE_COLUMN = "source_type"
    }
}

/**
 * La mesure et sa composition facultative, lues ensemble (PRD_SCALE 21.1).
 *
 * L'agrégat est lu d'un coup plutôt qu'en deux requêtes que l'appelant recollerait : entre les
 * deux, une écriture concurrente pourrait retirer la composition, et le domaine recevrait une
 * mesure accompagnée d'une composition qui n'existe plus — précisément l'état que BR-SCALE-006
 * déclare impossible.
 *
 * [composition] est nulle et pas absente d'une liste : la relation est un-à-un, portée par la même
 * clé des deux côtés.
 */
data class MeasurementWithComposition(
    @Embedded
    val measurement: MeasurementEntity,

    @Relation(parentColumn = "date", entityColumn = "date")
    val composition: BodyCompositionEntity? = null,
)

/**
 * Une provenance illisible est journalisée par son absence de correspondance et ramenée à
 * [MeasurementSource.MANUAL].
 *
 * [MeasurementSource.fromWire] renvoie volontairement `null` pour laisser la couche de conversion
 * décider du repli (PRD_SCALE 18.5). Ici, le repli est `manual` : il n'affirme aucune provenance
 * matérielle, ne rattache la mesure à aucune balance et ne peut donc pas faire passer une valeur
 * saisie à la main pour une valeur mesurée. C'est aussi la valeur que la migration a écrite pour
 * tout l'historique, donc la seule qui ne surprenne pas.
 */
internal fun MeasurementWithComposition.toDomain(): Measurement = Measurement(
    date = LocalDate.parse(measurement.date),
    weight = Weight.ofHundredthsClamped(measurement.weightCg),
    source = MeasurementSource.fromWire(measurement.sourceType) ?: MeasurementSource.MANUAL,
    sourceScaleId = measurement.sourceScaleId,
    impedanceOhm = measurement.impedanceOhm,
    bodyComposition = composition?.toDomainOrNull(),
)

internal fun Measurement.toEntity(): MeasurementEntity = MeasurementEntity(
    date = date.toString(),
    weightCg = weight.hundredthsKg,
    sourceType = source.wireValue,
    sourceScaleId = sourceScaleId,
    impedanceOhm = impedanceOhm,
)

/**
 * La ligne enfant à écrire, ou `null` quand la mesure n'en porte pas — auquel cas
 * [MeasurementDao.upsertAggregate] retire celle qui existait (BR-SCALE-007).
 *
 * La date de l'enfant est reprise de la mesure et non de [Measurement.bodyComposition] : les deux
 * doivent être égales, et prendre celle du parent rend l'inégalité impossible à écrire plutôt que
 * détectable après coup.
 */
internal fun Measurement.toCompositionEntity(): BodyCompositionEntity? =
    bodyComposition?.toEntity()?.copy(date = date.toString())
